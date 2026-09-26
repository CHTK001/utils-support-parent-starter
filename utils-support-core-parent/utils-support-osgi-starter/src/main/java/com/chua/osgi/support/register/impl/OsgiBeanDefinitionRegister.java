package com.chua.osgi.support.register.impl;

import com.chua.common.support.objects.acceptance.BeanAcceptance;
import com.chua.common.support.objects.definition.BeanDefinition;
import com.chua.common.support.objects.definition.FrameworkBeanDefinition;
import com.chua.common.support.objects.register.BeanDefinitionRegister;
import com.chua.common.support.objects.register.BeanSingletonRegistry;
import com.chua.common.support.osgi.BundleLifecycleListener;
import com.chua.common.support.osgi.OsgiLauncher;
import com.chua.common.support.osgi.OsgiServiceDescriptor;
import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.spi.annotations.SpiIgnore;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * osgi Bean 定义注册器（只读桥接）。
 *
 * <p>委托注入的 {@link OsgiLauncher} 获取 OSGi 框架，所有查询委派 OSGi 服务注册表。
 * Bean 实例由 Felix 容器管理，本注册器仅做桥接，因此 {@link #isWritable()} 恒为 false：
 * OSGi 侧的定义不由本注册器创建，只在容器查询时按当前框架状态投影生成。</p>
 *
 * <h3>优先级</h3>
 * <p>
 * OSGi 同一服务类型可被多个 bundle 重复注册，「当前生效」的那一个由框架的
 * {@code service.ranking} 决定。本注册器把该值原样写入
 * {@link BeanDefinition#setPriority(int)}（语义同为「值越大越优先」），
 * 于是容器既有的按优先级选优逻辑（如 {@code ObjectContext#getBeanOfType}）
 * 无需任何改动即可选出 ranking 最高的服务——两侧共用同一套优先级语义，
 * 不再各自维护排序规则。
 * </p>
 *
 * <h3>扩展对象的切换</h3>
 * <p>
 * 桥接产出的 {@link FrameworkBeanDefinition} 是「从 OSGi 服务扩展出来的对象」。
 * 若每次查询都新建，虽然总能反映最新状态，但容器侧的名称缓存、类型索引与
 * 调用方持有的引用会与框架状态脱节。本注册器改为维护一份<b>带版本号的快照</b>：
 * </p>
 * <ul>
 *   <li>快照仅在框架版本号变化时重建，避免每次查询都全量枚举服务注册表；</li>
 *   <li>本注册器实现 {@link BundleLifecycleListener}，在 bundle 安装、启动、停止、
 *       更新、卸载时主动推进版本号，使下一次查询必然重建——OSGi 侧的变化
 *       由此传导为扩展对象的变化；</li>
 *   <li>快照重建时，旧定义统一置为不可用并释放单例缓存，
 *       避免已下线的服务实例继续被引用。</li>
 * </ul>
 *
 * <h3>验收</h3>
 * <p>
 * 每个投影出的定义都会经过 {@link BeanAcceptance} 验收器链（多个命中时任一拒绝即拒绝），
 * 被拒绝的定义不进入快照，拒绝原因记录在 {@link #getRejectedSnapshots()} 供排查。
 * </p>
 *
 * <p>标记为 {@link SpiIgnore}，不参与 SPI 自动注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@SpiIgnore
@SpiDescribe("OSGi Bean 定义注册器（只读桥接，支持 ranking 优先级与生命周期失效）")
public class OsgiBeanDefinitionRegister extends BeanSingletonRegistry
        implements BeanDefinitionRegister, BundleLifecycleListener {

    /**
     * Bean 名称与服务类型全限定名的分隔符。
     */
    private static final String NAME_SEPARATOR = ":";

    /**
     * 是否已关闭。
     */
    private volatile boolean closed;

    /**
     * osgi 启动器。
     */
    private volatile OsgiLauncher osgiLauncher;

    /**
     * 框架状态版本号：每次 OSGi 侧变化即自增，快照据此判断是否需要重建。
     */
    private final AtomicLong frameworkVersion = new AtomicLong();

    /**
     * 当前快照：Bean 名称 → 桥接定义。版本不匹配时视为失效。
     */
    private volatile Snapshot snapshot = Snapshot.empty();

    /**
     * 最近一次快照重建中被验收拒绝的记录。
     */
    private volatile Map<String, String> rejectedSnapshots = Map.of();

    /**
     * 是否启用验收环节。
     */
    private volatile boolean acceptanceEnabled = true;

    /**
     * 是否在 bundle 生命周期变化时失效快照。
     */
    private volatile boolean invalidateOnBundleLifecycle = true;

    /**
     * 显式追加的验收器。
     * <p>
     * 与 SPI 装载到的验收器一并生效。用于不便走 SPI 的场景：
     * 运行时按条件启用的临时校验、以及测试注入。
     * </p>
     */
    private final List<BeanAcceptance> extraAcceptances = new CopyOnWriteArrayList<>();

    /**
     * 追加验收器。
     *
     * @param acceptance 验收器，{@code null} 被忽略
     */
    public void addAcceptance(BeanAcceptance acceptance) {
        if (acceptance != null) {
            extraAcceptances.add(acceptance);
        }
    }

    /**
     * 移除验收器。
     *
     * @param acceptance 验收器
     * @return 移除成功返回 true
     */
    public boolean removeAcceptance(BeanAcceptance acceptance) {
        return extraAcceptances.remove(acceptance);
    }

    /**
     * 设置是否启用验收环节。
     *
     * @param acceptanceEnabled 启用验收返回 true
     */
    public void setAcceptanceEnabled(boolean acceptanceEnabled) {
        this.acceptanceEnabled = acceptanceEnabled;
    }

    /**
     * 是否启用验收环节。
     *
     * @return 启用返回 true
     */
    public boolean isAcceptanceEnabled() {
        return acceptanceEnabled;
    }

    /**
     * 设置是否在 bundle 生命周期变化时失效快照。
     *
     * @param invalidateOnBundleLifecycle 启用联动返回 true
     */
    public void setInvalidateOnBundleLifecycle(boolean invalidateOnBundleLifecycle) {
        this.invalidateOnBundleLifecycle = invalidateOnBundleLifecycle;
    }

    /**
     * 是否在 bundle 生命周期变化时失效快照。
     *
     * @return 启用联动返回 true
     */
    public boolean isInvalidateOnBundleLifecycle() {
        return invalidateOnBundleLifecycle;
    }

    /**
     * 快照：一次框架状态投影的完整结果。
     *
     * @param version    对应的框架状态版本号
     * @param byName     Bean 名称 → 定义，保持服务优先级顺序
     * @param names      全部 Bean 名称（有序）
     * @param nameToType Bean 名称 → 服务类型全限定名
     */
    private record Snapshot(long version, Map<String, BeanDefinition> byName,
                            List<String> names, Map<String, String> nameToType) {

        /**
         * 空快照的版本哨兵。
         * <p>
         * 取一个真实版本号不会取到的极小值，避免与 {@code System.nanoTime()}
         * 派生的实际版本号碰撞——碰撞会让「已清空」被误判为「仍然有效」。</p>
         */
        private static final long EMPTY_VERSION = Long.MIN_VALUE;

        /**
         * 规范构造器：对三个集合组件做防御性拷贝。
         *
         * <p>value class 前置条件——集合组件必须深不可变，快照一旦发布即不应被外部改写，
         * 否则「已失效快照」的内容会被悄悄改掉，导致
         * {@link OsgiBeanDefinitionRegister#expireSnapshot(Snapshot)} 漏释放定义。</p>
         *
         * @param version    对应的框架状态版本号
         * @param byName     Bean 名称 → 定义，保持服务优先级顺序
         * @param names      全部 Bean 名称（有序）
         * @param nameToType Bean 名称 → 服务类型全限定名
         */
        public Snapshot {
            byName = Map.copyOf(Objects.requireNonNull(byName, "byName 不能为 null"));
            names = List.copyOf(Objects.requireNonNull(names, "names 不能为 null"));
            nameToType = Map.copyOf(Objects.requireNonNull(nameToType, "nameToType 不能为 null"));
        }

        /**
         * 构造空快照。
         *
         * @return 空快照
         */
        static Snapshot empty() {
            return new Snapshot(EMPTY_VERSION, Map.of(), List.of(), Map.of());
        }

        /**
         * 判断快照是否为空（即尚未投影过任何定义）。
         *
         * @return 为空返回 true
         */
        boolean isEmptyVersion() {
            return version == EMPTY_VERSION;
        }

        /**
         * 判断快照是否仍对当前框架状态有效。
         *
         * @param currentVersion 当前框架状态版本号
         * @return 有效返回 true
         */
        boolean validFor(long currentVersion) {
            return version == currentVersion;
        }
    }

    /**
     * 设置 osgi 启动器（由 Spring 注入，替代静态持有）。
     *
     * @param osgiLauncher osgi 启动器
     */
    public void setOsgiLauncher(OsgiLauncher osgiLauncher) {
        this.osgiLauncher = osgiLauncher;
    }

    @Override
    public String getName() {
        return "osgi";
    }

    @Override
    public int getPriority() {
        return 100;
    }

    @Override
    public boolean isSupport(BeanDefinition beanDefinition) {
        return false;
    }

    @Override
    public boolean isWritable() {
        return false;
    }

    /**
     * 注册定义。
     * <p>
     * 本注册器为只读桥接：定义由框架状态投影生成，不接受外部注册。
     * 拒绝写入是 {@link #isWritable()} 返回 false 的应有之义——
     * 调用方应据该标记先行判断，方法本身仍显式抛出以免误用被静默吞掉。
     * </p>
     *
     * @param beanDefinition Bean 定义
     * @return 不返回
     * @throws UnsupportedOperationException 固定抛出
     */
    @Override
    public boolean register(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("OSGi Bean 定义注册器为只读桥接，不支持手动注册");
    }

    /**
     * 注销定义。
     * <p>
     * 同 {@link #register(BeanDefinition)}：只读桥接不接受外部注销。
     * 需要让某个服务从容器视图消失时，正确做法是卸载对应 bundle
     * （或调整其 {@code service.ranking}），由生命周期联动推进快照版本。
     * </p>
     *
     * @param beanDefinition Bean 定义
     * @return 不返回
     * @throws UnsupportedOperationException 固定抛出
     */
    @Override
    public boolean unregister(BeanDefinition beanDefinition) {
        throw new UnsupportedOperationException("OSGi Bean 定义注册器为只读桥接，不支持手动注销");
    }

    /**
     * 按名称注销定义。
     *
     * @param beanName Bean 名称
     * @return 不返回
     * @throws UnsupportedOperationException 固定抛出
     */
    @Override
    public boolean unregister(String beanName) {
        throw new UnsupportedOperationException("OSGi Bean 定义注册器为只读桥接，不支持手动注销");
    }

    @Override
    public void initialize() {
        closed = false;
        frameworkVersion.set(System.nanoTime());
        rejectedSnapshots = Map.of();
        snapshot = Snapshot.empty();
    }

    @Override
    public BeanDefinition getBeanDefinition(String beanName) {
        if (beanName == null || closed) {
            return null;
        }
        int separator = beanName.indexOf(NAME_SEPARATOR);
        if (separator < 0) {
            return null;
        }
        String typeName = beanName.substring(0, separator);
        String serviceId = beanName.substring(separator + 1);
        if (typeName.isEmpty() || serviceId.isEmpty()) {
            return null;
        }
        BeanDefinition definition = currentSnapshot().byName().get(beanName);
        if (definition == null) {
            return null;
        }
        // 名称格式为 类型:服务标识，需确认类型段一致，防止跨类型撞名
        return typeName.equals(currentSnapshot().nameToType().get(beanName)) ? definition : null;
    }

    @Override
    public Collection<BeanDefinition> getBeanDefinitionOfType(String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        Snapshot current = currentSnapshot();
        List<BeanDefinition> result = new ArrayList<>();
        for (String beanName : current.names()) {
            if (!typeName.equals(current.nameToType().get(beanName))) {
                continue;
            }
            BeanDefinition definition = current.byName().get(beanName);
            if (definition != null) {
                result.add(definition);
            }
        }
        return result;
    }

    @Override
    public Collection<BeanDefinition> getBeanDefinitionOfType(String name, String typeName) {
        if (typeName == null || closed) {
            return Collections.emptyList();
        }
        if (name != null) {
            BeanDefinition def = getBeanDefinition(name);
            if (def != null && typeName.equals(def.getType())) {
                return List.of(def);
            }
            return Collections.emptyList();
        }
        return getBeanDefinitionOfType(typeName);
    }

    @Override
    public boolean containsBean(String beanName) {
        if (beanName == null || closed) {
            return false;
        }
        return currentSnapshot().byName().containsKey(beanName);
    }

    @Override
    public Collection<String> getBeanDefinitionNames() {
        if (closed) {
            return Collections.emptyList();
        }
        return currentSnapshot().names();
    }

    @Override
    public Map<String, BeanDefinition> getBeansWithAnnotation(Class<? extends Annotation> annotationType) {
        return collectByAnnotation(annotationType, false);
    }

    @Override
    public Map<String, BeanDefinition> getBeansWithMethodAnnotation(Class<? extends Annotation> annotationType) {
        return collectByAnnotation(annotationType, true);
    }

    /**
     * 关闭注册器并释放缓存。
     */
    @Override
    public void close() {
        closed = true;
        expireSnapshot(snapshot);
        snapshot = Snapshot.empty();
        rejectedSnapshots = Map.of();
        destroySingletons();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    // ==================== 生命周期联动 ====================

    @Override
    public void onBundleInstalled(String symbolicName) {
        invalidate("bundle 已安装: " + symbolicName);
    }

    @Override
    public void onBundleStarted(String symbolicName) {
        invalidate("bundle 已启动: " + symbolicName);
    }

    @Override
    public void onBundleStopped(String symbolicName) {
        invalidate("bundle 已停止: " + symbolicName);
    }

    @Override
    public void onBundleUpdated(String symbolicName, String newVersion) {
        invalidate("bundle 已升级: " + symbolicName + " -> " + newVersion);
    }

    @Override
    public void onBundleUninstalled(String symbolicName) {
        invalidate("bundle 已卸载: " + symbolicName);
    }

    @Override
    public void onBundleStateChanged(String symbolicName, String oldState, String newState) {
        invalidate("bundle 状态变化: " + symbolicName + " " + oldState + " -> " + newState);
    }

    @Override
    public void onBundleRemoved(String symbolicName, String location, String version) {
        invalidate("bundle 已移除: " + symbolicName);
    }

    @Override
    public void onBundleResolveFailed(String symbolicName, String reason) {
        invalidate("bundle 解析失败: " + symbolicName);
    }

    // ==================== 快照与验收 ====================

    /**
     * 推进框架状态版本号，使现有快照失效。
     *
     * @param reason 失效原因
     */
    private void invalidate(String reason) {
        if (!invalidateOnBundleLifecycle) {
            return;
        }
        long next = frameworkVersion.incrementAndGet();
        Snapshot current = snapshot;
        if (current.version() == next) {
            return;
        }
        expireSnapshot(current);
        log.debug("[osgi] 扩展对象快照失效（{}），版本 {} -> {}", reason, current.version(), next);
    }

    /**
     * 使快照内的定义全部不可用并释放其单例缓存。
     *
     * @param target 待失效的快照
     */
    private void expireSnapshot(Snapshot target) {
        for (BeanDefinition definition : target.byName().values()) {
            definition.setAvailable(false);
            try {
                definition.destroyBean();
            } catch (Exception e) {
                log.debug("[osgi] 释放过期桥接定义失败: {}", definition.getName(), e);
            }
        }
    }

    /**
     * 获取当前有效的快照，必要时重建。
     *
     * @return 当前快照
     */
    private Snapshot currentSnapshot() {
        OsgiLauncher launcher = this.osgiLauncher;
        if (launcher == null || !launcher.isActive()) {
            // 框架已停止（或从未启动）时必须返回空视图。
            // 不能只依赖版本号：框架停止不会产生任何 bundle 事件，
            // 版本号保持不变，旧快照会被误判为仍然有效，
            // 导致已下线服务的实例继续从容器中查到——在多个 Spring 上下文
            // 共享同一个 ObjectContext 时尤其明显（先启动的上下文停止后，
            // 其注册器仍留在容器注册表里）。故此处显式校验活跃状态。
            Snapshot stale = snapshot;
            if (!stale.isEmptyVersion()) {
                expireSnapshot(stale);
                snapshot = Snapshot.empty();
            }
            return snapshot;
        }
        long version = frameworkVersion.get();
        Snapshot current = snapshot;
        if (current.validFor(version)) {
            return current;
        }
        return rebuild(version);
    }

    /**
     * 依据框架真实状态重建快照。
     *
     * @param version 本次重建对应的版本号
     * @return 重建后的快照
     */
    private synchronized Snapshot rebuild(long version) {
        Snapshot current = snapshot;
        if (current.validFor(version)) {
            return current;
        }
        OsgiLauncher launcher = this.osgiLauncher;
        if (launcher == null || !launcher.isActive()) {
            expireSnapshot(current);
            // 记录当前版本号而非用空哨兵，使后续版本变化仍能触发重建
            snapshot = new Snapshot(version, Map.of(), List.of(), Map.of());
            return snapshot;
        }
        expireSnapshot(current);

        Map<String, BeanDefinition> byName = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        Map<String, String> nameToType = new LinkedHashMap<>();
        Map<String, String> rejected = new LinkedHashMap<>();

        // getServiceDescriptors 已按 service.ranking 降序，投影结果天然保持优先级顺序
        for (OsgiServiceDescriptor descriptor : launcher.getServiceDescriptors()) {
            String beanName = beanNameOf(descriptor);
            BeanDefinition definition = toBeanDefinition(beanName, descriptor);
            BeanAcceptance.AcceptanceResult result = accept(definition);
            if (!result.accepted()) {
                rejected.put(beanName, result.reason());
                log.debug("[osgi] 桥接定义未通过验收，已跳过: {}（{}）", beanName, result.reason());
                continue;
            }
            byName.put(beanName, definition);
            names.add(beanName);
            nameToType.put(beanName, descriptor.typeName());
        }

        this.rejectedSnapshots = Map.copyOf(rejected);
        Snapshot rebuilt = new Snapshot(version, Map.copyOf(byName), List.copyOf(names), Map.copyOf(nameToType));
        this.snapshot = rebuilt;
        log.debug("[osgi] 扩展对象快照已重建: {} 个桥接定义（{} 个未通过验收）", names.size(), rejected.size());
        return rebuilt;
    }

    /**
     * 对单个定义执行验收器链判定。
     *
     * @param definition 待验收定义
     * @return 验收结论
     */
    private BeanAcceptance.AcceptanceResult accept(BeanDefinition definition) {
        if (!acceptanceEnabled) {
            return BeanAcceptance.AcceptanceResult.accept();
        }
        List<BeanAcceptance> acceptances = loadAcceptances();
        if (acceptances.isEmpty()) {
            return BeanAcceptance.AcceptanceResult.accept();
        }
        for (BeanAcceptance acceptance : acceptances) {
            try {
                if (!acceptance.isSupport(definition)) {
                    continue;
                }
                BeanAcceptance.AcceptanceResult result = acceptance.accept(definition);
                if (result != null && !result.accepted()) {
                    return result;
                }
            } catch (Exception e) {
                // 验收器自身异常按拒绝处理：无法完成准入检查时不应放行
                log.warn("[osgi] 验收器异常，按拒绝处理: {}", acceptance.getClass().getName(), e);
                return BeanAcceptance.AcceptanceResult.reject("验收器异常: " + e.getMessage());
            }
        }
        return BeanAcceptance.AcceptanceResult.accept();
    }

    /**
     * 装载验收器列表，装载失败不阻断桥接主流程。
     *
     * @return 验收器列表
     */
    private List<BeanAcceptance> loadAcceptances() {
        List<BeanAcceptance> merged = new ArrayList<>(extraAcceptances);
        try {
            merged.addAll(ServiceProvider.of(BeanAcceptance.class).collect());
        } catch (Exception e) {
            log.debug("[osgi] 验收器装载失败，仅使用显式追加的验收器: {}", e.getMessage());
        }
        return merged;
    }

    /**
     * 扫描桥接定义上的指定形态注解。
     *
     * @param annotationType 注解类型
     * @param methodLevel    {@code true} 表示扫描方法注解，{@code false} 表示扫描类注解
     * @return 命中的 Bean 定义映射
     */
    private Map<String, BeanDefinition> collectByAnnotation(Class<? extends Annotation> annotationType,
                                                            boolean methodLevel) {
        Map<String, BeanDefinition> result = new LinkedHashMap<>();
        if (annotationType == null || closed) {
            return result;
        }
        for (BeanDefinition definition : currentSnapshot().byName().values()) {
            Object instance = definition.getBean();
            if (instance == null) {
                continue;
            }
            try {
                boolean hit = methodLevel
                        ? hasMethodAnnotation(instance.getClass(), annotationType)
                        : instance.getClass().isAnnotationPresent(annotationType);
                if (hit) {
                    result.put(definition.getName(), definition);
                }
            } catch (Throwable e) {
                // 跨类加载器的注解不可读，跳过而不影响整体扫描
                log.debug("[osgi] 扫描服务注解失败: {}", definition.getName(), e);
            }
        }
        return result;
    }

    /**
     * 判断类中是否有方法标注了指定注解（含继承来的方法）。
     *
     * @param type           待检查的类
     * @param annotationType 注解类型
     * @return 存在标注方法返回 true
     */
    private boolean hasMethodAnnotation(Class<?> type, Class<? extends Annotation> annotationType) {
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(annotationType)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取（并缓存）描述符对应的稳定 Bean 名称。
     *
     * @param descriptor 服务描述符
     * @return 稳定 Bean 名称
     */
    private String beanNameOf(OsgiServiceDescriptor descriptor) {
        return descriptor.stableId();
    }

    /**
     * 构造桥接 Bean 定义，并把服务优先级贯通到容器侧。
     *
     * @param beanName    Bean 名称
     * @param descriptor 服务描述符
     * @return Bean 定义
     */
    private BeanDefinition toBeanDefinition(String beanName, OsgiServiceDescriptor descriptor) {
        Class<?> type = ReflectUtils.forName(descriptor.typeName());
        if (type == null) {
            type = descriptor.instance() == null ? Object.class : descriptor.instance().getClass();
        }
        FrameworkBeanDefinition definition =
                new FrameworkBeanDefinition(beanName, type, descriptor.instance());
        long ranking = descriptor.ranking();
        // 容器侧 priority 语义为「值越大越优先」，与 OSGI service.ranking 一致，直接贯通
        definition.setPriority(clampPriority(ranking));
        definition.setAvailable(true);
        return definition;
    }

    /**
     * 将 ranking 收敛到容器优先级的整型取值域。
     *
     * @param ranking 服务 ranking
     * @return 收敛后的优先级
     */
    private int clampPriority(long ranking) {
        if (ranking > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (ranking < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) ranking;
    }

    /**
     * 获取当前框架状态版本号。
     *
     * @return 版本号
     */
    public long getFrameworkVersion() {
        return frameworkVersion.get();
    }

    /**
     * 获取最近一次快照重建中被验收拒绝的记录。
     *
     * @return Bean 名称 → 拒绝原因
     */
    public Map<String, String> getRejectedSnapshots() {
        return rejectedSnapshots;
    }
}
