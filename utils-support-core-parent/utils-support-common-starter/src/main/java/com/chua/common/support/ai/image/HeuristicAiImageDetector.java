package com.chua.common.support.ai.image;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDefault;
import com.chua.common.support.utils.PhotoMetadataUtils;

/**
 * 默认图片 AI 检测实现：元数据 + 像素统计启发式。
 *
 * <p>行为完全委托 {@link PhotoMetadataUtils#analyze(byte[])}，本类只把它的
 * {@link PhotoMetadataUtils.Report} 映射成与引擎无关的 {@link AiImageDetectResult}，
 * 判定口径零改动：</p>
 * <ul>
 *   <li>元数据命中（C2PA / 生成器声明 / 生成参数块）→ {@link AiImageVerdict#AI_GENERATED}；</li>
 *   <li>像素统计命中（上采样网格伪迹、缺失传感器噪声）→ {@link AiImageVerdict#AI_EDITED}；</li>
 *   <li>都没命中 → {@link AiImageVerdict#NO_AI_SIGNAL}；</li>
 *   <li>数据无法解码 / 格式不支持 → {@link AiImageVerdict#UNKNOWN}。</li>
 * </ul>
 *
 * <p>为什么不产出 {@code aiProbability}：启发式给不出校准过的概率，硬凑一个数字会被
 * 上层当成模型置信度使用。需要概率走模型实现。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
@SpiDefault
@Spi(AiImageDetector.DEFAULT_NAME)
public class HeuristicAiImageDetector implements AiImageDetector {

    /**
     * 实现名。
     */
    private static final String NAME = AiImageDetector.DEFAULT_NAME;

    /**
     * 实现名。
     *
     * @return heuristic
     */
    @Override
    public String name() {
        return NAME;
    }

    /**
     * 检测图片是否 AI 生成/编辑。
     *
     * @param data 图片原始字节
     * @return 统一结论，永不为 {@code null}
     */
    @Override
    public AiImageDetectResult detect(byte[] data) {
        PhotoMetadataUtils.Report report = PhotoMetadataUtils.analyze(data);
        return new AiImageDetectResult(NAME,
                verdictOf(report.verdict()),
                confidenceOf(report.confidence()),
                report.format(),
                report.hasContentCredentials(),
                report.digitalSourceType(),
                report.generator(),
                report.generationParams(),
                null,
                report.signals());
    }

    /**
     * 报告结论文案 → 语义枚举。
     *
     * @param verdict 报告结论文案
     * @return 语义结论，无法识别时取 {@link AiImageVerdict#UNKNOWN}
     */
    private static AiImageVerdict verdictOf(String verdict) {
        if (PhotoMetadataUtils.VERDICT_AI_GENERATED.equals(verdict)) {
            return AiImageVerdict.AI_GENERATED;
        }
        if (PhotoMetadataUtils.VERDICT_AI_EDITED.equals(verdict)) {
            return AiImageVerdict.AI_EDITED;
        }
        if (PhotoMetadataUtils.VERDICT_NO_AI_SIGNALS.equals(verdict)) {
            return AiImageVerdict.NO_AI_SIGNAL;
        }
        return AiImageVerdict.UNKNOWN;
    }

    /**
     * 报告置信度文案 → 语义枚举。
     *
     * @param confidence 报告置信度文案
     * @return 语义置信度，无法识别时取 {@link AiImageConfidence#LOW}
     */
    private static AiImageConfidence confidenceOf(String confidence) {
        if (PhotoMetadataUtils.CONFIDENCE_HIGH.equals(confidence)) {
            return AiImageConfidence.HIGH;
        }
        if (PhotoMetadataUtils.CONFIDENCE_MEDIUM.equals(confidence)) {
            return AiImageConfidence.MEDIUM;
        }
        return AiImageConfidence.LOW;
    }
}
