package com.chua.common.support.language.pinyin;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
* 拼音工厂接口，定义汉字转拼音的核心 SPI 契约。
* <p>所有拼音转换实现都需要实现此接口，并通过 SPI 机制注册。
* 系统默认会加载别名为 "{@code tiny}" 的实现作为默认拼音引擎。</p>
*
* <p>使用示例：</p>
* <pre>{@code
* // 获取默认拼音工厂并转换单个汉字
* PinyinFactory factory = PinyinFactory.getDefault();
* List<Pinyin> result = factory.transfer("中");
*
* // 直接获取拼音字符串
* String pinyin = factory.transferSplit("中国");
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface PinyinFactory {

    /**
    * 获取系统默认的拼音工厂实例。
    * <p>通过 SPI 机制加载别名为 "{@code tiny}" 的实现，
    * 如果找不到对应的实现则返回 {@code null}。</p>
    *
    * @return 默认拼音工厂实例，未注册时返回 {@code null}
     */
    static PinyinFactory getDefault() {
        ServiceProvider<PinyinFactory> provider = ServiceProvider.of(PinyinFactory.class);
        return provider.getExtension("tiny");
    }

    /**
    * 将指定汉字字符串转换为拼音列表。
    * <p>每个汉字对应一个 {@link Pinyin} 对象，
    * 多音字会包含多个拼音项。</p>
    *
    * @param word 待转换的汉字字符串
    * @return 拼音列表，每个元素对应一个汉字的拼音结果
     */
    List<Pinyin> transfer(String word);

    /**
    * 将多个汉字字符串批量转换为拼音列表。
    * <p>对每个输入的字符串执行 {@link #transfer(String)}，
    * 并提取每个转换结果中的第一个拼音项组装为列表返回。</p>
    *
    * @param words 待转换的汉字字符串数组
    * @return 拼音列表，按输入顺序对应每个汉字字符串的首个拼音
     */
    default List<Pinyin> transfer(String[] words) {
        if (CollectionUtils.isEmpty(words)) {
            return Collections.emptyList();
        }
        return Arrays.stream(words)
                .map(this::transfer)
                .map(CollectionUtils::findFirst)
                .filter(Objects::nonNull)
                .collect(ArrayList::new, List::add, List::addAll);
    }

    /**
    * 将汉字字符串转换为用空格分隔的拼音字符串。
    * <p>示例：{@code transferSplit("中国")} 返回 {@code "zhong guo"}。</p>
    *
    * @param word 待转换的汉字字符串
    * @return 空格分隔的拼音字符串
     */
    default String transferSplit(String word) {
        return transfer(word).stream()
                .map(Pinyin::getPinyin)
                .collect(Collectors.joining(" "));
    }

    /**
    * 将多个汉字字符串转换为连续拼音字符串。
    * <p>示例：{@code transferSplit(new String[]{"中国", "人民"})} 返回 {@code "zhongguorenmin"}。</p>
    *
    * @param words 待转换的汉字字符串数组
    * @return 连续拼音字符串（无分隔符）
     */
    default String transferSplit(String[] words) {
        return transfer(words).stream()
                .map(Pinyin::getPinyin)
                .collect(Collectors.joining());
    }

    /**
    * 获取汉字字符串的第一个拼音结果。
    * <p>对于多音字词语，仅返回第一个汉字的第一个拼音项。</p>
    *
    * @param word 待转换的汉字字符串
    * @return 第一个拼音结果；如果转换结果为空则返回 {@code null}
     */
    default Pinyin first(String word) {
        List<Pinyin> result = transfer(word);
        if (result.isEmpty()) {
            return null;
        }
        return result.getFirst();
    }
}
