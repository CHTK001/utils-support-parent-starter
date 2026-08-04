package com.chua.common.support.utils;

import com.chua.common.support.constant.CommonConstant;
import com.chua.common.support.matcher.PathMatcher;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;


/**
 * 匹配工具类，提供通配符、正则、自动等多种匹配方式。
 *
 * @author CH
 * @since 2025-01-15
 */
@NullUnmarked
@Slf4j
public class MatchUtils {

    /**
     * 匹配类型枚举
     */
    @Getter
    public enum MatchType {
        /** 通配符匹配：* 匹配任意字符，? 匹配单个字符 */
        WILDCARD("通配符匹配"),
        /** 正则表达式匹配 */
        REGEX("正则匹配"),
        /** 分段路径匹配 */
        SEGMENT_PATH("分段路径匹配"),
        /** 自动匹配：自动选择最优的匹配方式 */
        AUTO("自动匹配");

        /**
         * 描述
         */
        private final String description;

        MatchType(String description) {
            this.description = description;
        }
    }

    /**
     * 执行匹配
     *
     * @param matchValue 待匹配的值
     * @param matchedValue 被匹配的值
     * @param matchType 匹配类型
     * @return true 如果匹配成功
     */
    public static boolean isMatch(String matchValue, String matchedValue, MatchType matchType) {
        if (StringUtils.isEmpty(matchValue) || StringUtils.isEmpty(matchedValue)) {
            return StringUtils.equals(matchValue, matchedValue);
        }
        if (matchedValue.equals(matchValue)) {
            return true;
        }
        try {
            return switch (matchType) {
                case WILDCARD -> matchWildcard(matchValue, matchedValue);
                case REGEX -> matchRegex(matchValue, matchedValue);
                case AUTO -> matchAuto(matchValue, matchedValue);
                default -> {
                    log.warn("不支持的匹配类型：{}", matchType);
                    yield false;
                }
            };
        } catch (Exception e) {
            log.warn("匹配异常：matchValue={}, matchedValue={}", matchValue, matchedValue, e);
            return false;
        }
    }

    /**
     * 通配符匹配
     */
    private static boolean matchWildcard(String pattern, String text) {
        return PathMatcher.INSTANCE.match(pattern, text);
    }

    /**
     * 正则匹配
     */
    private static boolean matchRegex(String regex, String text) {
        try {
            return Pattern.matches(regex, text);
        } catch (Exception e) {
            log.warn("正则匹配异常：regex={}, text={}", regex, text, e);
            return false;
        }
    }

    /**
     * 自动匹配：先精确匹配，再通配符，再正则
     */
    private static boolean matchAuto(String pattern, String text) {
        if (pattern.equals(text)) {
            return true;
        }
        if (pattern.indexOf(CommonConstant.WILDCARD_ASTERISK) != CommonConstant.INDEX_NOT_FOUND
            || pattern.indexOf(CommonConstant.WILDCARD_QUESTION) != CommonConstant.INDEX_NOT_FOUND) {
            return matchWildcard(pattern, text);
        }
        try {
            return matchRegex(pattern, text);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 通配符匹配（便捷方法）
     *
     * @param pattern 通配符模式
     * @param text 待匹配文本
     * @return true 如果匹配成功
     */
    public static boolean isWildcardMatch(String pattern, String text) {
        return isMatch(pattern, text, MatchType.WILDCARD);
    }

    /**
     * 正则匹配（便捷方法）
     *
     * @param regex 正则表达式
     * @param text 待匹配文本
     * @return true 如果匹配成功
     */
    public static boolean isRegexMatch(String regex, String text) {
        return isMatch(regex, text, MatchType.REGEX);
    }

    /**
     * 自动匹配（便捷方法）
     *
     * @param pattern 匹配模式
     * @param text 待匹配文本
     * @return true 如果匹配成功
     */
    public static boolean isAutoMatch(String pattern, String text) {
        return isMatch(pattern, text, MatchType.AUTO);
    }
}