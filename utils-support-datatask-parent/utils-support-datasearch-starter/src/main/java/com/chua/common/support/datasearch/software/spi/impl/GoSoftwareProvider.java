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
 * Go 软件包管理器提供器。
 *
 * <p>通过 go CLI 查询、安装 Go 模块与命令。
 * 搜索使用 <code>go list -m -versions</code>（按模块路径查询可用版本），
 * 安装使用 <code>go install &lt;module&gt;@latest</code>，
 * 卸载为尽力而为（清除已安装的命令二进制）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("go")
public class GoSoftwareProvider implements SoftwareProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(GoSoftwareProvider.class);

    /** 名称 */
    private static final String NAME = "go";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "go list -m -versions " + keyword + " 2>&1";

        log.info("go 搜索: keyword={}", keyword);
        StringBuilder outputBuffer = new StringBuilder();
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 30, TimeUnit.SECONDS, new LineCallback() {
            @Override
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("go 搜索完成, exitCode={}", exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.warn("go 搜索异常: {}", throwable.getMessage());
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parseGoOutput(outputBuffer.toString());
    }

    @Override
    public boolean install(String packageId) {
        String cmd = "go install " + packageId + "@latest";
        log.info("go 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    public boolean uninstall(String packageId) {
        // Go 没有原生命令卸载全局安装的命令，这里尽力而为地清理二进制。
        String cmd = "go clean -i " + packageId + "@latest";
        log.info("go 卸载(尽力而为): {}", packageId);
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
        log.info("go {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    private List<SoftwareInfo> parseGoOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("go:") || trimmed.startsWith("go list")) {
                    // 跳过错误信息(如 go: module ...: invalid version)
                    continue;
                }
                // 输出格式: github.com/foo/bar v1.0.0 v1.1.0 ...
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length < 1) {
                    continue;
                }
                String name = tokens[0];
                String version = tokens.length > 1 ? tokens[1] : "";
                if (!name.isEmpty()) {
                    results.add(new SoftwareInfo(name, version, NAME, "", name));
                }
            }
        } catch (Exception e) {
            log.warn("解析 go 输出失败: {}", e.getMessage());
        }
        return results;
    }
}
