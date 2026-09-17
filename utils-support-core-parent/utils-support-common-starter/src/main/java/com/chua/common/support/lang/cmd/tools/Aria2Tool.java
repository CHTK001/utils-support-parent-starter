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
* 多线程下载工具 aria2c。
*
* <p>此前下载服务直接 {@code new ProcessBuilder("aria2c", ...)} 调用，
* 程序名硬编码且完全依赖 PATH，进程启动失败时只能得到笼统的 IOException。
* 改用本类后可预先用 {@link #isAvailable()} 判断，并得到明确的缺失提示。</p>
*
* <h3>使用示例</h3>
* <pre>{@code
* Aria2Tool aria2 = new Aria2Tool();
* if (aria2.isAvailable()) {
*     CmdResult result = aria2.download("https://example.com/file.zip", "/data/file.zip");
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("aria2c")
public class Aria2Tool extends CliTool {

    /**
    * 版本输出格式：{@code aria2 version 1.36.0}
    */
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("aria2 version (\\d[\\d.]*)");

    /**
    * 创建 aria2c 工具实例，使用预置的工具描述。
    */
    public Aria2Tool() {
        super(CliToolDescriptor.builder("aria2c")
                .displayName("aria2 多线程下载工具")
                .windowsExecutable("aria2c.exe")
                .envKey("ARIA2C_BIN")
                .candidateDirs(
                        "C:\\Program Files\\aria2",
                        "/usr/bin",
                        "/usr/local/bin",
                        "/opt/homebrew/bin")
                .versionArgs("--version")
                .versionPattern(VERSION_PATTERN)
                .minVersion(CliVersion.of(1, 30))
                .defaultTimeout(6, TimeUnit.HOURS)
                .installPackage("aria2")
                .build());
    }

    /**
    * 下载文件到指定路径，使用 16 线程断点续传。
    *
    * @param url      下载地址
    * @param savePath 保存路径
    * @return 执行结果
    */
    @Nonnull
    public CmdResult download(@Nonnull String url, @Nonnull String savePath) {
        return request()
                .args("-x", "16")
                .args("-s", "16")
                .args("-c")
                .args("-o", savePath)
                .args("--dir", ".")
                .args(url)
                .noTimeout()
                .execute();
    }

    /**
    * 以指定线程数下载文件。
    *
    * @param url         下载地址
    * @param savePath    保存路径
    * @param connections 连接数
    * @return 执行结果
    */
    @Nonnull
    public CmdResult download(@Nonnull String url, @Nonnull String savePath, int connections) {
        return request()
                .args("-x", String.valueOf(connections))
                .args("-s", String.valueOf(connections))
                .args("-c")
                .args("-o", savePath)
                .args("--dir", ".")
                .args(url)
                .noTimeout()
                .execute();
    }
}
