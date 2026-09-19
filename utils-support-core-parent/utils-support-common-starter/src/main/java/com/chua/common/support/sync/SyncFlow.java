package com.chua.common.support.sync;

import com.chua.common.support.sync.executor.SinkExecutor;
import com.chua.common.support.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 同步流
 * <p>数据同步管道的编排器，连接输入端（Input）、数据中心（Sink）和输出端（Output），
 * 完成"读取 → 缓冲 → 写出"的完整数据同步流程。</p>
 *
 * <p>执行模型：</p>
 * <ol>
 *   <li>每个 Input 分配一个生产线程，循环读取批次并写入 Sink</li>
 *   <li>当前线程作为消费循环，从 Sink 批量拉取并写出到所有 Output</li>
 *   <li>写出失败按 retryCount 重试，仍失败则逐条投递死信</li>
 *   <li>所有 Input 读完且 Sink 清空后，{@link #start()} 返回</li>
 * </ol>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * SyncFlow flow = SyncFlow.builder("my-sync")
 *         .addInput(input)
 *         .addOutput(output)
 *         .sink(new InMemorySink(1000))
 *         .batchSize(100)
 *         .build();
 * flow.start();
 * }</pre>0))
 *         .batchSize(100)
 *         .build();
 * flow.start();
 * }</pre>
 *
 * @author CH
 * @since 2026/07/28
 */
@Slf4j
public class SyncFlow implements AutoCloseable {

    /**
     * 同步流名称
     */
    private final String name;

    /**
     * 输入端列表
     */
    private final List<Input> inputs;

    /**
     * 输出端列表
     */
    private final List<Output> outputs;

    /**
     * 数据中心
     */
    private final Sink sink;

    /**
     * 批处理大小
     */
    private final int batchSize;

    /**
     * 写出失败重试次数
     */
    private final int retryCount;

    /**
     * 重试间隔（毫秒）
     */
    private final long retryInterval;

    /**
     * 运行状态标记
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 已同步数据条数
     */
    private final AtomicLong syncedCount = new AtomicLong(0);

    /**
     * 生产线程池
     */
    private ExecutorService producerExecutor;

    /**
     * 创建 同步流 实例
     * @param builder 构建器
     */
    private SyncFlow(Builder builder) {
        this.name = builder.name;
        this.inputs = builder.inputs;
        this.outputs = builder.outputs;
        this.sink = builder.sink;
        this.batchSize = builder.batchSize;
        this.retryCount = builder.retryCount;
        this.retryInterval = builder.retryInterval;
    }

    /**
     * 创建构建器
     *
     * @param name 同步流名称
     * @return 构建器实例
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * 启动同步流（阻塞直到同步完成或被停止）
     * <p>依次初始化 Sink、Input、Output，然后启动生产线程和消费循环。</p>
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.warn("同步流 [{}] 已在运行中, 忽略重复启动", name);
            return;
        }

        if (inputs.isEmpty()) {
            running.set(false);
            throw new IllegalStateException("同步流 [" + name + "] 未配置输入端");
        }
        if (outputs.isEmpty()) {
            running.set(false);
            throw new IllegalStateException("同步流 [" + name + "] 未配置输出端");
        }
        if (sink == null) {
            running.set(false);
            throw new IllegalStateException("同步流 [" + name + "] 未配置数据中心");
        }

        log.info("同步流 [{}] 启动: inputs={}, outputs={}, batchSize={}", name, inputs.size(), outputs.size(), batchSize);

        try {
            initializeComponents();
            CountDownLatch producerLatch = startProducers();
            consumeLoop(producerLatch);
            flushOutputs();
            log.info("同步流 [{}] 完成, 共同步 {} 条数据", name, syncedCount.get());
        } finally {
            running.set(false);
            releaseResources();
        }
    }

    /**
     * 停止同步流
     * <p>置停止标记并中断生产线程，消费循环会在处理完当前批次后退出。</p>
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("同步流 [{}] 收到停止指令", name);
        if (producerExecutor != null) {
            producerExecutor.shutdownNow();
        }
    }

    /**
     * 是否正在运行
     *
     * @return true 表示同步流运行中
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * 获取同步流名称
     *
     * @return 同步流名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取已同步数据条数
     *
     * @return 已同步条数
     */
    public long getSyncedCount() {
        return syncedCount.get();
    }

    @Override
    /**
     * 关闭
    */
    public void close() {
        stop();
    }

    /**
     * 初始化所有组件
     */
    private void initializeComponents() {
        sink.setExecutor(new SinkExecutor() {
            @Override
            /**
             * Wakeup
            */
            public void wakeup() {
                // 消费循环基于轮询, 无需显式唤醒
            }

            @Override
            /**
             * 是否Running
            */
            public boolean isRunning() {
                return running.get();
            }
        });
        sink.initialize();

        for (Input input : inputs) {
            input.initialize();
        }
        for (Output output : outputs) {
            output.initialize();
        }
    }

    /**
     * 启动生产线程
     * <p>每个输入端一个线程，循环读取批次写入数据中心；背压时等待。</p>
     *
     * @return 生产完成计数器
     */
    private CountDownLatch startProducers() {
        CountDownLatch latch = new CountDownLatch(inputs.size());
        producerExecutor = ThreadUtils.newDaemonFixedThreadPool(inputs.size(), "sync-flow-" + name + "-producer");

        for (Input input : inputs) {
            producerExecutor.submit(() -> {
                try {
                    produce(input);
                } catch (Exception e) {
                    log.error("同步流 [{}] 输入端 [{}] 读取失败", name, input.getInputId(), e);
                } finally {
                    latch.countDown();
                }
            });
        }
        return latch;
    }

    /**
     * 单输入端生产逻辑
     *
     * @param input 输入端
     * @throws InterruptedException 线程被中断时抛出
     */
    private void produce(Input input) throws InterruptedException {
        while (running.get() && input.hasNext()) {
            if (sink.isBackpressure()) {
                TimeUnit.MILLISECONDS.sleep(50);
                continue;
            }
            List<SyncContext> batch = input.readBatch(batchSize);
            if (batch == null || batch.isEmpty()) {
                if (!input.hasNext()) {
                    break;
                }
                TimeUnit.MILLISECONDS.sleep(20);
                continue;
            }
            sink.receiveBatch(batch);
        }
    }

    /**
     * 消费循环
     * <p>从数据中心批量拉取并写出，直到生产结束且缓冲清空。</p>
     *
     * @param producerLatch 生产完成计数器
     */
    private void consumeLoop(CountDownLatch producerLatch) {
        while (running.get()) {
            List<SyncContext> batch = sink.consumeBatch(batchSize);
            if (batch.isEmpty()) {
                boolean producersDone = producerLatch.getCount() == 0;
                if (producersDone && sink.isEmpty()) {
                    break;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            dispatchBatch(batch);
            sink.notifyInput();
        }
    }

    /**
     * 将批次分发到所有输出端
     * <p>失败按 retryCount 重试，仍失败则逐条投递死信。</p>
     *
     * @param batch 数据批次
     */
    private void dispatchBatch(List<SyncContext> batch) {
        for (Output output : outputs) {
            boolean success = false;
            Exception lastError = null;
            for (int attempt = 0; attempt <= retryCount; attempt++) {
                try {
                    output.writeBatch(batch);
                    success = true;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("同步流 [{}] 输出端 [{}] 写出失败, 第 {} 次重试", name, output.getOutputId(), attempt + 1, e);
                    try {
                        TimeUnit.MILLISECONDS.sleep(retryInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!success) {
                for (SyncContext context : batch) {
                    sink.sendToDeadLetter(context, lastError);
                }
            }
        }
        syncedCount.addAndGet(batch.size());
    }

    /**
     * 刷新所有输出端缓冲
     */
    private void flushOutputs() {
        for (Output output : outputs) {
            try {
                output.flush();
            } catch (Exception e) {
                log.warn("同步流 [{}] 输出端 [{}] 刷新失败", name, output.getOutputId(), e);
            }
        }
    }

    /**
     * 释放所有资源
     */
    private void releaseResources() {
        if (producerExecutor != null) {
            producerExecutor.shutdownNow();
            producerExecutor = null;
        }
        for (Input input : inputs) {
            try {
                input.close();
            } catch (Exception e) {
                log.warn("同步流 [{}] 关闭输入端失败", name, e);
            }
        }
        for (Output output : outputs) {
            try {
                output.close();
            } catch (Exception e) {
                log.warn("同步流 [{}] 关闭输出端失败", name, e);
            }
        }
        try {
            sink.close();
        } catch (Exception e) {
            log.warn("同步流 [{}] 关闭数据中心失败", name, e);
        }
    }

    /**
     * 同步流构建器
     *
     * @since 2026/07/28
     * @author CH
     */
    public static class Builder {

        /**
         * 同步流名称
         */
        private final String name;

        /**
         * 输入端列表
         */
        private final List<Input> inputs = new ArrayList<>();

        /**
         * 输出端列表
         */
        private final List<Output> outputs = new ArrayList<>();

        /**
         * 数据中心
         */
        private Sink sink;

        /**
         * 批处理大小（默认 100）
         */
        private int batchSize = 100;

        /**
         * 写出失败重试次数（默认 3）
         */
        private int retryCount = 3;

        /**
         * 重试间隔毫秒数（默认 1000）
         */
        private long retryInterval = 1000;

        /**
         * 创建 构建器 实例
         * @param name 名称
         */
        private Builder(String name) {
            this.name = name;
        }

        /**
         * 添加输入端
         *
         * @param input 输入端
         * @return 构建器自身
         */
        public Builder addInput(Input input) {
            if (input != null) {
                this.inputs.add(input);
            }
            return this;
        }

        /**
         * 添加输出端
         *
         * @param output 输出端
         * @return 构建器自身
         */
        public Builder addOutput(Output output) {
            if (output != null) {
                this.outputs.add(output);
            }
            return this;
        }

        /**
         * 设置数据中心
         *
         * @param sink 数据中心
         * @return 构建器自身
         */
        public Builder sink(Sink sink) {
            this.sink = sink;
            return this;
        }

        /**
         * 设置批处理大小
         *
         * @param batchSize 批处理大小
         * @return 构建器自身
         */
        public Builder batchSize(int batchSize) {
            if (batchSize > 0) {
                this.batchSize = batchSize;
            }
            return this;
        }

        /**
         * 设置重试次数
         *
         * @param retryCount 重试次数
         * @return 构建器自身
         */
        public Builder retryCount(int retryCount) {
            if (retryCount >= 0) {
                this.retryCount = retryCount;
            }
            return this;
        }

        /**
         * 设置重试间隔
         *
         * @param retryInterval 重试间隔（毫秒）
         * @return 构建器自身
         */
        public Builder retryInterval(long retryInterval) {
            if (retryInterval >= 0) {
                this.retryInterval = retryInterval;
            }
            return this;
        }

        /**
         * 构建同步流实例
         *
         * @return 同步流
         */
        public SyncFlow build() {
            return new SyncFlow(this);
        }
    }
}
