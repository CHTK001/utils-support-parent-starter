package com.chua.remote.support.gateway.ssh;

import com.chua.common.support.utils.DigestUtils;
import com.chua.common.support.utils.StringUtils;
import com.chua.remote.support.gateway.agent.AgentInfo;
import com.chua.remote.support.gateway.agent.AgentRegistry;
import com.chua.remote.support.gateway.config.GatewayProperties;
import com.chua.ssh.support.client.SftpClient;
import com.chua.ssh.support.client.SshClient;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway driven bootstrap for a temporary SOCKS5-capable Agent over SSH reverse forwarding.
 *
 * <p>The remote server only needs SSH access. Gateway opens an SSH session to the remote server,
 * creates a remote loopback port that forwards back to the local Gateway agent port, uploads an
 * Agent jar, and starts that Agent against the remote loopback port.</p>
 *
 * @author CH
 */
@Slf4j
public class Socks5AgentBootstrapManager {

    private static final int DEFAULT_SSH_PORT = 22;
    private static final int CONNECT_TIMEOUT_S = 15;
    private static final int COMMAND_TIMEOUT_S = 20;
    private static final int REGISTRATION_WAIT_TIMEOUT_MS = 12_000;
    private static final int REGISTRATION_WAIT_INTERVAL_MS = 300;
    private static final int DEFAULT_REMOTE_PORT_START = 39_001;
    private static final int DEFAULT_REMOTE_PORT_END = 49_999;
    private static final String DEFAULT_REMOTE_BASE_DIR = "/tmp/utils-remote-agent";
    private static final String AGENT_JAR_NAME = "socks5-agent.jar";
    private static final String PID_FILE_NAME = "agent.pid";
    private static final String PACKAGE_ACTION_SKIPPED = "skipped_same_md5";
    private static final String PACKAGE_ACTION_UPLOADED = "uploaded";
    private static final String PACKAGE_ACTION_FORCED = "forced_uploaded";

    private final GatewayProperties properties;
    private final AgentRegistry agentRegistry;
    private final Map<String, BootstrapInstance> instances = new ConcurrentHashMap<>();

    public Socks5AgentBootstrapManager(GatewayProperties properties) {
        this(properties, null);
    }

    public Socks5AgentBootstrapManager(GatewayProperties properties, AgentRegistry agentRegistry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.agentRegistry = agentRegistry;
    }

    public BootstrapResult bootstrap(BootstrapRequest request) throws Exception {
        validate(request);
        String agentId = StringUtils.defaultString(request.agentId(), "socks5-" + shortId());
        String remoteBaseDir = StringUtils.defaultString(request.remoteBaseDir(), DEFAULT_REMOTE_BASE_DIR);
        String remoteWorkDir = normalizeRemotePath(remoteBaseDir) + "/" + shellSafePathPart(agentId);
        String remoteJar = remoteWorkDir + "/" + AGENT_JAR_NAME;
        String remotePid = remoteWorkDir + "/" + PID_FILE_NAME;
        int localAgentPort = request.localAgentPort() > 0 ? request.localAgentPort() : properties.getTcpAgentPort();
        Path localJar = resolveAgentJar(request.agentJarPath());
        String localJarMd5 = DigestUtils.md5(Files.readAllBytes(localJar));
        String remoteJarMd5 = null;
        String packageAction;

        BootstrapInstance old = instances.remove(agentId);
        if (old != null) {
            try {
                if (old.sshClient() != null) {
                    stopRemoteAgent(old.sshClient(), old.remotePidFile(), old.remoteJar());
                }
            } catch (Exception e) {
                log.warn("[Socks5Bootstrap] stop old temporary agent failed: agentId={} {}", agentId, e.getMessage());
            } finally {
                old.close();
                clearTrustedTemporaryAgent(agentId);
            }
        }

        SshClient sshClient = createSshClient(request);
        sshClient.connect();
        try {
            int remoteListenPort = openReverseForward(sshClient, request, localAgentPort);
            markTrustedTemporaryAgent(agentId, request, remoteListenPort, remoteWorkDir);
            exec(sshClient, "mkdir -p " + quote(remoteWorkDir) + " " + quote(remoteWorkDir + "/logs"));
            stopRemoteAgent(sshClient, remotePid, remoteJar);
            remoteJarMd5 = fetchRemoteJarMd5(sshClient, remoteJar);
            boolean shouldUpload = shouldUploadJar(localJarMd5, remoteJarMd5, request.forceUpdate());
            packageAction = resolvePackageAction(localJarMd5, remoteJarMd5, request.forceUpdate());
            if (shouldUpload) {
                upload(sshClient, localJar, remoteJar);
                remoteJarMd5 = localJarMd5;
            }
            exec(sshClient, "chmod 0644 " + quote(remoteJar));

            String startCommand = buildStartCommand(request, agentId, remoteListenPort, remoteJar, remotePid, remoteWorkDir);
            SshClient.ExecResult startResult = exec(sshClient, startCommand);
            if (startResult.exitCode() != 0) {
                throw new IllegalStateException("remote agent start failed: " + startResult.stdout() + startResult.stderr());
            }

            Instant createdAt = Instant.now();
            BootstrapInstance instance = new BootstrapInstance(agentId, request.verifyCode(), sshClient, remoteListenPort,
                    localAgentPort, remoteWorkDir, remoteJar, remotePid, localJarMd5, remoteJarMd5, packageAction,
                    request.forceUpdate(), createdAt);
            instances.put(agentId, instance);
            boolean registered = waitForAgentRegistration(agentId);
            if (!registered) {
                log.warn("[Socks5Bootstrap] temporary agent started but not registered yet: agentId={} remoteHost={} remotePort={}",
                        agentId, request.sshHost(), remoteListenPort);
            }
            log.info("[Socks5Bootstrap] agent jar sync: agentId={} action={} localMd5={} remoteMd5={} forceUpdate={}",
                    agentId, packageAction, localJarMd5, remoteJarMd5, request.forceUpdate());
            log.info("[Socks5Bootstrap] temporary agent bootstrapped: agentId={} remote=127.0.0.1:{} -> gatewayAgentPort={} workDir={}",
                    agentId, remoteListenPort, localAgentPort, remoteWorkDir);
            return new BootstrapResult(agentId, request.verifyCode(), remoteListenPort, localAgentPort,
                    remoteWorkDir, remoteJar, true, registered,
                    registered ? "registered" : "waiting_agent_registration",
                    request.forceUpdate(), packageAction, localJarMd5, remoteJarMd5, createdAt);
        } catch (Exception e) {
            try {
                sshClient.disconnect();
            } catch (Exception ignored) {
            }
            clearTrustedTemporaryAgent(agentId);
            throw e;
        }
    }

    public boolean stop(String agentId) throws Exception {
        BootstrapInstance instance = instances.remove(agentId);
        if (instance == null) {
            return false;
        }
        try {
            if (instance.sshClient() != null) {
                stopRemoteAgent(instance.sshClient(), instance.remotePidFile(), instance.remoteJar());
            }
        } finally {
            instance.close();
            clearTrustedTemporaryAgent(agentId);
        }
        log.info("[Socks5Bootstrap] temporary agent stopped: agentId={} remotePort={}", agentId, instance.remoteListenPort());
        return true;
    }

    public Map<String, BootstrapResult> list() {
        Map<String, BootstrapResult> result = new LinkedHashMap<>();
        for (BootstrapInstance instance : instances.values()) {
            result.put(instance.agentId(), toResult(instance));
        }
        return result;
    }

    public BootstrapResult get(String agentId) {
        BootstrapInstance instance = instances.get(agentId);
        return instance == null ? null : toResult(instance);
    }

    public void closeAll() {
        for (String agentId : instances.keySet().toArray(String[]::new)) {
            try {
                stop(agentId);
            } catch (Exception e) {
                log.warn("[Socks5Bootstrap] close temporary agent failed: agentId={} {}", agentId, e.getMessage());
            }
        }
        instances.clear();
    }

    private BootstrapResult toResult(BootstrapInstance instance) {
        boolean connected = instance.sshClient() != null;
        boolean registered = isAgentRegistered(instance.agentId());
        return new BootstrapResult(instance.agentId(), instance.verifyCode(), instance.remoteListenPort(),
                instance.localAgentPort(), instance.remoteWorkDir(), instance.remoteJar(), connected, registered,
                connected ? (registered ? "registered" : "waiting_agent_registration") : "ssh_disconnected",
                instance.forceUpdate(), instance.packageAction(), instance.localJarMd5(), instance.remoteJarMd5(),
                instance.createdAt());
    }

    private boolean waitForAgentRegistration(String agentId) throws InterruptedException {
        if (agentRegistry == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + REGISTRATION_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() <= deadline) {
            if (isAgentRegistered(agentId)) {
                return true;
            }
            Thread.sleep(REGISTRATION_WAIT_INTERVAL_MS);
        }
        return false;
    }

    private boolean isAgentRegistered(String agentId) {
        if (agentRegistry == null || StringUtils.isBlank(agentId)) {
            return false;
        }
        AgentInfo agent = agentRegistry.getAgent(agentId);
        return agent != null && agent.isOnline()
                && agent.getChannel() != null && agent.getChannel().isActive();
    }

    private void validate(BootstrapRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request required");
        }
        if (StringUtils.isBlank(request.sshHost())) {
            throw new IllegalArgumentException("sshHost required");
        }
        if (StringUtils.isBlank(request.sshUsername())) {
            throw new IllegalArgumentException("sshUsername required");
        }
        if (StringUtils.isBlank(request.verifyCode())) {
            throw new IllegalArgumentException("verifyCode required");
        }
        if (StringUtils.isBlank(request.managementToken())) {
            throw new IllegalArgumentException("managementToken required");
        }
        if (StringUtils.isBlank(request.sshPassword()) && StringUtils.isBlank(request.sshKeyFile())) {
            throw new IllegalArgumentException("sshPassword or sshKeyFile required");
        }
    }

    private int openReverseForward(SshClient sshClient, BootstrapRequest request, int localAgentPort) throws Exception {
        if (request.remoteListenPort() > 0) {
            sshClient.forward().remote(request.remoteListenPort(), "127.0.0.1", localAgentPort).start();
            return request.remoteListenPort();
        }
        int start = request.remotePortStart() > 0 ? request.remotePortStart() : DEFAULT_REMOTE_PORT_START;
        int end = request.remotePortEnd() >= start ? request.remotePortEnd() : DEFAULT_REMOTE_PORT_END;
        int span = end - start + 1;
        int offset = Math.floorMod(UUID.randomUUID().hashCode(), span);
        Exception last = null;
        for (int i = 0; i < span; i++) {
            int port = start + ((offset + i) % span);
            try {
                sshClient.forward().remote(port, "127.0.0.1", localAgentPort).start();
                return port;
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IOException("no available remote loopback port in " + start + "-" + end, last);
    }

    private SshClient createSshClient(BootstrapRequest request) {
        SshClient.Builder builder = SshClient.builder()
                .host(request.sshHost())
                .port(request.sshPort() > 0 ? request.sshPort() : DEFAULT_SSH_PORT)
                .username(request.sshUsername())
                .connectTimeout(CONNECT_TIMEOUT_S)
                .sessionTimeout(COMMAND_TIMEOUT_S);
        if (!StringUtils.isBlank(request.sshPassword())) {
            builder.password(request.sshPassword());
        }
        if (!StringUtils.isBlank(request.sshKeyFile())) {
            builder.privateKey(request.sshKeyFile());
        }
        return builder.build();
    }

    private Path resolveAgentJar(String configuredPath) throws IOException {
        if (!StringUtils.isBlank(configuredPath)) {
            Path jar = Path.of(configuredPath).toAbsolutePath().normalize();
            if (Files.isRegularFile(jar)) {
                return jar;
            }
            throw new IOException("agentJarPath not found: " + jar);
        }
        Path moduleDir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path target = firstExistingDirectory(
                moduleDir.resolveSibling("utils-support-remote-agent-starter").resolve("target"),
                moduleDir.resolve("..").resolve("utils-support-remote-agent-starter").resolve("target"),
                moduleDir.resolve("utils-support-protocol-parent").resolve("utils-support-remote-agent-starter").resolve("target"),
                moduleDir.resolve("..").resolve("utils-support-protocol-parent").resolve("utils-support-remote-agent-starter").resolve("target")
        );
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                return stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith("utils-support-remote-agent-starter-"))
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .filter(path -> !path.getFileName().toString().startsWith("original-"))
                        .max((a, b) -> {
                            try {
                                return Files.getLastModifiedTime(a).compareTo(Files.getLastModifiedTime(b));
                            } catch (IOException e) {
                                return 0;
                            }
                        })
                        .orElseThrow(() -> new IOException("agent jar not found in " + target));
            }
        }
        throw new IOException("agent jar not found; provide agentJarPath");
    }

    private static Path firstExistingDirectory(Path... paths) {
        for (Path path : paths) {
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) {
                return normalized;
            }
        }
        return paths.length == 0 ? Path.of(".") : paths[0].toAbsolutePath().normalize();
    }

    private void upload(SshClient sshClient, Path localJar, String remoteJar) throws Exception {
        SftpClient.Builder builder = SftpClient.builder()
                .host(sshClient.getHost())
                .port(sshClient.getPort())
                .username(sshClient.getUsername());
        if (sshClient.getPassword() != null && !sshClient.getPassword().isEmpty()) {
            builder.password(sshClient.getPassword());
        }
        if (sshClient.getPrivateKeyPath() != null && !sshClient.getPrivateKeyPath().isEmpty()) {
            builder.privateKey(sshClient.getPrivateKeyPath());
        }
        SftpClient sftp = builder.build();
        sftp.connect();
        try {
            sftp.upload().local(localJar.toString()).remote(remoteJar).exec();
            log.info("[Socks5Bootstrap] uploaded agent jar: {} -> {}", localJar, remoteJar);
        } finally {
            sftp.disconnect();
        }
    }

    private void stopRemoteAgent(SshClient sshClient, String remotePid, String remoteJar) throws Exception {
        String command = "if [ -f " + quote(remotePid) + " ]; then "
                + "pid=$(cat " + quote(remotePid) + " 2>/dev/null || true); "
                + "if [ -n \"$pid\" ] && kill -0 \"$pid\" 2>/dev/null; then kill \"$pid\" 2>/dev/null || true; "
                + "for i in 1 2 3 4 5; do kill -0 \"$pid\" 2>/dev/null || break; sleep 1; done; "
                + "kill -9 \"$pid\" 2>/dev/null || true; fi; "
                + "rm -f " + quote(remotePid) + "; fi; "
                + "ps -eo pid=,args= | while read -r pid args; do "
                + "case \"$args\" in *" + quote(remoteJar) + "*) "
                + "if [ \"$pid\" != \"$$\" ]; then kill \"$pid\" 2>/dev/null || true; fi ;; "
                + "esac; done; "
                + "rm -f " + quote(remotePid);
        SshClient.ExecResult result = exec(sshClient, command);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("stop old remote agent failed: " + result.stdout() + result.stderr());
        }
    }

    private String buildStartCommand(BootstrapRequest request, String agentId, int remoteListenPort,
                                     String remoteJar, String remotePid, String remoteWorkDir) {
        String javaBin = StringUtils.defaultString(request.javaBin(), "java");
        String logFile = remoteWorkDir + "/logs/agent.log";
        return "cd " + quote(remoteWorkDir) + " && "
                + "rm -f " + quote(remotePid) + " && "
                + "( nohup " + quote(javaBin)
                + " -jar " + quote(remoteJar)
                + " --gateway.host=127.0.0.1"
                + " --gateway.port=" + remoteListenPort
                + " --agent.id=" + shellArg(agentId)
                + " --agent.secret=" + shellArg(properties.getAgentRegisterKey())
                + " --agent.verify-code=" + shellArg(request.verifyCode())
                + " --agent.protocols=SOCKS5"
                + " --agent.transport=TCP"
                + " --agent.capability.agent.temporary=true"
                + " --agent.capability.agent.bootstrap=ssh"
                + " --agent.capability.agent.remoteHost=" + shellArg(request.sshHost())
                + " --agent.capability.agent.remotePort=" + (request.sshPort() > 0 ? request.sshPort() : DEFAULT_SSH_PORT)
                + " --agent.capability.agent.remoteListenPort=" + remoteListenPort
                + " --agent.capability.agent.remoteWorkDir=" + shellArg(remoteWorkDir)
                + " < /dev/null > " + quote(logFile) + " 2>&1 & echo $! > " + quote(remotePid) + " )"
                + " < /dev/null > /dev/null 2>&1";
    }

    private void markTrustedTemporaryAgent(String agentId, BootstrapRequest request,
                                           int remoteListenPort, String remoteWorkDir) {
        if (agentRegistry == null) {
            return;
        }
        agentRegistry.markTemporarySshAgent(agentId, request.sshHost(),
                request.sshPort() > 0 ? request.sshPort() : DEFAULT_SSH_PORT,
                remoteListenPort, remoteWorkDir);
    }

    private void clearTrustedTemporaryAgent(String agentId) {
        if (agentRegistry == null) {
            return;
        }
        agentRegistry.clearTemporarySshAgent(agentId);
    }

    private SshClient.ExecResult exec(SshClient sshClient, String command) {
        return sshClient.exec().command(command).execute();
    }

    private String fetchRemoteJarMd5(SshClient sshClient, String remoteJar) {
        String command = "if [ -f " + quote(remoteJar) + " ]; then "
                + "if command -v md5sum >/dev/null 2>&1; then md5sum " + quote(remoteJar) + " | awk '{print $1}'; "
                + "elif command -v busybox >/dev/null 2>&1; then busybox md5sum " + quote(remoteJar) + " | awk '{print $1}'; "
                + "elif command -v openssl >/dev/null 2>&1; then openssl dgst -md5 " + quote(remoteJar) + " | awk '{print $NF}'; "
                + "fi; fi";
        SshClient.ExecResult result = exec(sshClient, command);
        if (result.exitCode() != 0) {
            return null;
        }
        String md5 = result.stdout() == null ? "" : result.stdout().trim();
        return md5.isEmpty() ? null : md5;
    }

    private static String quote(String value) {
        return "'" + String.valueOf(value).replace("'", "'\\''") + "'";
    }

    private static String shellArg(String value) {
        return quote(StringUtils.defaultString(value, ""));
    }

    static boolean shouldUploadJar(String localJarMd5, String remoteJarMd5, boolean forceUpdate) {
        if (forceUpdate) {
            return true;
        }
        if (StringUtils.isBlank(localJarMd5) || StringUtils.isBlank(remoteJarMd5)) {
            return true;
        }
        return !localJarMd5.equalsIgnoreCase(remoteJarMd5);
    }

    static String resolvePackageAction(String localJarMd5, String remoteJarMd5, boolean forceUpdate) {
        if (forceUpdate) {
            return PACKAGE_ACTION_FORCED;
        }
        if (StringUtils.isBlank(localJarMd5) || StringUtils.isBlank(remoteJarMd5) || !localJarMd5.equalsIgnoreCase(remoteJarMd5)) {
            return PACKAGE_ACTION_UPLOADED;
        }
        return PACKAGE_ACTION_SKIPPED;
    }

    private static String shellSafePathPart(String value) {
        String raw = StringUtils.defaultString(value, shortId());
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String normalizeRemotePath(String path) {
        String p = StringUtils.defaultString(path, DEFAULT_REMOTE_BASE_DIR).replace('\\', '/');
        while (p.endsWith("/") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public record BootstrapRequest(String sshHost, int sshPort, String sshUsername, String sshPassword,
                                   String sshKeyFile, String agentId, String verifyCode, String managementToken,
                                   String remoteBaseDir, int remoteListenPort, int remotePortStart,
                                   int remotePortEnd, int localAgentPort, String agentJarPath,
                                   String javaBin, boolean forceUpdate) {
    }

    public record BootstrapResult(String agentId, String verifyCode, int remoteListenPort, int localAgentPort,
                                  String remoteWorkDir, String remoteJar, boolean running, boolean agentRegistered, String status,
                                  boolean forceUpdate, String packageAction, String localJarMd5, String remoteJarMd5,
                                  Instant createdAt) {
    }

    private record BootstrapInstance(String agentId, String verifyCode, SshClient sshClient, int remoteListenPort,
                                     int localAgentPort, String remoteWorkDir, String remoteJar,
                                     String remotePidFile, String localJarMd5, String remoteJarMd5, String packageAction,
                                     boolean forceUpdate, Instant createdAt) {
        private void close() {
            try {
                if (sshClient != null) {
                    sshClient.disconnect();
                }
            } catch (Exception ignored) {
            }
        }
    }
}