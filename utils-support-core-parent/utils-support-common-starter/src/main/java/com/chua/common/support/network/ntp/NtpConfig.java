package com.chua.common.support.network.ntp;

import lombok.Builder;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * NTP 配置。
 *
 * <p>同时用于 {@link NtpServer}（监听地址/端口、层级、参考时钟标识）
 * 与 {@link NtpClient}（查询超时、协议版本）。</p>
 *
 * <p>标准 NTP 使用 UDP 123 端口。绑定 123 需要相应权限（Linux 为 root），
 * 测试或受限环境可通过 {@code port} 改用高端口，客户端查询时指定相同端口即可。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Data
@Builder
@Accessors(chain = true)
public class NtpConfig {

    /**
     * 服务端绑定地址，默认 0.0.0.0（所有网卡）
     */
    @Builder.Default
    private String bindHost = "0.0.0.0";

    /**
     * 服务端监听端口。标准 NTP 为 123；0 表示由系统分配（仅建议测试使用）
     */
    @Builder.Default
    private int port = 123;

    /**
     * 客户端单次查询超时（毫秒）
     */
    @Builder.Default
    private long timeoutMillis = 5_000L;

    /**
     * NTP 协议版本（RFC 2030 为版本 4）
     */
    @Builder.Default
    private int version = 4;

    /**
     * 服务端层级（stratum）：1 = 主时钟（如 GPS/原子钟），2 起为次级同步源
     */
    @Builder.Default
    private int stratum = 2;

    /**
     * 服务端参考时钟标识：层级 1 时为 ASCII 助记符（如 GPS），
     * 层级 2+ 时通常为上级 NTP 服务器 IPv4 地址；默认 LOCL（本地时钟）
     */
    @Builder.Default
    private String referenceId = "LOCL";
}
