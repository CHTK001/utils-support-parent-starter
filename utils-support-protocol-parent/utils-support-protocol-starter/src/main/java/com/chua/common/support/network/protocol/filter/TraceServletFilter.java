package com.chua.common.support.network.protocol.filter;

import com.chua.common.support.core.annotation.Spi;
import com.chua.common.support.core.annotation.SpiDescribe;
import com.chua.common.support.network.protocol.event.ServletEvent;
import com.chua.common.support.network.protocol.event.ServletListener;
import com.chua.common.support.network.protocol.request.ServletRequest;
import com.chua.common.support.network.protocol.request.ServletResponse;
import com.chua.common.support.network.protocol.server.ServletFilterChain;
import com.chua.common.support.network.protocol.server.ServletListenerRegistry;
import com.chua.common.support.network.protocol.server.UpgradeServletFilter;
import com.chua.common.support.network.protocol.event.ServletEventDispatcher;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 实时追踪过滤器
 *
 * 同时作为全局 ServletListener 监听所有过滤器前/后事件，实现全链路追踪
 */
@Slf4j
@Data
@EqualsAndHashCode(callSuper = true)
@Spi("trace")
@SpiDescribe("实时追踪过滤器")
public class TraceServletFilter extends UpgradeServletFilter<TraceServletFilter.TraceConfig>
        implements ServletListener {

    /**
     * 追踪配置
     */
    public record TraceConfig(
        /** 是否启用 */
            boolean enabled,
        /** 是否发布原始请求/响应对象 */
            boolean publishRaw,
        /** 是否同步发布（默认异步） */
            boolean sync,
        /** 是否仅在异常/拒绝时发布 */
            boolean onlyAbnormal,
        /** 采样比例(0-1)，1表示全量 */
            double sampleRate
    ) {
        /**
         * 默认配置
         */
        public TraceConfig() {
            this(true, false, false, false, 1.0);
        }
    }

    @Override
    public void init(com.chua.common.support.network.protocol.server.ServletFilterConfig config) throws Exception {
        super.init(config);
        // 注册为全局监听器，监听所有过滤器事件
        ServletListenerRegistry.registerGlobal(this);
    }

    @Override
    public void destroy() {
        ServletListenerRegistry.unregisterGlobal(this);
        super.destroy();
    }

    @Override
    protected void doFilterWithConfigObject(ServletRequest request, ServletResponse response,
            ServletFilterChain chain, TraceConfig config) throws Exception {
        // 自身不过度处理，仅透传，主追踪逻辑来自监听回调；这里保留原始事件发布能力（可选）
        chain.doFilter(request, response);
    }

    @Override
    public void onEvent(ServletEvent event) {
        // 作为全局监听器接收所有过滤器前/后事件
        if (event == null) {
            return;
        }
        // 可在此进行实时输出或转发（复用事件分发器，便于外部消费者接入）
        try {
            ServletEventDispatcher.publishAsync(event);
        } catch (Throwable ex) {
            if (log.isDebugEnabled()) {
                log.debug("[协议][追踪] 发布事件失败: {}", ex.getMessage());
            }
        }
    }

    @Override
    public int getOrder() {
        // 放在靠前位置，保证尽早采集
        return 1;
    }

    @Override
    public boolean supportProtocol(String protocol) {
        return true; // 所有协议均可追踪
    }
}
