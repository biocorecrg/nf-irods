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

    def 'should descramble password correctly'() {
        given:
        // Manual scramble implementation in test
        def password = "secret_password_123!"
        def uid = IrodsFileSystem.getUnixUid()
        def wheel = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!\"#\$%&'()*+,-./"
        def seq = 0xD768B678L // SEQ_LIST[0]
        def bitshift = 15
        
        // Scramble
        def scrambled = new StringBuilder(".E_abce")
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i)
            long offset = ((seq >> bitshift) & 0x1FL) + (uid & 0xF5FL)
            bitshift += 3
            if (bitshift > 28) {
                bitshift = 0
            }
            
            int wheelIndex = wheel.indexOf((int) c)
            if (wheelIndex != -1) {
                int targetIndex = (int) ((wheelIndex + offset) % wheel.length())
                scrambled.append(wheel.charAt(targetIndex))
            } else {
                scrambled.append(c)
            }
        }
        
        expect:
        IrodsFileSystem.descramble(scrambled.toString()) == password
    }
}
