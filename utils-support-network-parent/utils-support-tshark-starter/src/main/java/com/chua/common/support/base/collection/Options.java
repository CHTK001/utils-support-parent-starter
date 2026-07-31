package com.chua.common.support.base.collection;

import java.util.ArrayList;
import java.util.List;

public class Options {
    private final List<String> options = new ArrayList<>();

    public static Options of(String... options) {
        Options o = new Options();
        for (String opt : options) {
            o.options.add(opt);
        }
        return o;
    }

    public List<String> getOptions() {
        return options;
    }

    public void forEach(java.util.function.BiConsumer<Integer, String> consumer) {
        int i = 0;
        for (String opt : options) {
            consumer.accept(i++, opt);
        }
    }
}
