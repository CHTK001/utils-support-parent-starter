package com.chua.runtime.shell.output;

import java.io.PrintWriter;

/**
 * 控制台输出 — 提供字符界面（CUI）格式化输出能力。
 *
 * <p>支持表格、分隔线、进度条、带色输出。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class Console {

    /**
     * 输出目标
     */
    private final PrintWriter writer;

    /**
     * 表格边框字符
     */
    private static final char BOX = '+';

    /**
     * 创建控制台。
     *
     * @param writer 输出写入
     */
    public Console(PrintWriter writer) {
        this.writer = writer;
    }

    /**
     * 输出一行普通文本。
     *
     * @param text 文本
     */
    public void println(String text) {
        writer.println(text);
        writer.flush();
    }

    /**
     * 输出空行。
     */
    public void blank() {
        writer.println();
        writer.flush();
    }

    /**
     * 输出带标题的分隔线。
     *
     * @param title 标题
     */
    public void header(String title) {
        int width = Math.max(title.length() + 4, 40);
        StringBuilder line = new StringBuilder(width);
        for (int i = 0; i < width; i++) {
            line.append('=');
        }
        writer.println(line);
        writer.println("== " + title + " ==");
        writer.println(line);
        writer.flush();
    }

    /**
     * 输出分隔线。
     */
    public void line() {
        printRepeat('-', 40);
    }

    /**
     * 输出错误文本。
     *
     * @param message 错误信息
     */
    public void error(String message) {
        writer.println("[ERROR] " + message);
        writer.flush();
    }

    /**
     * 输出告警文本。
     *
     * @param message 告警信息
     */
    public void warn(String message) {
        writer.println("[WARN] " + message);
        writer.flush();
    }

    /**
     * 输出信息文本。
     *
     * @param message 信息
     */
    public void info(String message) {
        writer.println("[INFO] " + message);
        writer.flush();
    }

    /**
     * 输出成功文本。
     *
     * @param message 信息
     */
    public void success(String message) {
        writer.println("[OK] " + message);
        writer.flush();
    }

    /**
     * 打印重复字符。
     *
     * @param ch    字符
     * @param count 数量
     */
    private void printRepeat(char ch, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        writer.println(sb);
        writer.flush();
    }
}