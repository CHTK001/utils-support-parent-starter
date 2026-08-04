package com.chua.common.support.file.converter;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import org.jspecify.annotations.NullUnmarked;

/**
 * 文件资源定位描述符，统一抽象文件转换过程中的输入源和输出目标。
 *
 * <p>文件转换涉及读取源文件和写入目标文件两个端点，每个端点可以是：
 * <ul>
 *   <li><b>文件路径</b> — {@link #of(String)}，直接指定文件系统路径</li>
 *   <li><b>URL</b> — {@link #of(URL)}，从网络地址读取数据（如 http://、https://、file://）</li>
 *   <li><b>输入流</b> — {@link #of(InputStream, String)}，从内存流读取数据（需指明文件类型）</li>
 *   <li><b>输出流</b> — {@link #of(OutputStream, String)}，写入到内存流（需指明文件类型）</li>
 * </ul>
 * </p>
 *
 * <p>{@code type} 参数标识文件的格式类型（如 {@code "csv"}、{@code "xlsx"}、{@code "pdf"}），
 * 用于 SPI 查找对应的 {@link FileConverter} 实现。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 路径方式（最常用）
 * FileSource.from("input.csv");
 *
 * // URL 方式（支持网络资源）
 * FileSource.from(new URL("https://example.com/data.json"));
 * FileSource.from(new URL("file:///tmp/input.pdf"), "pdf");
 *
 * // 流方式（适用于网络传输或内存处理）
 * FileSource.from(inputStream, "json");
 * FileSource.to(outputStream, "xlsx");
 * }</pre>
 *
 * @author CH
 * @since 1.0.0
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class FileSource {

    /**
     * 文件系统路径（当通过路径创建时非空）
     */
    private final String path;

    /**
     * 网络地址（当通过 URL 创建时非空）
     */
    private final URL url;

    /**
     * 输入数据流（当作为输入源时非空）
     */
    private final InputStream inputStream;

    /**
     * 输出数据流（当作为输出目标时非空）
     */
    private final OutputStream outputStream;

    /**
     * 文件格式类型标识（如 csv, xlsx, pdf, json）
     */
    private final String type;

    private FileSource(String path) {
        this(path, null, null, null, null);
    }

    private FileSource(String path, InputStream inputStream, OutputStream outputStream, String type) {
        this(path, null, inputStream, outputStream, type);
    }

    private FileSource(String path, URL url, InputStream inputStream, OutputStream outputStream, String type) {
        this.path = path;
        this.url = url;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.type = type;
    }

    /**
     * 通过文件路径创建输入源
     */
    public static FileSource of(String path) {
        return new FileSource(path);
    }

    /**
     * 通过 URL 创建输入源（支持 http://、https://、file:// 等协议）
     *
     * @param url  源地址
     * @param type 文件格式类型（用于 SPI 查找转换器，当无法从 URL 推断时使用）
     */
    public static FileSource of(URL url, String type) {
        return new FileSource(null, url, null, null, type);
    }

    /**
     * 通过 URL 创建输入源（自动从 URL 路径推断文件类型）
     *
     * @param url 源地址
     */
    public static FileSource of(URL url) {
        String path = url.getPath();
        String ext = "";
        if (path != null && path.contains(".")) {
            ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
        }
        return new FileSource(null, url, null, null, ext);
    }

    /**
     * 通过输入流 + 文件类型创建输入源（类型用于 SPI 查找对应的转换器实现）
     */
    public static FileSource of(InputStream inputStream, String type) {
        return new FileSource(null, null, inputStream, null, type);
    }

    /**
     * 通过输出流 + 文件类型创建输出目标（类型用于 SPI 查找对应的转换器实现）
     */
    public static FileSource of(OutputStream outputStream, String type) {
        return new FileSource(null, null, null, outputStream, type);
    }

    public boolean isPath() {
        return path != null;
    }

    public boolean isUrl() {
        return url != null;
    }

    public boolean isInputStream() {
        return inputStream != null;
    }

    public boolean isOutputStream() {
        return outputStream != null;
    }

    public String getPath() {
        return path;
    }

    public URL getUrl() {
        return url;
    }

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public String getType() {
        return type;
    }
}
