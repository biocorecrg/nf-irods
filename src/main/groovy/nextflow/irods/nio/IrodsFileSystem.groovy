package nextflow.irods.nio

import java.nio.file.*
import java.nio.file.attribute.UserPrincipalLookupService
import groovy.json.JsonSlurper
import org.irods.jargon.core.connection.AuthScheme
import org.irods.jargon.core.connection.IRODSAccount
import org.irods.jargon.core.connection.SettableJargonProperties
import org.irods.jargon.core.connection.ClientServerNegotiationPolicy.SslNegotiationPolicy
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

    private Map readIrodsEnvironment() {
        def envFile = System.getenv("IRODS_ENVIRONMENT_FILE")
        if (!envFile) {
            def home = System.getProperty("user.home") ?: System.getenv("HOME")
            if (home) {
                envFile = "${home}/.irods/irods_environment.json"
            }
        }
        
        if (envFile) {
            def file = new File(envFile)
            if (file.exists()) {
                try {
                    log.info("Reading iRODS environment from ${file.absolutePath}")
                    def parsed = new JsonSlurper().parse(file)
                    if (parsed instanceof Map) {
                        return (Map) parsed
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse iRODS environment file: ${e.message}")
                }
            }
        }
        return [:]
    }

    private static final List<Long> SEQ_LIST = [
        0xD768B678L, 0xEDFDAF56L, 0x2420231BL, 0x987098D8L,
        0xC1BDFEEEL, 0xF572341FL, 0x478DEF3AL, 0xA830D343L,
        0x774DFA2AL, 0x6720731EL, 0x346FA320L, 0x6FFDF43AL,
        0x7723A320L, 0xDF67D02EL, 0x86AD240AL, 0xE76D342EL
    ]

    private static final String WHEEL = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!\"#\$%&'()*+,-./"

    static int getUnixUid() {
        try {
            Class<?> c = Class.forName("com.sun.security.auth.module.UnixSystem")
            Object system = c.getDeclaredConstructor().newInstance()
            int uid = (int) (long) c.getMethod("getUid").invoke(system)
            log.info("UnixSystem reflection succeeded, UID: ${uid}")
            return uid
        } catch (Throwable e) {
            log.warn("UnixSystem reflection failed: ${e.toString()}")
            for (def cmd in ["id -u", "/usr/bin/id -u", "/bin/id -u"]) {
                try {
                    def proc = cmd.execute()
                    proc.waitFor()
                    if (proc.exitValue() == 0) {
                        int uid = proc.text.trim().toInteger()
                        log.info("Command '${cmd}' succeeded, UID: ${uid}")
                        return uid
                    } else {
                        log.warn("Command '${cmd}' exited with code ${proc.exitValue()}: ${proc.err.text.trim()}")
                    }
                } catch (Throwable ex) {
                    log.warn("Command '${cmd}' failed: ${ex.toString()}")
                }
            }
            log.error("All methods to retrieve Unix UID failed, defaulting to 0")
            return 0
        }
    }

    static String descramble(String scrambled) {
        if (!scrambled || scrambled.length() < 7) {
            return ""
        }
        scrambled = scrambled.trim()
        int seqIndex = (int) scrambled.charAt(6) - (int) 'e'
        if (seqIndex < 0 || seqIndex >= SEQ_LIST.size()) {
            return ""
        }
        long seq = SEQ_LIST[seqIndex]
        int bitshift = 15
        int uid = getUnixUid()
        log.info("iRODS descramble: getUnixUid() returned UID ${uid}")

        String encodedString = scrambled.substring(7)
        StringBuilder decoded = new StringBuilder()

        for (int i = 0; i < encodedString.length(); i++) {
            char c = encodedString.charAt(i)
            if (c == (char) 0) {
                break
            }
            long offset = ((seq >> bitshift) & 0x1FL) + (uid & 0xF5FL)
            bitshift += 3
            if (bitshift > 28) {
                bitshift = 0
            }

            int wheelIndex = WHEEL.indexOf((int) c)
            if (wheelIndex != -1) {
                int len = WHEEL.length()
                int targetIndex = (int) ((len + wheelIndex - offset) % len)
                if (targetIndex < 0) {
                    targetIndex += len
                }
                decoded.append(WHEEL.charAt(targetIndex))
            } else {
                decoded.append(c)
            }
        }
        String result = decoded.toString()
        log.info("iRODS descramble: decoded password length is " + result.length() + ", starts with: " + (result.length() > 3 ? result.substring(0, 3) : "TOO SHORT"))
        return result
    }

    private String readObfuscatedPassword(Map envConfig) {
        def authFile = envConfig.irods_authentication_file
        if (!authFile) {
            def home = System.getProperty("user.home") ?: System.getenv("HOME")
            if (home) {
                authFile = "${home}/.irods/.irodsA"
            }
        } else {
            if (!authFile.startsWith("/")) {
                def home = System.getProperty("user.home") ?: System.getenv("HOME")
                if (home) {
                    authFile = "${home}/.irods/${authFile}"
                }
            }
        }

        if (authFile) {
            def file = new File(authFile)
            if (file.exists()) {
                try {
                    log.info("Reading obfuscated password from ${file.absolutePath}")
                    String scrambled = file.text
                    return descramble(scrambled)
                } catch (Exception e) {
                    log.warn("Failed to read or decode obfuscated password file: ${e.message}")
                }
            }
        }
        return ""
    }

    private void initIrods() {
        boolean fromObfuscatedFile = false
        try {
            def envConfig = readIrodsEnvironment()

            String host = config.host ?: System.getenv("IRODS_HOST") ?: envConfig.irods_host ?: "localhost"
            int port = config.port ? (config.port as int) : (System.getenv("IRODS_PORT") ? System.getenv("IRODS_PORT").toInteger() : (envConfig.irods_port ? envConfig.irods_port as int : 1247))
            String username = config.username ?: System.getenv("IRODS_USER_NAME") ?: envConfig.irods_user_name ?: System.getProperty("user.name")
            String password = config.password ?: System.getenv("IRODS_PASSWORD") ?: ""
            if (!password) {
                password = readObfuscatedPassword(envConfig)
                if (password) {
                    fromObfuscatedFile = true
                }
            }
            String zone = config.zone ?: System.getenv("IRODS_ZONE_NAME") ?: envConfig.irods_zone_name ?: ""
            String defaultStorageResource = config.defaultStorageResource ?: System.getenv("IRODS_DEFAULT_RESOURCE") ?: envConfig.irods_default_resource ?: ""
            String homeDirectory = config.homeDirectory ?: envConfig.irods_home ?: "/${zone}/home/${username}"
            String authSchemeStr = config.authenticationScheme ?: envConfig.irods_authentication_scheme ?: ""
            String clientServerPolicy = config.sslNegotiationPolicy ?: envConfig.irods_client_server_policy ?: ""

            log.info("Connecting to iRODS at ${host}:${port} as ${username} (zone: ${zone})")
            
            this.irodsFileSystem = IRODSFileSystem.instance()

            // Configure SSL Negotiation Policy on the session if specified
            if (clientServerPolicy) {
                def session = irodsFileSystem.getIrodsSession()
                def props = new SettableJargonProperties(session.getJargonProperties())
                if (clientServerPolicy.equalsIgnoreCase("CS_NEG_REQUIRE")) {
                    log.info("Setting SSL negotiation policy to CS_NEG_REQUIRE")
                    props.setNegotiationPolicy(SslNegotiationPolicy.CS_NEG_REQUIRE)
                } else if (clientServerPolicy.equalsIgnoreCase("CS_NEG_DONT_CARE")) {
                    log.info("Setting SSL negotiation policy to CS_NEG_DONT_CARE")
                    props.setNegotiationPolicy(SslNegotiationPolicy.CS_NEG_DONT_CARE)
                } else if (clientServerPolicy.equalsIgnoreCase("CS_NEG_REFUSE")) {
                    log.info("Setting SSL negotiation policy to CS_NEG_REFUSE")
                    props.setNegotiationPolicy(SslNegotiationPolicy.CS_NEG_REFUSE)
                }
                session.setJargonProperties(props)
            }

            this.irodsAccount = IRODSAccount.instance(host, port, username, password, homeDirectory, zone, defaultStorageResource)
            
            // Configure PAM Authentication if specified
            if (authSchemeStr.equalsIgnoreCase("pam") || authSchemeStr.equalsIgnoreCase("pam_password")) {
                if (fromObfuscatedFile) {
                    log.info("Using STANDARD authentication for iRODS connection because password was read from obfuscated file (which contains the negotiated short-lived token)")
                    irodsAccount.setAuthenticationScheme(AuthScheme.STANDARD)
                } else {
                    log.info("Using PAM authentication for iRODS connection")
                    irodsAccount.setAuthenticationScheme(AuthScheme.PAM)
                }
            }

            this.fileFactory = irodsFileSystem.getIRODSAccessObjectFactory().getIRODSFileFactory(irodsAccount)
        } catch (Exception e) {
            String msg = e.getMessage() ?: ""
            if (e.getClass().getSimpleName() == "InvalidUserException" || msg.contains("-816000") || msg.contains("-840000")) {
                if (fromObfuscatedFile) {
                    log.error("")
                    log.error("=========================================================================")
                    log.error(" iRODS Authentication Failed! ")
                    log.error(" Your short-lived PAM token (~/.irods/.irodsA) has likely EXPIRED.")
                    log.error(" Please generate a fresh token by running: iinit")
                    log.error(" Hint: If running a pipeline, use 'iinit --ttl 120' to avoid expiration.")
                    log.error("=========================================================================")
                    log.error("")
                } else {
                    log.error("iRODS Authentication Error: Invalid user or password.")
                }
            }
            log.error("Failed to initialize iRODS filesystem connection", e)
            throw new IOException("Failed to initialize iRODS connection: " + msg, e)
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
