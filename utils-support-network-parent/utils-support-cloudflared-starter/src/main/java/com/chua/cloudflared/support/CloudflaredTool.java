package com.chua.cloudflared.support;

import com.chua.common.support.lang.cmd.CliTool;
import com.chua.common.support.lang.cmd.CliToolDescriptor;
import com.chua.common.support.lang.cmd.CliVersion;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Cloudflare Tunnel 命令行客户端 cloudflared。
 *
 * <p>cloudflared 是 Cloudflare 官方发布的隧道守护进程，负责把本机端口通过
 * Cloudflare 边缘网络暴露为公网 HTTPS 地址。本类复用 {@link CliTool} 统一完成
 * 可执行文件定位、版本探测与最低版本校验，避免各模块重复实现。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CloudflaredTool tool = new CloudflaredTool();
 * if (tool.isAvailable()) {
 *     System.out.println(tool.version().raw());
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("cloudflared")
public class CloudflaredTool extends CliTool {

    /**
     * 版本输出格式：{@code cloudflared version 2024.10.1 (built 2024-10-08-...)}
     */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("cloudflared version (\\d{4}\\.\\d+\\.\\d+)");

    /**
     * 创建 cloudflared 工具实例，使用预置的工具描述。
     */
    public CloudflaredTool() {
        super(CliToolDescriptor.builder("cloudflared")
                .displayName("Cloudflare Tunnel 客户端")
                .windowsExecutable("cloudflared.exe")
                .envKey("CLOUDFLARED_BIN")
                .candidateDirs(
                        "/usr/local/bin",
                        "/usr/bin",
                        "/opt/homebrew/bin",
                        "C:\\Program Files\\cloudflared",
                        "C:\\Program Files (x86)\\cloudflared")
                .versionArgs("--version")
                .versionPattern(VERSION_PATTERN)
                .minVersion(CliVersion.of(2022, 1))
                .defaultTimeout(60, TimeUnit.SECONDS)
                .build());
    }
}
