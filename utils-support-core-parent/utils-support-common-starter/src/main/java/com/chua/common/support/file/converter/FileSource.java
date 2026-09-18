package com.chua.common.support.file.converter;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;

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

    /**
    * 创建 FileSource 实例
    * @param path path
    */
    private FileSource(String path) {
        this(path, null, null, null, null);
    }

    /**
    * 创建 FileSource 实例
    * @param path path
    * @param InputStream InputStream
    * @param OutputStream OutputStream
    * @param String String
    * @param inputStream input流，不允许为 null
    * @param outputStream output流，不允许为 null
    * @param type 类型，不允许为 null
    */
    private FileSource(String path, InputStream inputStream, OutputStream outputStream, String type) {
        this(path, null, inputStream, outputStream, type);
    }

    /**
    * 创建 FileSource 实例
    * @param path path
    * @param URL URL
    * @param InputStream InputStream
    * @param OutputStream OutputStream
    * @param String String
    * @param url URL，不允许为 null
    * @param inputStream input流，不允许为 null
    * @param outputStream output流，不允许为 null
    * @param type 类型，不允许为 null
    */
    private FileSource(String path, URL url, InputStream inputStream, OutputStream outputStream, String type) {
        this.path = path;
        this.url = url;
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.type = type;
    }

    /**
    * 通过文件路径创建输入源
    * @param path 路径，不允许为 null
    * @return 文件来源 对象
    */
    public static FileSource of(String path) {
        return new FileSource(path);
    }

    /**
    * 通过 URL 创建输入源（支持 http://、https://、file:// 等协议）
    *
    * @param url  源地址
    * @param type 文件格式类型（用于 SPI 查找转换器，当无法从 URL 推断时使用）
    * @return 文件来源 对象
    */
    public static FileSource of(URL url, String type) {
        return new FileSource(null, url, null, null, type);
    }

    /**
    * 通过 URL 创建输入源（自动从 URL 路径推断文件类型）
    *
    * @param url 源地址
    * @return 文件来源 对象
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
    * @param inputStream input流，不允许为 null
    * @param type 类型，不允许为 null
    * @return 文件来源 对象
    */
    public static FileSource of(InputStream inputStream, String type) {
        return new FileSource(null, null, inputStream, null, type);
    }

    /**
    * 通过输出流 + 文件类型创建输出目标（类型用于 SPI 查找对应的转换器实现）
    * @param outputStream output流，不允许为 null
    * @param type 类型，不允许为 null
    * @return 文件来源 对象
    */
    public static FileSource of(OutputStream outputStream, String type) {
        return new FileSource(null, null, null, outputStream, type);
    }

    /**
     * 是否Path
     * @return 是否成功（true 表示成功）
     */
    public boolean isPath() {
        return path != null;
    }

    /**
     * 是否Url
     * @return 是否成功（true 表示成功）
     */
    public boolean isUrl() {
        return url != null;
    }

    /**
     * 是否InputStream
     * @return 是否成功（true 表示成功）
     */
    public boolean isInputStream() {
        return inputStream != null;
    }

    /**
     * 是否OutputStream
     * @return 是否成功（true 表示成功）
     */
    public boolean isOutputStream() {
        return outputStream != null;
    }

    /**
     * 获取Path
     * @return 结果字符串
     */
    public String getPath() {
        return path;
    }

    /**
     * 获取Url
     * @return URL 对象
     */
    public URL getUrl() {
        return url;
    }

    /**
     * 获取InputStream
     * @return Input流 对象
     */
    public InputStream getInputStream() {
        return inputStream;
    }

    /**
     * 获取OutputStream
     * @return Output流 对象
     */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * 获取Type
     * @return 结果字符串
     */
    public String getType() {
        return type;
    }
}
