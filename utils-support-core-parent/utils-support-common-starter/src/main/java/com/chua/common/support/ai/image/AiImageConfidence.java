package com.chua.common.support.ai.image;

/**
 * 图片 AI 检测置信度。
 *
 * <p>语义是「这份证据有多硬」，不是概率值：模型实现若要给出概率，放在
 * {@link AiImageDetectResult#aiProbability()} 里，本枚举只做三档收敛。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public enum AiImageConfidence {

    /**
     * 高：元数据级确定性证据（C2PA / 生成器声明），或模型高置信输出。
     */
    HIGH,

    /**
     * 中：多条像素统计证据同时成立，或模型中等置信输出。
     */
    MEDIUM,

    /**
     * 低：单条启发式证据，或仅有「未发现痕迹」这一类否定性结论。
     */
    LOW
}
