package com.chua.common.support.image.compression;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * 图片压缩 SPI 接口。
 *
 * <p>按<b>引擎名</b>注册（而非按格式名），一个引擎内部再按输入格式分派到不同的压缩管线。
 * 实现类通过 {@code META-INF/extensions/com.chua.common.support.image.compression.ImageCompressor}
 * 注册，格式为 {@code 别名=实现类全限定名}。
 *
 * <h3>两类参考实现</h3>
 * <ul>
 *   <li>{@code tinypng} — 有损量化优先：PNG 降色到 8-bit 索引色调色板后再做 deflate 优化</li>
 *   <li>{@code zipic} — 多格式重编码优先：按压缩等级 1~6 对任意格式做整图重编码</li>
 * </ul>
 *
 * <h3>可替换的原生钩子</h3>
 * <p>deflate 与调色板量化各自抽出为独立 SPI：
 * {@link DeflateEngine} 与 {@link PaletteQuantizer}。默认走纯 Java 实现；
 * 后续接入 {@code libdeflate} / {@code libimagequant} 时只需新增实现类并注册，
 * 压缩引擎本身无需改动。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ImageCompressor compressor = ServiceProvider.of(ImageCompressor.class)
 *         .getNewExtension("tinypng");
 * CompressResult result = compressor.compress(source, CompressOptions.of("png"));
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.43
 * @see CompressOptions
 * @see CompressResult
 * @see DeflateEngine
 * @see PaletteQuantizer
 */
public interface ImageCompressor {

    /**
     * 压缩图片字节流。
     *
     * <p>实现方必须遵守以下约定：
     * <ul>
     *   <li>当压缩结果体积<b>不小于</b>原始体积且 {@code CompressOptions#isDiscardWhenLarger()} 为
     *       {@code true} 时，直接返回原始字节，不做无意义的放大</li>
     *   <li>不得修改传入的 {@code source} 数组</li>
     *   <li>压缩失败时抛出异常，由调用方决定是否回退</li>
     * </ul>
     *
     * @param source  原始图片字节，不得为 {@code null}
     * @param options 压缩参数，不得为 {@code null}
     * @return 压缩结果，不得为 {@code null}
     * @throws Exception 当源数据无法识别、格式不支持或压缩过程失败时
     */
    CompressResult compress(byte[] source, CompressOptions options) throws Exception;

    /**
     * 压缩图片输入流。
     *
     * @param input  输入流，由调用方负责关闭
     * @param output 输出流，由调用方负责关闭
     * @param options 压缩参数，不得为 {@code null}
     * @return 压缩结果，不得为 {@code null}
     * @throws Exception 当读取、压缩或写出过程失败时
     */
    CompressResult compress(InputStream input, OutputStream output, CompressOptions options) throws Exception;

    /**
     * 判断本引擎是否支持指定的输入格式。
     *
     * @param format 图片格式名（如 {@code png}、{@code jpeg}），忽略大小写
     * @return 支持返回 {@code true}，否则返回 {@code false}
     */
    boolean supports(String format);

    /**
     * 获取本引擎支持的全部输入格式名称集合（小写）。
     *
     * @return 格式名称集合，不会返回 {@code null}
     */
    Set<String> supportedFormats();

    /**
     * 获取引擎名称。
     *
     * @return 引擎名称，如 {@code tinypng}、{@code zipic}
     */
    String name();
}