package com.chua.common.support.lang.process;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;

/**
 * 多任务并排进度条，同时跟踪多个任务并在终端中统一刷新渲染。
 * <pre>{@code
 * // 快速创建
 * MultiProgressBar mpb = new MultiProgressBar(consumer,
 *     "任务A", 100,
 *     "任务B", 200,
 *     "任务C", 50
 * );
 *
 * for (int i = 0; i <= 100; i++) {
 *     mpb.stepBy(0, 1);
 *     if (i % 2 == 0) mpb.stepBy(1, 1);
 *     if (i % 5 == 0) mpb.stepBy(2, 1);
 *     Thread.sleep(50);
 * }
 * mpb.close();
 *
 * // 或使用 builder
 * MultiProgressBar mpb = MultiProgressBar.builder()
 *     .addTask("下载数据", 100)
 *     .addTask("处理中",  200)
 *     .addTask("写入",   50)
 *     .build();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@NullUnmarked
@SuppressWarnings("NullAway")
public class MultiProgressBar implements AutoCloseable {

    /**
     * 任务列表
     */
    private final List<TaskProgress> tasks = new ArrayList<>();

    /**
     * 进度条消费者
     */
    private final ProgressBarConsumer consumer;

    /**
     * 进度条渲染器
     */
    private final ProgressBarRenderer renderer;

    /**
     * 更新间隔（毫秒）
     */
    private final int updateIntervalMillis;

    /**
     * 定时刷新任务
     */
    private final ScheduledFuture<?> scheduledTask;

    /**
     * 是否已首次渲染
     */
    private boolean rendered;

    /**
     * 创建一个多任务进度条。
     *
     * @param consumer 进度条消费者
     * @param tasks    交替的任务名称和最大值（name1, max1, name2, max2, ...）
     */
    public MultiProgressBar(ProgressBarConsumer consumer, Object... tasks) {
        this(consumer, new DefaultProgressBarRenderer(ProgressBarStyle.ASCII), 100, tasks);
    }

    /**
     * 创建一个多任务进度条。
     *
     * @param consumer            进度条消费者
     * @param renderer            进度条渲染器
     * @param updateIntervalMillis 刷新间隔（毫秒）
     * @param tasks               交替的任务名称和最大值（name1, max1, name2, max2, ...）
     */
    public MultiProgressBar(
            ProgressBarConsumer consumer,
            ProgressBarRenderer renderer,
            int updateIntervalMillis,
            Object... tasks
    ) {
        this.consumer = consumer;
        this.renderer = renderer;
        this.updateIntervalMillis = updateIntervalMillis;
        for (int i = 0; i < tasks.length; i += 2) {
            String name = (String) tasks[i];
            long max = ((Number) tasks[i + 1]).longValue();
            this.tasks.add(new TaskProgress(name, max));
        }
        this.scheduledTask = Util.executor.scheduleAtFixedRate(
                this::refresh, updateIntervalMillis, updateIntervalMillis, TimeUnit.MILLISECONDS
        );
    }

    /**
     * 创建一个进度条构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 指定索引的任务步进指定数量。
     *
     * @param index 任务索引
     * @param n     步进数
     * @return this
     */
    public MultiProgressBar stepBy(int index, long n) {
        tasks.get(index).state.stepBy(n);
        refresh();
        return this;
    }

    /**
     * 指定名称的任务步进指定数量。
     *
     * @param name 任务名称
     * @param n    步进数
     * @return this
     */
    public MultiProgressBar stepBy(String name, long n) {
        return stepBy(indexOf(name), n);
    }

    /**
     * 指定索引的任务步进 1。
     *
     * @param index 任务索引
     * @return this
     */
    public MultiProgressBar step(int index) {
        return stepBy(index, 1);
    }

    /**
     * 指定名称的任务步进 1。
     *
     * @param name 任务名称
     * @return this
     */
    public MultiProgressBar step(String name) {
        return stepBy(name, 1);
    }

    /**
     * 指定索引的任务跳转到指定进度。
     *
     * @param index 任务索引
     * @param value 目标进度
     * @return this
     */
    public MultiProgressBar stepTo(int index, long value) {
        tasks.get(index).state.stepTo(value);
        refresh();
        return this;
    }

    /**
     * 指定名称的任务跳转到指定进度。
     *
     * @param name  任务名称
     * @param value 目标进度
     * @return this
     */
    public MultiProgressBar stepTo(String name, long value) {
        return stepTo(indexOf(name), value);
    }

    /**
     * 获取指定索引任务当前进度。
     *
     * @param index 任务索引
     * @return 当前进度
     */
    public long getCurrent(int index) {
        return tasks.get(index).state.getCurrent();
    }

    /**
     * 获取指定名称任务当前进度。
     *
     * @param name 任务名称
     * @return 当前进度
     */
    public long getCurrent(String name) {
        return getCurrent(indexOf(name));
    }

    /**
     * 判断所有任务是否已完成。
     *
     * @return true 全部完成
     */
    public boolean isAllDone() {
        return tasks.stream().allMatch(t -> t.state.getCurrent() >= t.state.getMax());
    }

    /**
     * 设置指定索引任务的附加消息。
     *
     * @param index 任务索引
     * @param msg   附加消息
     * @return this
     */
    public MultiProgressBar setExtraMessage(int index, String msg) {
        tasks.get(index).state.setExtraMessage(msg);
        refresh();
        return this;
    }

    /**
     * 设置指定名称任务的附加消息。
     *
     * @param name 任务名称
     * @param msg  附加消息
     * @return this
     */
    public MultiProgressBar setExtraMessage(String name, String msg) {
        return setExtraMessage(indexOf(name), msg);
    }

    @Override
    public void close() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel(false);
        }
        for (TaskProgress tp : tasks) {
            tp.state.kill();
        }
        // 光标下移，收起进度条
        if (rendered) {
            consumer.accept(TerminalUtils.moveCursorDown(tasks.size()));
        }
        consumer.close();
    }

    private void refresh() {
        if (!rendered) {
            // 首次渲染：直接输出所有行
            for (TaskProgress tp : tasks) {
                String line = renderer.render(tp.state, consumer.getMaxRenderedLength());
                consumer.accept(line + "\n");
            }
            rendered = true;
        } else {
            // 光标上移 N 行，重绘全部
            consumer.accept(TerminalUtils.moveCursorUp(tasks.size()));
            for (int i = 0; i < tasks.size(); i++) {
                String line = renderer.render(tasks.get(i).state, consumer.getMaxRenderedLength());
                consumer.accept(line + (i < tasks.size() - 1 ? "\n" : ""));
            }
        }
    }

    private int indexOf(String name) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).state.getTaskName().equals(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("未找到任务: " + name);
    }

    /**
     * 任务进度内部包装
     */
    static class TaskProgress {
        final ProgressState state;

        TaskProgress(String name, long max) {
            this.state = new ProgressState(name, max, 0, java.time.Duration.ZERO);
        }
    }

    /**
     * {@link MultiProgressBar} 构建器。
     */
    public static class Builder {

        private final List<String> taskNames = new ArrayList<>();
        private final List<Long> taskMaxs = new ArrayList<>();
        private ProgressBarConsumer consumer;
        private ProgressBarRenderer renderer;
        private int updateIntervalMillis = 100;

        Builder() {
        }

        /**
         * 添加一个任务。
         *
         * @param name 任务名称
         * @param max  最大值
         * @return this
         */
        public Builder addTask(String name, long max) {
            taskNames.add(name);
            taskMaxs.add(max);
            return this;
        }

        /**
         * 设置进度条消费者。
         *
         * @param consumer 消费者
         * @return this
         */
        public Builder consumer(ProgressBarConsumer consumer) {
            this.consumer = consumer;
            return this;
        }

        /**
         * 设置进度条渲染器。
         *
         * @param renderer 渲染器
         * @return this
         */
        public Builder renderer(ProgressBarRenderer renderer) {
            this.renderer = renderer;
            return this;
        }

        /**
         * 设置更新间隔（毫秒）。
         *
         * @param millis 毫秒
         * @return this
         */
        public Builder updateIntervalMillis(int millis) {
            this.updateIntervalMillis = millis;
            return this;
        }

        /**
         * 构建 {@link MultiProgressBar} 实例。
         *
         * @return 多任务进度条
         */
        public MultiProgressBar build() {
            if (taskNames.isEmpty()) {
                throw new IllegalStateException("至少需要添加一个任务");
            }
            ProgressBarConsumer c = consumer;
            if (c == null) {
                c = Util.createConsoleConsumer(120);
            }
            ProgressBarRenderer r = renderer;
            if (r == null) {
                r = new DefaultProgressBarRenderer(ProgressBarStyle.ASCII);
            }
            Object[] args = new Object[taskNames.size() * 2];
            for (int i = 0; i < taskNames.size(); i++) {
                args[i * 2] = taskNames.get(i);
                args[i * 2 + 1] = taskMaxs.get(i);
            }
            return new MultiProgressBar(c, r, updateIntervalMillis, args);
        }
    }
}
