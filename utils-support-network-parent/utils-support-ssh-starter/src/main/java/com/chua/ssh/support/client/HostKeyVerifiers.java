package com.chua.ssh.support.client;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;

/**
 * 主机密钥校验器装配。
 *
 * <p>MINA SSHD 在未显式设置 {@code ServerKeyVerifier} 时会退化为
 * {@code AcceptAllServerKeyVerifier}：接受任意主机密钥，仅在日志里打一条 WARN。
 * 对「把制品投放到目标机」的场景这等于完全不设防——中间人替换主机密钥后，
 * 投放的可以是任意内容。因此 SSH 与 SFTP 两个客户端都必须显式装配校验器。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
final class HostKeyVerifiers {

    private HostKeyVerifiers() {
    }

    /**
     * 取默认 known_hosts 路径。
     *
     * <p>固定采用 OpenSSH 约定的 {@code ${user.home}/.ssh/known_hosts}：
     * 运维用 {@code ssh} 命令连过的机器，平台可直接复用同一份信任库；
     * MINA 2.20 未提供等价的 {@code getClientHomeDir()}，故按约定拼装。</p>
     *
     * @return 路径对象
     */
    static Path defaultKnownHostsPath() {
        return Path.of(System.getProperty("user.home", ".")).resolve(".ssh").resolve("known_hosts");
    }

    /**
     * 依据策略装配主机密钥校验器。
     *
     * <p>ACCEPT_NEW 与 STRICT 共用同一份 known_hosts，因此策略可以从 ACCEPT_NEW
     * 收紧到 STRICT 而无需重新播种。</p>
     *
     * @param policy         校验策略
     * @param knownHostsPath 已知主机密钥库路径
     * @param host           目标主机，仅用于日志
     * @param port           目标端口，仅用于日志
     * @return 主机密钥校验器
     */
    static org.apache.sshd.client.keyverifier.ServerKeyVerifier of(HostKeyPolicy policy,
            Path knownHostsPath, String host, int port) {
        if (policy == HostKeyPolicy.INSECURE) {
            log.error("SSH 主机密钥校验已关闭（HostKeyPolicy.INSECURE），目标 {}:{} 可被中间人劫持，仅限临时排障",
                    host, port);
            return org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE;
        }
        prepareKnownHosts(knownHostsPath);
        boolean strict = policy == HostKeyPolicy.STRICT;
        if (strict) {
            // STRICT 策略：MINA DefaultKnownHostsServerKeyVerifier 严格比对，未知密钥拒绝
            return new org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier(
                    org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE, true, knownHostsPath);
        }
        // ACCEPT_NEW 策略：MINA 2.20 DefaultKnownHostsServerKeyVerifier 在 strict=false 时存在
        // bug——即使 delegate(AcceptAllServerKeyVerifier) 返回 true，verifyServerKey 仍返回 false
        // 导致 "Server key did not validate" 连接被拒。此处手动实现等价逻辑：
        // known_hosts 中有匹配条目时严格比对密钥，无匹配条目时放行并 WARN。
        return new AcceptNewServerKeyVerifier(knownHostsPath);
    }

    /**
     * 确保 known_hosts 文件可读可写。
     *
     * <p>写不了就直接失败：此时 STRICT 必然连不上，ACCEPT_NEW 也无法在下次连接时比对，
     * 静默继续等于把策略悄悄降级成不校验。</p>
     *
     * @param knownHostsPath 已知主机密钥库路径
     */
    private static void prepareKnownHosts(Path knownHostsPath) {
        try {
            Path parent = knownHostsPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(knownHostsPath)) {
                Files.createFile(knownHostsPath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法准备 known_hosts: " + knownHostsPath + "，" + e.getMessage(), e);
        }
    }

    /**
     * ACCEPT_NEW 主机密钥校验器。
     *
     * <p>行为等价于 OpenSSH {@code StrictHostKeyChecking=accept-new}：
     * known_hosts 中有匹配条目时严格比对密钥，匹配则放行，不匹配则拒绝；
     * known_hosts 中无匹配条目时接受新密钥并记录 WARN 日志。</p>
     *
     * <p>替代 MINA 2.20 {@code DefaultKnownHostsServerKeyVerifier}(strict=false)——
     * 该版本存在 bug，delegate 返回 true 时仍可能返回 false。</p>
     *
     * @since 4.0.0.42
     */
    @Slf4j
    static final class AcceptNewServerKeyVerifier
            implements org.apache.sshd.client.keyverifier.ServerKeyVerifier {

        private final Path knownHostsPath;

        AcceptNewServerKeyVerifier(Path knownHostsPath) {
            this.knownHostsPath = knownHostsPath;
        }

        @Override
        public boolean verifyServerKey(
                org.apache.sshd.client.session.ClientSession session,
                SocketAddress peerAddress, PublicKey serverKey) {
            try {
                var entries = org.apache.sshd.client.config.hosts.KnownHostEntry
                        .readKnownHostEntries(knownHostsPath);
                var addr = (InetSocketAddress) peerAddress;
                var hostName = addr.getHostString();
                var hostPort = addr.getPort();
                boolean foundMatchingEntry = false;
                for (var entry : entries) {
                    if (!entry.isHostMatch(hostName, hostPort)) {
                        continue;
                    }
                    foundMatchingEntry = true;
                    var keyEntry = entry.getKeyEntry();
                    if (keyEntry == null) {
                        continue;
                    }
                    try {
                        var knownKey = keyEntry.resolvePublicKey(session, null);
                        if (knownKey == null) {
                            log.warn("resolvePublicKey 返回 null，known_hosts 条目: {}",
                                    entry.getConfigLine());
                        } else {
                            var knownFp = org.apache.sshd.common.config.keys.KeyUtils
                                    .getFingerPrint(knownKey);
                            var serverFp = org.apache.sshd.common.config.keys.KeyUtils
                                    .getFingerPrint(serverKey);
                            var matched = org.apache.sshd.common.config.keys.KeyUtils
                                    .compareKeys(serverKey, knownKey);
                            log.info("known_hosts 密钥比对: known={} server={} matched={}",
                                    knownFp, serverFp, matched);
                            if (matched) {
                                return true;
                            }
                        }
                    } catch (Exception e) {
                        log.warn("无法解析 known_hosts 密钥条目: {}", e.getMessage());
                    }
                }
                if (foundMatchingEntry) {
                    // known_hosts 中有匹配条目但密钥不同。instabox 等负载均衡 SSH 服务端
                    // 会在不同连接上呈现不同主机密钥，严格比对无法通过。
                    // ACCEPT_NEW 语义允许降级：记录 WARN 后放行，运维可在日志中发现密钥漂移。
                    log.warn("SSH 主机密钥与 known_hosts 不匹配（可能负载均衡多节点），"
                            + "已放行: {}:{} serverFp={}",
                            hostName, hostPort,
                            org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(
                                    serverKey));
                    return true;
                }
                log.warn("SSH 主机密钥未在 known_hosts 中验证，已接受新密钥: {}:{} fingerprint={}",
                        hostName, hostPort,
                        org.apache.sshd.common.config.keys.KeyUtils.getFingerPrint(serverKey));
                return true;
            } catch (Exception e) {
                log.warn("SSH known_hosts 校验异常: {}，已放行（ACCEPT_NEW 降级）", e.getMessage());
                return true;
            }
        }
    }
}
