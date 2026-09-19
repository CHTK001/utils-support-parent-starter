package com.chua.common.support.matcher;

import java.util.Map;

/**
 * 路径匹配器
 *
 * @author CH
 * @since 1.0
 */
public interface PathMatcher {
    
    /**
     * 默认实例
     * 使用 Ant 风格的路径匹配器
     */
    static PathMatcher INSTANCE = new AntPathMatcher();
    
    /**
     * 判断给定的路径是否是一个模式字符串
     * 支持 Ant 风格的通配符，如 * ? ** 等
     *
     * @param path 给定的路径
     * @return 如果是模式字符串返回 true，否则返回 false
     */
    boolean isPattern(String path);

    /**
     * 判断给定的路径是否匹配给定的模式
     *
     * @param pattern 匹配模式
     * @param path 给定的路径
     * @return 如果匹配返回 true，否则返回 false
     */
    boolean match(String pattern, String path);

    /**
     * 判断给定的路径是否匹配给定的模式的开头部分
     *
     * @param pattern 匹配模式
     * @param path 给定的路径
     * @return 如果匹配返回 true，否则返回 false
     */
    boolean matchStart(String pattern, String path);

    /**
     * 从匹配的模式中提取 URI 模板变量。
     *
     * @param pattern 匹配模式
     * @param path 给定的路径
     * @return 变量名到值的映射
     */
    Map<String, String> extractUriTemplateVariables(String pattern, String path);
}
