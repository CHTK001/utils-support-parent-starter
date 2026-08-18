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
 * NuGet 软件包管理器提供器。
 *
 * <p>通过 dotnet / nuget CLI 搜索、安装和卸载 .NET 全局工具与包。
 * 搜索使用 <code>dotnet nuget search</code>，安装/卸载使用
 * <code>dotnet tool install/uninstall --global</code>。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("nuget")
public class NuGetSoftwareProvider implements SoftwareProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(NuGetSoftwareProvider.class);

    /** 名称 */
    private static final String NAME = "nuget";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "dotnet nuget search " + keyword + " 2>&1";

        log.info("nuget 搜索: keyword={}", keyword);
        StringBuilder outputBuffer = new StringBuilder();
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 30, TimeUnit.SECONDS, new LineCallback() {
            @Override
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("nuget 搜索完成, exitCode={}", exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.warn("nuget 搜索异常: {}", throwable.getMessage());
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parseNugetOutput(outputBuffer.toString());
    }

    @Override
    public boolean install(String packageId) {
        String cmd = "dotnet tool install --global " + packageId;
        log.info("nuget 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    public boolean uninstall(String packageId) {
        String cmd = "dotnet tool uninstall --global " + packageId;
        log.info("nuget 卸载: {}", packageId);
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
        log.info("nuget {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    private List<SoftwareInfo> parseNugetOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                // 跳过表头与分隔线
                if (trimmed.isEmpty()
                        || trimmed.contains("Package")
                        && trimmed.contains("Latest Version")
                        || trimmed.startsWith("---")
                        || trimmed.startsWith("The"))
                {
                    continue;
                }
                // 表格列: Package  Latest Version  Owners  Downloads  Verified
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
            log.warn("解析 nuget 输出失败: {}", e.getMessage());
        }
        return results;
    }

    private boolean looksLikeVersion(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        // 简单判定: 包含数字且不以纯字母开头(排除 Owners 等列)
        return s.matches(".*\\d.*") && !s.equalsIgnoreCase("Yes") && !s.equalsIgnoreCase("No");
    }
}
