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
 * Pacman 软件包管理器提供器。
 *
 * <p>通过 pacman CLI 在 Arch Linux 中搜索、安装和卸载软件包。
 * 支持 <code>pacman -Ss</code>、<code>pacman -S --noconfirm</code>、
 * <code>pacman -R --noconfirm</code>。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("pacman")
public class PacmanSoftwareProvider implements SoftwareProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(PacmanSoftwareProvider.class);

    /** 名称 */
    private static final String NAME = "pacman";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "pacman -Ss " + keyword + " 2>&1";

        log.info("pacman 搜索: keyword={}", keyword);
        StringBuilder outputBuffer = new StringBuilder();
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 30, TimeUnit.SECONDS, new LineCallback() {
            @Override
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("pacman 搜索完成, exitCode={}", exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.warn("pacman 搜索异常: {}", throwable.getMessage());
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parsePacmanOutput(outputBuffer.toString());
    }

    @Override
    public boolean install(String packageId) {
        String cmd = "pacman -S --noconfirm " + packageId;
        log.info("pacman 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    public boolean uninstall(String packageId) {
        String cmd = "pacman -R --noconfirm " + packageId;
        log.info("pacman 卸载: {}", packageId);
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
        log.info("pacman {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    private List<SoftwareInfo> parsePacmanOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("::") || trimmed.startsWith("warning")) {
                    continue;
                }
                // 格式: repo/name  version  description
                int firstSpace = trimmed.indexOf(' ');
                if (firstSpace <= 0) {
                    continue;
                }
                String repoName = trimmed.substring(0, firstSpace).trim();
                String rest = trimmed.substring(firstSpace + 1).trim();
                int secondSpace = rest.indexOf(' ');
                String version;
                String description;
                if (secondSpace > 0) {
                    version = rest.substring(0, secondSpace).trim();
                    description = rest.substring(secondSpace + 1).trim();
                } else {
                    version = rest;
                    description = "";
                }
                if (!repoName.isEmpty()) {
                    results.add(new SoftwareInfo(repoName, version, NAME, description, repoName));
                }
            }
        } catch (Exception e) {
            log.warn("解析 pacman 输出失败: {}", e.getMessage());
        }
        return results;
    }
}
