package com.chua.common.support.ai.image;

import java.util.List;

/**
 * 图片 AI 检测结论，与具体检测引擎无关的统一载体。
 *
 * <p>启发式（元数据 + 像素统计）与分类模型产出的字段并集都收敛到这里：
 * 元数据类字段在模型实现下取缺省值（{@code false} / 空串），
 * 模型概率在启发式实现下为 {@code null}（表示该实现不产出概率，而不是概率为 0）。</p>
 *
 * <p>约束：</p>
 * <ul>
 *   <li>{@code evidence} 为不可变列表，只描述「看到了什么」，不承载结论；</li>
 *   <li>{@code detector} 记录真正执行判定的实现名，回落默认实现时必须如实反映，
 *       不能因为上层配置了模型就写成模型名。</li>
 * </ul>
 *
 * @param detector              实际执行判定的实现名（如 heuristic / onnx）
 * @param verdict               判定结论
 * @param confidence            置信度
 * @param format                图像格式：JPEG / PNG / WebP / UNKNOWN
 * @param hasContentCredentials 是否存在 C2PA 内容凭证（JUMBF）
 * @param digitalSourceType     IPTC DigitalSourceType 值（无则空串）
 * @param generator             命中的生成器名称（无则空串）
 * @param generatorSource       生成器名称的来源字段（无则空串）
 * @param generationParams      是否检测到生成参数块（prompt / steps / sampler / seed 等）
 * @param aiProbability         模型给出的 AI 概率（0~1）；启发式实现为 {@code null}
 * @param evidence              证据描述列表
 * @author CH
 * @since 4.0.0.51
 */
public record AiImageDetectResult(String detector,
                                 AiImageVerdict verdict,
                                 AiImageConfidence confidence,
                                 String format,
                                 boolean hasContentCredentials,
                                 String digitalSourceType,
                                 String generator,
                                 String generatorSource,
                                 boolean generationParams,
                                 Double aiProbability,
                                 List<String> evidence) {

    /**
     * 归一化可空字段，保证调用方拿到的永远是可直接使用的值。
     */
    public AiImageDetectResult {
        detector = detector == null ? "" : detector;
        verdict = verdict == null ? AiImageVerdict.UNKNOWN : verdict;
        confidence = confidence == null ? AiImageConfidence.LOW : confidence;
        format = format == null || format.isBlank() ? "UNKNOWN" : format;
        digitalSourceType = digitalSourceType == null ? "" : digitalSourceType;
        generator = generator == null ? "" : generator;
        generatorSource = generatorSource == null ? "" : generatorSource;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * 是否判定为 AI 参与（生成或编辑）。
     *
     * @return true 表示结论落在 AI 生成 / AI 编辑
     */
    public boolean aiGenerated() {
        return verdict == AiImageVerdict.AI_GENERATED || verdict == AiImageVerdict.AI_EDITED;
    }

    /**
     * 构造「引擎不可用」结论。
     *
     * <p>供模型实现在缺模型文件、缺标签、推理失败时返回，让上层能区分
     * 「引擎没跑起来」与「跑了但没发现痕迹」——两者都不代表图片是人工创作，
     * 但排查方向不同。</p>
     *
     * @param detector 实现名
     * @param note     不可用原因
     * @return 不可用结论
     */
    public static AiImageDetectResult unavailable(String detector, String note) {
        return new AiImageDetectResult(detector, AiImageVerdict.UNKNOWN, AiImageConfidence.LOW,
                "UNKNOWN", false, "", "", "", false, null, List.of(note == null ? "" : note));
    }
}
