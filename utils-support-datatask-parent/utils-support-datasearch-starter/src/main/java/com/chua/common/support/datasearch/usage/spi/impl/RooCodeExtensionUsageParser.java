package com.chua.common.support.datasearch.usage.spi.impl;

import com.chua.common.support.spi.annotations.Spi;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * Roo Code VS Code 扩展用量解析器。
 *
 * <p>Roo Code（rooveterinaryinc.roo-cline，Cline 派生）将任务持久化到
 * {@code <IDE>/User/globalStorage/rooveterinaryinc.roo-cline/tasks/<task>/ui_messages.json}。
 * Roo 的 per-turn 载荷不含模型名（模型记录在兄弟文件
 * {@code api_conversation_history.json} 的 {@code <environment_details>} 块中，
 * 且任务中途可能换模型），因此模型名取该历史文件中<b>最后一次</b>出现的
 * {@code <model>} 标签；缺失时退化为 {@code protocol:<apiProtocol>}。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Spi("roo-code")
public class RooCodeExtensionUsageParser extends VscodeExtensionTaskUsageParser {

    @Override
    protected List<String> extensionIdPrefixes() {
        return List.of("rooveterinaryinc");
    }

    @Override
    protected String taskDirName() {
        return "tasks";
    }

    @Override
    public String name() {
        return "roo-code";
    }

    /**
     * Roo 的模型名在 {@code api_conversation_history.json} 中，不在 per-turn 载荷里。
     * 取历史文件中最后一次出现的 {@code <model>} 标签作为模型归属。
     */
    @Override
    protected com.chua.common.support.ai.AiUsage toAiUsage(Map<String, Object> msg, String taskId, long fallbackTime) {
        com.chua.common.support.ai.AiUsage usage = super.toAiUsage(msg, taskId, fallbackTime);
        if (usage != null && "unknown".equals(usage.getModel())) {
            String model = resolveRoocodeModel(taskId);
            if (model != null) {
                usage.setModel(model);
            }
        }
        return usage;
    }

    private String resolveRoocodeModel(String taskId) {
        for (Map<Path, String> batch : collectTaskFiles()) {
            for (Map.Entry<Path, String> entry : batch.entrySet()) {
                if (!taskId.equals(entry.getValue())) {
                    continue;
                }
                Path historyFile = entry.getKey().getParent().resolve("api_conversation_history.json");
                String model = extractLastModelFromHistory(historyFile);
                if (model != null) {
                    return model;
                }
            }
        }
        return null;
    }

    /**
     * 从历史文件提取最后一次出现的 {@code <model>} 标签值。
     *
     * @param historyFile 历史 JSONL 文件
     * @return 模型名；文件缺失或无标签时返回 null
     */
    private String extractLastModelFromHistory(Path historyFile) {
        if (!java.nio.file.Files.isRegularFile(historyFile)) {
            return null;
        }
        try {
            String raw = java.nio.file.Files.readString(historyFile, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Pattern p =
                    java.util.regex.Pattern.compile("<model>\\s*([^<\\s][^<]*?)\\s*</model>");
            java.util.regex.Matcher m = p.matcher(raw);
            String last = null;
            while (m.find()) {
                last = m.group(1);
            }
            return (last != null && !last.isBlank()) ? last.trim() : null;
        } catch (Exception e) {
            log.debug("[roo-code] history parse failed: {}", e.getMessage());
            return null;
        }
    }
}
