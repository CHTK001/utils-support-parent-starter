package com.chua.common.support.language.tokenizer;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.ObjectUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
* 分词器接口，定义中文分词的核心 SPI 契约。
* <p>所有分词实现都需要实现此接口并通过 SPI 机制注册。
* 系统默认会将别名为 "{@code jieba}" 的实现作为默认分词引擎。</p>
*
* <p>词性标注编码参考表：</p>
* <table border="1">
*   <caption>词性编码参考</caption>
*   <tr><th>编码</th><th>名称</th><th>说明</th></tr>
*   <tr><td>Ag</td><td>形语素</td><td>形容词性语素，形容词代码为 a，语素代码 g 前面置以 A</td></tr>
*   <tr><td>n</td><td>名词</td><td>取英语名词 noun 的第 1 个字母</td></tr>
*   <tr><td>v</td><td>动词</td><td>取英语动词 verb 的第 1 个字母</td></tr>
*   <tr><td>a</td><td>形容词</td><td>取英语形容词 adjective 的第 1 个字母</td></tr>
*   <tr><td>d</td><td>副词</td><td>取副词 adverb 的第 2 个字母（第 1 个字母已用于形容词）</td></tr>
*   <tr><td>p</td><td>介词</td><td>取英语介词 prepositional 的第 1 个字母</td></tr>
*   <tr><td>c</td><td>连词</td><td>取英语连词 conjunction 的第 1 个字母</td></tr>
*   <tr><td>r</td><td>代词</td><td>取英语代词 pronoun 的第 2 个字母（p 已用于介词）</td></tr>
*   <tr><td>ns</td><td>地名</td><td>名词代码 n 和处所词代码 s 并在一起</td></tr>
*   <tr><td>nr</td><td>人名</td><td>名词代码 n 和人 "ren" 的声母并在一起</td></tr>
*   <tr><td>nt</td><td>机构团体</td><td>"团" 的声母为 t，名词代码 n 和 t 并在一起</td></tr>
*   <tr><td>w</td><td>标点符号</td><td>标点、空格等非文字字符</td></tr>
*   <tr><td>un</td><td>未知词</td><td>不可识别词及用户自定义词组（非北大标准，CSW 分词中定义）</td></tr>
* </table>
*
* @author CH
* @since 4.0.0.42
 */
@Spi("jieba")
public interface Tokenizer {

    /**
    * 使用默认分词器对文本进行分词。
    * <p>通过 SPI 加载默认分词实现，如果未能找到合适的实现，
    * 则退化为按单个字符切分。</p>
    *
    * @param word 待分词的文本
    * @return 分词后的词组列表
     */
    static List<Word> segment(String word) {
        ServiceProvider<Tokenizer> serviceProvider = ServiceProvider.of(Tokenizer.class);
        Tokenizer tokenizer = serviceProvider.getSpiService();
        if (null != tokenizer) {
            return tokenizer.segments(word);
        }
        return Arrays.stream(word.split(""))
                .map(it -> new Word(it, ""))
                .collect(Collectors.toList());
    }

    /**
    * 使用指定类型的分词器对文本进行分词。
    * <p>通过 SPI 别名加载对应的分词器实现，适用于需要特定分词策略的场景。</p>
    *
    * @param word 待分词的文本
    * @param type 分词器 SPI 别名，如 "hanlp"、"jieba" 等
    * @return 分词后的词组列表
     */
    static List<Word> segment(String word, String type) {
        ServiceProvider<Tokenizer> serviceProvider = ServiceProvider.of(Tokenizer.class);
        Tokenizer tokenizer = serviceProvider.getExtension(type);
        if (null != tokenizer) {
            return tokenizer.segments(word);
        }
        return Arrays.stream(word.split(""))
                .map(it -> new Word(it, ""))
                .collect(Collectors.toList());
    }

    /**
    * 根据 SPI 别名获取分词器实例。
    * <p>每次调用都会返回一个新的分词器实例（非单例）。</p>
    *
    * @param type 分词器 SPI 别名
    * @return 分词器实例；未找到对应实现时返回 {@code null}
     */
    static Tokenizer of(String type) {
        ServiceProvider<Tokenizer> serviceProvider = ServiceProvider.of(Tokenizer.class);
        return serviceProvider.getNewExtension(type);
    }

    /**
    * 获取一个新的默认分词器实例。
    * <p>通过 SPI 查找默认实现并返回新实例，可用于需要多次独立使用分词器的场景。</p>
    *
    * @return 默认分词器实例；未找到时返回 {@code null}
     */
    static Tokenizer newDefault() {
        ServiceProvider<Tokenizer> serviceProvider = ServiceProvider.of(Tokenizer.class);
        return serviceProvider.getSpiService();
    }

    /**
    * 对文本进行分词，返回词组列表。
    * <p>实现类需要根据各自的分词算法对输入文本进行切分，
    * 每个切分结果附带词性标注信息。</p>
    *
    * @param word 待分词的文本
    * @return 分词后的词组列表
     */
    List<Word> segments(String word);

    /**
    * 对文本进行分词并返回用空格分隔的字符串。
    * <p>示例：{@code toSegment("我爱北京天安门")} 返回 {@code "我 爱 北京 天安门"}。</p>
    *
    * @param word 待分词的文本
    * @return 空格分隔的分词结果字符串
     */
    default String toSegment(String word) {
        return segments(word).stream()
                .map(Word::getWord)
                .collect(Collectors.joining(" "));
    }

    /**
    * 对文本进行分词并返回第一个词条的文字。
    * <p>适用于快速获取文本的第一个有意义词条的场景。</p>
    *
    * @param word 待分词的文本
    * @return 第一个词条的文字；如果分词结果为空则返回 {@code null}
     */
    default String toFirstSegment(String word) {
        List<Word> result = segment(word);
        if (result == null || result.isEmpty()) {
            return null;
        }
        return result.getFirst().getWord();
    }
}
