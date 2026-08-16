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
 * Composer 软件包管理器提供器。
 *
 * <p>通过 composer CLI 搜索、安装和卸载 PHP 依赖包。
 * 支持 <code>composer search</code>、<code>composer global require</code>、
 * <code>composer global remove</code>。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("composer")
public class ComposerSoftwareProvider implements SoftwareProvider {

    private static final Logger log = LoggerFactory.getLogger(ComposerSoftwareProvider.class);

    private static final String NAME = "composer";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "composer search " + keyword + " 2>&1";

        log.info("composer 搜索: keyword={}", keyword);
        StringBuilder outputBuffer = new StringBuilder();
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 30, TimeUnit.SECONDS, new LineCallback() {
            @Override
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("composer 搜索完成, exitCode={}", exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.warn("composer 搜索异常: {}", throwable.getMessage());
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parseComposerOutput(outputBuffer.toString());
    }

    @Override
    public boolean install(String packageId) {
        String cmd = "composer global require " + packageId;
        log.info("composer 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    public boolean uninstall(String packageId) {
        String cmd = "composer global remove " + packageId;
        log.info("composer 卸载: {}", packageId);
        return executeCommand(cmd, "卸载", packageId);
    }

    private boolean executeCommand(String cmd, String action, String packageId) {
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 120, TimeUnit.SECONDS, new LineCallback() {
            @Override
            public void onLine(String line) {
                log.info("  [{}] {}", action, line);
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("  [{}] 完成, exitCode={}", action, exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.error("  [{}] 异常: {}", action, throwable.getMessage());
            }
        });
        boolean ok = result.isSuccess();
        log.info("composer {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    private List<SoftwareInfo> parseComposerOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("Loading")) {
                    continue;
                }
                // composer search 输出: name description
                // 例如: laravel/framework The Laravel framework.
                int firstSpace = trimmed.indexOf(' ');
                String name;
                String description;
                if (firstSpace > 0) {
                    name = trimmed.substring(0, firstSpace).trim();
                    description = trimmed.substring(firstSpace + 1).trim();
                } else {
                    name = trimmed;
                    description = "";
                }
                if (!name.isEmpty()) {
                    results.add(new SoftwareInfo(name, "", NAME, description, name));
                }
            }
        } catch (Exception e) {
            log.warn("解析 composer 输出失败: {}", e.getMessage());
        }
        return results;
    }
}
