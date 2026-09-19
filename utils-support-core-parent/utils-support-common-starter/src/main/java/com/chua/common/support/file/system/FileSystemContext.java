package com.chua.common.support.file.system;

import java.util.Map;

/**
 * 文件系统数据上下文，封装文件解析后单行或单条数据的 Map 表示。
 *
 * <p>用于文件读取结果中存储每一行的字段名到字段值的映射关系，
 * 方便后续的数据访问和处理。</p>
 *
 * @param <T> 数据类型
 * @author CH
 * @since 1.0.0
 */
public class FileSystemContext<T> {

    /**
     * 字段名到字段值的映射数据
    */
    private Map<String, Object> data;

    /**
     * 创建 FileSystemContext 实例
    */
    public FileSystemContext() {
    }

    /**
     * 构造包含指定数据的上下文。
     *
     * @param data 字段映射数据
     */
    public FileSystemContext(Map<String, Object> data) {
        this.data = data;
    }

    /**
     * 获取全部字段映射。
     *
     * @return 字段名到值的 Map
     */
    public Map<String, Object> toMap() {
        return data;
    }

    /**
     * 设置字段映射。
     *
     * @param data 字段名到值的 Map
     */
    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    /**
     * 按字段名获取值。
     *
     * @param key 字段名
     * @param <R> 值类型
     * @return 字段值
     */
    @SuppressWarnings("unchecked")
    public <R> R get(String key) {
        if (data != null) {
            return (R) data.get(key);
        }
        return null;
    }
}
