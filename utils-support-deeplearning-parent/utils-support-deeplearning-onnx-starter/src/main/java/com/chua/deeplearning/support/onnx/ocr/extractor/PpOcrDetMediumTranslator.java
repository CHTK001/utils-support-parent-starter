package com.chua.deeplearning.support.onnx.ocr.extractor;


import com.chua.deeplearning.support.ai.DetectionConfiguration;
/**
 * PP-ocrv6 medium 文字检测（精度更高，模型体积更大）。
 *
 * <p>继承 {@link PpOcrDetTranslator}，默认加载 medium 资源。
 * medium 检测概率分布与 tiny 不同，DB 阈值降低到 0.25 以检出更多区域。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PpOcrDetMediumTranslator extends PpOcrDetTranslator {

    /** 创建 ppocrdetmediumtranslator 实例 */
    public PpOcrDetMediumTranslator() {
        super("ocr/PP-OCRv6/medium/det_infer/", "paddleocrv6-medium-det");
    }

    @Override
    /** 获取阈值 */
    protected float getThreshold() {
        return effectiveThreshold(0.25f);
    }
    /**
     * 创建 Translator（支持外部阈值覆盖）。
     *
     * @param configuration 检测配置（可空）
     */
    public PpOcrDetMediumTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        super("ocr/PP-OCRv6/medium/det_infer/", "paddleocrv6-medium-det");
        if (null != configuration) {
            applyThresholdOverride(configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, -1f));
        }
    }

}
