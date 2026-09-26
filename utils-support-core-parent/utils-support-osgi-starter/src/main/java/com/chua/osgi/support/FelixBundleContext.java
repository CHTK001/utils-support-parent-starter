package com.chua.osgi.support;

import com.chua.common.support.osgi.OsgiBundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Felix bundle 上下文 实现，包装 Felix 框架的 bundle 上下文。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FelixBundleContext implements com.chua.common.support.osgi.BundleContext {

    /**
     * 被包装的 OSGI bundle 上下文。
     */
    private final org.osgi.framework.BundleContext delegate;

    /**
     * 归属的 launcher，可为 {@code null}。
     */
    private final FelixOsgiLauncher owner;

    /**
     * 注册跟踪表：服务引用 → 服务注册句柄。
     * <p>
     * {@code unregisterService} 必须经 {@link org.osgi.framework.ServiceRegistration#unregister()}
     * 真正注销服务：仅调用 unget 只释放引用计数，服务仍留在注册表中，
     * 调用方后续仍能通过 {@code getServices} 取到它。
     * </p>
     */
    private final Map<org.osgi.framework.ServiceReference<?>, org.osgi.framework.ServiceRegistration<?>> registrations =
            new ConcurrentHashMap<>();

    /**
     * 本上下文持有的服务引用（获取服务时记账，注销或刷新时释放）。
     */
    private final List<org.osgi.framework.ServiceReference<?>> heldReferences = new CopyOnWriteArrayList<>();

    /**
     * 构造函数。
     *
     * @param delegate OSGI bundle 上下文
     */
    public FelixBundleContext(org.osgi.framework.BundleContext delegate) {
        this(delegate, null);
    }

    /**
     * 构造函数。
     * <p>
     * 传入归属 launcher 后，本上下文派生的 {@link FelixOsgiBundle} 会绑定该 launcher，
     * 使其 {@code stop()} / {@code uninstall()} 具备事件同步屏障
     * （Felix 在分发线程上异步投递 bundle 事件，不等待会读到滞后状态）。
     * </p>
     *
     * @param delegate OSGI bundle 上下文
     * @param owner    归属 launcher，可为 {@code null}
     */
    FelixBundleContext(org.osgi.framework.BundleContext delegate, FelixOsgiLauncher owner) {
        this.delegate = delegate;
        this.owner = owner;
    }

    @Override
    public <T> void registerService(Class<T> type, T service) {
        registerService(type, service, null);
    }

    @Override
    public <T> void registerService(Class<T> type, T service, Map<String, Object> properties) {
        org.osgi.framework.ServiceRegistration<?> reg =
                delegate.registerService(type.getName(), service, FelixOsgiBundle.toDictionary(properties));
        registrations.put(reg.getReference(), reg);
    }

    /**
     * 注销服务。
     * <p>
     * 仅遍历本上下文登记的注册句柄，且要求注册项对应的服务实例与入参为同一对象时才真正注销。
     * 对非本上下文注册的服务不做处理（无注册句柄则无法注销），
     * 返回 {@code false} 而非仅释放引用计数——后者会让服务"看似注销实则仍可被查到"。
     * </p>
     *
     * @param type    服务接口类型
     * @param service 服务实例
     * @param <T>     服务类型
     * @return 找到并成功注销返回 true
     */
    @Override
    public <T> boolean unregisterService(Class<T> type, T service) {
        if (service == null) {
            return false;
        }
        for (Map.Entry<org.osgi.framework.ServiceReference<?>, org.osgi.framework.ServiceRegistration<?>> entry
                : registrations.entrySet()) {
            org.osgi.framework.ServiceReference<?> reference = entry.getKey();
            if (safeGetService(reference) != service) {
                continue;
            }
            try {
                entry.getValue().unregister();
            } catch (Exception e) {
                // 注册项可能已由框架自动注销（bundle 停止/卸载），视为注销成功
            }
            registrations.remove(reference);
            heldReferences.remove(reference);
            return true;
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getServices(Class<T> type) {
        try {
            org.osgi.framework.ServiceReference<?>[] refs =
                    delegate.getServiceReferences(type.getName(), null);
            if (refs == null) {
                return Collections.emptyList();
            }
            List<T> result = new ArrayList<>();
            List<org.osgi.framework.ServiceReference<?>> acquired = new ArrayList<>();
            for (org.osgi.framework.ServiceReference<?> ref : FelixOsgiLauncher.orderByRanking(refs)) {
                Object service = delegate.getService(ref);
                if (service == null) {
                    continue;
                }
                if (!type.isInstance(service)) {
                    delegate.ungetService(ref);
                    continue;
                }
                result.add((T) service);
                acquired.add(ref);
            }
            heldReferences.addAll(acquired);
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    public <T> T getService(Class<T> type) {
        List<T> services = getServices(type);
        return services.isEmpty() ? null : services.getFirst();
    }

    @Override
    public List<String> getServiceTypes() {
        Set<String> types = new LinkedHashSet<>();
        try {
            org.osgi.framework.ServiceReference<?>[] refs = delegate.getAllServiceReferences(null, null);
            if (refs == null) {
                return Collections.emptyList();
            }
            for (org.osgi.framework.ServiceReference<?> ref : refs) {
                Object objectClass = ref.getProperty(org.osgi.framework.Constants.OBJECTCLASS);
                if (objectClass instanceof String[] names) {
                    Collections.addAll(types, names);
                } else if (objectClass instanceof String name) {
                    types.add(name);
                }
            }
        } catch (Exception e) {
            return List.copyOf(types);
        }
        return List.copyOf(types);
    }

    @Override
    public OsgiBundle getBundle(String symbolicName) {
        if (symbolicName == null) {
            return null;
        }
        try {
            for (org.osgi.framework.Bundle bundle : delegate.getBundles()) {
                if (symbolicName.equals(bundle.getSymbolicName())) {
                    FelixOsgiBundle wrapped = new FelixOsgiBundle(bundle);
                    wrapped.attachLauncher(owner);
                    return wrapped;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    @Override
    public void refresh() {
        releaseHeldReferences();
    }

    /**
     * 释放本上下文持有的全部服务引用。
     */
    public void releaseHeldReferences() {
        for (org.osgi.framework.ServiceReference<?> ref : heldReferences) {
            try {
                delegate.ungetService(ref);
            } catch (Exception e) {
                // 忽略：引用可能已随框架关闭失效
            }
        }
        heldReferences.clear();
    }

    /**
     * 获取被包装的原生上下文。
     *
     * @return 原生 bundle 上下文
     */
    public org.osgi.framework.BundleContext getDelegate() {
        return delegate;
    }

    /**
     * 供框架外组件按名查找服务的便捷入口。
     * <p>
     * 注：{@code getServiceReferences(名称, null)} 在部分框架实现下偶发不匹配，
     * 故统一走全量 API 后按 {@code objectClass} 手动匹配（实证可靠）。
     * </p>
     *
     * @param typeName 服务类型全限定名
     * @return 服务实例，未找到返回 {@code null}
     */
    public Object getServiceByName(String typeName) {
        if (typeName == null) {
            return null;
        }
        try {
            org.osgi.framework.ServiceReference<?>[] all = delegate.getAllServiceReferences(null, null);
            if (all == null) {
                return null;
            }
            for (org.osgi.framework.ServiceReference<?> ref : all) {
                Object objectClass = ref.getProperty(org.osgi.framework.Constants.OBJECTCLASS);
                if (objectClass instanceof String[] names && List.of(names).contains(typeName)) {
                    return delegate.getService(ref);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * 安全获取服务实例。
     *
     * @param reference 服务引用
     * @return 服务实例，异常时返回 {@code null}
     */
    private Object safeGetService(org.osgi.framework.ServiceReference<?> reference) {
        try {
            return delegate.getService(reference);
        } catch (Exception e) {
            return null;
        }
    }
}
