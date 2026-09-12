package com.chua.common.support.sync;

import java.util.List;

/**
* 同步输出端
* <p>负责将数据批量写入目标端（数据库、搜索引擎、文件等）。</p>
*
* <p>通过 SPI 注册实现，由 ServiceProvider 按名称创建，
* 实现类需提供 {@code (Map<String, Object> config)} 构造函数以接收节点配置。</p>
*
* @author CH
* @since 2026/07/28
 */
public interface Output extends AutoCloseable {

    /**
    * 初始化输出端
    * <p>在同步流启动前调用，用于建立连接、预编译语句等。</p>
     */
    default void initialize() {
    }

    /**
    * 获取输出端标识
    *
    * @return 输出端标识
     */
    default String getOutputId() {
        return getClass().getSimpleName();
    }

    /**
    * 批量写出数据
    *
    * @param contexts 同步上下文批次
    * @throws Exception 写出失败时抛出，由同步流决定重试或投递死信
     */
    void writeBatch(List<SyncContext> contexts) throws Exception;

    /**
    * 刷新缓冲
    * <p>同步结束前调用，确保所有数据落盘。</p>
     */
    default void flush() {
    }

    /**
    * 关闭输出端并释放资源
     */
    @Override
    default void close() {
    }
}
