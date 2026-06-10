package nextflow.irods.nio

import java.nio.file.*
import java.io.File
import java.net.URI

class IrodsPath implements Path {
    private final IrodsFileSystem fs
    private final String path
    private final boolean absolute

    IrodsPath(IrodsFileSystem fs, String path) {
        this.fs = fs
        this.absolute = path ? path.startsWith("/") : false
        this.path = path ? normalizePath(path) : ""
    }

    private String normalizePath(String p) {
        String res = p.replace('\\', '/')
        if (res.endsWith("/") && res.length() > 1) {
            res = res.substring(0, res.length() - 1)
        }
        return res
    }

    @Override
    FileSystem getFileSystem() {
        return fs
    }

    @Override
    boolean isAbsolute() {
        return absolute
    }

    @Override
    Path getRoot() {
        return absolute ? new IrodsPath(fs, "/") : null
    }

    @Override
    Path getFileName() {
        if (path == "" || path == "/") return null
        int index = path.lastIndexOf('/')
        if (index == -1) return new IrodsPath(fs, path)
        return new IrodsPath(fs, path.substring(index + 1))
    }

    @Override
    Path getParent() {
        if (path == "" || path == "/") return null
        int index = path.lastIndexOf('/')
        if (index == -1) return absolute ? getRoot() : null
        if (index == 0) return getRoot()
        return new IrodsPath(fs, path.substring(0, index))
    }

    @Override
    int getNameCount() {
        if (path == "" || path == "/") return 0
        String p = absolute ? path.substring(1) : path
        return p.split("/").length
    }

    @Override
    Path getName(int index) {
        if (path == "" || path == "/") throw new IllegalArgumentException()
        String p = absolute ? path.substring(1) : path
        String[] parts = p.split("/")
        if (index < 0 || index >= parts.length) throw new IllegalArgumentException()
        return new IrodsPath(fs, parts[index])
    }

    @Override
    Path subpath(int beginIndex, int endIndex) {
        if (path == "" || path == "/") throw new IllegalArgumentException()
        String p = absolute ? path.substring(1) : path
        String[] parts = p.split("/")
        if (beginIndex < 0 || endIndex > parts.length || beginIndex >= endIndex) {
            throw new IllegalArgumentException()
        }
        return new IrodsPath(fs, parts[beginIndex..endIndex-1].join("/"))
    }

    @Override
    boolean startsWith(Path other) {
        return this.toString().startsWith(other.toString())
    }

    @Override
    boolean startsWith(String other) {
        return this.toString().startsWith(other)
    }

    @Override
    boolean endsWith(Path other) {
        return this.toString().endsWith(other.toString())
    }

    @Override
    boolean endsWith(String other) {
        return this.toString().endsWith(other)
    }

    @Override
    Path normalize() {
        return this
    }

    @Override
    Path resolve(Path other) {
        if (other.isAbsolute()) return other
        return resolve(other.toString())
    }

    @Override
    Path resolve(String other) {
        if (other == "") return this
        if (other.startsWith("/")) return new IrodsPath(fs, other)
        String newPath
        if (path == "") newPath = other
        else if (path == "/") newPath = "/" + other
        else newPath = path + "/" + other
        return new IrodsPath(fs, newPath)
    }

    @Override
    Path resolveSibling(Path other) {
        Path parent = getParent()
        return parent == null ? other : parent.resolve(other)
    }

    @Override
    Path resolveSibling(String other) {
        return resolveSibling(new IrodsPath(fs, other))
    }

    @Override
    Path relativize(Path other) {
        String thisStr = this.toString()
        String otherStr = other.toString()
        if (thisStr == otherStr) return new IrodsPath(fs, "")
        if (otherStr.startsWith(thisStr + "/")) {
            return new IrodsPath(fs, otherStr.substring(thisStr.length() + 1))
        }
        throw new IllegalArgumentException("Cannot relativize unrelated paths")
    }

    @Override
    URI toUri() {
        return new URI("irods", "", path, null)
    }

    @Override
    Path toAbsolutePath() {
        return this
    }

    @Override
    Path toRealPath(LinkOption... options) throws IOException {
        return this
    }

    @Override
    File toFile() {
        throw new UnsupportedOperationException("iRODS paths cannot be converted to local files")
    }

    @Override
    WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException()
    }

    @Override
    WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events) throws IOException {
        throw new UnsupportedOperationException()
    }

    @Override
    Iterator<Path> iterator() {
        if (path == "" || path == "/") return Collections.emptyIterator()
        String p = absolute ? path.substring(1) : path
        String[] parts = p.split("/")
        return parts.collect { (Path) new IrodsPath(fs, it) }.iterator()
    }

    @Override
    int compareTo(Path other) {
        return this.toString().compareTo(other.toString())
    }

    @Override
    String toString() {
        return path
    }

    @Override
    boolean equals(Object obj) {
        if (obj instanceof IrodsPath) {
            return this.path == ((IrodsPath) obj).path
        }
        return false
    }

    @Override
    int hashCode() {
        return path.hashCode()
    }
}
