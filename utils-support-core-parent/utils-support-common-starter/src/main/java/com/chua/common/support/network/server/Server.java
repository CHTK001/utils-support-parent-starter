package com.chua.common.support.network.server;

import com.chua.common.support.network.ProtocolType;
import com.chua.common.support.network.server.filter.ServerFilter;
import com.chua.common.support.objects.ObjectContext;

import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 服务器顶层接口，协议无关。
 * <p>定义所有协议服务器共享的生命周期管理、过滤器链管理以及 IOC 集成能力。</p>
 *
 * @author CH
 * @version 2.0
 * @since 2026/07/16
 */
@NullUnmarked
public interface Server extends AutoCloseable {

    /**
     * 创建 ServerBuilder 实例。
     *
     * @return ServerBuilder
     */
    static ServerBuilder builder() {
        return ServerBuilder.create();
    }

    /**
     * 启动服务器。
     */
    void start();

    /**
     * 停止服务器。
     */
    void stop();

    /**
     * 判断服务器是否正在运行。
     *
     * @return true 表示服务器正在运行，否则为 false
     */
    boolean isRunning();

    /**
     * 获取当前服务器的协议类型。
     *
     * @return 协议类型枚举
     */
    ProtocolType getProtocolType();

    /**
     * 判断当前服务器是否支持 Reactor 请求处理模式。
     * <p>JDK HttpServer 等阻塞式传输实现返回 false。
     * 支持该能力的传输实现仍需同时开启 {@link ServerSetting#isReactor()} 配置才会进入 Reactor 处理路径。</p>
     *
     * @return true 表示支持 Reactor 请求处理模式
     */
    default boolean supportsReactor() {
        return false;
    }

    /**
     * 获取当前服务器的协议名称（小写）。
     *
     * @return 协议名称字符串
     */
    default String getProtocol() {
        return getProtocolType().name().toLowerCase();
    }

    /**
     * 获取服务器监听的端口号。
     *
     * @return 端口号
     */
    int getPort();

    /**
     * 获取服务器主机地址。
     *
     * @return 主机地址
     */
    default String getHost() {
        ServerSetting setting = getSetting();
        if (setting != null && setting.getHost() != null && !"0.0.0.0".equals(setting.getHost())) {
            return setting.getHost();
        }
        return "localhost";
    }

    /**
     * 获取服务器访问 URL。
     *
     * @return 服务器 URL
     */
    default String getServerUrl() {
        return getProtocol() + "://" + getHost() + ":" + getPort() + "/";
    }

    /**
     * 启动服务器（如果未运行）。
     */
    default void startIfNeeded() {
        if (!isRunning()) {
            start();
        }
    }

    /**
     * 获取服务器配置设置。
     *
     * @return 服务器设置对象
     */
    ServerSetting getSetting();

    /**
     * 获取 IOC 对象上下文。
     *
     * @return ObjectContext 实例，可能为 null
     */
    ObjectContext getObjectContext();

    /**
     * 设置 IOC 对象上下文。
     * <p>设置后会自动从上下文中发现 ServerFilter 等组件。</p>
     *
     * @param objectContext IOC 上下文对象
     */
    void setObjectContext(ObjectContext objectContext);

    /**
     * 获取当前的过滤器链列表。
     *
     * @return 过滤器列表
     */
    List<ServerFilter> getFilters();

    /**
     * 添加一个过滤器到过滤器链中。
     *
     * @param filter 要添加的过滤器
     * @return 当前服务器实例，支持链式调用
     */
    Server addFilter(ServerFilter filter);

    /**
     * 从过滤器链中移除指定的过滤器。
     *
     * @param filter 要移除的过滤器
     * @return 当前服务器实例，支持链式调用
     */
    Server removeFilter(ServerFilter filter);

    /**
     * 刷新过滤器链，重新加载或初始化过滤器。
     *
     * @return 当前服务器实例，支持链式调用
     */
    Server refreshFilters();

    /**
     * 注册一个对象到服务器的 IOC 容器。
     * <p>通过 {@link ObjectContext#registerBean(Object)} 将对象注册为 Bean，
     * 同时若对象包含 {@code @RequestMethod} 注解，自动创建路由映射。</p>
     *
     * @param bean 要注册的对象
     * @return 当前服务器实例，支持链式调用
     */
    Server registerBean(Object bean);

    /**
     * 从服务器的 IOC 容器中注销一个对象。
     *
     * @param bean 要注销的对象
     * @return 当前服务器实例，支持链式调用
     */
    Server unregisterBean(Object bean);

    /**
     * 关闭服务器并释放资源。
     */
    @Override
    void close();

}
