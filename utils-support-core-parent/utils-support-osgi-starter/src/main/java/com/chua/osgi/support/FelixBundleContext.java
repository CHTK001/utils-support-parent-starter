package com.chua.osgi.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
* Felix bundle上下文 实现，包装 Felix 框架的 bundle上下文。
*
* @author CH
* @since 4.0.0.42
 */
public class FelixBundleContext implements com.chua.common.support.osgi.BundleContext {

    /**
    * 被包装的 OSGI bundle上下文
     */
    private final org.osgi.framework.BundleContext delegate;

    /**
    * 注册跟踪表：服务引用 → 服务registration。
    * <p>{@code unregisterService} 需经 {@link org.osgi.framework.ServiceRegistration#unregister()}
    * 真正注销服务（仅 unget服务 只释放引用计数，服务仍留在注册表）。</p>
     */
    private final Map<org.osgi.framework.ServiceReference<?>, org.osgi.framework.ServiceRegistration<?>> registrations =
            new ConcurrentHashMap<>();

    /**
    * 构造函数。
    *
    * @param delegate OSGI bundle上下文
     */
    public FelixBundleContext(org.osgi.framework.BundleContext delegate) {
        this.delegate = delegate;
    }

    @Override
    /** 注册服务 */
    public <T> void registerService(Class<T> type, T service) {
        org.osgi.framework.ServiceRegistration<?> reg =
                delegate.registerService(type.getName(), service, null);
        registrations.put(reg.getReference(), reg);
    }

    @Override
    /** 注销服务 */
    public <T> void unregisterService(Class<T> type, T service) {
        for (Map.Entry<org.osgi.framework.ServiceReference<?>, org.osgi.framework.ServiceRegistration<?>> e
                : registrations.entrySet()) {
            Object svc = delegate.getService(e.getKey());
            if (svc == service) {
                e.getValue().unregister();
                registrations.remove(e.getKey());
                return;
            }
        }
        try {
            org.osgi.framework.ServiceReference<?>[] refs =
                    delegate.getServiceReferences(type.getName(), null);
            if (refs != null) {
                for (org.osgi.framework.ServiceReference<?> ref : refs) {
                    Object svc = delegate.getService(ref);
                    if (svc == service) {
                        delegate.ungetService(ref);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
    * 获取服务
    *
    * @param type 类型
    * @return 获取服务的结果
     */
    public <T> List<T> getServices(Class<T> type) {
        try {
            org.osgi.framework.ServiceReference<?>[] refs =
                    delegate.getServiceReferences(type.getName(), null);
            if (refs == null) {
                return Collections.emptyList();
            }
            List<T> result = new ArrayList<>();
            for (org.osgi.framework.ServiceReference<?> ref : refs) {
                Object service = delegate.getService(ref);
                if (service != null) {
                    result.add((T) service);
                    delegate.ungetService(ref);
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @Override
    /** 获取服务 */
    public <T> T getService(Class<T> type) {
        List<T> services = getServices(type);
        return services.isEmpty() ? null : services.get(0);
    }
}
