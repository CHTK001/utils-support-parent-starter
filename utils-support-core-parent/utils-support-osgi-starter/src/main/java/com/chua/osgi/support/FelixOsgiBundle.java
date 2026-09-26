package com.chua.osgi.support;

import com.chua.common.support.osgi.OsgiBundle;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Felix Bundle 实现，包装 OSGI 的 Bundle 对象。
 * <p>
 * 包装对象本身不缓存状态，所有查询均实时委派给底层 {@link Bundle}，
 * 因此可长期复用而不会读到过期数据。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FelixOsgiBundle implements OsgiBundle {

    /**
     * 底层原生 bundle。
     */
    private final Bundle bundle;

    /**
     * 本 bundle 持有的服务注册句柄。
     */
    private final List<ServiceRegistration<?>> registrations = new CopyOnWriteArrayList<>();

    /**
     * 本 bundle 当前持有的服务引用（供 {@link #stop()} / {@link #uninstall()} 前释放）。
     */
    private final List<ServiceReference<?>> heldReferences = new CopyOnWriteArrayList<>();

    /**
     * 归属的 launcher。
     * <p>
     * 用于让 {@link #stop()} / {@link #uninstall()} 在返回前等待框架事件处理完毕：
     * Felix 在分发线程上<b>异步</b>投递 bundle 事件，若不等待，调用方在操作返回后
     * 立即查询容器（如 ObjectContext）会读到尚未失效的滞后状态。
     * 经 {@code FelixBundleContext} 等途径独立构造的包装对象可能没有归属 launcher，
     * 此时退化为不等待。
     * </p>
     */
    private volatile FelixOsgiLauncher owner;

    /**
     * 创建 felix bundle 包装实例。
     *
     * @param bundle 原生 bundle
     */
    public FelixOsgiBundle(Bundle bundle) {
        this.bundle = bundle;
    }

    /**
     * 绑定归属的 launcher，用于生命周期操作的同步屏障。
     *
     * @param owner 归属 launcher
     */
    void attachLauncher(FelixOsgiLauncher owner) {
        this.owner = owner;
    }

    /**
     * 获取底层原生 bundle。
     *
     * @return 原生 bundle
     */
    public Bundle getDelegate() {
        return bundle;
    }

    @Override
    public long getBundleId() {
        return bundle.getBundleId();
    }

    @Override
    public String getSymbolicName() {
        String symbolicName = bundle.getSymbolicName();
        return symbolicName == null ? "" : symbolicName;
    }

    @Override
    public String getVersion() {
        return bundle.getVersion() == null ? "" : bundle.getVersion().toString();
    }

    @Override
    public String getLocation() {
        String location = bundle.getLocation();
        return location == null ? "" : location;
    }

    @Override
    public Map<String, String> getHeaders() {
        Dictionary<String, String> headers = bundle.getHeaders();
        if (headers == null) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        Enumeration<String> keys = headers.keys();
        while (keys.hasMoreElements()) {
            String key = keys.nextElement();
            result.put(key, headers.get(key));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public String getState() {
        return FelixOsgiLauncher.stateName(bundle.getState());
    }

    @Override
    public void start() {
        try {
            bundle.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start bundle: " + getSymbolicName(), e);
        }
    }

    @Override
    public void stop() {
        try {
            boolean wasActive = bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STARTING;
            FelixOsgiLauncher launcher = this.owner;
            int baseline = launcher == null ? 0 : launcher.processedEventCount(bundle.getBundleId(), BundleEvent.STOPPED);
            releaseHeldReferences();
            bundle.stop();
            // 规范约定：bundle 停止时其注册的服务由框架自动注销，此处仅清理本地句柄缓存，
            // 再次手工 unregister 会因注册项已失效而抛 IllegalStateException
            registrations.clear();
            if (wasActive && launcher != null) {
                // 等待 STOPPED 回调完毕，避免调用方读到「已停止但仍可查到服务」的滞后状态
                launcher.awaitBundleEvent("stop", bundle.getBundleId(), BundleEvent.STOPPED, baseline);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop bundle: " + getSymbolicName(), e);
        }
    }

    @Override
    public void uninstall() {
        try {
            if (bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STARTING) {
                stop();
            } else {
                releaseHeldReferences();
            }
            FelixOsgiLauncher launcher = this.owner;
            int baseline = launcher == null ? 0 : launcher.processedEventCount(bundle.getBundleId(), BundleEvent.UNINSTALLED);
            bundle.uninstall();
            registrations.clear();
            if (launcher != null) {
                // 等待 UNINSTALLED 回调完毕，避免调用方读到「已卸载但仍可查到服务」的滞后状态
                launcher.awaitBundleEvent("uninstall", bundle.getBundleId(), BundleEvent.UNINSTALLED, baseline);
                launcher.forgetBundleEventCounts(bundle.getBundleId());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to uninstall bundle: " + getSymbolicName(), e);
        }
    }

    @Override
    public void update(String url) {
        boolean wasActive = bundle.getState() == Bundle.ACTIVE;
        try {
            if (url == null || url.isBlank()) {
                bundle.update();
            } else {
                // Felix 7 的 org.osgi.framework.Bundle 未提供 update(URL) 重载，
                // 只能自行打开流后交由框架在本次调用内消费完毕
                // （new URL(String) 自 Java 20 起废弃，改经 URI 转换）
                try (java.io.InputStream input = java.net.URI.create(url).toURL().openStream()) {
                    bundle.update(input);
                }
            }
            if (wasActive && bundle.getState() != Bundle.ACTIVE) {
                bundle.start();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to update bundle: " + getSymbolicName(), e);
        }
    }

    @Override
    public <T> void registerService(Class<T> type, T service) {
        registerService(type, service, null);
    }

    @Override
    public <T> void registerService(Class<T> type, T service, Map<String, Object> properties) {
        ServiceRegistration<?> registration = bundle.getBundleContext()
                .registerService(type.getName(), service, toDictionary(properties));
        registrations.add(registration);
    }

    /**
     * 注销服务。
     * <p>
     * 仅当注册项对应的服务实例与入参为同一对象时才执行注销；
     * 仅类型相同但实例不同的情况会跳过，避免误注销。
     * </p>
     *
     * @param type    服务接口类型
     * @param service 服务实例
     * @param <T>     服务类型
     * @return 找到并成功注销返回 true
     */
    @Override
    public <T> boolean unregisterService(Class<T> type, T service) {
        if (type == null || service == null) {
            return false;
        }
        for (ServiceRegistration<?> registration : registrations) {
            try {
                if (!matchesType(registration, type.getName())) {
                    continue;
                }
                if (getServiceInstance(registration) != service) {
                    continue;
                }
                registration.unregister();
                registrations.remove(registration);
                return true;
            } catch (Exception e) {
                // 注册项可能已被框架自动注销，视为未命中
                registrations.remove(registration);
            }
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getServices(Class<T> type) {
        org.osgi.framework.BundleContext context = currentContext();
        if (context == null) {
            return Collections.emptyList();
        }
        List<T> result = new ArrayList<>();
        List<ServiceReference<?>> acquired = new ArrayList<>();
        try {
            ServiceReference<?>[] refs = context.getServiceReferences(type.getName(), null);
            if (refs == null) {
                return result;
            }
            for (ServiceReference<?> ref : FelixOsgiLauncher.orderByRanking(refs)) {
                Object service = context.getService(ref);
                if (service == null) {
                    continue;
                }
                if (!type.isInstance(service)) {
                    context.ungetService(ref);
                    continue;
                }
                result.add((T) service);
                acquired.add(ref);
            }
        } catch (Exception e) {
            return result;
        }
        heldReferences.addAll(acquired);
        return result;
    }

    @Override
    public List<String> getRegisteredServiceTypes() {
        Set<String> types = new LinkedHashSet<>();
        for (ServiceRegistration<?> registration : registrations) {
            try {
                Object objectClass = registration.getReference().getProperty(Constants.OBJECTCLASS);
                if (objectClass instanceof String[] names) {
                    Collections.addAll(types, names);
                } else if (objectClass instanceof String name) {
                    types.add(name);
                }
            } catch (Exception e) {
                // 注册项已失效，跳过
            }
        }
        return List.copyOf(types);
    }

    /**
     * 判断注册项是否声明了指定服务类型。
     *
     * @param registration 注册项
     * @param typeName     服务类型全限定名
     * @return 匹配返回 true
     */
    private boolean matchesType(ServiceRegistration<?> registration, String typeName) {
        try {
            Object objectClass = registration.getReference().getProperty(Constants.OBJECTCLASS);
            if (objectClass instanceof String[] names) {
                for (String name : names) {
                    if (name.equals(typeName)) {
                        return true;
                    }
                }
            } else {
                return typeName.equals(objectClass);
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 获取注册项对应的服务实例。
     *
     * @param registration 注册项
     * @return 服务实例，注册项已失效时返回 {@code null}
     */
    private Object getServiceInstance(ServiceRegistration<?> registration) {
        org.osgi.framework.BundleContext context = currentContext();
        if (context == null) {
            return null;
        }
        try {
            ServiceReference<?> reference = registration.getReference();
            if (reference == null) {
                return null;
            }
            return context.getService(reference);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 释放本 bundle 持有的全部服务引用。
     */
    private void releaseHeldReferences() {
        org.osgi.framework.BundleContext context = currentContext();
        if (context != null) {
            for (ServiceReference<?> reference : heldReferences) {
                try {
                    context.ungetService(reference);
                } catch (Exception e) {
                    // 忽略：引用可能已随框架关闭失效
                }
            }
        }
        heldReferences.clear();
    }

    /**
     * 获取可用的 bundle 上下文。
     *
     * @return bundle 上下文，bundle 已卸载时返回 {@code null}
     */
    private org.osgi.framework.BundleContext currentContext() {
        try {
            if (bundle.getState() == Bundle.UNINSTALLED) {
                return null;
            }
            return bundle.getBundleContext();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将属性映射转换为框架所需的服务属性字典。
     *
     * @param properties 属性映射，可为 {@code null}
     * @return 服务属性字典，入参为空时返回 {@code null}
     */
    static Dictionary<String, Object> toDictionary(Map<String, Object> properties) {
        if (properties == null || properties.isEmpty()) {
            return null;
        }
        java.util.Hashtable<String, Object> dictionary = new java.util.Hashtable<>();
        dictionary.putAll(properties);
        return dictionary;
    }
}
