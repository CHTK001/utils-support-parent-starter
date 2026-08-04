package com.chua.common.support.lang.directory;

import com.chua.common.support.lang.directory.environment.DirectoryPollerEnvironment;
import com.chua.common.support.lang.directory.executor.DirectoryPollerExecutor;
import com.chua.common.support.lang.directory.executor.VirtualThreadPollerExecutor;
import org.jspecify.annotations.NullUnmarked;

/**
 * 可轮询目录接口，定义目录或数据源变更监听的生命周期方法。
 * <p>
 * 适用于无法使用 JDK WatchService 的场景（如 FTP、SFTP、数据库 CDC），
 * 通过定时调用 {@link #upgrade()} 对比快照发现变更。
 * </p>
 *
 * @author CH
 * @since 2024/12/12
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public interface PolledDirectory extends AutoCloseable {

    /**
     * 判断当前实现是否委托操作系统监听（如 WatchService）。
     * <p>返回 true 时由事件驱动，返回 false 时使用定时轮询。</p>
     *
     * @return true 表示由操作系统事件驱动，false 表示需要定时轮询
     */
    default boolean isDelegatedOperatingSystem() {
        return false;
    }

    /**
     * 启动目录监听（自动创建执行器）。
     * <p>简化入口，无需手动构造执行器。
     * 事件驱动型（{@link #isDelegatedOperatingSystem()} 返回 true）忽略 executor，
     * 轮询型自动创建 {@link VirtualThreadPollerExecutor}。</p>
     *
     * @param environment 环境配置
     */
    default void start(DirectoryPollerEnvironment environment) {
        if (isDelegatedOperatingSystem()) {
            start(environment, null);
        } else {
            start(environment, new VirtualThreadPollerExecutor(this, environment));
        }
    }

    /**
     * 启动目录监听（自定义执行器）。
     *
     * @param environment 环境配置
     * @param executor    执行器，传入 null 时由实现类自行创建
     */
    void start(DirectoryPollerEnvironment environment, DirectoryPollerExecutor executor);

    /**
     * 执行一次轮询对比，由定时调度器周期性调用。
     * <p>实现类应在此方法中获取最新快照，与缓存对比后通过 {@link PolledListener} 分发事件。</p>
     */
    void upgrade();

    /**
     * 注册事件监听器。
     *
     * @param listener 监听器
     */
    void addListener(PolledListener listener);
}
