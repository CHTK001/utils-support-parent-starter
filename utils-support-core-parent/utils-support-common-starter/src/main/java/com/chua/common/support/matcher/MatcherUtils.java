package com.chua.common.support.matcher;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
* 路径匹配工具类，提供 URL 路径的 Ant 风格匹配、模板变量提取等功能。
*
* <p>支持的通配符：
* <ul>
*   <li>{@code ?} — 匹配任意单个字符</li>
*   <li>{@code *} — 匹配路径段中任意字符（不跨目录）</li>
*   <li>{@code **} — 匹配任意路径（跨目录）</li>
*   <li>{@code {var}} — 路径变量，匹配任意字符并捕获</li>
*   <li>{@code {var:regex}} — 带正则约束的路径变量</li>
* </ul>
*
* @author CH
* @since 2026/07/16
 */
public final class MatcherUtils {

    /** Path_matcher */
    private static final PathMatcher PATH_MATCHER = new AntPathMatcher();

    /** 创建 MatcherUtils 实例 */
    private MatcherUtils() {
    }

    /**
    * 判断路径是否包含通配符（是否为模式字符串）。
    *
    * @param path 请求路径
    * @return 如果是模式字符串返回 true
     */
    public static boolean isPattern(String path) {
        return PATH_MATCHER.isPattern(path);
    }

    /**
    * 判断路径是否匹配指定模式。
    *
    * @param pattern 匹配模式（支持 Ant 风格通配符）
    * @param path    请求路径
    * @return 如果匹配返回 true
     */
    public static boolean matchPath(String pattern, String path) {
        return PATH_MATCHER.match(pattern, path);
    }

    /**
    * 判断路径是否匹配模式的开头部分。
    *
    * @param pattern 匹配模式
    * @param path    请求路径
    * @return 如果匹配开头返回 true
     */
    public static boolean matchStart(String pattern, String path) {
        return PATH_MATCHER.matchStart(pattern, path);
    }

    /**
    * 从路径中提取模板变量的值。
    *
    * <p>例如：模式 {@code /users/{id}/posts/{postId}} 匹配路径 {@code /users/123/posts/456}，
    * 返回 {@code {id: "123", postId: "456"}}。</p>
    *
    * @param pattern 包含 {var} 模板变量的模式
    * @param path    请求路径
    * @return 变量名到值的映射，不匹配时返回空 Map
     */
    public static Map<String, String> extractTemplateVariables(String pattern, String path) {
        AntPathMatcher antMatcher = (AntPathMatcher) PATH_MATCHER;
        try {
            return antMatcher.extractUriTemplateVariables(pattern, path);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /**
    * 从多个模式中找出与路径最匹配的一个。
    *
    * <p>匹配优先级：精确匹配 &gt; {var} 模式 &gt; * 通配符 &gt; ** 通配符</p>
    *
    * @param patterns 候选模式列表
    * @param path     请求路径
    * @return 最佳匹配的模式，无匹配返回 null
     */
    public static String getMatchingPattern(Iterable<String> patterns, String path) {
        if (patterns == null) {
            return null;
        }
        String bestMatch = null;
        for (String pattern : patterns) {
            if (PATH_MATCHER.match(pattern, path)) {
                if (bestMatch == null) {
                    bestMatch = pattern;
                } else {
                    AntPathMatcher antMatcher = (AntPathMatcher) PATH_MATCHER;
                    if (antMatcher.getPatternComparator(path).compare(pattern, bestMatch) < 0) {
                        bestMatch = pattern;
                    }
                }
            }
        }
        return bestMatch;
    }
}
