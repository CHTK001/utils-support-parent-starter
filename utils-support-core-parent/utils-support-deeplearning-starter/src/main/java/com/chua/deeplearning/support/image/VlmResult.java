package com.chua.deeplearning.support.image;

import com.chua.common.support.utils.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * 指令式视觉推理结果。
 *
 * <p>与 {@link UnderstandResult}（纯文本）并列：本类型保留模型原始响应文本，同时把
 * 框/点/掩码解析成结构化数据，供下游直接消费，不必再各自写一遍坐标 token 解析。
 *
 * <p>「目标不存在」语义：模型对定位类任务会明确回答 {@code None}。此时 {@link #objects()}
 * 与 {@link #masks()} 为空且 {@link #text()} 为 {@code None}，{@link #targetMissing()} 返回
 * true。调用方不得把「空结果」渲染成「检到 0 个」，二者对定位类任务同义，但语义来源不同。
 *
 * @param task           任务类型
 * @param text           模型原始响应文本（已去除首尾空白）
 * @param objects        结构化目标列表（框/点）
 * @param masks          alpha 掩码列表
 * @param latencySeconds 推理耗时（秒），未知时为 0
 * @author CH
 * @since 4.0.0.51
 */
public record VlmResult(VlmTask task, String text, List<VlmObject> objects, List<VlmMask> masks,
                        double latencySeconds) {

    /**
     * 规范构造器：文本去空白，集合做不可变拷贝，耗时归一为非负。
     *
     * @param task           任务类型
     * @param text           模型原始响应文本
     * @param objects        结构化目标列表
     * @param masks          alpha 掩码列表
     * @param latencySeconds 推理耗时（秒）
     */
    public VlmResult {
        text = text == null ? "" : text.trim();
        objects = objects == null ? List.of() : List.copyOf(objects);
        masks = masks == null ? List.of() : List.copyOf(masks);
        latencySeconds = Math.max(0D, latencySeconds);
    }

    /**
     * 构造无结构化输出的结果（仅文本）。
     *
     * @param task 任务类型
     * @param text 模型原始响应文本
     * @return 结果
     */
    public static VlmResult ofText(VlmTask task, String text) {
        return new VlmResult(task, text, List.of(), List.of(), 0D);
    }

    /**
     * 是否有有效文本。
     *
     * @return 非空文本返回 true
     */
    public boolean hasText() {
        return StringUtils.isNotBlank(text);
    }

    /**
     * 是否解析出结构化目标。
     *
     * @return 目标列表非空返回 true
     */
    public boolean hasObjects() {
        return !objects.isEmpty();
    }

    /**
     * 模型是否明确回答目标不存在。
     *
     * <p>判定口径：显式 {@code None}（忽略大小写），或「无文本、无目标、无掩码」。
     * 只有文本（如自由问答）不算缺失。
     *
     * @return 目标不存在返回 true
     */
    public boolean targetMissing() {
        if (!objects.isEmpty() || !masks.isEmpty()) {
            return false;
        }
        return !hasText() || "none".equals(text.toLowerCase(Locale.ROOT));
    }

    /**
     * 有效框数量（含点任务中被归入目标但无框的项不计入）。
     *
     * @return 有效框数量
     */
    public long boxCount() {
        return objects.stream().filter(VlmObject::hasBox).count();
    }
}