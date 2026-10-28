package com.chua.webview.jcef.support.internal;

/**
 * 映射定义接口。
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MappingDefinition {

    /**
     * 获取 Name。
     *
     * @return 映射名称
     */
    String getName();

    /**
     * 获取 Method。
     *
     * @return HTTP 方法
     */
    String[] getMethod();

    /**
     * 获取 Path。
     *
     * @return 路径
     */
    String getPath();
}
