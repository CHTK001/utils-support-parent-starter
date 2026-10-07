package com.chua.osgi.support.source;

import com.chua.common.support.collection.wrap.Wraps;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;

/**
 * {@link SourceContext} 的全局持有者，供源码扩展点静态取用容器 Bean。
 *
 * <p>刻意用静态持有：源码产物是被扫描/反射实例化的，拿不到构造参数，
 * 而它在方法体里需要即时取用 Bean——静态门面是最小侵入的通道。</p>
 *
 * <p><b>并发</b>：读多写极少（装配期写一次），用 {@link volatile} 引用整体替换即可，
 * 不需要加锁；源码在重载期间读到的始终是某一个完整快照。</p>
 *
 * <p><b>为什么不做成 ThreadLocal</b>：源码的 SPI 实现可能在任意线程被调用
 * （定时任务、请求线程），ThreadLocal 会取不到值。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
@Slf4j
public final class SourceContextHolder {

    /**
     * 当前上下文，为 {@code null} 表示未装配。
     */
    private static volatile SourceContext current;

    /**
     * 工具类，禁止实例化。
     */
    private SourceContextHolder() {
    }

    /**
     * 安装容器 Bean 映射。
     *
     * @param beans 已就绪的 Bean；为 {@code null} 时清空
     */
    public static void install(Map<String, Object> beans) {
        current = beans == null || beans.isEmpty() ? null : new MapSourceContext(beans);
        log.debug("[osgi-source] 容器上下文已安装，共 {} 个 Bean", current == null ? 0 : current.size());
    }

    /**
     * 清空上下文。
     */
    public static void clear() {
        current = null;
        log.debug("[osgi-source] 容器上下文已清空");
    }

    /**
     * 取得当前上下文。
     *
     * @return 上下文，未装配时返回 {@code null}
     */
    public static SourceContext get() {
        return current;
    }

    /**
     * 按类型取 Bean，源码侧的主要入口。
     *
     * @param type Bean 类型
     * @param <T>  Bean 类型
     * @return Bean 实例，未提供时返回 {@code null}
     */
    public static <T> T get(Class<T> type) {
        var context = current;
        if (context == null) {
            log.warn("[osgi-source] 容器上下文未安装，无法提供 {}；"
                    + "源码扩展点若需要容器 Bean，请在装配时调用 SourceContextHolder.install", type.getName());
            return null;
        }
        return context.get(type);
    }

    /**
     * 按名字取 Bean。
     *
     * <p>刻意不叫 {@code get}：与 {@link #get(Class)} 重载会让调用点出现歧义，
     * 调用方被迫写显式转型才能编译。</p>
     *
     * @param name Bean 名称
     * @return Bean 实例，不存在或未安装时返回 {@code null}
     */
    public static Object getBean(String name) {
        var context = current;
        return context == null ? null : context.getBean(name);
    }

    /**
     * 基于普通 Map 的上下文实现。
     *
     * <p>刻意不用 record：record 的紧凑构造器是规范构造器，无法另写一个带
     * 索引构建逻辑的构造器（会与编译器生成的冲突）。</p>
     */
    private static final class MapSourceContext implements SourceContext {

        /**
         * 按 Bean 名的索引。
         */
        private final Map<String, Object> byName;

        /**
         * 按 Bean 类型的索引。
         */
        private final Map<Class<?>, Object> byType;

        /**
         * 建立按名与按类型的双向索引。
         *
         * @param beans Bean 映射
         */
        private MapSourceContext(Map<String, Object> beans) {
            this.byName = Wraps.linkedMap(beans);
            var types = Wraps.<Class<?>, Object>linkedMap();
            for (Object bean : beans.values()) {
                if (bean != null) {
                    types.putIfAbsent(bean.getClass(), bean);
                }
            }
            this.byType = types;
        }

        @Override
        public <T> T get(Class<T> type) {
            Object found = byType.get(type);
            if (found == null) {
                // 精确类型未命中时按接口/父类再找一次，避免 Bean 以实现类注册导致取不到
                for (Map.Entry<Class<?>, Object> entry : byType.entrySet()) {
                    if (type.isAssignableFrom(entry.getKey())) {
                        return type.cast(entry.getValue());
                    }
                }
            }
            return type.cast(found);
        }

        @Override
        public Object getBean(String name) {
            return byName.get(name);
        }

        @Override
        public boolean contains(Class<?> type) {
            return get(type) != null;
        }

        @Override
        public int size() {
            return byName.size();
        }
    }
}
