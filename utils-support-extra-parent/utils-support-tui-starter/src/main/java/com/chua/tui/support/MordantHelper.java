package com.chua.tui.support;

import com.github.ajalt.mordant.rendering.BorderType;
import com.github.ajalt.mordant.rendering.OverflowWrap;
import com.github.ajalt.mordant.rendering.TextAlign;
import com.github.ajalt.mordant.rendering.TextColors;
import com.github.ajalt.mordant.rendering.TextStyles;
import com.github.ajalt.mordant.rendering.Theme;
import com.github.ajalt.mordant.rendering.Whitespace;
import com.github.ajalt.mordant.terminal.Terminal;
import com.github.ajalt.mordant.terminal.TerminalInterface;
import com.github.ajalt.mordant.widgets.Padding;
import com.github.ajalt.mordant.widgets.Panel;

/**
 * Mordant 终端渲染辅助工具。
 * <p>
 * 封装 Mordant 的 Kotlin API 为 Java 友好的静态方法。
 * Mordant 是 Kotlin 优先的库，Java 调用时通过枚举常量的 {@code invoke} 等方法访问。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class MordantHelper {

    /**
     * 平台原生终端接口，通过反射查找实现。
     */
    private static final TerminalInterface TERMINAL_INTERFACE = findTerminalInterface();

    /**
     * 共享终端实例，自动检测终端能力（颜色支持、宽度等）。
     */
    private static final Terminal TERMINAL = createTerminal();

    /**
     * 创建默认 Terminal（适配 Mordant 3.x Kotlin 默认参数构造）。
     * <p>
     * 若找不到平台原生 TerminalInterface，则返回 null。
     * </p>
     *
     * @return Terminal 实例，失败时返回 null
     */
    public static Terminal createTerminal() {
        try {
            return new Terminal(null, Theme.Companion.getDefault(), null, null, null, null, null, 8, null, TERMINAL_INTERFACE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过反射查找平台原生 TerminalInterface 实现。
     *
     * @return TerminalInterface
     */
    private static TerminalInterface findTerminalInterface() {
        String osName = System.getProperty("os.name", "unknown").toLowerCase();
        try {
            if (osName.contains("win")) {
                Class<?> cls = Class.forName(
                        "com.github.ajalt.mordant.terminal.terminalinterface.jna.TerminalInterfaceJnaWindows");
                return (TerminalInterface) ReflectUtils.instantiate(cls);
            }
            Class<?> cls = Class.forName(
                    "com.github.ajalt.mordant.terminal.terminalinterface.jna.TerminalInterfaceJnaLinux");
            return (TerminalInterface) ReflectUtils.instantiate(cls);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 清屏并回到左上角。
     *
     * @param terminal 终端实例
     */
    public static void clearScreen(Terminal terminal) {
        if (terminal != null) {
            terminal.rawPrint("\u001b[H\u001b[2J", false);
        }
    }

    /**
     * 输出一行文本。
     *
     * @param terminal 终端实例
     * @param text     文本
     */
    public static void println(Terminal terminal, String text) {
        if (terminal != null) {
            terminal.println(text, Whitespace.NORMAL, TextAlign.NONE, OverflowWrap.NORMAL, null, false);
        }
    }

    // ======================== 颜色渲染 ========================

    /**
     * 使用 Mordant 的颜色渲染文本。
     * <p>
     * Mordant 会自动检测终端是否支持 16 色 / 256 色 / RGB，
     * 在不支持的终端上自动降级。
     * </p>
     *
     * @param text  要着色的文本
     * @param color 颜色名称：green / red / yellow / cyan / blue / magenta / white
     * @return 带 ANSI 颜色码的字符串
     */
    public static String color(String text, String color) {
        return switch (color.toLowerCase()) {
            case "green" -> TextColors.green.invoke(text);
            case "red" -> TextColors.red.invoke(text);
            case "yellow" -> TextColors.yellow.invoke(text);
            case "cyan" -> TextColors.cyan.invoke(text);
            case "blue" -> TextColors.blue.invoke(text);
            case "magenta" -> TextColors.magenta.invoke(text);
            case "white" -> TextColors.white.invoke(text);
            default -> text;
        };
    }

    /**
     * 使用 Mordant 渲染粗体文本。
     *
     * @param text 文本
     * @return 带 ANSI 粗体码的字符串
     */
    public static String bold(String text) {
        return TextStyles.bold.invoke(text);
    }

    /**
     * 使用 Mordant 渲染暗淡文本。
     *
     * @param text 文本
     * @return 带 ANSI 暗淡码的字符串
     */
    public static String dim(String text) {
        return TextStyles.dim.invoke(text);
    }

    // ======================== 面板渲染 ========================

    /**
     * 创建带边框的面板。
     * <p>
     * 使用 Mordant 的 {@link Panel} 组件渲染带边框的面板。
     * </p>
     *
     * @param content 面板内容文本
     * @param title   面板标题（显示在左上角）
     * @return 面板的 ANSI 渲染字符串
     */
    public static String panel(String content, String title) {
        return panel(content, title, "single");
    }

    /**
     * 创建带边框的面板（使用指定边框风格）。
     *
     * @param content     面板内容文本
     * @param title       面板标题
     * @param borderStyle 边框风格：round / single / double / bold / none
     * @return 面板的 ANSI 渲染字符串
     */
    public static String panel(String content, String title, String borderStyle) {
        BorderType borderType = resolveBorderType(borderStyle);
        Panel panel = new Panel(content, title, null, true, new Padding(0), borderType, TextAlign.NONE, TextAlign.NONE, null, null);
        if (TERMINAL == null) {
            return content;
        }
        return TERMINAL.render(panel);
    }

    /**
     * 创建带边框面板（内容使用带颜色文本）。
     *
     * @param content     面板内容文本（已含 ANSI 颜色码）
     * @param title       面板标题
     * @param borderStyle 边框风格
     * @return 面板的 ANSI 渲染字符串
     */
    public static String panelStyled(String content, String title, String borderStyle) {
        return panel(content, title, borderStyle);
    }

    /**
     * 解析边框风格。
     *
     * @param borderStyle 边框风格名称
     * @return BorderType
     */
    private static BorderType resolveBorderType(String borderStyle) {
        if (borderStyle == null) {
            return BorderType.Companion.getSQUARE();
        }
        return switch (borderStyle.toLowerCase()) {
            case "round" -> BorderType.Companion.getROUNDED();
            case "double" -> BorderType.Companion.getDOUBLE();
            case "bold" -> BorderType.Companion.getHEAVY();
            case "none" -> BorderType.Companion.getBLANK();
            default -> BorderType.Companion.getSQUARE();
        };
    }

    // ======================== 进度条 ========================

    /**
     * 渲染彩色进度条。
     * <p>
     * 使用 Mordant 的颜色 API 生成带颜色的进度条。
     * </p>
     *
     * @param usage 使用率（0-100）
     * @param width 进度条字符宽度
     * @return 渲染后的进度条字符串（不含换行）
     */
    public static String progressBar(double usage, int width) {
        int filled = clamp((int) (usage / 100.0 * width), 0, width);
        int empty = width - filled;

        String bar = "█".repeat(filled) + "░".repeat(empty);
        String colorName = getColorName(usage);
        return color(bar, colorName);
    }

    /**
     * 渲染带百分比的进度条。
     *
     * @param usage 使用率（0-100）
     * @param width 进度条字符宽度
     * @return 格式如 "████░░░░ 45.2%"
     */
    public static String progressBarWithPercent(double usage, int width) {
        String bar = progressBar(usage, width);
        String colorName = getColorName(usage);
        String pct = color(String.format(" %5.1f%%", usage), colorName);
        return bar + pct;
    }

    /**
     * 根据使用率获取颜色名称。
     *
     * @param usage 使用率（0-100）
     * @return 颜色名称
     */
    private static String getColorName(double usage) {
        if (usage >= 80) {
            return "red";
        } else if (usage >= 50) {
            return "yellow";
        } else {
            return "green";
        }
    }

    /**
     * 将值限制在范围内。
     *
     * @param value 原值
     * @param min   最小值
     * @param max   最大值
     * @return 限制后的值
     */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
