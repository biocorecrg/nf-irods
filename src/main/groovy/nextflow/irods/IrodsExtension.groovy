package nextflow.irods

import groovy.transform.CompileStatic
import nextflow.Session
import nextflow.plugin.extension.PluginExtensionPoint
import nextflow.irods.nio.IrodsFileSystemProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class IrodsExtension extends PluginExtensionPoint {
    private static final Logger log = LoggerFactory.getLogger(IrodsExtension)

    @Override
    protected void init(Session session) {
        log.info("Initializing nf-irods plugin")
        def irodsConfig = session.config.irods
        if (irodsConfig instanceof Map) {
            log.info("Configuring iRODS file system with custom config: ${irodsConfig}")
            IrodsFileSystemProvider.setSessionConfig((Map) irodsConfig)
        } else {
            log.info("No iRODS session configuration found. Will fall back to default credentials.")
        }
    }
}
