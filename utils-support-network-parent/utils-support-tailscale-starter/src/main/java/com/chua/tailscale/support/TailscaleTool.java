// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
// [P3C E1 豁免] E1: 含 System.out 的代码示例在 Javadoc <pre> 中，非实际执行。
package com.chua.tailscale.support;

import com.chua.common.support.lang.cmd.CliTool;
import com.chua.common.support.lang.cmd.CliToolDescriptor;
import com.chua.common.support.lang.cmd.CliVersion;
import com.chua.common.support.spi.annotations.Spi;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Tailscale 命令行客户端。
 *
 * <p>Windows 下默认位于 {@code C:\Program Files\Tailscale\tailscale.exe}，Linux/macOS 上
 * 加入 PATH 即可。Tailscale 不提供可内嵌的 Java SDK（ngrok-java 那样的），
 * 因此本类仅负责把官方 CLI 定位、版本探测与最低版本校验收敛为一处，
 * 进程管理交给 {@link TailscaleTunnelClient}。</p>
 *
 * <h3>版本输出</h3>
 * <pre>{@code
 * 1.102.4
 * tailscale commit: 3caf7d9e7dcaba589cfc58beda596929733e4fea
 * long version: 1.102.4-t3caf7d9e7-g084ee3b64
 * }</pre>
 * 解析后主版本为 {@code 1.102.4}，限定符部分不参与比较。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * TailscaleTool tool = new TailscaleTool();
 * if (tool.isAvailable()) {
 *     System.out.println("Tailscale 版本: " + tool.version().raw());
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.53
 */
@Spi("tailscale")
public class TailscaleTool extends CliTool {

    /**
     * 版本输出格式：{@code 1.102.4-t3caf7d9e7-g084ee3b64}，
     * 需把限定符中的非数字段（如 {@code 3caf7d9e7}）排除在比较之外。
     */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(\\d+\\.\\d+\\.\\d+)(-\\S+)?");

    /**
     * 创建 Tailscale 工具实例，使用预置的工具描述。
     */
    public TailscaleTool() {
        super(CliToolDescriptor.builder("tailscale")
                .displayName("Tailscale 命令行客户端")
                .windowsExecutable("tailscale.exe")
                .envKey("TAILSCALE_BIN")
                .candidateDirs(
                        "C:\\Program Files\\Tailscale",
                        "C:\\Program Files (x86)\\Tailscale",
                        "/usr/bin",
                        "/usr/local/bin")
                .versionArgs("--version")
                .versionPattern(VERSION_PATTERN)
                .minVersion(CliVersion.of(1, 50))
                .defaultTimeout(60, TimeUnit.SECONDS)
                .build());
    }
}
