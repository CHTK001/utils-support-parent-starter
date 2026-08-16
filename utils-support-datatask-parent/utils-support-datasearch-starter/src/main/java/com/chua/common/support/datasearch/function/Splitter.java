package com.chua.common.support.datasearch.function;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class Splitter {

    private final String delimiter;

    private Splitter(String delimiter) {
        this.delimiter = delimiter;
    }

    public static Splitter on(String delimiter) {
        return new Splitter(delimiter);
    }

    public List<String> splitToList(String input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.asList(input.split(delimiter));
    }
}
