package com.chua.common.support.language.pinyin;

import com.chua.common.support.utils.NumberUtils;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.LinkedList;
import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 拼音结果，表示一个汉字或词语的完整拼音转换结果。
 * <p>每个 {@code Pinyin} 实例对应一个汉字的拼音信息集合，包含：</p>
 * <ul>
 *   <li>原始汉字 — 被转换的中文字符</li>
 *   <li>拼音项列表 — 该汉字可能对应的一个或多个拼音项（处理多音字）</li>
 * </ul>
 *
 * @author CH
 * @since 2021-12-30
 */
@NullUnmarked
@SuppressWarnings("NullAway")
@Data
@AllArgsConstructor
@RequiredArgsConstructor
public class Pinyin {

    /**
     * 原始汉字字符串。
     * <p>需要转换拼音的中文字符，支持单个汉字或已确认的词语。</p>
     */
    @NonNull
    private String word;

    /**
     * 该汉字对应的拼音项列表。
     * <p>对于多音字，列表中可能包含多个 {@link PinyinItem}，每个代表一种读音。
     * 单音字列表中只包含一个元素。</p>
     */
    private List<PinyinItem> items;

    /**
     * 使用单个拼音字符串构造 Pinyin 实例。
     * <p>适用于非中文字符或已确定读音的场景，自动创建一个拼音项。</p>
     *
     * @param items 拼音字符串
     * @param word  原始汉字
     */
    public Pinyin(String items, @NonNull String word) {
        this.word = word;
        this.items = new LinkedList<>();
        this.items.add(new PinyinItem(items, word, items.substring(0, 1), null));
    }

    /**
     * 使用拼音数组构造 Pinyin 实例。
     * <p>适用于从 API 返回的多拼音情况，自动解析声调数字并创建拼音项。</p>
     *
     * @param items 拼音字符串数组，每个元素可能包含声调数字后缀
     * @param word  原始汉字
     */
    public Pinyin(String[] items, @NonNull String word) {
        this.word = word;
        this.items = new LinkedList<>();
        String marks = null;
        String name;
        String fNames;
        for (String item : items) {
            name = item.substring(0, item.length() - 1);
            String substring = item.substring(item.length() - 1);
            if (NumberUtils.isNumber(substring)) {
                marks = item.substring(item.length() - 1);
            } else {
                name = item;
            }
            fNames = name.substring(0, 1);
            this.items.add(new PinyinItem(name, word, fNames, marks));
        }
    }

    /**
     * 获取该汉字的拼音首字母。
     * <p>如果存在多个拼音项，默认返回第一个拼音项的首字母。</p>
     *
     * @return 拼音首字母；如果没有拼音项则返回 {@code null}
     */
    public String getFirst() {
        if (null == items || items.isEmpty()) {
            return null;
        }
        return items.get(0).first();
    }

    /**
     * 获取该汉字的完整拼音文本。
     * <p>如果存在多个拼音项，默认返回第一个拼音项的完整拼音。</p>
     *
     * @return 拼音文本；如果没有拼音项则返回 {@code null}
     */
    public String getPinyin() {
        if (null == items || items.isEmpty()) {
            return null;
        }
        return items.get(0).name();
    }
}
