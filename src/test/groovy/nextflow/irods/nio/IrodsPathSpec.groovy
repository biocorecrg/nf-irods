package nextflow.irods.nio

import spock.lang.Specification
import java.nio.file.FileSystems
import java.net.URI

class IrodsPathSpec extends Specification {

    def 'should discover irods nio provider'() {
        when:
        def providers = java.nio.file.spi.FileSystemProvider.installedProviders()
        def irodsProvider = providers.find { it.scheme == 'irods' }

        then:
        irodsProvider != null
        irodsProvider.scheme == 'irods'
    }

    def 'should test basic path operations'() {
        given:
        def fs = Mock(IrodsFileSystem)
        def path = new IrodsPath(fs, "/zone/home/user/data/reads.fastq.gz")

        expect:
        path.toString() == "/zone/home/user/data/reads.fastq.gz"
        path.getFileName().toString() == "reads.fastq.gz"
        path.getParent().toString() == "/zone/home/user/data"
        path.getRoot().toString() == "/"
        path.getNameCount() == 5
        path.getName(0).toString() == "zone"
        path.getName(4).toString() == "reads.fastq.gz"
        
        and:
        path.resolve("sub").toString() == "/zone/home/user/data/reads.fastq.gz/sub"
        path.getParent().resolve("other.fastq.gz").toString() == "/zone/home/user/data/other.fastq.gz"
    }
}
