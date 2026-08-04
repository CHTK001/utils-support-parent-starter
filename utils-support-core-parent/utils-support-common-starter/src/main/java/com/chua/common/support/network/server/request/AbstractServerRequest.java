package com.chua.common.support.network.server.request;

import com.chua.common.support.network.http.HttpHeader;
import com.chua.common.support.network.http.HttpMethod;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NullUnmarked;

/**
 * 服务器请求抽象基类，提供 {@link ServerRequest} 常用方法的默认实现。
 *
 * <p>子类只需实现协议特定的数据获取方法（如 {@link #getUri()}、{@link #getMethod()} 等），
 * 无需重复处理属性存储、请求体缓存等通用逻辑。
 *
 * <h2>属性存储</h2>
 * 内置线程安全的 {@link ConcurrentHashMap} 用于在 Filter 之间传递数据，
 * 子类无需自行维护属性 Map。
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
public abstract class AbstractServerRequest implements ServerRequest {

    /** 请求属性，用于 Filter 间传递数据 */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /** 缓存的请求体，避免重复读取输入流 */
    private byte[] cachedBody;

    /** 是否已读取请求体 */
    private boolean bodyRead;

    @Override
    public Map<String, String> getParams() {
        return Collections.emptyMap();
    }

    @Override
    public String getParam(String name) {
        return getParams().get(name);
    }

    @Override
    public String getContentType() {
        return getHeader("Content-Type");
    }

    @Override
    public long getContentLength() {
        String len = getHeader("Content-Length");
        return len != null ? Long.parseLong(len) : -1;
    }

    @Override
    public byte[] getBody() {
        if (!bodyRead) {
            cachedBody = readBody();
            bodyRead = true;
        }
        return cachedBody != null ? cachedBody : new byte[0];
    }

    @Override
    public String getBodyString() {
        return new String(getBody(), StandardCharsets.UTF_8);
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(getBody());
    }

    @Override
    public Map<String, Object> getAttributes() {
        return new HashMap<>(attributes);
    }

    @Override
    public Object getAttribute(String name) {
        return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    /**
     * 子类实现：从底层协议读取请求体字节数组。
     *
     * @return 请求体字节数组，无请求体返回 null
     */
    protected byte[] readBody() {
        return new byte[0];
    }

    /**
     * 子类实现：获取请求头集合。
     *
     * @return 请求头集合
     */
    @Override
    public abstract HttpHeader getHeaders();

    /**
     * 子类实现：获取指定请求头值。
     *
     * @param name 请求头名称
     * @return 请求头值，不存在返回 null
     */
    @Override
    public abstract String getHeader(String name);

    /**
     * 子类实现：获取完整 URI。
     *
     * @return 完整 URI
     */
    @Override
    public abstract String getUri();

    /**
     * 子类实现：获取请求路径。
     *
     * @return 请求路径
     */
    @Override
    public abstract String getPath();

    /**
     * 子类实现：获取 HTTP 请求方法。
     *
     * @return HTTP 方法
     */
    @Override
    public abstract HttpMethod getMethod();

    /**
     * 子类实现：获取客户端地址。
     *
     * @return 客户端地址
     */
    @Override
    public abstract String getRemoteAddress();

    /**
     * 子类实现：获取客户端端口。
     *
     * @return 客户端端口
     */
    @Override
    public abstract int getRemotePort();
}
