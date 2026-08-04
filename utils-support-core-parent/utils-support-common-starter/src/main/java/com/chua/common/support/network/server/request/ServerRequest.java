package com.chua.common.support.network.server.request;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NullUnmarked;

/**
 * 服务器请求抽象，封装 HTTP 或自定义协议的请求数据。
 * <p>
 * 提供对请求行、请求头、请求体、参数、属性等元素的统一访问接口。
 *
 * @author CH
 */
@NullUnmarked
public interface ServerRequest {

    /** @return 完整 URI，包含路径和查询参数 */
    String getUri();

    /** @return 请求路径，不含查询参数 */
    String getPath();

    /** @return HTTP 请求方法（GET、POST 等） */
    HttpMethod getMethod();

    /** @param name 请求头名称 @return 请求头值，不存在返回 null */
    String getHeader(String name);

    /** @return 所有请求头 */
    HttpHeader getHeaders();

    /** @return 查询参数 Map */
    Map<String, String> getParams();

    /** @param name 参数名 @return 参数值，不存在返回 null */
    String getParam(String name);

    /** @return Content-Type 头值 */
    String getContentType();

    /** @return 请求体长度，-1 表示未知 */
    long getContentLength();

    /** @return 请求体字节数组 */
    byte[] getBody();

    /** @return 请求体字符串（UTF-8 编码） */
    String getBodyString();

    /** @return 请求体输入流 */
    InputStream getInputStream();

    /** @return 客户端地址 */
    String getRemoteAddress();

    /** @return 客户端端口 */
    int getRemotePort();

    /**
     * 获取所有请求属性（非请求参数，由 Filter 或框架设置）。
     *
     * @return 属性 Map
     */
    Map<String, Object> getAttributes();

    /** @param name 属性名 @return 属性值 */
    Object getAttribute(String name);

    /**
     * 设置请求属性，用于在 Filter 之间传递数据。
     *
     * @param name  属性名
     * @param value 属性值
     */
    void setAttribute(String name, Object value);

    /**
     * 获取表单字段（仅 multipart/form-data 或 application/x-www-form-urlencoded 时有效）。
     *
     * @return 表单字段名到值的映射，不存在时返回空 Map
     */
    default Map<String, String> getFormData() {
        return Collections.emptyMap();
    }

    /**
     * 获取上传的文件列表（仅 multipart/form-data 时有效）。
     *
     * @return 上传文件列表，不存在时返回空 List
     */
    default List<FormFile> getFiles() {
        return Collections.emptyList();
    }
}
