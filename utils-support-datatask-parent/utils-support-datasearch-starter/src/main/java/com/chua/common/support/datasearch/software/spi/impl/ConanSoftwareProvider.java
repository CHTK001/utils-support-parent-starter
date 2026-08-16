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
 * Conan 软件包管理器提供器。
 *
 * <p>通过 conan CLI 在 ConanCenter 中搜索、安装和卸载 C/C++ 库。
 * 搜索使用 <code>conan search -r=conancenter</code>，安装使用
 * <code>conan install --requires=... --build=missing</code>，卸载使用
 * <code>conan remove -c</code> 清理本地缓存。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("conan")
public class ConanSoftwareProvider implements SoftwareProvider {

    private static final Logger log = LoggerFactory.getLogger(ConanSoftwareProvider.class);

    private static final String NAME = "conan";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "conan search " + keyword + " -r=conancenter 2>&1";

        log.info("conan 搜索: keyword={}", keyword);
        StringBuilder outputBuffer = new StringBuilder();
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 30, TimeUnit.SECONDS, new LineCallback() {
            @Override
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
            }

            @Override
            public void onComplete(int exitCode) {
                log.info("conan 搜索完成, exitCode={}", exitCode);
            }

            @Override
            public void onError(String command, Throwable throwable) {
                log.warn("conan 搜索异常: {}", throwable.getMessage());
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parseConanOutput(outputBuffer.toString());
    }

    @Override
    public boolean install(String packageId) {
        String cmd = "conan install --requires=" + packageId + " --build=missing";
        log.info("conan 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    public boolean uninstall(String packageId) {
        String cmd = "conan remove -c " + packageId;
        log.info("conan 卸载(清理缓存): {}", packageId);
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
        log.info("conan {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    private List<SoftwareInfo> parseConanOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("Existing package recipes")
                        || trimmed.startsWith("conan")
                        || trimmed.startsWith("There are")
                        || trimmed.startsWith("WARN")
                        || trimmed.startsWith("ERROR")) {
                    continue;
                }
                // 格式: name/version[@user/channel]
                int slash = trimmed.indexOf('/');
                if (slash <= 0) {
                    continue;
                }
                String name = trimmed.substring(0, slash).trim();
                String rest = trimmed.substring(slash + 1).trim();
                int at = rest.indexOf('@');
                String version = at >= 0 ? rest.substring(0, at).trim() : rest;
                if (!name.isEmpty() && !version.isEmpty()) {
                    results.add(new SoftwareInfo(name, version, NAME, "", name + "/" + version));
                }
            }
        } catch (Exception e) {
            log.warn("解析 conan 输出失败: {}", e.getMessage());
        }
        return results;
    }
}
