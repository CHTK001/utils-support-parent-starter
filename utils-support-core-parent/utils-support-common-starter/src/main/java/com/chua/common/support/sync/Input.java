package com.chua.common.support.sync;

import java.util.List;

/**
 * 同步输入端
 * <p>负责从数据源（数据库、文件、消息队列等）批量读取数据。</p>
 *
 * <p>通过 SPI 注册实现，由 ServiceProvider 按名称创建，
 * 实现类需提供 {@code (Map<String, Object> config)} 构造函数以接收节点配置。</p>
 *
 * <p>读取模式：同步流循环调用 {@link #readBatch(int)}，
 * 直到 {@link #hasNext()} 返回 false 表示读取完成。</p>
 *
 * @author CH
 * @since 2026/07/28
*/
public interface Input extends AutoCloseable {

    /**
    * 初始化输入端
    * <p>在同步流启动前调用，用于建立连接、打开游标等。</p>
    */
    default void initialize() {
    }

    /**
    * 获取输入端标识
    *
    * @return 输入端标识，用于位点归属
    */
    default String getInputId() {
        return getClass().getSimpleName();
    }

    /**
    * 是否还有数据可读
    *
    * @return true 表示数据未读完
    */
    boolean hasNext();

    /**
    * 批量读取数据
    *
    * @param batchSize 期望的批次大小
    * @return 同步上下文批次（可能为空列表，不会为 空）
    */
    List<SyncContext> readBatch(int batchSize);

    /**
    * 定位到指定位点
    * <p>用于断点续传，从上次同步的位置继续读取。</p>
    *
    * @param position 目标位点
    */
    default void seek(Position position) {
    }

    /**
    * 关闭输入端并释放资源
    */
    @Override
    default void close() {
    }
}
