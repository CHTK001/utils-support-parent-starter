package com.chua.common.support.export.spi;

import com.chua.common.support.export.meta.ColumnMeta;

/**
 * 值转换器 SPI —— 把单元格原始值转换为可写入的展示值。
 *
 * <p>注册方式：实现类上加 {@code @Spi("money")}，并在
 * {@code META-INF/extensions/com.chua.common.support.export.spi.ValueConverter}
 * 中登记 {@code money=全限定类名}。也可在 {@code @ExportColumn(converter = "...")}
 * 中直接写全限定类名，由 {@code Converters} 反射实例化。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ValueConverter {

    /**
     * 转换器名称。
     *
     * @return 名称
     */
    String name();

    /**
     * 转换单个值。
     *
     * @param value  原始值，可为 {@code null}
     * @param column 列元数据
     * @param row    整行对象
     * @return 转换后的值
     */
    Object convert(Object value, ColumnMeta column, Object row);

    /**
     * 判断是否支持该列。
     *
     * @param column 列元数据
     * @return 是否支持
     */
    default boolean supports(ColumnMeta column) {
        return true;
    }
}