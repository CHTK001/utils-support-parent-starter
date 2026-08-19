package com.chua.osgi.support;

import com.chua.common.support.osgi.BundleApplication;
import com.chua.common.support.osgi.BundleContext;
import com.chua.common.support.osgi.BundleLifecycleListener;
import com.chua.common.support.osgi.BundleStateQuery;
import com.chua.common.support.osgi.OsgiBundle;
import com.chua.common.support.osgi.OsgiLauncher;
import com.chua.common.support.osgi.OsgiLauncherHolder;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.felix.framework.FrameworkFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Felix OSGI 启动器实现。
 * <p>
 * 启动后将自身注册到 {@link OsgiLauncherHolder} 作为全局唯一实例，
 * 并发现所有 {@link BundleApplication} SPI 实现，回调传入 Bundle 上下文。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FelixOsgiLauncher implements OsgiLauncher, BundleStateQuery {

    /** framework */
    private volatile Framework framework;
    /** Applications */
    private final List<BundleApplication> applications = new CopyOnWriteArrayList<>();
    /** Listeners */
    private final List<BundleLifecycleListener> listeners = new CopyOnWriteArrayList<>();
    /** Auto开始installedbundles */
    private boolean autoStartInstalledBundles = true;

    @Override
    /** 开始 */
    public void start(Map<String, String> config) {
        if (framework != null && framework.getState() == Bundle.ACTIVE) {
            log.warn("[osgi] OSGI framework is already active");
            return;
        }

        Map<String, String> felixConfig = new HashMap<>();
        if (config != null) {
            felixConfig.putAll(config);
        }
        felixConfig.putIfAbsent(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);

        try {
            FrameworkFactory factory = new FrameworkFactory();
            framework = factory.newFramework(felixConfig);
            framework.init();
            framework.start();
            OsgiLauncherHolder.setInstance(this);
            notifyApplications();
            log.info("[osgi] OSGI framework started successfully");
        } catch (Exception e) {
            log.error("[osgi] Failed to start OSGI framework", e);
            throw new RuntimeException("Failed to start OSGI framework", e);
        }
    }

    /** 通知Applications */
    private void notifyApplications() {
        BundleContext ctx = new FelixBundleContext(framework.getBundleContext());
        ServiceProvider<BundleApplication> provider = ServiceProvider.of(BundleApplication.class);
        provider.forEach((name, app) -> {
            try {
                app.onBundleStart(ctx);
                applications.add(app);
                log.debug("[osgi] BundleApplication notified: {}", app.getClass().getName());
            } catch (Exception e) {
                log.warn("[osgi] BundleApplication start failed: {}", app.getClass().getName(), e);
            }
        });
    }

    /** 添加Listener */
    public void addListener(BundleLifecycleListener listener) {
        listeners.add(listener);
    }

    /** 移除Listener */
    public void removeListener(BundleLifecycleListener listener) {
        listeners.remove(listener);
    }

    /** FireBundleInstalled */
    private void fireBundleInstalled(String symbolicName) {
        listeners.forEach(l -> l.onBundleInstalled(symbolicName));
    }

    /** FireBundleStarted */
    private void fireBundleStarted(String symbolicName) {
        listeners.forEach(l -> l.onBundleStarted(symbolicName));
    }

    /** FireBundleStopped */
    private void fireBundleStopped(String symbolicName) {
        listeners.forEach(l -> l.onBundleStopped(symbolicName));
    }

    /** FireBundleUpdated */
    private void fireBundleUpdated(String symbolicName, String newVersion) {
        listeners.forEach(l -> l.onBundleUpdated(symbolicName, newVersion));
    }

    /** FireBundleUninstalled */
    private void fireBundleUninstalled(String symbolicName) {
        listeners.forEach(l -> l.onBundleUninstalled(symbolicName));
    }

    /** FireBundleStateChanged */
    private void fireBundleStateChanged(String symbolicName, String oldState, String newState) {
        listeners.forEach(l -> l.onBundleStateChanged(symbolicName, oldState, newState));
    }

    /** 是否Auto开始InstalledBundles */
    public boolean isAutoStartInstalledBundles() {
        return autoStartInstalledBundles;
    }

    /** 设置Auto开始InstalledBundles */
    public void setAutoStartInstalledBundles(boolean autoStart) {
        this.autoStartInstalledBundles = autoStart;
    }

    @Override
    /** 停止 */
    public void stop() {
        if (framework == null) {
            return;
        }
        try {
            BundleContext ctx = new FelixBundleContext(framework.getBundleContext());
            for (BundleApplication app : applications) {
                try {
                    app.onBundleStop(ctx);
                } catch (Exception e) {
                    log.warn("[osgi] BundleApplication stop failed: {}", app.getClass().getName(), e);
                }
            }
            applications.clear();
            framework.stop();
            framework.waitForStop(5000);
            OsgiLauncherHolder.clear();
            log.info("[osgi] OSGI framework stopped");
        } catch (Exception e) {
            log.error("[osgi] Failed to stop OSGI framework", e);
        }
    }

    @Override
    /** 是否Active */
    public boolean isActive() {
        return framework != null && framework.getState() == Bundle.ACTIVE;
    }

    @Override
    /** 获取Services */
    public <T> List<T> getServices(Class<T> type) {
        if (!isActive()) {
            return Collections.emptyList();
        }
        org.osgi.framework.BundleContext context = framework.getBundleContext();
        List<T> result = new ArrayList<>();
        try {
            ServiceReference<?>[] refs = context.getServiceReferences(type.getName(), null);
            if (refs != null) {
                for (ServiceReference<?> ref : refs) {
                    Object service = context.getService(ref);
                    if (service != null) {
                        result.add(type.cast(service));
                        context.ungetService(ref);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[osgi] Failed to get OSGI services for type: {}", type.getName(), e);
        }
        return result;
    }

    @Override
    /** 获取Service */
    public <T> T getService(Class<T> type) {
        List<T> services = getServices(type);
        return services.isEmpty() ? null : services.get(0);
    }

    @Override
    /** 获取Bundles */
    public List<OsgiBundle> getBundles() {
        if (!isActive()) {
            return Collections.emptyList();
        }
        org.osgi.framework.BundleContext context = framework.getBundleContext();
        List<OsgiBundle> result = new ArrayList<>();
        for (Bundle bundle : context.getBundles()) {
            result.add(new FelixOsgiBundle(bundle));
        }
        return result;
    }

    @Override
    /** InstallBundle */
    public OsgiBundle installBundle(String url) {
        if (!isActive()) {
            throw new IllegalStateException("OSGI framework is not active");
        }
        try {
            org.osgi.framework.BundleContext context = framework.getBundleContext();
            Bundle bundle = context.installBundle(url);
            String symbolicName = bundle.getSymbolicName();
            fireBundleInstalled(symbolicName);
            log.info("[osgi] Bundle installed: {} ({})", symbolicName, url);
            if (autoStartInstalledBundles) {
                try {
                    bundle.start();
                    fireBundleStarted(symbolicName);
                } catch (Exception e) {
                    log.warn("[osgi] Failed to auto-start bundle: {}", symbolicName, e);
                }
            }
            return new FelixOsgiBundle(bundle);
        } catch (Exception e) {
            throw new RuntimeException("Failed to install bundle: " + url, e);
        }
    }

    @Override
    /** UninstallBundle */
    public void uninstallBundle(String bundleSymbolicName) {
        if (!isActive()) {
            return;
        }
        org.osgi.framework.BundleContext context = framework.getBundleContext();
        for (Bundle bundle : context.getBundles()) {
            if (bundleSymbolicName.equals(bundle.getSymbolicName())) {
                try {
                    int state = bundle.getState();
                    if (state == Bundle.ACTIVE || state == Bundle.STARTING) {
                        bundle.stop();
                        fireBundleStopped(bundleSymbolicName);
                    }
                    bundle.uninstall();
                    fireBundleUninstalled(bundleSymbolicName);
                    log.info("[osgi] Bundle uninstalled: {}", bundleSymbolicName);
                    return;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to uninstall bundle: " + bundleSymbolicName, e);
                }
            }
        }
        log.warn("[osgi] Bundle not found: {}", bundleSymbolicName);
    }

    /** 获取Bundle */
    public OsgiBundle getBundle(String symbolicName) {
        List<OsgiBundle> bundles = getBundles();
        return bundles.stream()
                .filter(b -> b.getSymbolicName().equals(symbolicName))
                .findFirst()
                .orElse(null);
    }

    /** 获取BundlesByState */
    public List<OsgiBundle> getBundlesByState(String state) {
        return getBundles().stream()
                .filter(b -> b.getState().equals(state))
                .collect(Collectors.toList());
    }

    /** 获取ActiveBundles */
    public List<OsgiBundle> getActiveBundles() {
        return getBundlesByState("ACTIVE");
    }

    /** 获取Bundle计算数量 */
    public long getBundleCount() {
        return getBundles().size();
    }

    /** 获取FrameworkStats */
    public Map<String, Object> getFrameworkStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("active", isActive());
        stats.put("totalBundles", getBundleCount());
        stats.put("activeBundles", getActiveBundles().size());
        stats.put("registeredApplications", applications.size());
        stats.put("listeners", listeners.size());
        Map<String, Long> stateCounts = new HashMap<>();
        for (OsgiBundle bundle : getBundles()) {
            String state = bundle.getState();
            stateCounts.merge(state, 1L, Long::sum);
        }
        stats.put("bundleStates", stateCounts);
        return stats;
    }
}
