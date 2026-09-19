package com.chua.common.support.storage.request;

import com.chua.common.support.storage.metadata.Metadata;
import lombok.Builder;
import lombok.Data;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static com.chua.common.support.utils.UrlUtils.*;

/**
 * 上传文件请求对象。
 *
 * <p>包含上传文件所需的全部信息：文件内容、文件名、路径、元数据等。</p>
 *
 * <p>支持三种输入方式：</p>
 * <ul>
 *   <li>{@code content} — 直接传入字节数组</li>
 *   <li>{@code file} — 传入本地文件，运行时自动读取为字节数组</li>
 *   <li>{@code inputStream} — 传入输入流，运行时自动读取为字节数组</li>
 * </ul>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * // 方式1：字节数组
 * PutObjectRequest req1 = PutObjectRequest.builder()
 *     .fileName("hello.txt")
 *     .content("Hello".getBytes())
 *     .build();
 *
 * // 方式2：本地文件（自动读取内容和文件名）
 * PutObjectRequest req2 = PutObjectRequest.builder()
 *     .file(new File("/path/to/report.pdf"))
 *     .build();
 *
 * // 方式3：输入流
 * PutObjectRequest req3 = PutObjectRequest.builder()
 *     .fileName("data.csv")
 *     .inputStream(inputStream)
 *     .build();
 * }</pre>ctRequest req3 = PutObjectRequest.builder()
 *     .fileName("data.csv")
 *     .inputStream(inputStream)
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 1.0
 */
@Data
@Builder
public class PutObjectRequest {

    /**
     * 文件内容（字节数组）。
     */
    private byte[] content;

    /**
     * 本地文件（运行时自动读取为字节数组）。
     */
    private File file;

    /**
     * 输入流（运行时自动读取为字节数组）。
     */
    private InputStream inputStream;

    /**
     * 文件名（含扩展名，如 "report.pdf"）。
     * <p>当使用 {@link #file} 时，若未设置则自动取文件名。</p>
     */
    private String fileName;

    /**
     * 文件路径（不含文件名，如 "/docs/2024/"）。
     */
    private String filePath;

    /**
     * 文件元数据（如 内容-类型、内容-长度 等）。
     */
    private Metadata metadata;

    /**
     * 获取文件内容的字节数组。
     *
     * <p>按优先级读取：content → file → inputStream</p>
     *
     * @return 文件内容
     * @throws IOException 读取失败时抛出
     */
    public byte[] getContentBytes() throws IOException {
        if (content != null) {
            return content;
        }
        if (file != null) {
            if (fileName == null) {
                fileName = file.getName();
            }
            try (var fis = new java.io.FileInputStream(file)) {
                return fis.readAllBytes();
            }
        }
        if (inputStream != null) {
            return inputStream.readAllBytes();
        }
        return null;
    }

    /**
     * 获取文件名。
     *
     * <p>若显式设置了 fileName 则返回它，否则尝试从 file 中获取。</p>
     *
     * @return 文件名
     */
    public String getFileName() {
        if (fileName != null) {
            return fileName;
        }
        if (file != null) {
            return file.getName();
        }
        return null;
    }

    /**
     * 获取完整的对象 键。
     *
     * <p>自动规范化：去除多余斜杠、反斜杠，防止路径穿越（../）。</p>
     *
     * @return 规范化后的 键（路径 + 文件名）
     */
    public String getKey() {
        String name = getFileName();
        if (name == null) {
            return null;
        }
        if (filePath == null || filePath.isEmpty()) {
            return normalize(name);
        }
        return normalize(filePath + "/" + name);
    }

    /**
     * 从本地文件创建上传请求（自动读取内容和文件名）。
     *
     * @param file 本地文件
     * @return 上传请求
     */
    public static PutObjectRequest of(File file) {
        return PutObjectRequest.builder()
                .fileName(file.getName())
                .content(readBytes(file))
                .build();
    }

    /**
     * 从本地文件创建上传请求，指定远程路径。
     *
     * @param file     本地文件
     * @param filePath 远程路径
     * @return 上传请求
     */
    public static PutObjectRequest of(File file, String filePath) {
        return PutObjectRequest.builder()
                .fileName(file.getName())
                .filePath(filePath)
                .content(readBytes(file))
                .build();
    }

    /**
     * 从输入流创建上传请求。
     *
     * @param inputStream 文件输入流
     * @param fileName    文件名
     * @return 上传请求
     */
    public static PutObjectRequest of(InputStream inputStream, String fileName) {
        try {
            return PutObjectRequest.builder()
                    .fileName(fileName)
                    .content(inputStream.readAllBytes())
                    .build();
        } catch (IOException e) {
            throw new RuntimeException("读取流失败: " + fileName, e);
        }
    }

    /**
     * 读取Bytes
     *
     * @param file 文件
     * @return 读取bytes的结果
     */
    private static byte[] readBytes(File file) {
        try (var fis = new java.io.FileInputStream(file)) {
            return fis.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("文件读取失败: " + file.getName(), e);
        }
    }
}
