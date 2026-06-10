package nextflow.irods.nio

import java.nio.file.*
import java.nio.file.attribute.UserPrincipalLookupService
import org.irods.jargon.core.connection.IRODSAccount
import org.irods.jargon.core.pub.IRODSFileSystem
import org.irods.jargon.core.pub.io.IRODSFile
import org.irods.jargon.core.pub.io.IRODSFileFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class IrodsFileSystem extends FileSystem {
    private static final Logger log = LoggerFactory.getLogger(IrodsFileSystem)
    private final IrodsFileSystemProvider provider
    private final URI uri
    private final Map config
    private boolean open = true

    private IRODSFileSystem irodsFileSystem
    private IRODSAccount irodsAccount
    private IRODSFileFactory fileFactory

    IrodsFileSystem(IrodsFileSystemProvider provider, URI uri, Map config) {
        this.provider = provider
        this.uri = uri
        this.config = config ?: [:]
        initIrods()
    }

    private void initIrods() {
        try {
            String host = config.host ?: System.getenv("IRODS_HOST") ?: "localhost"
            int port = config.port ? (config.port as int) : (System.getenv("IRODS_PORT") ? System.getenv("IRODS_PORT").toInteger() : 1247)
            String username = config.username ?: System.getenv("IRODS_USER_NAME") ?: System.getProperty("user.name")
            String password = config.password ?: System.getenv("IRODS_PASSWORD") ?: ""
            String zone = config.zone ?: System.getenv("IRODS_ZONE_NAME") ?: ""
            String defaultStorageResource = config.defaultStorageResource ?: System.getenv("IRODS_DEFAULT_RESOURCE") ?: ""
            String homeDirectory = config.homeDirectory ?: "/${zone}/home/${username}"

            log.info("Connecting to iRODS at ${host}:${port} as ${username} (zone: ${zone})")
            
            this.irodsFileSystem = IRODSFileSystem.instance()
            this.irodsAccount = IRODSAccount.instance(host, port, username, password, homeDirectory, zone, defaultStorageResource)
            this.fileFactory = irodsFileSystem.getIRODSAccessObjectFactory().getIRODSFileFactory(irodsAccount)
        } catch (Exception e) {
            log.error("Failed to initialize iRODS filesystem connection", e)
            throw new IOException("Failed to initialize iRODS connection: " + e.getMessage(), e)
        }
    }

    IRODSFile getIrodsFile(String path) {
        if (!isOpen()) {
            throw new ClosedFileSystemException()
        }
        return fileFactory.instanceIRODSFile(path)
    }

    IRODSFileSystem getIrodsFileSystem() {
        return irodsFileSystem
    }

    IRODSAccount getIrodsAccount() {
        return irodsAccount
    }

    @Override
    IrodsFileSystemProvider provider() {
        return provider
    }

    @Override
    void close() throws IOException {
        if (open) {
            open = false
            if (irodsFileSystem != null) {
                irodsFileSystem.closeAndKeepSession()
            }
        }
    }

    @Override
    boolean isOpen() {
        return open
    }

    @Override
    boolean isReadOnly() {
        return false
    }

    @Override
    String getSeparator() {
        return "/"
    }

    @Override
    Iterable<Path> getRootDirectories() {
        return List.of((Path) new IrodsPath(this, "/"))
    }

    @Override
    Iterable<FileStore> getFileStores() {
        return Collections.emptyList()
    }

    @Override
    Set<String> supportedFileAttributeViews() {
        return Set.of("basic")
    }

    @Override
    Path getPath(String first, String... more) {
        String path = first
        if (more != null && more.length > 0) {
            path = path + "/" + more.join("/")
        }
        return new IrodsPath(this, path)
    }

    @Override
    PathMatcher getPathMatcher(String syntaxAndPattern) {
        String[] parts = syntaxAndPattern.split(":", 2)
        String syntax = parts[0]
        String pattern = parts[1]
        
        if (syntax.equalsIgnoreCase("glob")) {
            String regex = pattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".")
            def regexPattern = java.util.regex.Pattern.compile(regex)
            return new PathMatcher() {
                @Override
                boolean matches(Path path) {
                    return regexPattern.matcher(path.toString()).matches()
                }
            }
        } else if (syntax.equalsIgnoreCase("regex")) {
            def regexPattern = java.util.regex.Pattern.compile(pattern)
            return new PathMatcher() {
                @Override
                boolean matches(Path path) {
                    return regexPattern.matcher(path.toString()).matches()
                }
            }
        }
        throw new UnsupportedOperationException("Unsupported syntax: " + syntax)
    }

    @Override
    UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException()
    }

    @Override
    WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException()
    }
}
