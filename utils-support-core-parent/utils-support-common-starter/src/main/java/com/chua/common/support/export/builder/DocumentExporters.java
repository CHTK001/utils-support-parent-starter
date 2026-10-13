package com.chua.common.support.export.builder;

import com.chua.common.support.export.meta.ColumnMeta;
import com.chua.common.support.export.meta.DocumentMeta;
import com.chua.common.support.export.resolver.MetaResolvers;
import com.chua.common.support.export.support.Rows;

import java.util.List;
import java.util.Set;

/**
 * 构建器共享逻辑。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class DocumentExporters {

    private DocumentExporters() {
    }

    /**
     * 解析元数据并应用构建器覆写。
     *
     * <p>顺序：解析器链 → 构建器元数据回调 → 列包含 / 排除限定。</p>
     *
     * @param builder 数据构建器
     * @return 文档元数据
     */
    public static DocumentMeta buildMeta(DataExportBuilder builder) {
        List<Object> rows = builder.rows();
        Class<?> type = builder.type() != null ? builder.type() : Rows.typeOf(rows);
        DocumentMeta meta = MetaResolvers.resolve(type);
        if (meta.allColumns().isEmpty() && !rows.isEmpty()) {
            // 反射链无产出（如 Map 行），由样本补全列
            MetaResolvers.inferFromSample(rows, meta);
        }

        for (var tweak : builder.metaTweaks()) {
            tweak.accept(meta);
        }

        Set<String> includes = builder.includes();
        if (!includes.isEmpty()) {
            for (ColumnMeta column : meta.allColumns()) {
                if (!includes.contains(column.getProperty())) {
                    column.ignore(true);
                }
            }
            int order = 0;
            for (String property : includes) {
                meta.column(property).ignore(false).order(order++);
            }
        }
        builder.excludes().forEach(property -> meta.column(property).ignore(true));
        return meta;
    }
}