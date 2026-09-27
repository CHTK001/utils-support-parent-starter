package com.chua.common.support.datasearch.function;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class Splitter {

    /**
     * Delimiter
    */
    private final String delimiter;

    /**
     * 创建 Splitter 实例
     * @param delimiter delimiter
     */
    private Splitter(String delimiter) {
        this.delimiter = delimiter;
    }

    /**
     * On
     *
     * @param delimiter delimiter
     * @return on的结果
     */
    public static Splitter on(String delimiter) {
        return new Splitter(delimiter);
    }

    /**
     * 分割转为列表
     *
     * @param input 输入
     * @return 分割转为列表的结果
     */
    public List<String> splitToList(String input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        // 分隔符按字面量处理（$$$ 之类作正则会失配）；limit=-1 保留尾部空串以维持下标对齐
        return Arrays.asList(input.split(Pattern.quote(delimiter), -1));
    }
}
