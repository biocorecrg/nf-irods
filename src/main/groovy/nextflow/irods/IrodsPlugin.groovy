package nextflow.irods

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper
import nextflow.file.FileHelper
import nextflow.irods.nio.IrodsFileSystemProvider
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class IrodsPlugin extends BasePlugin {
    private static final Logger log = LoggerFactory.getLogger(IrodsPlugin)

    IrodsPlugin(PluginWrapper wrapper) {
        super(wrapper)
    }

    @Override
    void start() {
        log.info("Starting nf-irods plugin")
        try {
            FileHelper.getOrInstallProvider(IrodsFileSystemProvider)
            log.info("Successfully registered IrodsFileSystemProvider")
        } catch (Exception e) {
            log.error("Failed to register IrodsFileSystemProvider", e)
        }
    }
}
