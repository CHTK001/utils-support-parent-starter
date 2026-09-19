package com.chua.common.support.file.system;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文件读取结果，封装解析后的数据集合。
 *
 * <p>包含多条 {@link FileSystemContext} 数据，
 * 提供添加、遍历、判空、计数等操作方法。</p>
 *
 * @param <T> 数据类型
 * @author CH
 * @since 1.0.0
 */
public class ReadResult<T> {

    /**
     * 解析后的数据上下文列表
    */
    private final List<FileSystemContext<?>> contexts = new ArrayList<>();

    /**
     * 创建 ReadResult 实例
    */
    public ReadResult() {
    }

    /**
     * 构造包含指定数据的读取结果。
     *
     * @param contexts 数据上下文列表
     */
    public ReadResult(List<FileSystemContext<?>> contexts) {
        if (contexts != null) {
            this.contexts.addAll(contexts);
        }
    }

    /**
     * 获取全部数据上下文（只读）。
     *
     * @return 数据上下文列表
     */
    public List<FileSystemContext<?>> getContexts() {
        return Collections.unmodifiableList(contexts);
    }

    /**
     * 添加一条数据上下文。
     *
     * @param context 数据上下文
     */
    public void addContext(FileSystemContext<?> context) {
        contexts.add(context);
    }

    /**
     * 判断结果集是否为空。
     *
     * @return 是否为空
     */
    public boolean isEmpty() {
        return contexts.isEmpty();
    }

    /**
     * 获取结果集大小。
     *
     * @return 数据条数
     */
    public int size() {
        return contexts.size();
    }
}
