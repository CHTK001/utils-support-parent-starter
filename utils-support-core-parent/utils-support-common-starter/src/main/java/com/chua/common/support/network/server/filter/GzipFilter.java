package com.chua.common.support.network.server.filter;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.ServerSetting;
import com.chua.common.support.network.server.request.ServerRequest;
import com.chua.common.support.network.server.response.ServerResponse;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.NullUnmarked;

/**
 * Gzip 响应压缩过滤器。
 *
 * <p>当客户端请求头包含 {@code Accept-Encoding: gzip} 且响应体大小超过配置阈值时，
 * 对响应体进行 Gzip 压缩。压缩等级和阈值通过 {@link ServerSetting} 控制。</p>
 *
 * <p>仅在 {@link ServerSetting#isGzipEnabled()} 为 true 时生效。</p>
 *
 * @author CH
 * @since 2024/12/20
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class GzipFilter implements ServerFilter {

    /**
     * 服务器配置引用，用于读取 gzip 开关、压缩等级和最小压缩大小。
     */
    private volatile ServerSetting setting;

    @Override
    public int getOrder() {
        return Integer.MIN_VALUE;
    }

    @Override
    public ProtocolType[] supportProtocols() {
        return new ProtocolType[]{ProtocolType.HTTP};
    }

    @Override
    public void init(ServerFilterConfig config) throws Exception {
        if (config != null) {
            this.setting = config.getServerSetting();
        } else {
            this.setting = ServerSetting.defaults();
        }
    }

    @Override
    public void updateConfig(java.util.Map<String, Object> config) {
        if (setting == null || config == null) {
            return;
        }
        if (config.containsKey("gzipEnabled")) {
            Boolean value = Boolean.parseBoolean(String.valueOf(config.get("gzipEnabled")));
            setting.setGzipEnabled(value);
        }
        if (config.containsKey("gzipLevel")) {
            Integer value = Integer.parseInt(String.valueOf(config.get("gzipLevel")));
            setting.setGzipLevel(value);
        }
        if (config.containsKey("gzipMinSize")) {
            Integer value = Integer.parseInt(String.valueOf(config.get("gzipMinSize")));
            setting.setGzipMinSize(value);
        }
    }

    @Override
    public void doFilter(ServerRequest request, ServerResponse response, ServerFilterChain chain) throws Exception {
        chain.doFilter(request, response);

        if (setting == null || !setting.isGzipEnabled()) {
            return;
        }
        if (response.getBody() == null || response.getHeader("Content-Encoding") != null) {
            return;
        }
        byte[] body = response.getBody();
        if (body.length < setting.getGzipMinSize()) {
            return;
        }
        String acceptEncoding = request.getHeader("Accept-Encoding");
        if (acceptEncoding == null || !acceptEncoding.toLowerCase().contains("gzip")) {
            return;
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream(body.length / 2);
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos) {{
            def.setLevel(setting.getGzipLevel());
        }}) {
            gzip.write(body);
        }
        response.setBody(baos.toByteArray());
        response.setHeader("Content-Encoding", "gzip");
        response.setHeader("Vary", "Accept-Encoding");
    }
}
