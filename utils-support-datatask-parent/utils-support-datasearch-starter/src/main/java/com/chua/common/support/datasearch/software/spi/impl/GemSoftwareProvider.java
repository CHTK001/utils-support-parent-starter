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
* Gem 软件包管理器提供器。
*
* <p>通过 gem CLI 搜索、安装和卸载 Ruby gem。
* 支持 <code>gem 搜索</code>、<code>gem install</code>、<code>gem uninstall</code>。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("gem")
public class GemSoftwareProvider implements SoftwareProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(GemSoftwareProvider.class);

    /** 名称 */
    private static final String NAME = "gem";

    @Override
    /** 名称 */
    public String name() {
        return NAME;
    }

    @Override
    /** 搜索 */
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "gem search " + keyword + " --remote";

        log.info("gem 搜索: keyword={}", keyword);
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
                log.info("gem 搜索完成, exitCode={}", exitCode);
            }

            @Override
            /** On记录错误 */
            public void onError(String command, Throwable throwable) {
                log.warn("gem 搜索异常: {}", throwable.getMessage());
            }
        });

        if (!result.isSuccess() || outputBuffer.isEmpty()) {
            return results;
        }

        return parseGemOutput(outputBuffer.toString());
    }

    @Override
    /** Install */
    public boolean install(String packageId) {
        String cmd = "gem install " + packageId;
        log.info("gem 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    /** Uninstall */
    public boolean uninstall(String packageId) {
        String cmd = "gem uninstall -x " + packageId;
        log.info("gem 卸载: {}", packageId);
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
        log.info("gem {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    /**
    * 解析gem输出
    *
    * @param output 输出
    * @return 解析gem输出的结果
     */
    private List<SoftwareInfo> parseGemOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("***")) {
                    continue;
                }
                // gem search 输出格式: name (version[, version...])
                // 例如: rails (7.1.0, 7.0.0)
                int parenStart = trimmed.indexOf(" (");
                if (parenStart <= 0) {
                    continue;
                }
                String name = trimmed.substring(0, parenStart).trim();
                String version = "";
                int parenEnd = trimmed.indexOf(")", parenStart + 2);
                if (parenEnd > 0) {
                    String versionStr = trimmed.substring(parenStart + 2, parenEnd);
                    // 取第一个版本号
                    int commaIndex = versionStr.indexOf(",");
                    if (commaIndex > 0) {
                        version = versionStr.substring(0, commaIndex).trim();
                    } else {
                        version = versionStr.trim();
                    }
                }
                if (!name.isEmpty()) {
                    results.add(new SoftwareInfo(name, version, NAME, "", name));
                }
            }
        } catch (Exception e) {
            log.warn("解析 gem 输出失败: {}", e.getMessage());
        }
        return results;
    }
}