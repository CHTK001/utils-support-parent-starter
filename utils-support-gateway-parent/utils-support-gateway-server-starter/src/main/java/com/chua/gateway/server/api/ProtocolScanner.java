package com.chua.gateway.server.api;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.gateway.server.spi.ProtocolServerFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 协议扫描器：从 SPI 加载 {@link ProtocolServerFactory} 实现。
 *
 * <p>前端 {@code GET /api/connections/list} 调用此扫描器，列出可用协议。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class ProtocolScanner {

    /**
     * SPI 提供者缓存
     */
    private volatile ServiceProvider<ProtocolServerFactory> provider;

    /**
     * 列出可用协议名。
     *
     * @return 协议名列表
     */
    public List<String> listProtocols() {
        Set<String> names = getProvider().getExtensions();
        List<String> result = new ArrayList<>(names);
        result.sort(String::compareTo);
        return result;
    }

    /**
     * 延迟初始化 SPI 提供者。
     *
     * @return ServiceProvider 单例
     */
    private ServiceProvider<ProtocolServerFactory> getProvider() {
        ServiceProvider<ProtocolServerFactory> p = provider;
        if (p == null) {
            synchronized (this) {
                p = provider;
                if (p == null) {
                    p = ServiceProvider.of(ProtocolServerFactory.class);
                    provider = p;
                    log.info("协议扫描器加载: protocols={}", p.getExtensions());
                }
            }
        }
        return p;
    }
}
