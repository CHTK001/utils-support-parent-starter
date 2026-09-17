package com.chua.common.support.network.client;

import lombok.Getter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
* Multipart/form-data 请求体封装，支持文本字段和文件上传。
*
* <p>自动生成 boundary 分隔符，按照 <a href="https://tools.ietf.org/html/rfc1341">RFC 1341</a>
* 格式将多个表单字段和文件内容序列化为字节数组，适用于 HTTP multipart 请求。
*
* <p>通常不直接使用此类，而是通过 {@link HttpClientBuilder#formData(String, String)} 添加文本字段，
* 或通过 {@link HttpClientBuilder#formData(String, byte[], String, String)} 添加文件上传字段，
* 构建器会自动创建并序列化 {@code MultipartBody}。
*
* <p><b>序列化格式示例：</b>
* <pre>{@code
* ------FormBoundary1234567890
* Content-Disposition: form-data; name="field1"
* Content-Type: text/plain; charset=UTF-8
*
* value1
* ------FormBoundary1234567890
* Content-Disposition: form-data; name="file1"; filename="test.txt"
* Content-Type: text/plain
*
* file content
* ------FormBoundary1234567890--
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public class MultipartBody {

    /** Crlf */
    private static final byte[] CRLF = {'\r', '\n'};
    /** Dashes */
    private static final byte[] DASHES = {'-', '-'};

    /**
    * 文本字段的默认 Content-Type
    */
    private static final String DEFAULT_TEXT_CONTENT_TYPE = "text/plain; charset=UTF-8";

    /**
    * 所有表单部件（文本字段 + 文件）
    */
    private final List<Part> parts = new ArrayList<>();

    /**
    * 唯一的分隔符字符串
    * -- GETTER --
    *  获取当前使用的 boundary 分隔符。

    */
    @Getter
    /** Boundary */
    private final String boundary;

    /**
    * 创建空的 multipart 请求体，自动生成随机 boundary。
    */
    public MultipartBody() {
        this.boundary = "----FormBoundary" + Long.toHexString(System.nanoTime())
                + Long.toHexString(Thread.currentThread().getId());
    }

    /**
    * 获取完整的 Content-Type 头值（含 boundary）。
    *
    * <p>调用此方法获取的值应设置为 HTTP 请求的 Content-Type 头，
    * 例如 {@code "multipart/form-data; boundary=----FormBoundaryxxx"}。
    *
    * @return Content-Type 头值
    */
    public String getContentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    /**
    * 添加一个文本字段。
    *
    * <p>该字段会被序列化为 multipart 的一部分，Content-Type 为
    * {@code text/plain; charset=UTF-8}。
    *
    * @param name  字段名，不能为 null
    * @param value 字段值，为 null 时视为空字符串
    */
    public void addField(String name, String value) {
        byte[] content = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        parts.add(new Part(name, content, DEFAULT_TEXT_CONTENT_TYPE, null));
    }

    /**
    * 添加一个文件上传字段。
    *
    * <p>该字段会被序列化为 multipart 中带 {@code filename} 的文件部件，
    * 使用指定的 Content-Type 标识文件类型。
    *
    * @param name        表单字段名
    * @param content     文件内容的字节数组
    * @param contentType 文件的 MIME 类型，如 {@code "image/png"}、{@code "application/pdf"}
    * @param filename    上传的文件名，如 {@code "photo.png"}、{@code "report.pdf"}
    */
    public void addFile(String name, byte[] content, String contentType, String filename) {
        parts.add(new Part(name, content != null ? content : new byte[0],
                contentType != null ? contentType : "application/octet-stream", filename));
    }

    /**
    * 判断是否没有任何字段或文件。
    *
    * @return 空返回 true
    */
    public boolean isEmpty() {
        return parts.isEmpty();
    }

    /**
    * 将整个 multipart 请求体序列化为字节数组。
    *
    * <p>序列化格式遵循 RFC 1341：每个部件由 {@code --boundary} 分隔，
    * 末尾以 {@code --boundary--} 结束。每个部件包含 Content-Disposition 头、
    * Content-Type 头和内容体。
    *
    * @return 字节数组表示，空体返回空数组
    */
    public byte[] toBytes() {
        if (parts.isEmpty()) {
            return new byte[0];
        }

        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);

        for (Part part : parts) {
            write(out, DASHES);
            write(out, boundaryBytes);
            write(out, CRLF);

            write(out, "Content-Disposition: form-data; name=\"");
            write(out, part.name);
            out.write('"');

            if (part.filename != null) {
                write(out, "; filename=\"");
                write(out, part.filename);
                out.write('"');
            }
            write(out, CRLF);

            if (part.contentType != null) {
                write(out, "Content-Type: ");
                write(out, part.contentType);
                write(out, CRLF);
            }

            write(out, CRLF);
            write(out, part.content);
            write(out, CRLF);
        }

        write(out, DASHES);
        write(out, boundaryBytes);
        write(out, DASHES);
        write(out, CRLF);

        return out.toByteArray();
    }

    /** 写入 */
    private static void write(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }

    /** 写入 */
    private static void write(ByteArrayOutputStream out, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    // ==================== 内部部件定义 ====================

    /**
    * Multipart 中的单个部件，可以是文本字段或文件。
    */
    static class Part {

        /**
        * 表单字段名
        */
        final String name;

        /**
        * 内容字节数组
        */
        final byte[] content;

        /**
        * 内容的 MIME 类型，如 {@code text/plain}、{@code image/png}
        */
        final String contentType;

        /**
        * 文件名（仅文件部件有值，文本字段为 null）
        */
        final String filename;

        /**
        * 创建单个 multipart 部件。
        *
        * @param name        字段名
        * @param content     内容字节数组
        * @param contentType 内容类型
        * @param filename    文件名（文件上传时使用，文本字段传 null）
        */
        Part(String name, byte[] content, String contentType, String filename) {
            this.name = name;
            this.content = content;
            this.contentType = contentType;
            this.filename = filename;
        }
    }
}
