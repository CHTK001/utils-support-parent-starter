package com.chua.ssh.support.server;

import com.chua.common.support.lang.process.MultiProgressBar;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

/**
* SSH 多任务并排进度条，封装 {@link MultiProgressBar} 自动适配 SSH 输出流。
* <pre>{@code
* @ShellMethod("batch")
* public void batch(String[] args, SshCommandResponse res) throws Exception {
*     SshMultiProgress mp = new SshMultiProgress(res);
*     mp.add("任务A", 100);
*     mp.add("任务B", 200);
*     mp.add("任务C", 50);
*
*     for (int i = 0; i <= 100; i++) {
*         mp.stepBy(0, 1);
*         if (i % 2 == 0) mp.stepBy(1, 1);
*         if (i % 5 == 0) mp.stepBy(2, 1);
*         Thread.sleep(50);
*     }
*     mp.close();
* }
* }</pre>pBy(2, 1);
*         Thread.sleep(50);
*     }
* mp.关闭();
* }
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public class SshMultiProgress implements AutoCloseable {

    /**
    * 响应
    */
    private final SshCommandResponse response;
    /**
    * bar Width
    */
    private final int barWidth;
    /**
    * pending
    */
    private final List<TaskDef> pending = new ArrayList<>();
    /**
    * 委托对象
    */
    private MultiProgressBar delegate;

    /**
    * 创建多任务进度条。
    *
    * @param response SSH 响应
    */
    public SshMultiProgress(SshCommandResponse response) {
        this(response, 80);
    }

    /**
    * 创建多任务进度条。
    *
    * @param response SSH 响应
    * @param barWidth 每个进度条的最大字符宽度
    */
    public SshMultiProgress(SshCommandResponse response, int barWidth) {
        this.response = response;
        this.barWidth = barWidth;
    }

    /**
    * 添加一个任务。
    *
    * @param name  任务名称
    * @param total 总进度
    */
    public void add(@Nonnull String name, long total) {
        pending.add(new TaskDef(name, total));
    }

    /**
    * 指定索引的任务步进指定数量。
    *
    * @param index 任务索引
    * @param n     步进数
    * @return this
    */
    public SshMultiProgress stepBy(int index, long n) {
        lazyInit();
        delegate.stepBy(index, n);
        return this;
    }

    /**
    * 指定名称的任务步进指定数量。
    *
    * @param name 任务名称
    * @param n    步进数
    * @return this
    */
    public SshMultiProgress stepBy(@Nonnull String name, long n) {
        lazyInit();
        delegate.stepBy(name, n);
        return this;
    }

    /**
    * 指定索引的任务跳转到指定进度。
    *
    * @param index 任务索引
    * @param value 目标进度
    * @return this
    */
    public SshMultiProgress stepTo(int index, long value) {
        lazyInit();
        delegate.stepTo(index, value);
        return this;
    }

    /**
    * 指定名称的任务跳转到指定进度。
    *
    * @param name  任务名称
    * @param value 目标进度
    * @return this
    */
    public SshMultiProgress stepTo(@Nonnull String name, long value) {
        lazyInit();
        delegate.stepTo(name, value);
        return this;
    }

    @Override
    /** 关闭 */
    public void close() {
        if (delegate != null) {
            delegate.close();
        }
    }

    /** Lazy初始化 */
    private void lazyInit() {
        if (delegate != null) {
            return;
        }
        if (pending.isEmpty()) {
            throw new IllegalStateException("请先通过 add() 添加至少一个任务");
        }
        MultiProgressBar.Builder builder = MultiProgressBar.builder()
                .consumer(new SshProgressBarConsumer(response, barWidth))
                .updateIntervalMillis(50);
        for (TaskDef td : pending) {
            builder.addTask(td.name, td.total);
        }
        delegate = builder.build();
    }

    static class TaskDef {
        final String name; // 名称
        final long total; // total

        TaskDef(String name, long total) {
            this.name = name;
            this.total = total;
        }
    }
}
