package com.chua.osgi.support;

import com.chua.common.support.osgi.BundleApplication;
import com.chua.common.support.osgi.BundleContext;
import com.chua.common.support.osgi.BundleLifecycleListener;
import com.chua.common.support.osgi.BundleStateQuery;
import com.chua.common.support.osgi.OsgiBundle;
import com.chua.common.support.osgi.OsgiLauncher;
import com.chua.common.support.osgi.OsgiLauncherHolder;
import com.chua.common.support.osgi.OsgiServiceDescriptor;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.felix.framework.FrameworkFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.launch.Framework;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Felix OSGI 启动器实现。
 * <p>
 * 启动后将自身注册到 {@link OsgiLauncherHolder} 作为全局唯一实例，
 * 并发现所有 {@link BundleApplication} SPI 实现，回调传入 Bundle 上下文。
 * </p>
 *
 * <h3>事件模型</h3>
 * <p>
 * 生命周期事件统一由框架级 {@link BundleListener} 驱动，
 * 而非由 {@link #installBundle(String)}、{@link #uninstallBundle(String)} 等
 * 客户端方法手工触发。这样通过任何途径（含直接操作框架上下文）引发的
 * bundle 状态变化都能被监听器感知，且每个事件只会被投递一次。
 * </p>
 *
 * <h3>服务引用持有</h3>
 * <p>
 * {@link #getServices(Class)} 与 {@link #getService(Class)} 取到的服务实例
 * 会连同其 {@link ServiceReference} 一并被本启动器持有（引用计数 +1），
 * 直至 {@link #releaseServices(Class)}、{@link #releaseAllServices()}
 * 或 {@link #stop()} 释放。这样调用方拿到的实例在框架回收前始终有效。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FelixOsgiLauncher implements OsgiLauncher, BundleStateQuery {

    /**
     * 全量服务枚举时持有服务引用的记账键。
     * <p>
     * 该键不对应任何服务类型，仅用于让 {@link #getServiceDescriptors()}
     * 取到的实例同样受引用持有保护，避免被框架提前回收。
     * </p>
     */
    private static final Class<?> ALL_SERVICES_KEY = OsgiLauncherHolder.class;

    /**
     * 框架。
     */
    private volatile Framework framework;

    /**
     * 原始 osgi bundle 上下文（服务注册表按名查找入口）。
     */
    private volatile org.osgi.framework.BundleContext frameworkContext;

    /**
     * 框架级 bundle 监听器句柄，注销时使用。
     */
    private volatile BundleListener frameworkListener;

    /**
     * Applications。
     */
    private final List<BundleApplication> applications = new CopyOnWriteArrayList<>();

    /**
     * 监听器。
     */
    private final List<BundleLifecycleListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 是否自动启动已安装的 bundle。
     */
    private volatile boolean autoStartInstalledBundles = true;

    /**
     * bundle 编号 → 状态快照，供状态变更事件推导旧状态。
     */
    private final Map<Long, BundleStateSnapshot> stateSnapshots = new ConcurrentHashMap<>();

    /**
     * bundle 编号 → 包装对象缓存（包装本身无状态，可安全复用）。
     */
    private final Map<Long, FelixOsgiBundle> bundlesById = new ConcurrentHashMap<>();

    /**
     * 服务类型 → 本启动器持有的服务引用。
     */
    private final Map<Class<?>, List<ServiceReference<?>>> heldServiceReferences = new ConcurrentHashMap<>();

    /**
     * 服务引用 → 提供该服务的 bundle 编号。
     * <p>
     * 持有服务引用会使引用计数保持大于 0，而 OSGI 规范只在服务被<b>注销</b>时使引用失效；
     * bundle 卸载时框架虽会注销其注册的服务，但此前被 use 过的引用仍可能取到实例。
     * 记录来源 bundle 才能在卸载时定向释放，避免已下线服务继续被桥接进容器。
     * </p>
     */
    private final Map<ServiceReference<?>, Long> referenceOwners = new ConcurrentHashMap<>();

    /**
     * 各 bundle 已处理的事件计数（按事件类型分别计数）。
     * <p>
     * Felix 在 {@code FelixFramework DispatchQueue} 线程上异步投递 bundle 事件，
     * 而规范提供的同步注册重载在 Felix 7 中并不存在，无法让框架同步回调。
     * 这里改为自建屏障：按「事件类型」记录处理次数，
     * {@link #installBundle(String)} / {@link #uninstallBundle(String)}
     * 以及 {@code FelixOsgiBundle#stop()} / {@code FelixOsgiBundle#uninstall()}
     * 在返回前等待<b>目标事件</b>处理完毕。
     * </p>
     * <p>
     * 必须区分事件类型：一次安装会连续产生
     * {@code INSTALLED → RESOLVED → STARTED} 多条事件，若只数总条数，
     * 等待会在中间事件（如 {@code RESOLVED}）处提前放行，
     * 此时 {@code STARTED} 仍在队列中，调用方依旧会读到滞后状态。
     * </p>
     */
    private final Map<Long, Map<Integer, java.util.concurrent.atomic.AtomicInteger>> processedEventCounts =
            new ConcurrentHashMap<>();

    /**
     * 等待事件投递完成的超时毫秒数。
     * <p>
     * 屏障只是消除竞态，不应因框架异常把调用方永久挂住；
     * 超时后放弃等待并告警，由调用方按「事件可能延迟」处理。
     * </p>
     */
    private static final long EVENT_BARRIER_TIMEOUT_MILLIS = 3000L;

    /**
     * 屏障轮询间隔毫秒数。
     */
    private static final long EVENT_BARRIER_POLL_MILLIS = 2L;

    /**
     * 自动启动失败累计次数。
     */
    private final java.util.concurrent.atomic.AtomicLong autoStartFailureCount =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 最近一次自动启动失败的原因描述。
     */
    private final java.util.concurrent.atomic.AtomicReference<String> lastAutoStartFailure =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * 启动并激活 Felix 框架。
     * <p>
     * 具备幂等性：框架已活动时直接返回；已创建但未活动时复用同一实例。
     * 同一时刻只允许一个 {@link Framework} 实例处于活动状态，
     * 因此本方法串行执行。
     * </p>
     *
     * @param config 启动配置参数，可为 {@code null}
     */
    @Override
    public synchronized void start(Map<String, String> config) {
        Framework current = framework;
        if (current != null) {
            int state = current.getState();
            if (state == Bundle.ACTIVE || state == Bundle.STARTING) {
                log.warn("[osgi] OSGI framework 已处于活动状态（state={}），忽略重复启动", stateName(state));
                return;
            }
        }

        Map<String, String> felixConfig = new HashMap<>();
        if (config != null) {
            felixConfig.putAll(config);
        }
        felixConfig.putIfAbsent(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);

        try {
            Framework target = framework;
            if (target == null) {
                FrameworkFactory factory = new FrameworkFactory();
                target = factory.newFramework(felixConfig);
                target.init();
                framework = target;
            } else {
                log.warn("[osgi] 复用已初始化的 framework 实例，本次传入的 {} 项配置不再生效", felixConfig.size());
            }
            frameworkContext = target.getBundleContext();
            registerFrameworkListener(target.getBundleContext());
            target.start();
            OsgiLauncherHolder.setInstance(this);
            notifyApplications();
            log.info("[osgi] OSGI framework started successfully");
        } catch (Exception e) {
            log.error("[osgi] Failed to start OSGI framework", e);
            throw new RuntimeException("Failed to start OSGI framework", e);
        }
    }

    /**
     * 停止并释放框架。
     * <p>
     * 具备幂等性：框架未创建时直接返回。停止过程中会依次回调
     * {@link BundleApplication#onBundleStop(BundleContext)}、释放持有的服务引用、
     * 注销框架监听器并清空缓存。
     * </p>
     */
    @Override
    public synchronized void stop() {
        Framework current = framework;
        if (current == null) {
            return;
        }
        try {
            unregisterFrameworkListener();
            BundleContext ctx = new FelixBundleContext(current.getBundleContext(), this);
            for (BundleApplication app : applications) {
                try {
                    app.onBundleStop(ctx);
                } catch (Exception e) {
                    log.warn("[osgi] BundleApplication stop failed: {}", app.getClass().getName(), e);
                }
            }
            applications.clear();
            releaseAllServices();
            current.stop();
            current.waitForStop(5000L);
            log.info("[osgi] OSGI framework stopped");
        } catch (Exception e) {
            log.error("[osgi] Failed to stop OSGI framework", e);
        } finally {
            OsgiLauncherHolder.clear();
            frameworkContext = null;
            framework = null;
            stateSnapshots.clear();
            bundlesById.clear();
            referenceOwners.clear();
            lastAutoStartFailure.set(null);
        }
    }

    /**
     * 是否活跃。
     *
     * @return 已激活返回 true
     */
    @Override
    public boolean isActive() {
        Framework current = framework;
        return current != null && current.getState() == Bundle.ACTIVE;
    }

    /**
     * 获取原始 osgi bundle 上下文（服务注册表访问入口，供框架外组件按名查找 osgi 服务）。
     *
     * @return 框架 bundle 上下文，框架未启动时返回 {@code null}
     */
    public org.osgi.framework.BundleContext getFrameworkBundleContext() {
        return frameworkContext;
    }

    // ==================== 服务 ====================

    /**
     * 获取指定类型的全部服务实例。
     * <p>
     * 取到的服务引用由本启动器持有，在显式释放或框架停止前实例保持有效。
     * </p>
     * <p>
     * <b>顺序即优先级</b>：结果按 OSGI 原生 {@code service.ranking} <b>降序</b>排列
     * （值越大越优先，缺省 0），同 ranking 时按 {@code service.id} 升序保证结果稳定。
     * 因此 {@link #getService(Class)} 取首个元素即等价于「取当前生效的服务」。
     * </p>
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务实例列表，框架未激活时返回空列表
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> List<T> getServices(Class<T> type) {
        if (!isActive()) {
            return Collections.emptyList();
        }
        org.osgi.framework.BundleContext context = framework.getBundleContext();
        List<T> result = new ArrayList<>();
        List<ServiceReference<?>> acquired = new ArrayList<>();
        try {
            ServiceReference<?>[] refs = context.getServiceReferences(type.getName(), null);
            if (refs == null) {
                return result;
            }
            // 先按 ranking 排好序再取值：ranking 决定哪个服务当前生效，
            // 不能依赖框架返回引用的顺序
            List<ServiceReference<?>> ordered = orderByRanking(refs);
            for (ServiceReference<?> ref : ordered) {
                Object service = context.getService(ref);
                if (service == null) {
                    continue;
                }
                if (!type.isInstance(service)) {
                    // 跨类加载器的同名类型：类身份不同，无法强转，跳过而非让整批查询失败
                    log.debug("[osgi] 服务实例类型不匹配（类加载器身份不同），已跳过: {}", type.getName());
                    context.ungetService(ref);
                    continue;
                }
                result.add((T) service);
                acquired.add(ref);
            }
        } catch (Exception e) {
            log.warn("[osgi] Failed to get OSGI services for type: {}", type.getName(), e);
        }
        if (!acquired.isEmpty()) {
            trackHeldReferences(type, acquired);
        }
        return result;
    }

    /**
     * 获取指定类型的单个服务实例（取服务 Ranking 最高者）。
     *
     * @param type 服务接口类型
     * @param <T>  服务类型
     * @return 服务实例，未找到返回 {@code null}
     */
    @Override
    public <T> T getService(Class<T> type) {
        List<T> services = getServices(type);
        return services.isEmpty() ? null : services.getFirst();
    }

    /**
     * 记录已持有的服务引用及其来源 bundle。
     *
     * @param type 记账键（服务类型或全量枚举哨兵）
     * @param refs 本次新增持有的引用
     */
    private void trackHeldReferences(Class<?> type, List<ServiceReference<?>> refs) {
        if (refs.isEmpty()) {
            return;
        }
        heldServiceReferences.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>()).addAll(refs);
        for (ServiceReference<?> ref : refs) {
            try {
                org.osgi.framework.Bundle owner = ref.getBundle();
                if (owner != null) {
                    referenceOwners.put(ref, owner.getBundleId());
                }
            } catch (Exception e) {
                // 归属未知时不记账，卸载时退化为整体释放
                log.debug("[osgi] 记录服务引用归属失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 释放指定 bundle 名下被本启动器持有的全部服务引用。
     * <p>
     * 在 {@code UNINSTALLED} 事件到达时调用。这些服务所属 bundle 已卸载，
     * 若继续持有引用，其实例在服务注册表中仍可见，会被桥接进容器，
     * 表现为「卸载后仍能查到旧对象」——本方法用于切断这条通路。
     * </p>
     *
     * @param bundleId bundle 编号
     * @return 释放的引用数量
     */
    private int releaseReferencesOf(long bundleId) {
        List<ServiceReference<?>> owned = new ArrayList<>();
        for (Map.Entry<ServiceReference<?>, Long> entry : referenceOwners.entrySet()) {
            if (entry.getValue() == bundleId) {
                owned.add(entry.getKey());
            }
        }
        if (owned.isEmpty()) {
            return 0;
        }
        for (List<ServiceReference<?>> refs : heldServiceReferences.values()) {
            refs.removeAll(owned);
        }
        heldServiceReferences.entrySet().removeIf(e -> e.getValue().isEmpty());
        referenceOwners.keySet().removeAll(owned);
        org.osgi.framework.BundleContext context = currentBundleContext();
        if (context == null) {
            return owned.size();
        }
        for (ServiceReference<?> ref : owned) {
            try {
                context.ungetService(ref);
            } catch (Exception e) {
                log.debug("[osgi] 释放已卸载 bundle 的服务引用失败: {}", e.getMessage());
            }
        }
        log.debug("[osgi] bundle {} 已卸载，释放其 {} 个服务引用", bundleId, owned.size());
        return owned.size();
    }

    /**
     * 释放本启动器持有的指定类型全部服务引用。
     *
     * @param type 服务接口类型
     */
    @Override
    public void releaseServices(Class<?> type) {
        List<ServiceReference<?>> refs = heldServiceReferences.remove(type);
        if (refs == null || refs.isEmpty()) {
            return;
        }
        org.osgi.framework.BundleContext context = currentBundleContext();
        if (context == null) {
            return;
        }
        for (ServiceReference<?> ref : refs) {
            try {
                context.ungetService(ref);
            } catch (Exception e) {
                log.debug("[osgi] 释放服务引用失败: {}", e.getMessage());
            }
        }
        refs.forEach(referenceOwners::remove);
        log.debug("[osgi] 已释放 {} 项服务引用: {}", refs.size(), type.getName());
    }

    /**
     * 释放本启动器持有的全部服务引用。
     */
    @Override
    public void releaseAllServices() {
        for (Class<?> type : new ArrayList<>(heldServiceReferences.keySet())) {
            releaseServices(type);
        }
    }

    /**
     * 获取服务注册表中已登记的全部服务类型全限定名。
     *
     * @return 服务类型全限定名列表，框架未激活时返回空列表
     */
    @Override
    public List<String> getRegisteredServiceTypes() {
        org.osgi.framework.BundleContext context = currentBundleContext();
        if (context == null) {
            return Collections.emptyList();
        }
        Set<String> types = new LinkedHashSet<>();
        try {
            ServiceReference<?>[] refs = context.getAllServiceReferences(null, null);
            if (refs == null) {
                return Collections.emptyList();
            }
            for (ServiceReference<?> ref : refs) {
                Object objectClass = ref.getProperty(Constants.OBJECTCLASS);
                if (objectClass instanceof String[] names) {
                    Collections.addAll(types, names);
                } else if (objectClass instanceof String name) {
                    types.add(name);
                }
            }
        } catch (Exception e) {
            log.warn("[osgi] 枚举服务类型失败", e);
        }
        return List.copyOf(types);
    }

    /**
     * 获取服务注册表中已登记的全部服务描述符。
     * <p>
     * 为每项登记产出一个描述符：类型名取 {@code objectClass} 的首个条目，
     * 并同时保留全部类型名与全部服务属性。
     * </p>
     * <p>
     * <b>顺序即优先级</b>：按 {@code service.ranking} 降序（同值按 {@code service.id} 升序），
     * 跨服务类型混排，因此调用方若要取「某类型下当前生效的服务」，
     * 仍应使用 {@link #getServices(Class)} 而非本方法。
     * </p>
     *
     * @return 服务描述符列表，框架未激活时返回空列表
     */
    @Override
    public List<OsgiServiceDescriptor> getServiceDescriptors() {
        org.osgi.framework.BundleContext context = currentBundleContext();
        if (context == null) {
            return Collections.emptyList();
        }
        List<OsgiServiceDescriptor> result = new ArrayList<>();
        List<ServiceReference<?>> acquired = new ArrayList<>();
        try {
            ServiceReference<?>[] refs = context.getAllServiceReferences(null, null);
            if (refs == null) {
                return result;
            }
            for (ServiceReference<?> ref : orderByRanking(refs)) {
                String[] types = objectClasses(ref);
                if (types.length == 0) {
                    continue;
                }
                Object service = context.getService(ref);
                if (service == null) {
                    continue;
                }
                String consistentType = consistentTypeName(types, service);
                if (consistentType == null) {
                    // 类身份不一致：多实例场景下先前的 bundle 可能被框架从 storage 恢复，
                    // 其接口由旧类加载器加载，与当前类加载器下的同名类并非同一类型。
                    // 此时若照常桥接，容器侧会拿到一个无法被业务类型接受的实例，
                    // 表现为「查得到 Bean 但用不了」。此处与 getServices 保持同一口径：跳过。
                    log.debug("[osgi] 服务类型与当前类加载器不一致，已跳过: {}", types[0]);
                    context.ungetService(ref);
                    continue;
                }
                acquired.add(ref);
                result.add(new OsgiServiceDescriptor(consistentType, safeString(ref, Constants.SERVICE_PID),
                        service, readProperties(ref, types)));
            }
        } catch (Exception e) {
            log.warn("[osgi] 枚举服务描述符失败", e);
        }
        if (!acquired.isEmpty()) {
            trackHeldReferences(ALL_SERVICES_KEY, acquired);
        }
        return result;
    }

    /**
     * 从服务声明的类型中挑出与当前类加载器一致的那一个。
     * <p>
     * 一项服务可同时声明多个接口（{@code objectClass} 为多元素数组），
     * 桥接时必须以「当前类加载器能认得」的那个类型为准，否则容器侧会拿到
     * 无法被业务类型接受的实例。
     * </p>
     *
     * @param types  服务声明的全部类型名
     * @param service 服务实例
     * @return 一致的类型名，全部不一致时返回 {@code null}
     */
    private String consistentTypeName(String[] types, Object service) {
        for (String type : types) {
            Class<?> candidate = ReflectUtils.forName(type);
            if (candidate != null && candidate.isInstance(service)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 按服务优先级对引用排序。
     * <p>
     * 排序规则与 OSGI 规范一致：{@code service.ranking} 降序（值大者优先，缺省 0），
     * 同 ranking 时按 {@code service.id} 升序——后者保证同一优先级下的顺序可重现，
     * 避免每次枚举得到不同排列而使「取首个即取最优」的语义变得不确定。
     * </p>
     *
     * @param refs 服务引用数组
     * @return 排好序的引用列表
     */
    static List<ServiceReference<?>> orderByRanking(ServiceReference<?>[] refs) {
        List<ServiceReference<?>> ordered = new ArrayList<>(List.of(refs));
        ordered.sort(Comparator
                .comparingLong(FelixOsgiLauncher::rankingOf).reversed()
                .thenComparing(FelixOsgiLauncher::serviceIdOf));
        return ordered;
    }

    /**
     * 读取服务引用的 ranking。
     *
     * @param reference 服务引用
     * @return ranking 值，缺失或非数值时返回 0
     */
    private static long rankingOf(ServiceReference<?> reference) {
        Object value = reference.getProperty(Constants.SERVICE_RANKING);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * 读取服务引用的 service.id。
     *
     * @param reference 服务引用
     * @return service.id，缺失或非数值时返回 {@link Long#MAX_VALUE}（排到同优先级末尾）
     */
    private static long serviceIdOf(ServiceReference<?> reference) {
        Object value = reference.getProperty(Constants.SERVICE_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return Long.MAX_VALUE;
            }
        }
        return Long.MAX_VALUE;
    }

    /**
     * 读取服务引用的 {@code objectClass} 属性。
     *
     * @param reference 服务引用
     * @return 类型全限定名数组，无类型时返回空数组
     */
    private static String[] objectClasses(ServiceReference<?> reference) {
        Object objectClass = reference.getProperty(Constants.OBJECTCLASS);
        if (objectClass instanceof String[] names) {
            return names;
        }
        if (objectClass instanceof String name) {
            return new String[]{name};
        }
        return new String[0];
    }

    /**
     * 安全读取字符串属性。
     *
     * @param reference  服务引用
     * @param propertyKey 属性键
     * @return 属性值，非字符串或缺失时返回 {@code null}
     */
    private static String safeString(ServiceReference<?> reference, String propertyKey) {
        Object value = reference.getProperty(propertyKey);
        return value instanceof String text ? text : null;
    }

    /**
     * 读取服务引用上的全部可枚举属性。
     *
     * @param reference 服务引用
     * @param types     该登记声明的全部服务类型
     * @return 服务属性，只读映射
     */
    private static Map<String, Object> readProperties(ServiceReference<?> reference, String[] types) {
        Map<String, Object> properties = new HashMap<>();
        List<String> typeList = new ArrayList<>(List.of(types));
        properties.put(Constants.OBJECTCLASS, Collections.unmodifiableList(typeList));
        try {
            String[] keys = reference.getPropertyKeys();
            if (keys != null) {
                for (String key : keys) {
                    if (Constants.OBJECTCLASS.equals(key)) {
                        continue;
                    }
                    properties.put(key, reference.getProperty(key));
                }
            }
        } catch (Exception e) {
            log.debug("[osgi] 读取服务属性失败: {}", e.getMessage());
        }
        return Collections.unmodifiableMap(properties);
    }

    // ==================== bundle ====================

    /**
     * 获取全部已安装 bundle。
     *
     * @return bundle 列表，框架未激活时返回空列表
     */
    @Override
    public List<OsgiBundle> getBundles() {
        org.osgi.framework.BundleContext context = currentBundleContext();
        if (context == null) {
            return Collections.emptyList();
        }
        Bundle[] raw = context.getBundles();
        if (raw == null) {
            return Collections.emptyList();
        }
        List<OsgiBundle> result = new ArrayList<>(raw.length);
        for (Bundle bundle : raw) {
            result.add(wrap(bundle));
        }
        return result;
    }

    /**
     * 按符号名称获取 bundle。
     *
     * @param symbolicName bundle 符号名称
     * @return 对应 bundle，未找到返回 {@code null}
     */
    @Override
    public OsgiBundle getBundle(String symbolicName) {
        if (symbolicName == null) {
            return null;
        }
        org.osgi.framework.BundleContext context = currentBundleContext();
        if (context == null) {
            return null;
        }
        for (Bundle bundle : context.getBundles()) {
            if (symbolicName.equals(bundle.getSymbolicName())) {
                return wrap(bundle);
            }
        }
        return null;
    }

    /**
     * 按框架内唯一编号获取 bundle。
     *
     * @param bundleId bundle 唯一编号
     * @return 对应 bundle，未找到返回 {@code null}
     */
    @Override
    public OsgiBundle getBundle(long bundleId) {
        org.osgi.framework.BundleContext context = currentBundleContext();
        if (context == null) {
            return null;
        }
        Bundle bundle = context.getBundle(bundleId);
        return bundle == null ? null : wrap(bundle);
    }

    /**
     * 按来源位置精确匹配获取 bundle 列表。
     *
     * @param location 来源位置 URL
     * @return 匹配的 bundle 列表
     */
    @Override
    public List<OsgiBundle> getBundlesByLocation(String location) {
        if (location == null || location.isBlank()) {
            return Collections.emptyList();
        }
        return getBundles().stream()
                .filter(b -> location.equals(b.getLocation()))
                .collect(Collectors.toList());
    }

    /**
     * 按状态获取 bundle 列表。
     *
     * @param state 状态名（ACTIVE/RESOLVED/INSTALLED 等）
     * @return 匹配的 bundle 列表
     */
    @Override
    public List<OsgiBundle> getBundlesByState(String state) {
        if (state == null) {
            return Collections.emptyList();
        }
        return getBundles().stream()
                .filter(b -> state.equals(b.getState()))
                .collect(Collectors.toList());
    }

    /**
     * 获取处于活动状态的 bundle 列表。
     *
     * @return 活动状态的 bundle 列表
     */
    @Override
    public List<OsgiBundle> getActiveBundles() {
        return getBundlesByState("ACTIVE");
    }

    /**
     * 获取已安装 bundle 总数。
     *
     * @return bundle 总数
     */
    @Override
    public long getBundleCount() {
        return getBundles().size();
    }

    /**
     * 安装指定 URL 的 bundle。
     * <p>
     * 安装成功后若 {@link #isAutoStartInstalledBundles()} 为真则自动启动。
     * 自动启动失败（如依赖无法满足导致解析失败）不会使本次安装回滚——
     * bundle 已处于 INSTALLED 状态，调用方可稍后重试启动；
     * 失败原因会记入 {@link #getFrameworkStats()} 的 {@code autoStartFailures}，
     * 并通过 {@link BundleLifecycleListener#onBundleResolveFailed(String, String)} 通知。
     * </p>
     * <p>生命周期事件由框架监听器统一投递，本方法不额外触发。</p>
     *
     * @param url bundle 的 jar 路径或远程 URL
     * @return 已安装的 bundle 包装
     * @throws IllegalStateException 框架未激活时抛出
     */
    @Override
    public OsgiBundle installBundle(String url) {
        if (!isActive()) {
            throw new IllegalStateException("OSGI framework is not active");
        }
        try {
            org.osgi.framework.BundleContext context = framework.getBundleContext();
            Bundle bundle = context.installBundle(url);
            long bundleId = bundle.getBundleId();
            log.info("[osgi] Bundle installed: {} ({}), autoStart={}",
                    bundle.getSymbolicName(), url, autoStartInstalledBundles);
            boolean started = false;
            if (autoStartInstalledBundles) {
                try {
                    bundle.start();
                    started = true;
                } catch (Exception e) {
                    recordAutoStartFailure(bundle.getSymbolicName(), e);
                }
            }
            // 等待本次安装/启动的关键事件回调完毕：
            // 自动启动成功时等 STARTED，否则只等 INSTALLED，
            // 避免调用方在事件队列尚未消费时就读到滞后状态
            awaitBundleEvent("installBundle", bundleId, started ? BundleEvent.STARTED : BundleEvent.INSTALLED, 0);
            FelixOsgiBundle wrapped = new FelixOsgiBundle(bundle);
            wrapped.attachLauncher(this);
            return wrapped;
        } catch (Exception e) {
            throw new RuntimeException("Failed to install bundle: " + url, e);
        }
    }

    /**
     * 读取指定 bundle 指定事件类型的已处理次数。
     *
     * @param bundleId  bundle 编号
     * @param eventType 事件类型，取值见 {@link BundleEvent}
     * @return 已处理次数
     */
    int processedEventCount(long bundleId, int eventType) {
        Map<Integer, java.util.concurrent.atomic.AtomicInteger> counts = processedEventCounts.get(bundleId);
        if (counts == null) {
            return 0;
        }
        java.util.concurrent.atomic.AtomicInteger counter = counts.get(eventType);
        return counter == null ? 0 : counter.get();
    }

    /**
     * 等待指定 bundle 的目标生命周期事件处理完成。
     * <p>
     * Felix 在分发线程上异步投递 bundle 事件，而规范提供的同步注册重载
     * 在 Felix 7 中不存在，无法让框架同步回调（详见
     * {@link #registerFrameworkListener}）。此处在操作后等待目标事件的
     * 处理次数超过操作前基线，保证方法返回时本次操作的关键事件已经回调，
     * 调用方「操作后立即读状态/读监听器记录」不再与事件投递赛跑。
     * </p>
     * <p>
     * 该屏障只消除竞态，不承诺事件一定由本线程回调；超时后放弃等待并告警。
     * </p>
     *
     * @param operation 操作名称，用于告警定位
     * @param bundleId  目标 bundle 编号
     * @param eventType 期望的事件类型，取值见 {@link BundleEvent}
     * @param baseline  操作开始前该事件的已处理次数
     */
    void awaitBundleEvent(String operation, long bundleId, int eventType, int baseline) {
        long deadline = System.nanoTime() + EVENT_BARRIER_TIMEOUT_MILLIS * 1_000_000L;
        while (processedEventCount(bundleId, eventType) <= baseline) {
            if (System.nanoTime() - deadline >= 0) {
                log.warn("[osgi] 等待 {} 的 {} 事件投递超时，事件可能延迟回调",
                        operation, eventName(eventType));
                return;
            }
            try {
                Thread.sleep(EVENT_BARRIER_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 清除指定 bundle 的事件计数记录。
     * <p>
     * bundle 卸载后其编号不会复用，保留记录只会无谓占用内存，故在卸载路径清理。
     * </p>
     *
     * @param bundleId bundle 编号
     */
    void forgetBundleEventCounts(long bundleId) {
        processedEventCounts.remove(bundleId);
    }

    /**
     * 记录一次自动启动失败。
     *
     * <p>自动启动失败属于「装了但没起来」，安装本身仍然成功。
     * 若不显式留痕，调用方只能通过 bundle 状态反推，排查成本高；
     * 因此除日志与事件通知外，再把最近失败原因累积到统计信息中。</p>
     *
     * @param symbolicName bundle 符号名
     * @param cause        失败原因
     */
    private void recordAutoStartFailure(String symbolicName, Exception cause) {
        String reason = cause.getMessage();
        lastAutoStartFailure.set(symbolicName + " -> " + (reason == null ? cause.getClass().getSimpleName() : reason));
        autoStartFailureCount.incrementAndGet();
        log.warn("[osgi] 自动启动失败（bundle 已安装但未启动，state={}）: {}",
                getBundleStateQuietly(symbolicName), symbolicName, cause);
        fireBundleResolveFailed(symbolicName, reason);
    }

    /**
     * 静默获取 bundle 状态，用于失败日志。
     *
     * @param symbolicName bundle 符号名
     * @return 状态名，查询失败时返回 {@code "UNKNOWN"}
     */
    private String getBundleStateQuietly(String symbolicName) {
        OsgiBundle bundle = getBundle(symbolicName);
        return bundle == null ? "UNKNOWN" : bundle.getState();
    }

    /**
     * 对已安装的 bundle 执行原生热升级。
     * <p>
     * 走框架原生 {@code Bundle#update}，保留 bundle 标识与已解析的依赖关系，
     * 升级前处于活动状态则升级后自动重新启动。相比「卸载 + 重装」，
     * 不存在卸载与重装之间的窗口期。
     * </p>
     *
     * @param bundleSymbolicName bundle 符号名称
     * @param url                新版本来源，{@code null} 表示沿用原位置
     * @return 升级后的 bundle 包装
     * @throws IllegalStateException    框架未激活时抛出
     * @throws IllegalArgumentException bundle 不存在时抛出
     */
    @Override
    public OsgiBundle updateBundle(String bundleSymbolicName, String url) {
        if (!isActive()) {
            throw new IllegalStateException("OSGI framework is not active");
        }
        OsgiBundle target = getBundle(bundleSymbolicName);
        if (target == null) {
            throw new IllegalArgumentException("Bundle not found: " + bundleSymbolicName);
        }
        long begin = System.currentTimeMillis();
        target.update(url);
        log.info("[osgi] Bundle updated: {} -> version={}, cost={}ms",
                bundleSymbolicName, target.getVersion(), System.currentTimeMillis() - begin);
        return target;
    }

    /**
     * 卸载指定符号名称的 bundle。
     *
     * @param bundleSymbolicName bundle 符号名称
     * @return 存在并成功卸载返回 true，bundle 不存在返回 false
     */
    @Override
    public boolean uninstallBundle(String bundleSymbolicName) {
        if (!isActive() || bundleSymbolicName == null) {
            return false;
        }
        org.osgi.framework.BundleContext context = framework.getBundleContext();
        for (Bundle bundle : context.getBundles()) {
            if (bundleSymbolicName.equals(bundle.getSymbolicName())) {
                long bundleId = bundle.getBundleId();
                int baseline = processedEventCount(bundleId, BundleEvent.UNINSTALLED);
                try {
                    if (bundle.getState() == Bundle.ACTIVE || bundle.getState() == Bundle.STARTING) {
                        bundle.stop();
                    }
                    bundle.uninstall();
                    // 等待 UNINSTALLED 回调完毕，避免调用方读到滞后状态
                    awaitBundleEvent("uninstallBundle", bundleId, BundleEvent.UNINSTALLED, baseline);
                    forgetBundleEventCounts(bundleId);
                    log.info("[osgi] Bundle uninstalled: {}", bundleSymbolicName);
                    return true;
                } catch (Exception e) {
                    throw new RuntimeException("Failed to uninstall bundle: " + bundleSymbolicName, e);
                }
            }
        }
        log.warn("[osgi] Bundle not found: {}", bundleSymbolicName);
        return false;
    }

    /**
     * 获取框架统计信息。
     *
     * @return 统计信息键值对
     */
    @Override
    public Map<String, Object> getFrameworkStats() {
        Map<String, Object> stats = new HashMap<>();
        Framework current = framework;
        stats.put("active", current != null && current.getState() == Bundle.ACTIVE);
        stats.put("frameworkState", current == null ? "NONE" : stateName(current.getState()));
        stats.put("totalBundles", getBundleCount());
        stats.put("activeBundles", getActiveBundles().size());
        stats.put("registeredApplications", applications.size());
        stats.put("listeners", listeners.size());
        stats.put("registeredServiceTypes", getRegisteredServiceTypes().size());
        stats.put("heldServiceReferences", heldServiceReferences.values().stream()
                .mapToInt(List::size).sum());
        stats.put("autoStartFailures", autoStartFailureCount.get());
        stats.put("lastAutoStartFailure", lastAutoStartFailure.get());
        Map<String, Long> stateCounts = new HashMap<>();
        for (OsgiBundle bundle : getBundles()) {
            stateCounts.merge(bundle.getState(), 1L, Long::sum);
        }
        stats.put("bundleStates", stateCounts);
        return stats;
    }

    /**
     * 是否自动启动已安装的 bundle。
     *
     * @return 自动启动返回 true
     */
    @Override
    public boolean isAutoStartInstalledBundles() {
        return autoStartInstalledBundles;
    }

    /**
     * 设置是否自动启动已安装的 bundle。
     *
     * @param autoStart 为 true 时启用自动启动
     */
    @Override
    public void setAutoStartInstalledBundles(boolean autoStart) {
        this.autoStartInstalledBundles = autoStart;
    }

    // ==================== 生命周期监听 ====================

    /**
     * 添加生命周期监听器。
     *
     * @param listener 监听器
     */
    public void addListener(BundleLifecycleListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * 移除生命周期监听器。
     *
     * @param listener 监听器
     */
    public void removeListener(BundleLifecycleListener listener) {
        listeners.remove(listener);
    }

    /**
     * 获取已注册的 BundleApplication 列表。
     *
     * @return 应用列表，只读视图
     */
    public List<BundleApplication> getApplications() {
        return List.copyOf(applications);
    }

    // ==================== 内部实现 ====================

    /**
     * 注册框架级 bundle 监听器。
     * <p>
     * 框架事件由 Felix 的 {@code FelixFramework DispatchQueue} 线程<b>异步</b>投递
     * （实测事件常迟于调用方数十毫秒到达）。规范提供的
     * {@code addBundleListener(listener, SYNCHRONOUS)} 同步注册重载在
     * Felix 7 内嵌的 API 中并不存在（{@code BundleContext} 与
     * {@code BundleContextImpl} 均只有单参数版本），因此无法依赖框架做同步投递；
     * 改由 {@link #awaitBundleEvent} 在 {@link #installBundle(String)} 与
     * {@link #uninstallBundle(String)} 返回前建立屏障，确保本次操作产生的事件
     * 已被处理完毕，调用方「操作后立即读状态」不再与事件投递赛跑。
     * </p>
     *
     * @param context 框架 bundle 上下文
     */
    private void registerFrameworkListener(org.osgi.framework.BundleContext context) {
        if (frameworkListener != null) {
            return;
        }
        BundleListener listener = new FrameworkBundleListener();
        try {
            context.addBundleListener(listener);
            frameworkListener = listener;
        } catch (Exception e) {
            log.error("[osgi] 注册 framework bundle 监听器失败", e);
        }
    }

    /**
     * 注销框架级 bundle 监听器。
     */
    private void unregisterFrameworkListener() {
        BundleListener listener = frameworkListener;
        if (listener == null) {
            return;
        }
        org.osgi.framework.BundleContext context = currentBundleContext();
        if (context != null) {
            try {
                context.removeBundleListener(listener);
            } catch (Exception e) {
                log.debug("[osgi] 注销 framework bundle 监听器失败: {}", e.getMessage());
            }
        }
        frameworkListener = null;
    }

    /**
     * 通知所有 {@link BundleApplication} 框架已就绪。
     */
    private void notifyApplications() {
        BundleContext ctx = new FelixBundleContext(framework.getBundleContext(), this);
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

    /**
     * 获取当前可用的框架 bundle 上下文。
     *
     * @return bundle 上下文，框架未活动时返回 {@code null}
     */
    private org.osgi.framework.BundleContext currentBundleContext() {
        Framework current = framework;
        if (current == null || current.getState() != Bundle.ACTIVE) {
            return null;
        }
        return current.getBundleContext();
    }

    /**
     * 包装（并缓存）原生 bundle。
     *
     * @param bundle 原生 bundle
     * @return 包装对象
     */
    private FelixOsgiBundle wrap(Bundle bundle) {
        return bundlesById.computeIfAbsent(bundle.getBundleId(), id -> {
            FelixOsgiBundle wrapped = new FelixOsgiBundle(bundle);
            wrapped.attachLauncher(this);
            return wrapped;
        });
    }

    /**
     * 框架状态常量转名称。
     *
     * @param state 状态常量
     * @return 状态名称
     */
    static String stateName(int state) {
        return switch (state) {
            case Bundle.UNINSTALLED -> "UNINSTALLED";
            case Bundle.INSTALLED -> "INSTALLED";
            case Bundle.RESOLVED -> "RESOLVED";
            case Bundle.STARTING -> "STARTING";
            case Bundle.STOPPING -> "STOPPING";
            case Bundle.ACTIVE -> "ACTIVE";
            default -> "UNKNOWN";
        };
    }

    /**
     * bundle 事件类型常量转名称。
     *
     * @param type 事件类型常量
     * @return 事件类型名称
     */
    static String eventName(int type) {
        return switch (type) {
            case BundleEvent.INSTALLED -> "INSTALLED";
            case BundleEvent.STARTED -> "STARTED";
            case BundleEvent.STOPPED -> "STOPPED";
            case BundleEvent.UPDATED -> "UPDATED";
            case BundleEvent.UNINSTALLED -> "UNINSTALLED";
            case BundleEvent.RESOLVED -> "RESOLVED";
            case BundleEvent.UNRESOLVED -> "UNRESOLVED";
            case BundleEvent.STARTING -> "STARTING";
            case BundleEvent.STOPPING -> "STOPPING";
            default -> "UNKNOWN";
        };
    }

    /**
     * bundle 状态变更事件处理器。
     * <p>
     * 依据事件类型分派到具体语义回调；未能归入语义事件的类型统一走
     * {@code onBundleStateChanged}，旧状态取自内部状态快照。
     * </p>
     */
    private final class FrameworkBundleListener implements BundleListener {

        @Override
        public void bundleChanged(BundleEvent event) {
            Bundle bundle = event.getBundle();
            if (bundle == null) {
                return;
            }
            long bundleId = bundle.getBundleId();
            String symbolicName = bundle.getSymbolicName();
            if (symbolicName == null) {
                symbolicName = "#" + bundleId;
            }
            if (log.isDebugEnabled()) {
                log.debug("[osgi] 收到 bundle 事件: type={}, bundle={}, id={}, 当前状态={}",
                        eventName(event.getType()), symbolicName, bundleId, stateName(bundle.getState()));
            }
            try {
                switch (event.getType()) {
                    case BundleEvent.INSTALLED -> {
                        stateSnapshots.put(bundleId, snapshot(bundle));
                        fireBundleInstalled(symbolicName);
                    }
                    case BundleEvent.STARTED -> {
                        stateSnapshots.put(bundleId, snapshot(bundle));
                        fireBundleStarted(symbolicName);
                    }
                    case BundleEvent.STOPPED -> {
                        stateSnapshots.put(bundleId, snapshot(bundle));
                        fireBundleStopped(symbolicName);
                    }
                    case BundleEvent.UPDATED -> {
                        stateSnapshots.put(bundleId, snapshot(bundle));
                        fireBundleUpdated(symbolicName, safeVersion(bundle));
                    }
                    case BundleEvent.UNINSTALLED -> {
                        BundleStateSnapshot previous = stateSnapshots.remove(bundleId);
                        bundlesById.remove(bundleId);
                        // 先切断服务引用通路，再广播事件：
                        // 否则监听器（如 Bean 桥接注册器）在随后的快照重建中
                        // 仍可能读到属于已卸载 bundle 的服务实例
                        releaseReferencesOf(bundleId);
                        String location = previous != null ? previous.location() : bundle.getLocation();
                        String version = previous != null ? previous.version() : safeVersion(bundle);
                        fireBundleStateChanged(symbolicName,
                                previous != null ? previous.state() : "UNKNOWN", "UNINSTALLED");
                        fireBundleRemoved(symbolicName, location, version);
                    }
                    case BundleEvent.UNRESOLVED -> {
                        fireStateChangedWithSnapshot(bundleId, bundle, symbolicName);
                        fireBundleResolveFailed(symbolicName, "依赖无法满足，框架解析失败");
                    }
                    default -> fireStateChangedWithSnapshot(bundleId, bundle, symbolicName);
                }
            } catch (Exception e) {
                log.warn("[osgi] 处理 bundle 事件失败: type={}, bundle={}", event.getType(), symbolicName, e);
            } finally {
                // 无论处理成功与否都要登记事件计数，否则 installBundle 的屏障会一直等下去
                processedEventCounts
                        .computeIfAbsent(bundleId, id -> new ConcurrentHashMap<>())
                        .computeIfAbsent(event.getType(), type -> new java.util.concurrent.atomic.AtomicInteger())
                        .incrementAndGet();
            }
        }

        /**
         * 更新状态快照并投递状态变更事件。
         *
         * @param bundleId     bundle 唯一编号
         * @param bundle       原生 bundle
         * @param symbolicName bundle 符号名称
         */
        private void fireStateChangedWithSnapshot(long bundleId, Bundle bundle, String symbolicName) {
            BundleStateSnapshot previous = stateSnapshots.get(bundleId);
            String current = stateName(bundle.getState());
            if (previous != null && current.equals(previous.state())) {
                return;
            }
            stateSnapshots.put(bundleId, snapshot(bundle));
            fireBundleStateChanged(symbolicName, previous != null ? previous.state() : "UNKNOWN", current);
        }
    }

    /**
     * 构造 bundle 状态快照。
     *
     * @param bundle 原生 bundle
     * @return 状态快照
     */
    private static BundleStateSnapshot snapshot(Bundle bundle) {
        return new BundleStateSnapshot(stateName(bundle.getState()),
                bundle.getLocation(), safeVersion(bundle));
    }

    /**
     * 安全获取 bundle 版本号。
     *
     * @param bundle 原生 bundle
     * @return 版本号，未声明时返回 空
     */
    private static String safeVersion(Bundle bundle) {
        try {
            return bundle.getVersion() == null ? "" : bundle.getVersion().toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 投递 bundle 安装事件。
     *
     * @param symbolicName bundle 符号名称
     */
    private void fireBundleInstalled(String symbolicName) {
        forEachListener(listener -> listener.onBundleInstalled(symbolicName));
    }

    /**
     * 投递 bundle 启动事件。
     *
     * @param symbolicName bundle 符号名称
     */
    private void fireBundleStarted(String symbolicName) {
        forEachListener(listener -> listener.onBundleStarted(symbolicName));
    }

    /**
     * 投递 bundle 停止事件。
     *
     * @param symbolicName bundle 符号名称
     */
    private void fireBundleStopped(String symbolicName) {
        forEachListener(listener -> listener.onBundleStopped(symbolicName));
    }

    /**
     * 投递 bundle 更新事件。
     *
     * @param symbolicName bundle 符号名称
     * @param newVersion   更新后版本号
     */
    private void fireBundleUpdated(String symbolicName, String newVersion) {
        forEachListener(listener -> listener.onBundleUpdated(symbolicName, newVersion));
    }

    /**
     * 投递 bundle 卸载事件。
     *
     * @param symbolicName bundle 符号名称
     * @param location     卸载前来源位置
     * @param version      卸载前版本号
     */
    private void fireBundleRemoved(String symbolicName, String location, String version) {
        forEachListener(listener -> listener.onBundleRemoved(symbolicName, location, version));
    }

    /**
     * 投递 bundle 状态变更事件。
     *
     * @param symbolicName bundle 符号名称
     * @param oldState     旧状态
     * @param newState     新状态
     */
    private void fireBundleStateChanged(String symbolicName, String oldState, String newState) {
        forEachListener(listener -> listener.onBundleStateChanged(symbolicName, oldState, newState));
    }

    /**
     * 投递 bundle 解析失败事件。
     *
     * @param symbolicName bundle 符号名称
     * @param reason       失败原因
     */
    private void fireBundleResolveFailed(String symbolicName, String reason) {
        forEachListener(listener -> listener.onBundleResolveFailed(symbolicName, reason));
    }

    /**
     * 遍历监听器投递事件，隔离单个监听器的异常。
     *
     * @param action 事件投递动作
     */
    private void forEachListener(java.util.function.Consumer<BundleLifecycleListener> action) {
        for (BundleLifecycleListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.warn("[osgi] 生命周期监听器异常: {}", listener.getClass().getName(), e);
            }
        }
    }

    /**
     * bundle 状态快照记录。
     *
     * @param state    状态名
     * @param location 来源位置
     * @param version  版本号
     */
    private record BundleStateSnapshot(String state, String location, String version) {
        // 内部快照载体
    }
}
