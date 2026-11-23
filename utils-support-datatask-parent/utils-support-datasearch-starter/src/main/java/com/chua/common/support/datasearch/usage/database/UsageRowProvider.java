package com.chua.common.support.datasearch.usage.database;

import java.util.List;
import java.util.Map;

/**
 * 数据库用量行查询 SPI — 由持有 AI 用量存储的业务模块提供原始数据行。
 *
 * <p>utils 层的 {@link DatabaseUsageParser} 只做「数据行 → {@code AiUsage}」的映射与编排，
 * 不感知任何业务表结构；表名、列名与查询 SQL 由本接口的实现自行决定
 * （如 ai 模块的 sys_ai_usage）。这是模块边界铁律（R2）的要求：
 * utils 层不得内嵌任何业务表的知识。</p>
 *
 * <p>返回的每行 Map 必须以下列逻辑字段名为键（无值的键可省略，
 * 不允许返回 null，无数据时返回空列表）：</p>
 * <ul>
 *   <li>{@link #PROVIDER}：供应商标识；</li>
 *   <li>{@link #MODEL}：模型名；</li>
 *   <li>{@link #INPUT_TOKENS}：输入 Token（含缓存命中）；</li>
 *   <li>{@link #OUTPUT_TOKENS}：输出 Token（不含推理）；</li>
 *   <li>{@link #TOTAL_TOKENS}：总 Token；</li>
 *   <li>{@link #START_TIME}：调用时刻，{@link java.sql.Timestamp}/{@link java.util.Date}/epoch 毫秒数值；</li>
 *   <li>{@link #COST}：总费用；</li>
 *   <li>{@link #INPUT_COST}：输入费用；</li>
 *   <li>{@link #OUTPUT_COST}：输出费用。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface UsageRowProvider {

    /**
     * 供应商标识字段名
     */
    String PROVIDER = "provider";

    /**
     * 模型名字段名
     */
    String MODEL = "model";

    /**
     * 输入 Token 字段名（含缓存命中）
     */
    String INPUT_TOKENS = "input-tokens";

    /**
     * 输出 Token 字段名（不含推理）
     */
    String OUTPUT_TOKENS = "output-tokens";

    /**
     * 总 Token 字段名
     */
    String TOTAL_TOKENS = "total-tokens";

    /**
     * 调用时刻字段名（Timestamp/Date/epoch 毫秒数值）
     */
    String START_TIME = "start-time";

    /**
     * 总费用字段名
     */
    String COST = "cost";

    /**
     * 输入费用字段名
     */
    String INPUT_COST = "input-cost";

    /**
     * 输出费用字段名
     */
    String OUTPUT_COST = "output-cost";

    /**
     * 查询一批用量数据行。
     *
     * <p>实现自行决定表名、列名与查询 SQL，并负责把物理列名映射为
     * 本接口定义的逻辑字段名；一次调用返回当前可得的全部数据行。</p>
     *
     * @return 逻辑字段名键控的用量数据行，不允许为 null（无数据时返回空列表）
     */
    List<Map<String, Object>> queryRows();
}
