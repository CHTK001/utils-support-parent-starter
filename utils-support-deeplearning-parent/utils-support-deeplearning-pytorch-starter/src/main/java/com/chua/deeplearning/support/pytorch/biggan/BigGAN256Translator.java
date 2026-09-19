package com.chua.deeplearning.support.pytorch.biggan;

/**
 * biggan 256x256 生成 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BigGAN256Translator extends BigGANTranslator {

    /**
     * 创建 biggan256Translator 实例
    */
    public BigGAN256Translator() {
        super(256, 0.4f);
    }
}
