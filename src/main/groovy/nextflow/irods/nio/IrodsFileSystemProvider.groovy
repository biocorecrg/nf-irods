package nextflow.irods.nio

import java.nio.channels.SeekableByteChannel
import java.nio.file.*
import java.nio.file.attribute.*
import java.nio.file.spi.FileSystemProvider
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import org.irods.jargon.core.pub.io.IRODSFile
import org.irods.jargon.core.pub.io.IRODSFileInputStream
import org.irods.jargon.core.pub.io.IRODSFileOutputStream
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class IrodsFileSystemProvider extends FileSystemProvider {
    private static final Logger log = LoggerFactory.getLogger(IrodsFileSystemProvider)
    private static Map sessionConfig = [:]
    
    static void setSessionConfig(Map config) {
        this.sessionConfig = config
    }

    static Map getSessionConfig() {
        def config = [:]
        if (sessionConfig) {
            config.putAll(sessionConfig)
        }
        try {
            def session = nextflow.Global.session
            if (session?.config?.irods instanceof Map) {
                config.putAll((Map) session.config.irods)
            }
        } catch (Throwable e) {
            log.trace("Could not read irods config from Global session", e)
        }
        return config
    }

    private final Map<URI, IrodsFileSystem> fileSystems = [:]

    @Override
    String getScheme() {
        return "irods"
    }

    @Override
    FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        synchronized (fileSystems) {
            if (fileSystems.containsKey(uri)) {
                throw new FileSystemAlreadyExistsException()
            }
            def fs = new IrodsFileSystem(this, uri, getSessionConfig())
            fileSystems.put(uri, fs)
            return fs
        }
    }

    @Override
    FileSystem getFileSystem(URI uri) {
        synchronized (fileSystems) {
            def fs = fileSystems.get(uri)
            if (!fs) {
                fs = new IrodsFileSystem(this, uri, getSessionConfig())
                fileSystems.put(uri, fs)
            }
            return fs
        }
    }

    @Override
    Path getPath(URI uri) {
        return getFileSystem(uri).getPath(uri.getPath())
    }

    private IRODSFile toIrodsFile(Path path) {
        if (path instanceof IrodsPath) {
            return ((IrodsFileSystem) path.getFileSystem()).getIrodsFile(path.toString())
        }
        throw new ProviderMismatchException("Path is not an IrodsPath: " + path)
    }

    @Override
    InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        log.info("Staging file from iRODS into Nextflow workspace: ${path}")
        IRODSFile file = toIrodsFile(path)
        try {
            def fs = (IrodsFileSystem) path.getFileSystem()
            return new BufferedInputStream(fs.fileFactory.instanceIRODSFileInputStream(file))
        } catch (Exception e) {
            throw new IOException("Failed to open input stream for " + path + ": " + e.getMessage(), e)
        }
    }

    @Override
    OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        log.info("Writing file to iRODS: ${path}")
        IRODSFile file = toIrodsFile(path)
        try {
            // Ensure parent directories exist
            file.getParentFile().mkdirs()
            def fs = (IrodsFileSystem) path.getFileSystem()
            return new BufferedOutputStream(fs.fileFactory.instanceIRODSFileOutputStream(file))
        } catch (Exception e) {
            throw new IOException("Failed to open output stream for " + path + ": " + e.getMessage(), e)
        }
    }

    @Override
    SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        // Fallback SeekableByteChannel using local temporary files
        IRODSFile file = toIrodsFile(path)
        Path tempFile = Files.createTempFile("irods-channel-", ".tmp")
        boolean isWrite = options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND)
        boolean isRead = options.contains(StandardOpenOption.READ) || !isWrite

        if (isRead && file.exists()) {
            newInputStream(path).withStream { input ->
                Files.newOutputStream(tempFile).withStream { out ->
                    input.transferTo(out)
                }
            }
        }

        FileChannel localChannel = FileChannel.open(tempFile, options)
        return new SeekableByteChannel() {
            @Override
            int read(java.nio.ByteBuffer dst) throws IOException {
                return localChannel.read(dst)
            }

            @Override
            int write(java.nio.ByteBuffer src) throws IOException {
                return localChannel.write(src)
            }

            @Override
            long position() throws IOException {
                return localChannel.position()
            }

            @Override
            SeekableByteChannel position(long newPosition) throws IOException {
                localChannel.position(newPosition)
                return this
            }

            @Override
            long size() throws IOException {
                return localChannel.size()
            }

            @Override
            SeekableByteChannel truncate(long size) throws IOException {
                localChannel.truncate(size)
                return this
            }

            @Override
            boolean isOpen() {
                return localChannel.isOpen()
            }

            @Override
            void close() throws IOException {
                try {
                    localChannel.close()
                    if (isWrite) {
                        // Upload back to iRODS
                        newOutputStream(path).withStream { out ->
                            Files.newInputStream(tempFile).withStream { input ->
                                input.transferTo(out)
                            }
                        }
                    }
                } finally {
                    Files.deleteIfExists(tempFile)
                }
            }
        }
    }

    @Override
    DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        log.info("TRACE: Entering newDirectoryStream for path: ${dir}")
        IRODSFile file = toIrodsFile(dir)
        if (!file.exists()) {
            throw new NoSuchFileException(dir.toString())
        }
        if (!file.isDirectory()) {
            throw new NotDirectoryException(dir.toString())
        }
        try {
            log.info("TRACE: Calling file.list() for ${dir}")
            String[] list = file.list()
            log.info("TRACE: iRODS list() returned: ${list ? list.length : 0} items")
            if (list == null) {
                list = new String[0]
            }
            List<Path> paths = list.collect { String name ->
                dir.resolve(name)
            }.findAll { Path p -> filter == null || filter.accept(p) }
            log.info("Filtered paths: ${paths}")
            
            return new DirectoryStream<Path>() {
                @Override
                Iterator<Path> iterator() {
                    return paths.iterator()
                }
                @Override
                void close() throws IOException {}
            }
        } catch (Exception e) {
            log.error("Failed to list directory " + dir + ": " + e.getMessage(), e)
            throw new IOException("Failed to list directory " + dir + ": " + e.getMessage(), e)
        }
    }

    @Override
    void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        IRODSFile file = toIrodsFile(dir)
        if (file.exists()) {
            throw new FileAlreadyExistsException(dir.toString())
        }
        if (!file.mkdirs()) {
            throw new IOException("Failed to create directory: " + dir)
        }
    }

    @Override
    void delete(Path path) throws IOException {
        IRODSFile file = toIrodsFile(path)
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString())
        }
        if (!file.delete()) {
            throw new IOException("Failed to delete " + path)
        }
    }

    @Override
    void copy(Path source, Path target, CopyOption... options) throws IOException {
        log.info("Staging file from ${source} into ${target}")
        newInputStream(source).withStream { input ->
            newOutputStream(target).withStream { out ->
                input.transferTo(out)
            }
        }
    }

    @Override
    void move(Path source, Path target, CopyOption... options) throws IOException {
        log.info("Moving file from ${source} to ${target}")
        IRODSFile src = toIrodsFile(source)
        IRODSFile dst = toIrodsFile(target)
        dst.getParentFile().mkdirs()
        if (!src.renameTo(dst)) {
            throw new IOException("Failed to move " + source + " to " + target)
        }
    }

    @Override
    boolean isSameFile(Path path, Path path2) throws IOException {
        return path.toAbsolutePath().toString() == path2.toAbsolutePath().toString()
    }

    @Override
    boolean isHidden(Path path) throws IOException {
        return path.getFileName()?.toString()?.startsWith(".") ?: false
    }

    @Override
    FileStore getFileStore(Path path) throws IOException {
        return null
    }

    @Override
    void checkAccess(Path path, AccessMode... modes) throws IOException {
        IRODSFile file = toIrodsFile(path)
        if (!file.exists()) {
            throw new NoSuchFileException(path.toString())
        }
    }

    @Override
    <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        if (type == BasicFileAttributeView.class) {
            return (V) new IrodsFileAttributeView(toIrodsFile(path))
        }
        return null
    }

    @Override
    <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        if (type == BasicFileAttributes.class) {
            IRODSFile file = toIrodsFile(path)
            if (!file.exists()) {
                throw new NoSuchFileException(path.toString())
            }
            return (A) new IrodsFileAttributes(file)
        }
        throw new UnsupportedOperationException("Unsupported attribute type: " + type)
    }

    @Override
    Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        // Basic implementation for reading attributes as map
        def attrs = readAttributes(path, BasicFileAttributes.class, options)
        return [
            "lastModifiedTime": attrs.lastModifiedTime(),
            "lastAccessTime": attrs.lastAccessTime(),
            "creationTime": attrs.creationTime(),
            "isRegularFile": attrs.isRegularFile(),
            "isDirectory": attrs.isDirectory(),
            "isSymbolicLink": attrs.isSymbolicLink(),
            "isOther": attrs.isOther(),
            "size": attrs.size()
        ]
    }

    @Override
    void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw new UnsupportedOperationException("Setting attributes not supported")
    }
}
