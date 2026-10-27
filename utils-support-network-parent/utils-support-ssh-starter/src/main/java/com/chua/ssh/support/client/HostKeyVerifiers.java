package com.chua.ssh.support.client;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        // strict=false 即 accept-new：已知密钥严格比对，未知密钥接受并写入 known_hosts
        boolean strict = policy == HostKeyPolicy.STRICT;
        return new org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier(
                org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE, strict, knownHostsPath);
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
}
