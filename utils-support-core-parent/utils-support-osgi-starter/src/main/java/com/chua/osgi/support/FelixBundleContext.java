package com.chua.osgi.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Felix BundleContext 实现，包装 Felix 框架的 BundleContext。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FelixBundleContext implements com.chua.common.support.osgi.BundleContext {

    /**
     * 被包装的 OSGI BundleContext
     */
    private final org.osgi.framework.BundleContext delegate;

    /**
     * 构造函数。
     *
     * @param delegate OSGI BundleContext
     */
    public FelixBundleContext(org.osgi.framework.BundleContext delegate) {
        this.delegate = delegate;
    }

    @Override
    /** 注册Service */
    public <T> void registerService(Class<T> type, T service) {
        delegate.registerService(type.getName(), service, null);
    }

    @Override
    /** 注销Service */
    public <T> void unregisterService(Class<T> type, T service) {
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
    /** 获取Services */
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
    /** 获取Service */
    public <T> T getService(Class<T> type) {
        List<T> services = getServices(type);
        return services.isEmpty() ? null : services.get(0);
    }
}
