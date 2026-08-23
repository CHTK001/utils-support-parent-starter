package com.chua.common.support.image.png;

import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.imageio.ImageWriteParam;
import java.util.Locale;
/**
 * PNG 图像写入参数类，继承自 ImageWriteParam，配置 PNG 图像的压缩参数。
 *
* @author CH
 * @since 4.0.0.42
*/
@EqualsAndHashCode(callSuper = true)
@Data
public final class PNGImageWriteParam extends ImageWriteParam {

    /**
     * 默认的压缩质量级别为 0.5，表示中等压缩。
     */
    private static final float DEFAULT_QUALITY = 0.5f;

    /**
     * 压缩方法名称数组，PNG 目前只支持 Deflate 压缩方法。
     */
    private static final String[] compressionNames = {"Deflate"};

    /**
     * 压缩质量值数组，表示不同的压缩质量级别。
     */
    private static final float[] qualityVals = {0.00F, 0.30F, 0.75F, 1.00F};

    /**
     * 压缩质量描述数组，对应不同的压缩质量级别。
     */
    private static final String[] qualityDescs = {
            // 高压缩率，低图像质量
            "Low compression",
            // 中等压缩率，中等图像质量
            "Medium compression",
            // 低压缩率，高图像质量
            "High compression"
    };

    /**
     * 是否包含 IDAT（图像数据）块的动画 PNG。
     */
    private boolean animContainsIDAT;

    /**
     * 构造函数，初始化 PNG 图像写入参数。
     *
     * @param locale 本地化设置，确定压缩质量描述的语言
     */
    PNGImageWriteParam(Locale locale) {
        super();
        // 支持渐进式编码
        this.canWriteProgressive = true;
        this.locale = locale;
        // 支持压缩
        this.canWriteCompressed = true;
        // 设置压缩方法
        this.compressionTypes = compressionNames;
        // 默认使用 Deflate 压缩方法
        this.compressionType = compressionNames[0];
        // 默认压缩模式
        this.compressionMode = MODE_DEFAULT;
        // 默认压缩质量
        this.compressionQuality = DEFAULT_QUALITY;
        // 默认动画 PNG 包含 IDAT 块
        animContainsIDAT = true;
    }

    /**
     * 重置压缩质量设置，将其恢复为默认值。
     *
     * <p> 默认实现会将压缩质量重置为 <code>0.5F</code>。
     *
     * @throws IllegalStateException 如果压缩模式不是 <code>MODE_EXPLICIT</code>，则抛出此异常。
     */
    @Override
    public void unsetCompression() {
        super.unsetCompression();
        // 恢复默认压缩方法
        this.compressionType = compressionType;
        // 恢复默认压缩质量
        this.compressionQuality = DEFAULT_QUALITY;
    }

    /**
     * 返回 <code>true</code>，因为 PNG 插件仅支持无损压缩。
     *
     * @return 始终返回 <code>true</code>，表示 PNG 压缩是无损的
     */
    @Override
    public boolean isCompressionLossless() {
        
        return true;
    
    }

    /**
     * 获取压缩质量描述。
     *
     * @return 返回压缩质量描述数组的副本
     */
    @Override
    public String[] getCompressionQualityDescriptions() {
        super.getCompressionQualityDescriptions();
        return qualityDescs.clone();
    }

    /**
     * 获取压缩质量值。
     *
     * @return 返回压缩质量值数组的副本
     */
    @Override
    public float[] getCompressionQualityValues() {
        super.getCompressionQualityValues();
        return qualityVals.clone();
    }

}

