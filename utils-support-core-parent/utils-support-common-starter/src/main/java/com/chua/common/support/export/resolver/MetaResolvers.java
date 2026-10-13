package com.chua.common.support.export.resolver;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.spi.MetaResolver;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析器链调度器。
 *
 * <p>按优先级升序依次执行多个 {@link MetaResolver}，作用于同一份
 * {@link DocumentMeta}，实现"兜底 → 三方注解 → 自定义注解"的分层覆盖。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class MetaResolvers {

    /**
     * 日志。
     */
    private static final Logger log = LoggerFactory.getLogger(MetaResolvers.class);

    private MetaResolvers() {
    }

    /**
     * 获取全部已注册解析器，按优先级升序。
     *
     * @return 解析器链
     */
    public static List<MetaResolver> chain() {
        List<MetaResolver> all = Wraps.list();
        ServiceProvider.of(MetaResolver.class).collect(all::add);
        all.sort(Comparator.comparingInt(MetaResolvers::priority));
        return all;
    }

    /**
     * 构建数据行类型的元数据。
     *
     * @param type 数据行类型
     * @return 文档元数据
     */
    public static DocumentMeta resolve(Class<?> type) {
        DocumentMeta meta = new DocumentMeta();
        meta.type(type);
        for (MetaResolver resolver : chain()) {
            try {
                if (resolver.supports(type)) {
                    resolver.resolve(type, meta);
                }
            } catch (Exception e) {
                log.warn("元数据解析器 {} 处理 {} 失败，已跳过", resolver.name(), type.getName(), e);
            }
        }
        return meta;
    }

    /**
     * 使用给定解析器链构建元数据（便于测试或临时覆盖）。
     *
     * @param type  数据行类型
     * @param chain 解析器链，可为 {@code null} 表示使用默认链
     * @return 文档元数据
     */
    public static DocumentMeta resolve(Class<?> type, List<MetaResolver> chain) {
        if (chain == null) {
            return resolve(type);
        }
        DocumentMeta meta = new DocumentMeta();
        meta.type(type);
        List<MetaResolver> sorted = Wraps.list(chain);
        sorted.sort(Comparator.comparingInt(MetaResolvers::priority));
        for (MetaResolver resolver : sorted) {
            if (resolver.supports(type)) {
                resolver.resolve(type, meta);
            }
        }
        return meta;
    }

    /**
     * 由样本行补全列 —— 供 {@link java.util.Map} 行等无法反射出字段的类型使用。
     *
     * <p>仅在解析器链未产出任何列时调用，因此不会影响 POJO 的分层覆盖语义。
     * 键按首次出现顺序合并全部样本行，类型取首个非空值的运行时类型。</p>
     *
     * @param rows 样本行
     * @param meta 待补全的元数据
     */
    public static void inferFromSample(List<Object> rows, DocumentMeta meta) {
        Map<String, Object> samples = new LinkedHashMap<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                if (value != null) {
                    samples.putIfAbsent(key, value);
                } else {
                    samples.putIfAbsent(key, null);
                }
            }
        }
        int order = 0;
        for (Map.Entry<String, Object> entry : samples.entrySet()) {
            ColumnMeta column = meta.column(entry.getKey());
            if (!column.isDefined(ColumnMeta.Attribute.NAME)) {
                column.name(JavabeanMetaResolver.humanize(entry.getKey()));
            }
            if (!column.isDefined(ColumnMeta.Attribute.TYPE)) {
                column.type(JavabeanMetaResolver.infer(
                        entry.getValue() == null ? Object.class : entry.getValue().getClass()));
            }
            if (!column.isDefined(ColumnMeta.Attribute.ORDER)) {
                column.order(order);
            }
            order++;
        }
    }

    /**
     * 获取解析器优先级：优先取实现类上的 {@code @Spi(order)}，否则取 {@link MetaResolver#order()}。
     *
     * @param resolver 解析器
     * @return 优先级
     */
    public static int priority(MetaResolver resolver) {
        Spi spi = resolver.getClass().getAnnotation(Spi.class);
        if (spi != null) {
            return spi.order();
        }
        return resolver.order();
    }
}