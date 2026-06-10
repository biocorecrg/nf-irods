package nextflow.irods

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

@CompileStatic
class IrodsPlugin extends BasePlugin {
    IrodsPlugin(PluginWrapper wrapper) {
        super(wrapper)
    }
}
