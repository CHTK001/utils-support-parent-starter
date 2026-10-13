package com.chua.common.support.export.spi;

import com.chua.common.support.export.ExportContext;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

/**
 * 文档生成器 SPI —— 接口一：<b>输入 List 数据 → 直接生成文档</b>。
 *
 * <p>不需要任何模板文件，由实现方依据 {@link ExportContext} 中的
 * {@code DocumentMeta} 与行数据从零构建文档。</p>
 *
 * <p>实现类通过 {@code META-INF/extensions/com.chua.common.support.export.spi.DocumentGenerator}
 * 注册。别名即"引擎名"（如 {@code poi}），也可用 {@link #formats()} 声明支持的格式
 * （如 {@code xlsx}）作为别名注册。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DocumentGenerator {

    /**
     * 本实现支持的格式标识集合，如 {@code ["xlsx", "xls"]}。
     *
     * @return 格式标识集合
     */
    Set<String> formats();

    /**
     * 生成文档并写入输出流。实现方<b>不得关闭</b>传入的输出流。
     *
     * @param context 导出上下文
     * @param out     输出流
     * @throws IOException 写出失败
     */
    void generate(ExportContext context, OutputStream out) throws IOException;

    /**
     * 引擎名，用于显式指定实现。
     *
     * @return 引擎名
     */
    default String engine() {
        return getClass().getSimpleName();
    }
}