// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.software.model.SoftwareInfo;
import com.chua.common.support.datasearch.software.spi.SoftwareProvider;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * NPM 软件包管理器提供器。
 *
 * <p>通过 npm CLI 搜索、安装和卸载 Node.js 软件包。
 * 支持 <code>NPM 搜索</code>、<code>NPM install</code>、<code>NPM uninstall</code>。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("npm")
public class NpmSoftwareProvider implements SoftwareProvider {

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(NpmSoftwareProvider.class);

    /**
     * 名称
    */
    private static final String NAME = "npm";

    /**
     * 名称
    */
    @Override
    public String name() {
        return NAME;
    }

    /**
     * 搜索
    */
    @Override
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = Wraps.list();
        String[] cmd = {"npm", "search", keyword, "--json"};

        log.info("npm 搜索: keyword={}", keyword);
        StringBuilder outputBuffer = new StringBuilder();
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 30, TimeUnit.SECONDS, new LineCallback() {
            /**
             * on线
            */
            @Override
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
            }

            /**
             * on完成
            */
            @Override
            public void onComplete(int exitCode) {
                log.info("npm 搜索完成, exitCode={}", exitCode);
            }

            /**
             * On记录错误
            */
            @Override
            public void onError(String command, Throwable throwable) {
                log.warn("npm 搜索异常: {}", throwable.getMessage(), throwable);
            }
        });

        if (!result.isSuccess() || outputBuffer.isEmpty()) {
            return results;
        }

        return parseNpmOutput(outputBuffer.toString());
    }

    /**
     * Install
    */
    @Override
    public boolean install(String packageId) {
        String[] cmd = {"npm", "install", "-g", packageId};
        log.info("npm 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    /**
     * Uninstall
    */
    @Override
    public boolean uninstall(String packageId) {
        String[] cmd = {"npm", "uninstall", "-g", packageId};
        log.info("npm 卸载: {}", packageId);
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
    private boolean executeCommand(String[] cmd, String action, String packageId) {
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 120, TimeUnit.SECONDS, new LineCallback() {
            /**
             * on线
            */
            @Override
            public void onLine(String line) {
                log.info("  [{}] {}", action, line);
            }

            /**
             * on完成
            */
            @Override
            public void onComplete(int exitCode) {
                log.info("  [{}] 完成, exitCode={}", action, exitCode);
            }

            /**
             * On记录错误
            */
            @Override
            public void onError(String command, Throwable throwable) {
                log.error("  [{}] 异常: {}", action, throwable.getMessage(), throwable);
            }
        });
        boolean ok = result.isSuccess();
        log.info("npm {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    /**
     * 解析npm输出
     *
     * @param output 输出
     * @return 解析npm输出的结果
     */
    private List<SoftwareInfo> parseNpmOutput(String output) {
        List<SoftwareInfo> results = Wraps.list();
        try {
 // NPM 搜索 --json 返回 JSON 数组，每项包含 名称、版本、description 等
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || "[]".equals(trimmed)) {
                    continue;
                }
                // 简单解析：提取 "name"、"version"、"description" 字段
                String name = extractJsonValue(trimmed, "name");
                String version = extractJsonValue(trimmed, "version");
                String description = extractJsonValue(trimmed, "description");
                if (StringUtils.isNotEmpty(name)) {
                    results.add(new SoftwareInfo(name, version != null ? version : "",
                            NAME, description != null ? description : "", name));
                }
            }
        } catch (Exception e) {
            log.warn("解析 npm 输出失败: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * extractjson值
     *
     * @param json json
     * @param key 键
     * @return extractjson值的结果
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyIndex = json.indexOf(searchKey);
        if (keyIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(":", keyIndex + searchKey.length());
        if (colonIndex < 0) {
            return null;
        }
        int valueStart = json.indexOf("\"", colonIndex + 1);
        if (valueStart < 0) {
            return null;
        }
        int valueEnd = json.indexOf("\"", valueStart + 1);
        if (valueEnd < 0) {
            return null;
        }
        return json.substring(valueStart + 1, valueEnd);
    }
}