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
* vcpkg 软件包管理器提供器。
*
* <p>通过 vcpkg CLI 搜索、安装和卸载 C/C++ 库。
* 支持 <code>vcpkg 搜索</code>、<code>vcpkg install</code>、
* <code>vcpkg remove</code>。
*
* @author CH
* @since 4.0.0.42
 */
@Spi("vcpkg")
public class VcpkgSoftwareProvider implements SoftwareProvider {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(VcpkgSoftwareProvider.class);

    /** 名称 */
    private static final String NAME = "vcpkg";

    @Override
    /** 名称 */
    public String name() {
        return NAME;
    }

    @Override
    /** 搜索 */
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();
        String cmd = "vcpkg search " + keyword + " 2>&1";

        log.info("vcpkg 搜索: keyword={}", keyword);
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
                log.info("vcpkg 搜索完成, exitCode={}", exitCode);
            }

            @Override
            /** On记录错误 */
            public void onError(String command, Throwable throwable) {
                log.warn("vcpkg 搜索异常: {}", throwable.getMessage());
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parseVcpkgOutput(outputBuffer.toString());
    }

    @Override
    /** Install */
    public boolean install(String packageId) {
        String cmd = "vcpkg install " + packageId;
        log.info("vcpkg 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    /** Uninstall */
    public boolean uninstall(String packageId) {
        String cmd = "vcpkg remove " + packageId;
        log.info("vcpkg 卸载: {}", packageId);
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
        log.info("vcpkg {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    /**
    * 解析vcpkg输出
    *
    * @param output 输出
    * @return 解析vcpkg输出的结果
     */
    private List<SoftwareInfo> parseVcpkgOutput(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()
                        || trimmed.startsWith("The following")
                        || trimmed.startsWith("Did you mean")
                        || trimmed.startsWith("You can")) {
                    continue;
                }
 // 列格式: 名称[:triplet]  版本  description
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length < 2) {
                    continue;
                }
                String rawName = tokens[0];
                // 去掉 :triplet 或 [triplet] 后缀
                String name = rawName;
                int colon = name.indexOf(':');
                if (colon > 0) {
                    name = name.substring(0, colon);
                }
                int bracket = name.indexOf('[');
                if (bracket > 0) {
                    name = name.substring(0, bracket);
                }
                String version = looksLikeVersion(tokens[1]) ? tokens[1] : "";
                if (!name.isEmpty()) {
                    results.add(new SoftwareInfo(name, version, NAME, "", name));
                }
            }
        } catch (Exception e) {
            log.warn("解析 vcpkg 输出失败: {}", e.getMessage());
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
