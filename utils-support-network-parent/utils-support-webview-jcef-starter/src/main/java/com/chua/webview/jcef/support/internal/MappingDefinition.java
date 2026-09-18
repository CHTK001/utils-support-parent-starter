package com.chua.webview.jcef.support.internal;

/**
* 映射定义接口。
*
* @author CH
* @since 4.0.0.42
 */
public interface MappingDefinition {

    /**
    * @return 映射名称
    */
    String getName();

    /**
    * @return HTTP 方法
    */
    String[] getMethod();

    /**
    * @return 路径
    */
    String getPath();
}
