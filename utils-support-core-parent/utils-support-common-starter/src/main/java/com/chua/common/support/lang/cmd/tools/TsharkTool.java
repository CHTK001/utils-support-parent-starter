package com.chua.common.support.lang.cmd.tools;

import com.chua.common.support.lang.cmd.CliTool;
import com.chua.common.support.lang.cmd.CliToolDescriptor;
import com.chua.common.support.lang.cmd.CliVersion;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.spi.annotations.Spi;

import javax.annotation.Nonnull;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Wireshark 的命令行抓包工具 tshark。
 *
 * <p>此前监控模块直接 {@code new ProcessBuilder(TSHARK_BIN, "-D")} 调用，
 * 可执行文件只能靠 {@code TSHARK_BIN} 环境变量或 PATH 兜底，
 * 且没有版本校验与超时控制。改用本类后，定位、版本、超时由 {@link CliTool} 统一处理。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * TsharkTool tshark = new TsharkTool();
 * if (tshark.isAvailable()) {
 *     CmdResult result = tshark.listInterfaces();
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("tshark")
public class TsharkTool extends CliTool {

    /** 版本输出格式：{@code TShark (Wireshark) 3.6.2 (Git v3.6.2 ...)} */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("TShark \\(Wireshark\\) (\\d[\\d.]*)");

    /**
     * 创建 tshark 工具实例，使用预置的工具描述。
     */
    public TsharkTool() {
        super(CliToolDescriptor.builder("tshark")
                .displayName("Wireshark 命令行抓包工具")
                .windowsExecutable("tshark.exe")
                .envKey("TSHARK_BIN")
                .candidateDirs(
                        "C:\\Program Files\\Wireshark",
                        "C:\\Program Files (x86)\\Wireshark",
                        "/usr/bin",
                        "/usr/local/bin",
                        "/opt/local/bin",
                        "/Applications/Wireshark.app/Contents/MacOS")
                .versionArgs("--version")
                .versionPattern(VERSION_PATTERN)
                .minVersion(CliVersion.of(2, 6))
                .defaultTimeout(120, TimeUnit.SECONDS)
                .installPackage("wireshark")
                .build());
    }

    /**
     * 列出可用的网络接口，等价于 {@code tshark -D}。
     *
     * @return 执行结果，stdout 中每行为一个网卡
     */
    @Nonnull
    public CmdResult listInterfaces() {
        return execute("-D");
    }

    /**
     * 读取抓包文件并输出为 JSON，等价于 {@code tshark -r <file> -T json}。
     *
     * <p>由于解析大文件耗时较长，这里使用 5 分钟超时，不受默认超时限制。</p>
     *
     * @param captureFile 抓包文件路径
     * @return 执行结果，stdout 为 JSON 文本
     */
    @Nonnull
    public CmdResult readAsJson(@Nonnull String captureFile) {
        return execute(5, TimeUnit.MINUTES, "-r", captureFile, "-T", "json");
    }
}
