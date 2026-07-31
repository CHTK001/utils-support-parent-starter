package com.chua.common.support.sync;

import com.chua.common.support.sync.executor.SinkExecutor;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 数据中心（缓冲池）
 * <p>连接输入端（Input）和输出端（Output）的数据缓冲层，
 * 支持批量写入、批量消费、位点追踪、死信投递和背压控制。</p>
 *
 * <p>通过 SPI（@Spi 注解或 META-INF/services）注册实现，
 * 由 ServiceProvider 按名称发现。</p>
 *
 * @author CH
 * @since 2026/07/28
 */
public interface Sink extends AutoCloseable {

    /**
     * 初始化数据中心
     * <p>在同步流启动前调用，用于建立连接、分配资源等。</p>
     */
    void initialize();

    /**
     * 批量接收数据
     * <p>由输入端调用，将读取到的数据写入缓冲。</p>
     *
     * @param contexts 同步上下文批次
     */
    void receiveBatch(List<SyncContext> contexts);

    /**
     * 批量消费数据
     * <p>由输出端调用，从缓冲中拉取待写出的数据。</p>
     *
     * @param batchSize 期望的批次大小
     * @return 同步上下文批次（可能为空列表，不会为 null）
     */
    List<SyncContext> consumeBatch(int batchSize);

    /**
     * 获取当前位点
     *
     * @return 当前同步位点，无数据时返回 null
     */
    Position getCurrentPosition();

    /**
     * 获取缓冲中的数据量
     *
     * @return 数据条数
     */
    int size();

    /**
     * 缓冲是否为空
     *
     * @return true 表示缓冲无数据
     */
    boolean isEmpty();

    /**
     * 获取数据流
     * <p>以响应式流的方式订阅缓冲中的数据。</p>
     *
     * @return 数据流
     */
    Flux<SyncContext> getFlux();

    /**
     * 投递到死信队列
     * <p>当输出端多次重试仍然失败时调用。</p>
     *
     * @param context 失败的数据
     * @param e       失败原因
     */
    void sendToDeadLetter(SyncContext context, Exception e);

    /**
     * 设置执行器
     * <p>由同步流注入，Sink 可通过它唤醒消费线程。</p>
     *
     * @param executor 数据中心执行器
     */
    void setExecutor(SinkExecutor executor);

    /**
     * 通知输入端
     * <p>缓冲有空闲空间时调用，通知输入端继续生产数据。</p>
     */
    void notifyInput();

    /**
     * 是否处于背压状态
     *
     * @return true 表示缓冲已满，输入端应暂停生产
     */
    boolean isBackpressure();

    /**
     * 关闭数据中心并释放资源
     */
    @Override
    void close();
}
