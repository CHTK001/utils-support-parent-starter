package com.chua.common.support.language.pinyin;

import java.util.List;

/**
 * 拼音转换器接口，定义汉字到拼音的转换契约。
 * <p>与 {@link PinyinFactory} 不同，该接口仅提供基础的单个字符串转换能力，
 * 不包含默认的聚合方法和 SPI 加载逻辑，适用于需要自行控制实现选择的场景。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * PinyinConverter converter = new MyPinyinConverter();
 * List<Pinyin> result = converter.transfer("你好");
 * }</pre>
 *
 * @author CH
 */
public interface PinyinConverter {

    /**
     * 将指定汉字字符串转换为拼音列表。
     * <p>每个汉字对应一个 {@link Pinyin} 对象，
     * 多音字会包含多个 {@link PinyinItem}。</p>
     *
     * @param word 待转换的汉字字符串
     * @return 拼音列表，每个元素对应一个汉字的拼音结果
     */
    List<Pinyin> transfer(String word);
}
