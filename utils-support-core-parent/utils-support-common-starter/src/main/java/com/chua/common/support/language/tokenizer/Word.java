package com.chua.common.support.language.tokenizer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
* 分词结果中的一个词单元，包含词语文本和词性信息。
*
* <p>每个 {@code Word} 实例表示分词器处理输入文本后输出的一个基本单位，
* 包含以下信息：
* <ul>
*   <li>词语文本 — 分词后的实际词条内容</li>
*   <li>词性标注 — 该词语在上下文中的词性（名词、动词等）</li>
*   <li>偏移位置 — 词语在原始文本中的起始和结束位置</li>
*   <li>概率权重 — 分词结果的置信度和自定义权重</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
* @version 1.0.0
 */
@Data
@AllArgsConstructor
@RequiredArgsConstructor
@EqualsAndHashCode
public class Word {

    /**
    * 分词后的词语文本内容。
    * <p>例如：输入"我爱北京天安门"，分词后可能包含 "我"、"爱"、"北京"、"天安门" 等。</p>
    */
    @NonNull
    /** 词 */
    public String word;

    /**
    * 词语的词性标注。
    * <p>采用标准词性标记集，例如 "n" 表示名词，"v" 表示动词，"a" 表示形容词等。</p>
    */
    @NonNull
    /** Nature */
    public String nature;

    /**
    * 词语在原始文本中的起始字符偏移位置（从 0 开始计数）。
    * <p>需要分词器在分词时启用 offset 选项才能正确填充此字段。</p>
    */
    public int offset;

    /**
    * 词语在原始文本中的结束字符偏移位置。
    * <p>该位置是词语最后一个字符的下一个索引（即末端位置，不包含该字符）。</p>
    */
    public int end;

    /**
    * 分词结果的置信度概率值。
    * <p>取值范围通常在 0.0 ~ 1.0 之间，值越高表示分词结果越可靠。</p>
    */
    private double prob;

    /**
    * 该词语在上下文中的权重值。
    * <p>用于关键词提取、文本摘要等场景中衡量词语的重要性。</p>
    */
    private Float weight;

    @Override
    /** ToString */
    public String toString() {
        return word + "(" + nature + ")";
    }
}
