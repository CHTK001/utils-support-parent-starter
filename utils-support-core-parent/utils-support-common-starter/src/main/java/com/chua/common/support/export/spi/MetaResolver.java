package com.chua.common.support.export.spi;

import com.chua.common.support.export.meta.DocumentMeta;

/**
 * 元数据解析器 SPI —— 把数据行类型解析为 {@link DocumentMeta}。
 *
 * <p>多个解析器按 {@code @Spi(order)} <b>升序</b>依次作用于同一份
 * {@link DocumentMeta}，后解析者覆盖先解析者已声明的属性。内置分层：</p>
 *
 * <table border="1">
 *     <caption>内置解析器分层</caption>
 *     <tr><th>order</th><th>解析器</th><th>作用</th></tr>
 *     <tr><td>{@code -100}</td><td>{@code JavabeanMetaResolver}</td><td>反射字段推断兜底</td></tr>
 *     <tr><td>{@code 0}</td><td>{@code ThirdPartyMetaResolver}</td><td>读取三方注解（EasyExcel / FastExcel）</td></tr>
 *     <tr><td>{@code 100}</td><td>{@code ExportAnnotationMetaResolver}</td><td>读取 {@code @ExportColumn} / {@code @ExportSheet}，最终覆盖</td></tr>
 * </table>
 *
 * <p>三方注解以反射方式探测，<b>不引入任何编译期依赖</b>；未引入对应库时
 * {@link #supports(Class)} 返回 {@code false}，自动跳过。</p>
 *
 * <p>实现类通过 {@code META-INF/extensions/com.chua.common.support.export.spi.MetaResolver}
 * 注册，格式为 {@code 别名=全限定类名}，别名可省略（取类名）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MetaResolver {

    /**
     * 判断是否支持给定的数据行类型。
     *
     * @param type 数据行类型
     * @return 是否支持
     */
    boolean supports(Class<?> type);

    /**
     * 解析元数据，结果就地写入 {@code meta}。
     *
     * <p>实现只应写入"自己确实能确定的"属性，未确定的属性保持原值，
     * 以便更高优先级的解析器覆盖。</p>
     *
     * @param type 数据行类型
     * @param meta 元数据容器
     */
    void resolve(Class<?> type, DocumentMeta meta);

    /**
     * 解析器优先级，等价于 {@code @Spi(order)}。默认 0。
     *
     * @return 优先级，数值越大越后执行（优先级越高）
     */
    default int order() {
        return 0;
    }

    /**
     * 解析器名称。
     *
     * @return 名称
     */
    default String name() {
        return getClass().getSimpleName();
    }
}