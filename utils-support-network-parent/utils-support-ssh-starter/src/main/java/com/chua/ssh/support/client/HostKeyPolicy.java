package com.chua.ssh.support.client;

/**
 * SSH 主机密钥校验策略。
 *
 * <p>未显式配置时 {@link SshClient} 使用 {@link #ACCEPT_NEW}（等价 OpenSSH 的
 * {@code StrictHostKeyChecking=accept-new}）：首次连接记录指纹，之后每次连接都比对，
 * 指纹变化即拒绝连接。这既能挡住中间人替换主机密钥，又不需要预先播种
 * {@code known_hosts}，因此可作为默认策略。</p>
 *
 * <p>MINA SSHD 在未设置 {@code ServerKeyVerifier} 时会静默使用
 * {@code AcceptAllServerKeyVerifier}——它接受任意密钥，只在日志里打一条 WARN。
 * 对「负责往生产机投放制品」这类场景，等于完全不设防，故此处以枚举形式
 * 把策略显式化。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum HostKeyPolicy {

    /**
     * 首次连接记录指纹，之后严格比对；指纹变化直接拒绝。
     *
     * <p>作为默认值，兼顾开箱可用与中间人防护。</p>
     */
    ACCEPT_NEW,

    /**
     * 只接受已记录在 {@code known_hosts} 中的主机密钥，未记录的一律拒绝。
     *
     * <p>适合 {@code known_hosts} 已由运维预置的受管环境。</p>
     */
    STRICT,

    /**
     * 接受任意主机密钥，不做校验。
     *
     * <p>仅供内网自签名临时排障使用，连接时会以 ERROR 级别告警，
     * 不建议在生产启用。</p>
     */
    INSECURE
}
