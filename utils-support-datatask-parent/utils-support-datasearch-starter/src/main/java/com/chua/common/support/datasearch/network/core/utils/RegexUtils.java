package com.chua.common.support.datasearch.network.core.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * @author CH
 * @since 4.0.0.42
 */

public class RegexUtils {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    public static Integer getFirstNumber(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group());
        }
        return null;
    }
}
