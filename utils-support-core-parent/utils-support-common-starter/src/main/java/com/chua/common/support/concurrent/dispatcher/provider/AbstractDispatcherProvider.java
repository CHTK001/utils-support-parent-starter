package com.chua.common.support.concurrent.dispatcher.provider;

import com.chua.common.support.concurrent.dispatcher.DispatcherConfig;
import com.chua.common.support.concurrent.dispatcher.DispatcherProvider;
import lombok.Getter;
import org.jspecify.annotations.NullUnmarked;

/**
 * 抽象分发器提供者工具基类，封装通用配置持有与生命周期管理能力。
 * <p>
 * 具体实现类需负责消息的发布、订阅、取消订阅以及资源释放等核心行为，
 * 通过继承该类可以统一获取 {@link DispatcherConfig} 并复用公共状态。
 * </p>
 *
 * @author CH
 * @since 2025-11-26
 */
@NullUnmarked
@Getter
public abstract class AbstractDispatcherProvider implements DispatcherProvider {

    /**
     * 当前分发器提供者使用的配置对象。
     */
    protected final DispatcherConfig config;

    /**
     * 创建抽象分发器提供者实例。
     *
     * @param config 分发器配置对象
     */
    protected AbstractDispatcherProvider(DispatcherConfig config) {
        this.config = config;
    }
}
