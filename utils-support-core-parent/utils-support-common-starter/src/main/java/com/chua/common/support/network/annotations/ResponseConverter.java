package com.chua.common.support.network.annotations;

import com.chua.common.support.network.server.response.ServerResponse;

/**
* 响应转化器 SPI，将服务端响应内容转换为指定格式。
* <p>
* 支持以下格式：
* <ul>
*   <li>HTML — {@code text/html}</li>
*   <li>XML — {@code application/xml}</li>
*   <li>JSON — {@code application/json}</li>
*   <li>二进制 — {@code application/octet-stream}</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
 */
public interface ResponseConverter {

    /**
    * 将对象转换为指定格式并写入响应。
    *
    * @param response 响应对象
    * @param data     待转换数据
    * @throws Exception 转换异常
    */
    void convert(ServerResponse response, Object data) throws Exception;

    /**
    * 获取支持的 Content-Type。
    *
    * @return Content-Type 值
    */
    String contentType();

    /**
    * 判断是否支持该数据类型。
    *
    * @param data 数据对象
    * @return 支持返回 true
    */
    boolean support(Object data);

    /**
    * 获取 SPI 排序值。
    *
    * <p>值越小优先级越高。
    *
    * @return 排序值，默认 0
    */
    default int getOrder() {
        return 0;
    }
}
