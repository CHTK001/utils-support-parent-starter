package com.chua.common.support.network.rpc;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 同 JVM 直调（inline）进程级服务注册表。
 *
 * <p>为 RPC 客户端开启 {@link RpcConsumerConfig#getInline()} 时提供跨协议
 * （native/zmq 等）的本地服务查找。与简单静态 {@code Map<String, Object>}
 * 的区别：</p>
 * <ul>
 *   <li><b>多实例支持</b>：同一服务名可注册多个实例（如多应用/多服务端），
 *       不互相覆盖，调用时按注册顺序取可用实例</li>
 *   <li><b>精确注销</b>：{@link #unregister(String, Object)} 按对象引用移除
 *       指定实例，不影响同名下其他实例；服务端 {@code close()} 只清理自己注册的</li>
 *   <li><b>进程内单例</b>：进程内共享一份，多服务端/多客户端共用同一视图</li>
 * </ul>
 *
 * <p>线程安全：注册表基于 {@link ConcurrentHashMap} 与 {@link CopyOnWriteArrayList}，
 * 支持并发注册/注销/查询。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class LocalServiceRegistry {

    /**
     * 进程内共享注册表实例（inline 直调为进程内语义，多实例共享同一视图）
     */
    public static final LocalServiceRegistry INSTANCE = new LocalServiceRegistry();

    /**
     * 服务名 → 已注册服务实例列表（保持注册顺序，支持同名多实例）
     */
    private final Map<String, CopyOnWriteArrayList<Object>> services = new ConcurrentHashMap<>();

    /**
     * 构造方法，创建 Local服务Registry 实例。
     */
    private LocalServiceRegistry() {
    }

    /**
     * 注册一个服务实例到指定服务名。
     *
     * <p>同一服务名可注册多个实例，实例间按注册顺序排列；重复注册同一
     * 对象引用（{@code ==}）时忽略，避免重复条目。</p>
     *
     * @param name    服务接口全限定名
     * @param service 服务实现对象
     */
    public void register(String name, Object service) {
        if (name == null || service == null) {
            return;
        }
        services.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>())
                .addIfAbsent(service);
        log.debug("LocalServiceRegistry register: {} -> {}", name, service.getClass().getName());
    }

    /**
     * 注销指定服务实例。
     *
     * <p>按对象引用（{@code ==}）精确移除，不影响同名下其他实例；
     * 列表清空后删除该服务名条目。</p>
     *
     * @param name    服务接口全限定名
     * @param service 待注销的服务实现对象
     */
    public void unregister(String name, Object service) {
        if (name == null || service == null) {
            return;
        }
        List<Object> list = services.get(name);
        if (list != null) {
            list.removeIf(item -> item == service);
            if (list.isEmpty()) {
                services.remove(name, list);
            }
        }
        log.debug("LocalServiceRegistry unregister: {}", name);
    }

    /**
     * 注销指定服务名下的全部实例。
     *
     * <p>适用于服务端整体关闭的场景：一次移除该服务名下所有实例。</p>
     *
     * @param name 服务接口全限定名
     */
    public void unregisterAll(String name) {
        if (name == null) {
            return;
        }
        services.remove(name);
        log.debug("LocalServiceRegistry unregisterAll: {}", name);
    }

    /**
     * 按服务名查询可用实例（取第一个注册的）。
     *
     * @param name 服务接口全限定名
     * @return 服务实例；未注册或已全部注销时返回 {@code null}
     */
    public Object get(String name) {
        if (name == null) {
            return null;
        }
        List<Object> list = services.get(name);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }

    /**
     * 判断指定服务名是否至少注册了一个实例。
     *
     * @param name 服务接口全限定名
     * @return 存在可用实例返回 {@code true}
     */
    public boolean contains(String name) {
        if (name == null) {
            return false;
        }
        List<Object> list = services.get(name);
        return list != null && !list.isEmpty();
    }

    /**
     * 当前注册的服务名集合（只读快照）。
     *
     * @return 服务名列表，按名称排序
     */
    public List<String> names() {
        return new ArrayList<>(services.keySet());
    }

    /**
     * 清空注册表（主要用于测试隔离）。
     */
    public void clear() {
        services.clear();
    }
}
