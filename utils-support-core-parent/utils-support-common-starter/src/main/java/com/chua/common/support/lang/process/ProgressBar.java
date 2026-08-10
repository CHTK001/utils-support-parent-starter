package com.chua.common.support.lang.process;

import com.chua.common.support.lang.process.wrapped.ProgressBarWrappedInputStream;
import com.chua.common.support.lang.process.wrapped.ProgressBarWrappedIterable;
import com.chua.common.support.lang.process.wrapped.ProgressBarWrappedIterator;
import com.chua.common.support.lang.process.wrapped.ProgressBarWrappedOutputStream;
import com.chua.common.support.lang.process.wrapped.ProgressBarWrappedReader;
import com.chua.common.support.lang.process.wrapped.ProgressBarWrappedSpliterator;
import com.chua.common.support.lang.process.wrapped.ProgressBarWrappedWriter;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.io.Writer;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.BaseStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.chua.common.support.lang.process.Util.createConsoleConsumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 进度条主类，支持自动定时刷新、多进度条共存。
 * <p>
 * 实现 AutoCloseable 接口，支持 try-with-resources 语法自动关闭。
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
public class ProgressBar implements AutoCloseable {

    /**
     * 进度状态
     */
    private final ProgressState progress;

    /**
     * 进度更新动作
     */
    private final ProgressUpdateAction action;

    /**
     * 定时刷新任务
     */
    private final ScheduledFuture<?> scheduledTask;
    /**
     * 创建一个进度条
     *
     * @param task 任务名称
     * @param initialMax 最大进度值
     */
    public ProgressBar(String task, long initialMax) {
        this(task, initialMax, ProgressUnitType.ORIGINAL);
    }
    /**
     * 创建一个进度条
     *
     * @param task 任务名称
     * @param initialMax 最大进度值
     * @param unit 进度单位
     */
    public ProgressBar(String task, long initialMax, ProgressUnit unit) {
        this(
                task, initialMax, 1000, false, false,
                System.out, ProgressBarStyle.ASCII, unit,
                "", 1L, false, null,
                ChronoUnit.SECONDS, 0L, Duration.ZERO
        );
    }
    /**
     * 创建一个进度条
     *
     * @param task 任务名称
     * @param initialMax 最大进度值
     * @param updateIntervalMillis 更新间隔（毫秒）
     * @param continuousUpdate 是否持续更新
     * @param clearDisplayOnFinish 完成后是否清除显示
     * @param os 输出流
     * @param style 进度条样式
     * @param unit 进度单位
     * @param unitName 单位名称
     * @param unitSize 单位大小
     * @param showSpeed 是否显示速度
     * @param speedFormat 速度格式
     * @param speedUnit 速度单位
     * @param processed 已处理数量
     * @param elapsed 已消耗时间
     */
    public ProgressBar(
            String task,
            long initialMax,
            int updateIntervalMillis,
            boolean continuousUpdate,
            boolean clearDisplayOnFinish,
            PrintStream os,
            ProgressBarStyle style,
            ProgressUnit unit,
            String unitName,
            long unitSize,
            boolean showSpeed,
            DecimalFormat speedFormat,
            ChronoUnit speedUnit,
            long processed,
            Duration elapsed
    ) {
        this(task, initialMax, updateIntervalMillis, continuousUpdate, clearDisplayOnFinish, processed, elapsed,
                new DefaultProgressBarRenderer(
                        style, unit, unitName, unitSize,
                        showSpeed, speedFormat, speedUnit,
                        true, Util::linearEta
                ),
                createConsoleConsumer(os)
        );
    }

    /**
     * 创建一个进度条（高级构造器）
     *
     * @param task 任务名称
     * @param initialMax 最大进度值
     * @param updateIntervalMillis 更新间隔（毫秒）
     * @param continuousUpdate 是否持续更新
     * @param clearDisplayOnFinish 完成后是否清除显示
     * @param processed 已处理数量
     * @param elapsed 已消耗时间
     * @param renderer 进度条渲染器
     * @param consumer 进度条消费者
     */
    public ProgressBar(
            String task,
            long initialMax,
            int updateIntervalMillis,
            boolean continuousUpdate,
            boolean clearDisplayOnFinish,
            long processed,
            Duration elapsed,
            ProgressBarRenderer renderer,
            ProgressBarConsumer consumer
    ) {
        this.progress = new ProgressState(task, initialMax, processed, elapsed);
        this.action = new ProgressUpdateAction(progress, renderer, consumer, continuousUpdate, clearDisplayOnFinish);
        scheduledTask = Util.executor.scheduleAtFixedRate(
                action, 0, updateIntervalMillis, TimeUnit.MILLISECONDS
        );
    }

    /**
     * 按指定步进数前进
     *
     * @param n 步进数量
     * @return 当前进度条实例
     */
    public ProgressBar stepBy(long n) {
        progress.stepBy(n);
        return this;
    }

    /**
     * 前进到指定进度值
     *
     * @param n 目标进度值
     * @return 当前进度条实例
     */
    public ProgressBar stepTo(long n) {
        boolean back = n < progress.current;
        progress.stepTo(n);
        if (back) {
            action.forceRefresh();  // fix #124
        }
        return this;
    }

    /**
     * 步进 1
     *
     * @return 当前进度条实例
     */
    public ProgressBar step() {
        progress.stepBy(1);
        return this;
    }

    /**
     * 设置最大进度值提示
     * <p>
     * 传入 -1 将切换为不确定模式
     *
     * @param n 最大进度值（-1 表示不确定模式）
     * @return 当前进度条实例
     */
    public ProgressBar maxHint(long n) {
        if (n < 0) {
            progress.setAsIndefinite();
        } else {
            progress.setAsDefinite();
            progress.maxHint(n);
        }
        return this;
    }

    /**
     * 暂停进度条
     *
     * @return 当前进度条实例
     */
    public ProgressBar pause() {
        progress.pause();
        return this;
    }

    /**
     * 恢复进度条
     *
     * @return 当前进度条实例
     */
    public ProgressBar resume() {
        progress.resume();
        return this;
    }

    /**
     * 重置进度条（归零）
     *
     * @return 当前进度条实例
     */
    public ProgressBar reset() {
        progress.reset();
        action.forceRefresh();  // force refresh, fixing #124
        return this;
    }

    /**
     * 关闭进度条，停止刷新并输出最终状态
     * <p>
     * 实现 {@link AutoCloseable} 接口，支持 try-with-resource 语法自动关闭
     *
     * @since 0.7.0
     */
    @Override
    public void close() {
        if (scheduledTask.isCancelled()) {
            return;
        }
        scheduledTask.cancel(false);
        progress.kill();
        try {
            Util.executor.schedule(action, 0, TimeUnit.NANOSECONDS).get();
        } catch (InterruptedException | ExecutionException e) {
            // 忽略中断或执行异常
        }
    }

    /**
     * 设置附加消息
     *
     * @param msg 附加消息
     * @return 当前进度条实例
     */
    public ProgressBar setExtraMessage(String msg) {
        progress.setExtraMessage(msg);
        return this;
    }

    /**
     * 获取当前进度值
     *
     * @return 当前进度值
     */
    public long getCurrent() {
        return progress.getCurrent();
    }

    /**
     * 获取最大进度值
     *
     * @return 最大进度值
     */
    public long getMax() {
        return progress.getMax();
    }

    /**
     * 获取起始进度值
     *
     * @return 起始进度值
     */
    public long getStart() {
        return progress.getStart();
    }

    /**
     * 获取归一化进度值（范围 0.0 ~ 1.0）
     *
     * @return 归一化进度值
     */
    public double getNormalizedProgress() {
        return progress.getNormalizedProgress();
    }

    /**
     * 获取开始时间戳
     * <p>
     * 返回进度条开始时的瞬时时间点
     *
     * @return 开始时间戳
     */
    public Instant getStartInstant() {
        return progress.startInstant;
    }

    /**
     * 获取开始前已消耗时间
     * <p>
     * 返回进度开始前记录的时间量
     *
     * @return 开始前已消耗时间
     */
    public Duration getElapsedBeforeStart() {
        return progress.getElapsedBeforeStart();
    }

    /**
     * 获取开始后已消耗时间
     * <p>
     * 返回从进度开始到现在的时间量
     *
     * @return 开始后已消耗时间
     */
    public Duration getElapsedAfterStart() {
        return progress.getElapsedAfterStart();
    }

    /**
     * 获取总消耗时间
     * <p>
     * 包含开始前和开始后的总时间
     *
     * @return 总消耗时间
     */
    public Duration getTotalElapsed() {
        return progress.getTotalElapsed();
    }

    /**
     * 获取任务名称
     *
     * @return 任务名称
     */
    public String getTaskName() {
        return progress.getTaskName();
    }

    /**
     * 获取附加消息
     *
     * @return 附加消息
     */
    public String getExtraMessage() {
        return progress.getExtraMessage();
    }

    /**
     * 判断是否为不确定模式
     *
     * @return true 表示不确定模式，false 表示确定模式
     */
    public boolean isIndefinite() {
        return progress.indefinite;
    }

    /**
     * 强制刷新显示
     * <p>
     * 立即更新进度条的显示内容
     */
    public void refresh() {
        action.refresh();
    }

    // ========== 静态包装方法 ==========

    /**
     * 包装迭代器为带进度条的迭代器
     *
     * @param it 迭代器
     * @param task 任务名称
     * @param <T> 元素类型
     * @return 包装后的迭代器
     */
    public static <T> Iterator<T> wrap(Iterator<T> it, String task) {
        return wrap(it,
                new ProgressBarBuilder().setTaskName(task).setInitialMax(-1)
        ); // indefinite progress bar
    }

    /**
     * 包装迭代器为带进度条的迭代器
     *
     * @param it 迭代器
     * @param pbb 进度条构建器
     * @param <T> 元素类型
     * @return 包装后的迭代器
     */
    public static <T> Iterator<T> wrap(Iterator<T> it, ProgressBarBuilder pbb) {
        return new ProgressBarWrappedIterator<>(it, pbb.build());
    }

    /**
     * 包装可迭代对象为带进度条的可迭代对象
     * <p>
     * 使用方法：
     * <pre>
     * for (T x : ProgressBar.wrap(collection, "任务名称")) { ... }
     * </pre>
     *
     * @param ts 可迭代对象
     * @param task 任务名称
     * @param <T> 元素类型
     * @return 包装后的可迭代对象
     */
    public static <T> Iterable<T> wrap(Iterable<T> ts, String task) {
        return wrap(ts, new ProgressBarBuilder().setTaskName(task));
    }

    /**
     * 包装可迭代对象为带进度条的可迭代对象
     * <p>
     * 使用 {@link ProgressBarBuilder} 自定义进度条配置
     *
     * @param ts 可迭代对象
     * @param pbb 进度条构建器
     * @param <T> 元素类型
     * @return 包装后的可迭代对象
     */
    public static <T> Iterable<T> wrap(Iterable<T> ts, ProgressBarBuilder pbb) {
        if (!pbb.initialMaxIsSet()) {
            pbb.setInitialMax(Util.getSpliteratorSize(ts.spliterator()));
        }
        return new ProgressBarWrappedIterable<>(ts, pbb);
    }

    /**
     * 包装输入流为带进度条的输入流
     *
     * @param is 输入流
     * @param task 任务名称
     * @return 包装后的输入流
     */
    public static InputStream wrap(InputStream is, String task) {
        ProgressBarBuilder pbb = new ProgressBarBuilder().setTaskName(task);
        return wrap(is, pbb);
    }

    /**
     * 包装输入流为带进度条的输入流
     * <p>
     * 使用 {@link ProgressBarBuilder} 自定义进度条配置
     *
     * @param is 输入流
     * @param pbb 进度条构建器
     * @return 包装后的输入流
     */
    public static InputStream wrap(InputStream is, ProgressBarBuilder pbb) {
        if (!pbb.initialMaxIsSet()) {
            pbb.setInitialMax(Util.getInputStreamSize(is));
        }
        return new ProgressBarWrappedInputStream(is, pbb.build());
    }

    /**
     * 包装输出流为带进度条的输出流
     *
     * @param os 输出流
     * @param task 任务名称
     * @return 包装后的输出流
     */
    public static OutputStream wrap(OutputStream os, String task) {
        ProgressBarBuilder pbb = new ProgressBarBuilder().setTaskName(task);
        return wrap(os, pbb);
    }

    /**
     * 包装输出流为带进度条的输出流
     * <p>
     * 使用 {@link ProgressBarBuilder} 自定义进度条配置
     *
     * @param os 输出流
     * @param pbb 进度条构建器
     * @return 包装后的输出流
     */
    public static OutputStream wrap(OutputStream os, ProgressBarBuilder pbb) {
        return new ProgressBarWrappedOutputStream(os, pbb.build());
    }

    /**
     * 包装 Reader 为带进度条的 Reader
     *
     * @param reader Reader
     * @param task 任务名称
     * @return 包装后的 Reader
     */
    public static Reader wrap(Reader reader, String task) {
        ProgressBarBuilder pbb = new ProgressBarBuilder().setTaskName(task);
        return wrap(reader, pbb);
    }

    /**
     * 包装 Reader 为带进度条的 Reader
     * <p>
     * 使用 {@link ProgressBarBuilder} 自定义进度条配置
     *
     * @param reader Reader
     * @param pbb 进度条构建器
     * @return 包装后的 Reader
     */
    public static Reader wrap(Reader reader, ProgressBarBuilder pbb) {
        return new ProgressBarWrappedReader(reader, pbb.build());
    }

    /**
     * 包装 Writer 为带进度条的 Writer
     *
     * @param writer Writer
     * @param task 任务名称
     * @return 包装后的 Writer
     */
    public static Writer wrap(Writer writer, String task) {
        ProgressBarBuilder pbb = new ProgressBarBuilder().setTaskName(task);
        return wrap(writer, pbb);
    }

    /**
     * 包装 Writer 为带进度条的 Writer
     * <p>
     * 使用 {@link ProgressBarBuilder} 自定义进度条配置
     *
     * @param writer Writer
     * @param pbb 进度条构建器
     * @return 包装后的 Writer
     */
    public static Writer wrap(Writer writer, ProgressBarBuilder pbb) {
        return new ProgressBarWrappedWriter(writer, pbb.build());
    }

    /**
     * 包装 Spliterator 为带进度条的 Spliterator
     *
     * @param sp Spliterator
     * @param task 任务名称
     * @param <T> 元素类型
     * @return 包装后的 Spliterator
     */
    public static <T> Spliterator<T> wrap(Spliterator<T> sp, String task) {
        ProgressBarBuilder pbb = new ProgressBarBuilder().setTaskName(task);
        return wrap(sp, pbb);
    }

    /**
     * 包装 Spliterator 为带进度条的 Spliterator
     * <p>
     * 使用 {@link ProgressBarBuilder} 自定义进度条配置
     *
     * @param sp Spliterator
     * @param pbb 进度条构建器
     * @param <T> 元素类型
     * @return 包装后的 Spliterator
     */
    public static <T> Spliterator<T> wrap(Spliterator<T> sp, ProgressBarBuilder pbb) {
        if (!pbb.initialMaxIsSet()) {
            pbb.setInitialMax(Util.getSpliteratorSize(sp));
        }
        return new ProgressBarWrappedSpliterator<>(sp, pbb.build());
    }

    /**
     * 包装 Stream 为带进度条的 Stream
     *
     * @param stream Stream 流
     * @param task 任务名称
     * @param <T> 元素类型
     * @param <S> Stream 类型
     * @return 包装后的 Stream
     */
    public static <T, S extends BaseStream<T, S>> Stream<T> wrap(S stream, String task) {
        ProgressBarBuilder pbb = new ProgressBarBuilder().setTaskName(task);
        return wrap(stream, pbb);
    }

    /**
     * 包装 Stream 为带进度条的 Stream
     * <p>
     * 使用 {@link ProgressBarBuilder} 自定义进度条配置
     *
     * @param stream Stream 流
     * @param pbb 进度条构建器
     * @param <T> 元素类型
     * @param <S> Stream 类型
     * @return 包装后的 Stream
     */
    public static <T, S extends BaseStream<T, S>> Stream<T> wrap(S stream, ProgressBarBuilder pbb) {
        Spliterator<T> sp = wrap(stream.spliterator(), pbb);
        return StreamSupport.stream(sp, stream.isParallel());
    }

    /**
     * 包装数组为带进度条的 Stream
     *
     * @param array 数组
     * @param task 任务名称
     * @param <T> 元素类型
     * @return 包装后的 Stream
     */
    public static <T> Stream<T> wrap(T[] array, String task) {
        ProgressBarBuilder pbb = new ProgressBarBuilder().setTaskName(task);
        return wrap(array, pbb);
    }

    /**
     * 包装数组为带进度条的 Stream
     * <p>
     * 使用 {@link ProgressBarBuilder} 自定义进度条配置
     *
     * @param array 数组
     * @param pbb 进度条构建器
     * @param <T> 元素类型
     * @return 包装后的 Stream
     */
    public static <T> Stream<T> wrap(T[] array, ProgressBarBuilder pbb) {
        pbb.setInitialMax(array.length);
        return wrap(Arrays.stream(array), pbb);
    }

    /**
     * 创建一个进度条构建器
     *
     * @return 进度条构建器实例
     */
    public static ProgressBarBuilder builder() {
        return new ProgressBarBuilder();
    }

}