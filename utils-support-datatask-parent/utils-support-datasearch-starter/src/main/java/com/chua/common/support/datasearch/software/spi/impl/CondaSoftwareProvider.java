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
 * Conda 软件包管理器提供器。
 *
 * <p>通过 conda CLI 搜索、安装和卸载 Conda / Anaconda 环境中的软件包。
 * 支持 <code>Conda 搜索</code>、<code>Conda install -y</code>、
 * <code>conda remove -y</code>。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("conda")
public class CondaSoftwareProvider implements SoftwareProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(CondaSoftwareProvider.class);

    /** 名称 */
    private static final String NAME = "conda";

    @Override
    /** 名称 */
    public String name() {
        return NAME;
    }

    @Override
    /** 搜索 */
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "conda search " + keyword + " 2>&1";

        log.info("conda 搜索: keyword={}", keyword);
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
                log.info("conda 搜索完成, exitCode={}", exitCode);
            }

            @Override
            /** On记录错误 */
            public void onError(String command, Throwable throwable) {
                log.warn("conda 搜索异常: {}", throwable.getMessage());
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parseCondaOutput(outputBuffer.toString());
    }

    @Override
    /** Install */
    public boolean install(String packageId) {
        String cmd = "conda install -y " + packageId;
        log.info("conda 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    /** Uninstall */
    public boolean uninstall(String packageId) {
        String cmd = "conda remove -y " + packageId;
        log.info("conda 卸载: {}", packageId);
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
        log.info("conda {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    /**
     * 解析conda输出
     *
     * @param output 输出
     * @return 解析conda输出的结果
     */
    private List<SoftwareInfo> parseCondaOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("#")
                        || trimmed.startsWith("Loading")
                        || trimmed.equals("done")) {
                    continue;
                }
 // 列格式: 名称  版本  构建  通道
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length < 2) {
                    continue;
                }
                String name = tokens[0];
                String version = looksLikeVersion(tokens[1]) ? tokens[1] : "";
                if (!name.isEmpty()) {
                    results.add(new SoftwareInfo(name, version, NAME, "", name));
                }
            }
        } catch (Exception e) {
            log.warn("解析 conda 输出失败: {}", e.getMessage());
        }
        return results;
    }

    /**
     * lookslike版本
     *
     * @param s s
     * @return lookslike版本的结果
     */
    private boolean looksLikeVersion(String s) {
        return s != null && s.matches(".*\\d.*") && !s.contains("/");
    }
}
