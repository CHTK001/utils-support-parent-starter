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
* pip 软件包管理器提供器。
*
* <p>通过 pip CLI 搜索、安装和卸载 Python 软件包。
* 支持 <code>pip 搜索</code>（已废弃，使用 pip install 试探）、
* <code>pip install</code>、<code>pip uninstall</code>。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("pip")
public class PipSoftwareProvider implements SoftwareProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(PipSoftwareProvider.class);

    /** 名称 */
    private static final String NAME = "pip";

    @Override
    /** 名称 */
    public String name() {
        return NAME;
    }

    @Override
    /** 搜索 */
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();

 // pip 搜索 已被 pypi 禁用，改用 pip 索引 版本 或 pip install --dry-运行 试探
 // 尝试通过 pip install --dry-运行 --report 获取信息
        String cmd = "pip install --dry-run --report - " + keyword + " 2>&1";
        log.info("pip 搜索: keyword={}", keyword);

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
                log.info("pip 搜索完成, exitCode={}", exitCode);
            }

            @Override
            /** On记录错误 */
            public void onError(String command, Throwable throwable) {
                log.warn("pip 搜索异常: {}", throwable.getMessage());
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parsePipOutput(outputBuffer.toString());
    }

    @Override
    /** Install */
    public boolean install(String packageId) {
        String cmd = "pip install " + packageId;
        log.info("pip 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    /** Uninstall */
    public boolean uninstall(String packageId) {
        String cmd = "pip uninstall -y " + packageId;
        log.info("pip 卸载: {}", packageId);
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
        log.info("pip {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    /**
    * 解析pip输出
    *
    * @param output 输出
    * @return 解析pip输出的结果
     */
    private List<SoftwareInfo> parsePipOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("ERROR") || trimmed.startsWith("WARNING")) {
                    continue;
                }
                // pip 输出通常包含包名和版本信息
                if (trimmed.contains("Collecting") || trimmed.contains("Requirement already satisfied")) {
                    String name = trimmed;
                    String version = "";
                    if (trimmed.contains("Collecting")) {
                        name = trimmed.substring(trimmed.indexOf("Collecting") + 11).trim();
                    } else if (trimmed.contains("Requirement already satisfied")) {
                        name = trimmed.substring(trimmed.indexOf("Requirement already satisfied") + 29).trim();
                    }
                    if (name.contains(" ")) {
                        String[] parts = name.split("\\s+");
                        name = parts[0];
                    }
                    if (!name.isEmpty()) {
                        results.add(new SoftwareInfo(name, version, NAME, "", name));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 pip 输出失败: {}", e.getMessage());
        }
        return results;
    }
}