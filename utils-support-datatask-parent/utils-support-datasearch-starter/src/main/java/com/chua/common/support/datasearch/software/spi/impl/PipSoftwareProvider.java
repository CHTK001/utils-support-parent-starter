package com.chua.common.support.datasearch.software.spi.impl;

import com.chua.common.support.datasearch.software.model.SoftwareInfo;
import com.chua.common.support.datasearch.software.spi.SoftwareProvider;
import com.chua.common.support.lang.cmd.CmdExecutors;
import com.chua.common.support.lang.cmd.CmdResult;
import com.chua.common.support.lang.cmd.LineCallback;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * 日志
    */
    private static final Logger log = LoggerFactory.getLogger(PipSoftwareProvider.class);

    /**
     * 名称
    */
    private static final String NAME = "pip";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 依赖已满足时的输出行：名称、路径、末括号内的已安装版本
     */
    private static final Pattern SATISFIED = Pattern.compile(
            "Requirement already satisfied:\\s*(\\S+)\\s+in\\s+.*\\(([^()]+)\\)\\s*$");

    /**
     * 需求串中的版本约束起点（flask&gt;=2.0 → flask）
     */
    private static final Pattern CONSTRAINT = Pattern.compile("[<>=!~\\[;(]");

    @Override
    /**
     * 名称
    */
    public String name() {
        return NAME;
    }

    @Override
    /**
     * 搜索
    */
    public List<SoftwareInfo> search(String keyword) {
        List<SoftwareInfo> results = new ArrayList<>();

        // pip 索引查询已被 PyPI 禁用，改用 install --dry-run --report 试探；
        // 数组形式直接作为进程参数传递，不经 cmd.exe 解析，keyword 不会被拼成命令执行
        String[] cmd = {"pip", "install", "--dry-run", "--report", "-", keyword};
        log.info("pip 搜索: keyword={}", keyword);

        StringBuilder outputBuffer = new StringBuilder();
        CmdExecutors.executeWithOutput(cmd, 30, TimeUnit.SECONDS, new LineCallback() {
            @Override
            /**
             * on线
            */
            public void onLine(String line) {
                outputBuffer.append(line).append("\n");
            }

            @Override
            /**
             * on完成
            */
            public void onComplete(int exitCode) {
                log.info("pip 搜索完成, exitCode={}", exitCode);
            }

            @Override
            /**
             * On记录错误
            */
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
    /**
     * Install
    */
    public boolean install(String packageId) {
        String[] cmd = {"pip", "install", packageId};
        log.info("pip 安装: {}", packageId);
        return executeCommand(cmd, "安装", packageId);
    }

    @Override
    /**
     * Uninstall
    */
    public boolean uninstall(String packageId) {
        String[] cmd = {"pip", "uninstall", "-y", packageId};
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
    private boolean executeCommand(String[] cmd, String action, String packageId) {
        CmdResult result = CmdExecutors.executeWithOutput(cmd, 120, TimeUnit.SECONDS, new LineCallback() {
            @Override
            /**
             * on线
            */
            public void onLine(String line) {
                log.info("  [{}] {}", action, line);
            }

            @Override
            /**
             * on完成
            */
            public void onComplete(int exitCode) {
                log.info("  [{}] 完成, exitCode={}", action, exitCode);
            }

            @Override
            /**
             * On记录错误
            */
            public void onError(String command, Throwable throwable) {
                log.error("  [{}] 异常: {}", action, throwable.getMessage());
            }
        });
        boolean ok = result.isSuccess();
        log.info("pip {}: packageId={}, success={}", action, packageId, ok);
        return ok;
    }

    /**
     * 解析 pip 输出：优先读 {@code --report} 的 JSON，其次读已满足依赖的行。
     *
     * @param output 输出
     * @return 解析pip输出的结果
     */
    static List<SoftwareInfo> parsePipOutput(String output) {
        List<SoftwareInfo> results = parseReport(output);
        if (!results.isEmpty()) {
            return results;
        }
        // 依赖全部已满足时 report 的 install 为空数组，版本只在
        // "Requirement already satisfied: xxx in <路径> (1.2.3)" 的末括号里
        for (String line : output.split("\\r?\\n")) {
            Matcher m = SATISFIED.matcher(line.trim());
            if (!m.find()) {
                continue;
            }
            String name = stripConstraint(m.group(1));
            String version = m.group(2);
            if (name.isEmpty() || version.startsWith("from ")) {
                continue;
            }
            results.add(new SoftwareInfo(name, version, NAME, "", name));
        }
        return results;
    }

    /**
     * 解析 {@code pip install --dry-run --report -} 输出中的 JSON 报告。
     *
     * @param output 输出
     * @return 报告中的软件信息；无报告或解析失败返回空列表
     */
    private static List<SoftwareInfo> parseReport(String output) {
        List<SoftwareInfo> results = new ArrayList<>();
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return results;
        }
        try {
            JsonNode install = MAPPER.readTree(output.substring(start, end + 1)).path("install");
            for (JsonNode item : install) {
                JsonNode metadata = item.path("metadata");
                String name = metadata.path("name").asText("");
                if (name.isEmpty()) {
                    continue;
                }
                results.add(new SoftwareInfo(name, metadata.path("version").asText(""), NAME,
                        metadata.path("summary").asText(""), name));
            }
        } catch (Exception e) {
            log.warn("解析 pip 报告失败: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 去掉包名尾随的版本约束（flask&gt;=2.0 → flask）。
     *
     * @param requirement 需求串
     * @return 纯包名
     */
    private static String stripConstraint(String requirement) {
        Matcher m = CONSTRAINT.matcher(requirement);
        return m.find() ? requirement.substring(0, m.start()) : requirement;
    }
}
