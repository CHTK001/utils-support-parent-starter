package com.chua.common.support.lang.process;

import java.io.PrintStream;

import static com.chua.common.support.lang.process.TerminalUtils.CARRIAGE_RETURN;

/**
* 控制台进度条消费者，将进度输出到控制台。
* <p>
* 默认输出到 System.out，也可配置为 {@link System#err} 等其他输出流。
*
* @author CH
* @since 2024-01-01
* @version 1.0.0
 */
public class ConsoleProgressBarConsumer implements ProgressBarConsumer {

    /**
    * 控制台右侧边距，用于计算实际可用显示宽度。
    */
    private static final int CONSOLE_RIGHT_MARGIN = 1;

    /**
    * 最大渲染长度。
    * <p>
    * 如果小于等于 0，则自动检测终端宽度并减去右侧边距。
    */
    private int maxRenderedLength = -1;

    /**
    * 目标输出流，用于打印进度信息。
    */
    private final PrintStream out;

    /**
    * 构造函数，初始化输出流。
    *
    * @param out 输出流实例。
    */
    public ConsoleProgressBarConsumer(PrintStream out) {
        this.out = out;
    }

    /**
    * 构造函数，初始化输出流和最大渲染长度。
    *
    * @param out             输出流实例。
    * @param maxRenderedLength 最大渲染长度，小于等于 0 时自动检测终端宽度。
    */
    public ConsoleProgressBarConsumer(PrintStream out, int maxRenderedLength) {
        this.maxRenderedLength = maxRenderedLength;
        this.out = out;
    }

    /**
    * 获取当前允许的最大渲染长度。
    *
    * @return 最大渲染长度，若未设置则返回终端宽度减去边距。
    */
    @Override
    public int getMaxRenderedLength() {
        if (maxRenderedLength <= 0) {
            return TerminalUtils.getTerminalWidth() - CONSOLE_RIGHT_MARGIN;
        } else {
            return maxRenderedLength;
        }
    }

    /**
    * 接受字符串输入并将其作为进度条内容输出到控制台。
    * <p>
    * 该方法会先清除当前行，然后输出截断后的字符串。
    *
    * @param str 需要输出的进度字符串。
    */
    @Override
    public void accept(String str) {
        String trimmedStr = StringDisplayUtils.trimDisplayLength(str, getMaxRenderedLength());
        out.print(CARRIAGE_RETURN + trimmedStr);
    }

    /**
    * 关闭消费者，输出换行符并刷新缓冲区。
    */
    @Override
    public void close() {
        out.println();
        out.flush();
    }
}
