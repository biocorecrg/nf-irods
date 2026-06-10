package nextflow.irods.nio

import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import org.irods.jargon.core.pub.io.IRODSFile

class IrodsFileAttributeView implements BasicFileAttributeView {
    private final IRODSFile file

    IrodsFileAttributeView(IRODSFile file) {
        this.file = file
    }

    @Override
    String name() {
        return "basic"
    }

    @Override
    BasicFileAttributes readAttributes() throws IOException {
        return new IrodsFileAttributes(file)
    }

    @Override
    void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
        // no-op
    }
}
