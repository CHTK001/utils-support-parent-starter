// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.datasearch.software.model.SoftwareInfo;
import com.chua.common.support.datasearch.software.spi.SoftwareProvider;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Helm 软件包管理器提供器。
 *
 * <p>通过 helm CLI 在 Artifact Hub 中搜索、安装和卸载 Kubernetes Helm Chart。
 * 搜索使用 <code>Helm 搜索 hub</code>，安装/卸载使用
 * <code>helm install/uninstall</code>（release 名称由包标识派生）。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("helm")
public class HelmSoftwareProvider implements SoftwareProvider {

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(HelmSoftwareProvider.class);

    /**
     * 名称
    */
    private static final String NAME = "helm";

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
        String[] cmd = {"helm", "search", "hub", keyword};

        log.info("helm 搜索: keyword={}", keyword);
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
                log.info("helm 搜索完成, exitCode={}", exitCode);
            }

            /**
             * On记录错误
            */
            @Override
            public void onError(String command, Throwable throwable) {
                log.warn("helm 搜索异常: {}", throwable.getMessage(), throwable);
            }
        });

        if (outputBuffer.isEmpty()) {
            return results;
        }

        return parseHelmOutput(outputBuffer.toString());
    }

    /**
     * Install
    */
    @Override
    public boolean install(String packageId) {
        String release = sanitize(packageId);
        String[] cmd = {"helm", "install", release, packageId};
        log.info("helm 安装: {} (release={})", packageId, release);
        return executeCommand(cmd, "安装", packageId);
    }

    /**
     * Uninstall
    */
    @Override
    public boolean uninstall(String packageId) {
        String release = sanitize(packageId);
        String[] cmd = {"helm", "uninstall", release};
        log.info("helm 卸载: {} (release={})", packageId, release);
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
        log.info("helm {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    /**
     * 解析helm输出
     *
     * @param output 输出
     * @return 解析helm输出的结果
     */
    private List<SoftwareInfo> parseHelmOutput(String output) {
        List<SoftwareInfo> results = Wraps.list();
        try {
            for (String line : output.split("\\r?\\n")) {
                String trimmed = line.trim();
                // 跳过表头与分隔线
                if (trimmed.isEmpty()
                        || trimmed.contains("NAME")
                        && trimmed.contains("CHART VERSION")
                        || trimmed.startsWith("---")) {
                    continue;
                }
 // 列格式: URL  CHART 版本  APP 版本  DESCRIPTION
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length < 2) {
                    continue;
                }
                String url = tokens[0];
                String chartName = extractChartName(url);
                String version = looksLikeVersion(tokens[1]) ? tokens[1] : "";
                if (!chartName.isEmpty()) {
                    results.add(new SoftwareInfo(chartName, version, NAME, "", chartName));
                }
            }
        } catch (Exception e) {
            log.warn("解析 helm 输出失败: {}", e.getMessage(), e);
        }
        return results;
    }

    /**
     * extractchart名称
     *
     * @param url url
     * @return extractchart名称的结果
     */
    private String extractChartName(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        // URL 形如 https://artifacthub.io/packages/helm/<repo>/<name>
        String path = url;
        int idx = url.indexOf("packages/helm/");
        if (idx >= 0) {
            path = url.substring(idx + "packages/helm/".length());
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? url : name;
    }

    /**
     * lookslike版本
     *
     * @param s s
     * @return lookslike版本的结果
     */
    private boolean looksLikeVersion(String s) {
        return s != null && s.matches(".*\\d.*") && !s.equalsIgnoreCase("true");
    }

    /**
     * Sanitize
     *
     * @param packageId 包标识
     * @return sanitize的结果
     */
    private String sanitize(String packageId) {
        if (packageId == null) {
            return "release";
        }
        String s = packageId.replaceAll("[^a-zA-Z0-9-]", "-").toLowerCase();
        return s.isEmpty() ? "release" : s;
    }
}