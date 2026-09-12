package com.chua.common.support.language.pinyin;


/**
* 拼音单元，表示一个汉字对应的拼音信息。
* <p>每个 {@code PinyinItem} 实例包含一个汉字的完整拼音信息：</p>
* <ul>
*   <li>拼音文本 — 该汉字的标准拼音拼写，如 "zhong"</li>
*   <li>原始汉字 — 该拼音所对应的原始中文字符</li>
*   <li>首字母 — 拼音的首字母缩写，如 "z"</li>
*   <li>声调标记 — 拼音的声调数字（1~5），如 "1" 表示第一声</li>
* </ul>
*
* @param name  拼音文本，例如 "zhong"、"guo"
* @param word  对应的原始汉字字符串
* @param first 拼音首字母，用于缩写生成
* @param mark  声调数字标记（可选），如 "1" 表示阴平、"4" 表示去声
* @author CH
* @since 2021-12-30
 */
public record PinyinItem(
        String name,
        String word,
        String first,
        String mark) {
}
