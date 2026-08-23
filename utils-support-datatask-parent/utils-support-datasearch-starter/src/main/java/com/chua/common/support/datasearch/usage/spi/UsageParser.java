package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;

import java.util.List;

/**
 * AI 用量解析器接口 — 解析外部 AI 工具本地存储的用量数据
 *
 * <p>各工具（OpenCode、Cursor、VSCode 等）在本地存储了各自的用量信息，
 * UsageParser 负责从这些本地文件/配置中解析出标准化的 {@link AiUsage}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface UsageParser {

    /**
     * 解析全量用量数据
     *
     * @return 用量数据列表
     */
    List<AiUsage> parseAll();

    /**
     * 按天聚合用量数据
     *
     * @return 每天一条聚合记录，按日期升序
     */
    default List<AiUsage> parseDaily() {
        return parseAll();
    }

    /**
     * 当前解析器标识（如 "opencode"、"cursor-byok"）
     */
    String name();
}
