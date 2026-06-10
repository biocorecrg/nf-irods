package nextflow.irods.nio

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import org.irods.jargon.core.pub.io.IRODSFile

class IrodsFileAttributes implements BasicFileAttributes {
    private final boolean isDir
    private final boolean isFile
    private final long length
    private final long lastModified

    IrodsFileAttributes(IRODSFile file) {
        this.isDir = file.isDirectory()
        this.isFile = file.isFile()
        this.length = file.length()
        this.lastModified = file.lastModified()
    }

    @Override
    FileTime lastModifiedTime() {
        return FileTime.fromMillis(lastModified)
    }

    @Override
    FileTime lastAccessTime() {
        return FileTime.fromMillis(lastModified)
    }

    @Override
    FileTime creationTime() {
        return FileTime.fromMillis(lastModified)
    }

    @Override
    boolean isRegularFile() {
        return isFile
    }

    @Override
    boolean isDirectory() {
        return isDir
    }

    @Override
    boolean isSymbolicLink() {
        return false
    }

    @Override
    boolean isOther() {
        return false
    }

    @Override
    long size() {
        return length
    }

    @Override
    Object fileKey() {
        return null
    }
}
