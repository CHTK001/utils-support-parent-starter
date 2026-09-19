package com.chua.multipart.support;

import com.chua.common.support.network.server.request.FormFile;
import com.chua.common.support.network.server.request.MultipartParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileItemInput;
import org.apache.commons.fileupload2.core.FileItemInputIterator;
import org.apache.commons.fileupload2.core.RequestContext;
import org.apache.commons.fileupload2.jakarta.JakartaServletFileUpload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 Apache Commons 文件upload2 的 multipart/form-数据 解析器。
 *
 * @author CH
 * @since 2026/07/17
 */
@Spi("fileupload")
@SpiDescribe("Apache Commons FileUpload2 解析器")
public class ApacheMultipartParser implements MultipartParser {

    @Override
    /** 支持 */
    public boolean support(String contentType) {
        return contentType != null && contentType.toLowerCase().startsWith("multipart/form-data");
    }

    @Override
    /** 解析 */
    public List<FormFile> parse(byte[] body, String contentType) {
        if (body == null || body.length == 0 || contentType == null) {
            return List.of();
        }
        List<FormFile> files = new ArrayList<>();
        try {
            DiskFileItemFactory factory = DiskFileItemFactory.builder()
                    .setPath(new java.io.File(System.getProperty("java.io.tmpdir")).toPath())
                    .setCharset(StandardCharsets.UTF_8)
                    .get();
            JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory> upload =
                    new JakartaServletFileUpload<>(factory);
            RequestContext ctx = new RequestContext() {
                @Override
                /** 获取内容类型 */
                public String getContentType() { return contentType; }
                @Override
                /** 获取character编码 */
                public String getCharacterEncoding() { return StandardCharsets.UTF_8.name(); }
                @Override
                /** 获取内容获取长度 */
                public long getContentLength() { return body.length; }
                @Override
                /** 获取输入流 */
                public InputStream getInputStream() { return new ByteArrayInputStream(body); }
            };
            FileItemInputIterator iter = upload.getItemIterator(ctx);
            while (iter.hasNext()) {
                FileItemInput item = iter.next();
                if (item.isFormField()) {
                    continue;
                }
                String fieldName = item.getFieldName();
                String fileName = item.getName();
                String fileContentType = item.getContentType();
                byte[] data = readAllBytes(item.getInputStream());
                files.add(new FormFile(fieldName, fileName, fileContentType, data));
            }
        } catch (IOException e) {
            // 解析失败返回空列表
        }
        return files;
    }

    @Override
    /** 解析form字段 */
    public Map<String, String> parseFormFields(byte[] body, String contentType) {
        if (body == null || body.length == 0 || contentType == null) {
            return Map.of();
        }
        Map<String, String> fields = new LinkedHashMap<>();
        try {
            DiskFileItemFactory factory = DiskFileItemFactory.builder()
                    .setPath(new java.io.File(System.getProperty("java.io.tmpdir")).toPath())
                    .setCharset(StandardCharsets.UTF_8)
                    .get();
            JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory> upload =
                    new JakartaServletFileUpload<>(factory);
            RequestContext ctx = new RequestContext() {
                @Override
                /** 获取内容类型 */
                public String getContentType() { return contentType; }
                @Override
                /** 获取character编码 */
                public String getCharacterEncoding() { return StandardCharsets.UTF_8.name(); }
                @Override
                /** 获取内容获取长度 */
                public long getContentLength() { return body.length; }
                @Override
                /** 获取输入流 */
                public InputStream getInputStream() { return new ByteArrayInputStream(body); }
            };
            FileItemInputIterator iter = upload.getItemIterator(ctx);
            while (iter.hasNext()) {
                FileItemInput item = iter.next();
                if (!item.isFormField()) {
                    continue;
                }
                fields.put(item.getFieldName(),
                        new String(readAllBytes(item.getInputStream()), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // 解析失败返回空
        }
        return fields;
    }

    /**
     * 读取全部bytes
     *
     * @param in 入
     * @return 读取全部bytes的结果
     */
    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }
}
