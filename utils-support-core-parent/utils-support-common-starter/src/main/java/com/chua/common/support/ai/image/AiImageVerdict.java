package com.chua.common.support.ai.image;

import com.chua.common.support.utils.PhotoMetadataUtils;

/**
 * 图片 AI 检测结论。
 *
 * <p>用枚举而不是字符串：结论需要在不同实现（启发式 / 分类模型）之间比较与透出，
 * 字符串口径一旦分叉，上下游的判断会静默错位。展示文案由 {@link #displayText()} 统一映射，
 * 与 {@link PhotoMetadataUtils} 的既有常量保持同一份来源。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum AiImageVerdict {

    /**
     * 有确定性证据表明由 AI 生成。
     *
     * <p>典型来源：C2PA 内容凭证 / XMP 生成器声明 / 生成参数块。</p>
     */
    AI_GENERATED,

    /**
     * 有证据表明经 AI 编辑或合成，但不必然是纯文生图。
     *
     * <p>典型来源：像素统计命中（上采样网格伪迹、缺失相机传感器噪声）等启发式证据。
     * 这类证据只能支撑「疑似」，不能支撑「确定生成」。</p>
     */
    AI_EDITED,

    /**
     * 未发现 AI 痕迹。
     *
     * <p><b>不等于</b>已证明为人工创作。元数据被剥离时，纯统计量无法区分
     * 「AI 平涂插画」与「人类平涂插画」，上层展示文案不得把它渲染成「人工」结论。</p>
     */
    NO_AI_SIGNAL,

    /**
     * 无法判断：数据无法解码，或检测引擎不可用（模型未配置、未下载、标签缺失等）。
     */
    UNKNOWN;

    /**
     * 文案 → 语义结论。
     *
     * @param text 中文结论文案（{@link PhotoMetadataUtils} 常量）
     * @return 语义结论；无法识别时取 {@link #UNKNOWN}
     */
    public static AiImageVerdict ofText(String text) {
        if (PhotoMetadataUtils.VERDICT_AI_GENERATED.equals(text)) {
            return AI_GENERATED;
        }
        if (PhotoMetadataUtils.VERDICT_AI_EDITED.equals(text)) {
            return AI_EDITED;
        }
        if (PhotoMetadataUtils.VERDICT_NO_AI_SIGNALS.equals(text)) {
            return NO_AI_SIGNAL;
        }
        return UNKNOWN;
    }

    /**
     * 语义结论 → 展示文案。
     *
     * @return 中文文案，与既有接口返回保持一致
     */
    public String displayText() {
        switch (this) {
            case AI_GENERATED:
                return PhotoMetadataUtils.VERDICT_AI_GENERATED;
            case AI_EDITED:
                return PhotoMetadataUtils.VERDICT_AI_EDITED;
            case NO_AI_SIGNAL:
                return PhotoMetadataUtils.VERDICT_NO_AI_SIGNALS;
            default:
                return PhotoMetadataUtils.VERDICT_UNKNOWN;
        }
    }
}
