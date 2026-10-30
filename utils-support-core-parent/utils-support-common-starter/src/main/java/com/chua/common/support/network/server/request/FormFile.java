package com.chua.common.support.network.server.request;


/**
 * 上传文件描述，包含字段名、文件名、内容类型和文件数据。
 *
 * @author CH
 * @since 2026/07/17
 */
public class FormFile {

    /**
     * 表单字段名
     */
    private final String fieldName;

    /**
     * 原始文件名
     */
    private final String fileName;

    /**
     * 文件内容类型
     */
    private final String contentType;

    /**
     * 文件字节数据
     */
    private final byte[] data;

    /**
     * 创建 FormFile 实例
     * @param fieldName fieldName
     * @param data data
     * @param fileName 文件名称，不允许为 null
     * @param contentType 内容类型，不允许为 null
     */
    public FormFile(String fieldName, String fileName, String contentType, byte[] data) {
        this.fieldName = fieldName;
        this.fileName = fileName;
        this.contentType = contentType;
        this.data = data;
    }

    /**
     * 获取FieldName
     * @return 返回值
    */
    public String getFieldName() {
        return fieldName;
    }
    /**
     * 获取FileName
     * @return 返回值
    */
    public String getFileName() {
        return fileName;
    }
    /**
     * 获取ContentType
     * @return 返回值
    */
    public String getContentType() {
        return contentType;
    }
    /**
     * 获取Data
     * @return 返回值
    */
    public byte[] getData() {
        return data;
    }
    /**
     * 获取获取大小
     * @return 返回值
    */
    public long getSize() {
        return data != null ? data.length : 0;
    }
}
