// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.pytorch.biggan;

/**
 * biggan 512x512 生成 Translator。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class BigGAN512Translator extends BigGANTranslator {

    /**
     * 创建 biggan512Translator 实例
    */
    public BigGAN512Translator() {
        super(512, 0.4f);
    }
}