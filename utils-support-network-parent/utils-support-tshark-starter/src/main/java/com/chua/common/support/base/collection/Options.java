package com.chua.common.support.base.collection;

import java.util.ArrayList;
import java.util.List;

/**
 * 字符串选项集合包装，提供静态工厂 {@link #of(String...)} 与下标访问回调。
 *
 * @author CH
 * @since 4.0.0
 */
public class Options {

    /**
     * 内部字符串列表
     */
    private final List<String> options = new ArrayList<>();

    /**
     * 静态工厂。
     *
     * @param options 变长字符串参数
     * @return Options 实例
     */
    public static Options of(String... options) {
        Options o = new Options();
        for (String opt : options) {
            o.options.add(opt);
        }
        return o;
    }

    /**
     * @return 内部字符串列表
     */
    public List<String> getOptions() {
        return options;
    }

    /**
     * 按 (index, value) 形式遍历所有选项。
     *
     * @param consumer 下标 + 字符串消费者
     */
    public void forEach(java.util.function.BiConsumer<Integer, String> consumer) {
        int i = 0;
        for (String opt : options) {
            consumer.accept(i++, opt);
        }
    }
}
