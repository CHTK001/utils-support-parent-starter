package com.chua.osgi.support;

import com.chua.common.support.osgi.OsgiBundle;
import org.osgi.framework.Bundle;
import org.osgi.framework.ServiceRegistration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Felix Bundle 实现，包装 OSGI 的 Bundle 对象。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class FelixOsgiBundle implements OsgiBundle {

    /** Bundle */
    private final Bundle bundle;
    private final List<ServiceRegistration<?>> registrations = new CopyOnWriteArrayList<>(); // registrations

    /**
      * 创建 felixosgibundle 实例
     * @param bundle bundle
     */
    public FelixOsgiBundle(Bundle bundle) {
        this.bundle = bundle;
    }

    @Override
    /** 获取symbolic名称 */
    public String getSymbolicName() {
        return bundle.getSymbolicName();
    }

    @Override
    /** 获取版本 */
    public String getVersion() {
        return bundle.getVersion().toString();
    }

    @Override
    /** 获取状态 */
    public String getState() {
        return switch (bundle.getState()) {
            case Bundle.ACTIVE -> "ACTIVE";
            case Bundle.INSTALLED -> "INSTALLED";
            case Bundle.RESOLVED -> "RESOLVED";
            case Bundle.STARTING -> "STARTING";
            case Bundle.STOPPING -> "STOPPING";
            case Bundle.UNINSTALLED -> "UNINSTALLED";
            default -> "UNKNOWN";
        };
    }

    @Override
    /** 开始 */
    public void start() {
        try {
            bundle.start();
        } catch (Exception e) {
            throw new RuntimeException("Failed to start bundle: " + getSymbolicName(), e);
        }
    }

    @Override
    /** 停止 */
    public void stop() {
        try {
            for (ServiceRegistration<?> reg : registrations) {
                reg.unregister();
            }
            registrations.clear();
            bundle.stop();
        } catch (Exception e) {
            throw new RuntimeException("Failed to stop bundle: " + getSymbolicName(), e);
        }
    }

    @Override
    /** 卸载 */
    public void uninstall() {
        try {
            for (ServiceRegistration<?> reg : registrations) {
                reg.unregister();
            }
            registrations.clear();
            bundle.uninstall();
        } catch (Exception e) {
            throw new RuntimeException("Failed to uninstall bundle: " + getSymbolicName(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 注册服务
     *
     * @param type 类型
     * @param service 服务
     * @return 注册服务的结果
     */
    public <T> void registerService(Class<T> type, T service) {
        ServiceRegistration<?> registration = bundle.getBundleContext()
                .registerService(type.getName(), service, null);
        registrations.add(registration);
    }

    @Override
    @SuppressWarnings("unchecked")
    /**
     * 注销服务
     *
     * @param type 类型
     * @param service 服务
     * @return 注销服务的结果
     */
    public <T> void unregisterService(Class<T> type, T service) {
        registrations.removeIf(reg -> {
            try {
                org.osgi.framework.ServiceReference<?> ref = reg.getReference();
                if (ref == null) {
                    return false;
                }
                String[] classes = (String[]) ref.getProperty("objectClass");
                if (classes == null || classes.length == 0) {
                    return false;
                }
                for (String cls : classes) {
                    if (cls.equals(type.getName())) {
                        reg.unregister();
                        return true;
                    }
                }
            } catch (Exception e) {
                // ignore
            }
            return false;
        });
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
                    bundle.getBundleContext().getServiceReferences(type.getName(), null);
            if (refs == null) {
                return Collections.emptyList();
            }
            List<T> result = new ArrayList<>();
            for (org.osgi.framework.ServiceReference<?> ref : refs) {
                Object service = bundle.getBundleContext().getService(ref);
                if (service != null) {
                    result.add((T) service);
                    bundle.getBundleContext().ungetService(ref);
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
