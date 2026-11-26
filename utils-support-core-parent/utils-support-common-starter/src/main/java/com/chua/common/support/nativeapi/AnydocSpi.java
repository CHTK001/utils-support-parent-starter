package com.chua.common.support.nativeapi;

import java.nio.file.Path;

/**
 * anydoc 文档解析原生库 SPI。
 *
 * <p>由 {@code utils-support-native-anydoc} 提供实现：底层是 Rust cdylib（基于 firecrawl
 * {@code anydoc}），把 doc/docx/odt/pdf/ppt/pptx/rtf/epub/xlsx/xlsm/xlsb/xls/ods/odp/csv
 * 等 14 种文档解析为 GitHub-Flavored Markdown。纯文档解析：无 OCR、无模型推理，
 * 扫描件 PDF 返回解析失败而不是回退到图像识别。</p>
 *
 * <p>实现方负责原生库加载与内存管理，调用方只接触 Java {@code String}；原生制品缺失时
 * {@link NativeServices#get(Class)} 返回 {@code null}，调用方按能力不可用降级。</p>
 *
 * @author CH
 * @since 4.0.0.51
 */
public interface AnydocSpi extends NativeService {

    /**
     * 加载原生库。
     *
     * <p>幂等：重复调用只做一次实际加载。失败不抛异常，由 {@link #isLoaded()} 与
     * {@link #getLoadError()} 判断。</p>
     */
    void load();

    /**
     * 原生库是否已加载成功。
     *
     * <p>只读取加载结果，不触发加载。</p>
     *
     * @return 已加载返回 {@code true}
     */
    boolean isLoaded();

    @Override
    default boolean available() {
        if (isLoaded()) {
            return true;
        }
        load();
        return isLoaded();
    }

    /**
     * 原生库加载失败原因。
     *
     * @return 失败异常；加载成功返回 {@code null}
     */
    Throwable getLoadError();

    /**
     * 原生库与封装层版本信息。
     *
     * @return 形如 {@code anydoc-ffi 0.1.0 (anydoc 0.2.5)} 的版本串；未加载返回空串
     */
    String version();

    /**
     * 把一个文档文件转换为 Markdown。
     *
     * <p>格式按内容签名探测，文件扩展名兜底；无内容签名的格式（如 CSV）依赖扩展名识别。</p>
     *
     * @param file 文档路径
     * @return GitHub-Flavored Markdown 文本
     * @throws IllegalStateException 原生库不可用、路径不存在或解析失败时；异常消息携带原生侧真实错误
     */
    String convertFile(Path file);

    /**
     * 把内存中的文档字节转换为 Markdown。
     *
     * @param data   文档原始字节
     * @param format 格式扩展名（如 {@code "csv"}）；传 {@code null} 表示按内容签名自动探测，
     *               CSV 等无签名格式必须显式命名
     * @return GitHub-Flavored Markdown 文本
     * @throws IllegalStateException 原生库不可用或解析失败时；异常消息携带原生侧真实错误
     */
    String convertBytes(byte[] data, String format);

    /**
     * 当前线程最近一次原生侧错误信息。
     *
     * @return 错误信息；无错误或原生库不可用时返回 {@code null}，下一次原生调用前有效
     */
    String lastError();
}