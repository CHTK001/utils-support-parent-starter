package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.datasearch.software.model.SoftwareInfo;
import com.chua.common.support.datasearch.software.spi.SoftwareProvider;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
* Cargo 软件包管理器提供器。
*
* <p>通过 cargo CLI 搜索、安装和卸载 Rust crate。
* 支持 <code>Cargo 搜索</code>、<code>Cargo install</code>、<code>Cargo uninstall</code>。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("cargo")
public class CargoSoftwareProvider implements SoftwareProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(CargoSoftwareProvider.class);

    /** 名称 */
    private static final String NAME = "cargo";

    @Override
    /** 名称 */
    public String name() {
        return NAME;
    }

    @Override
    /** 搜索 */
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "cargo search " + keyword + " --limit 10";

        log.info("cargo 搜索: keyword={}", keyword);
        StringBuilder outputBuffer = new StringBuilder();
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 30, TimeUnit.SECONDS, new LineCallback() {
            @Override
            /** on线 */
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
            }

            @Override
            /** on完成 */
            public void onComplete(int exitCode) {
                log.info("cargo 搜索完成, exitCode={}", exitCode);
            }

            @Override
            /** On记录错误 */
            public void onError(String command, Throwable throwable) {
                log.warn("cargo 搜索异常: {}", throwable.getMessage());
            }
        });

        if (!result.isSuccess() || outputBuffer.isEmpty()) {
            return results;
        }

        return parseCargoOutput(outputBuffer.toString());
    }

    @Override
    /** Install */
    public boolean install(String packageId) {
        String cmd = "cargo install " + packageId;
        log.info("cargo 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    /** Uninstall */
    public boolean uninstall(String packageId) {
        String cmd = "cargo uninstall " + packageId;
        log.info("cargo 卸载: {}", packageId);
        return executeCommand(cmd, "卸载", packageId);
    }

    /**
    * 执行命令
    *
    * @param cmd CMD
    * @param action 动作
    * @param packageId 包标识
    * @return 执行命令的结果
     */
    private boolean executeCommand(String cmd, String action, String packageId) {
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 120, TimeUnit.SECONDS, new LineCallback() {
            @Override
            /** on线 */
            public void onLine(String line) {
                log.info("  [{}] {}", action, line);
            }

            @Override
            /** on完成 */
            public void onComplete(int exitCode) {
                log.info("  [{}] 完成, exitCode={}", action, exitCode);
            }

            @Override
            /** On记录错误 */
            public void onError(String command, Throwable throwable) {
                log.error("  [{}] 异常: {}", action, throwable.getMessage());
            }
        });
        boolean ok = result.isSuccess();
        log.info("cargo {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    /**
    * 解析cargo输出
    *
    * @param output 输出
    * @return 解析cargo输出的结果
     */
    private List<SoftwareInfo> parseCargoOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("...")) {
                    continue;
                }
                // cargo search 输出格式: name = "version"  # description
                // 例如: serde = "1.0.0"   # A generic serialization/deserialization framework
                int eqIndex = trimmed.indexOf(" = ");
                if (eqIndex <= 0) {
                    continue;
                }
                String name = trimmed.substring(0, eqIndex).trim();
                String version = "";
                String description = "";
                int quoteStart = trimmed.indexOf("\"", eqIndex + 3);
                if (quoteStart > 0) {
                    int quoteEnd = trimmed.indexOf("\"", quoteStart + 1);
                    if (quoteEnd > 0) {
                        version = trimmed.substring(quoteStart + 1, quoteEnd);
                    }
                }
                int descIndex = trimmed.indexOf("# ");
                if (descIndex > 0) {
                    description = trimmed.substring(descIndex + 2).trim();
                }
                if (!name.isEmpty()) {
                    results.add(new SoftwareInfo(name, version, NAME, description, name));
                }
            }
        } catch (Exception e) {
            log.warn("解析 cargo 输出失败: {}", e.getMessage());
        }
        return results;
    }
}