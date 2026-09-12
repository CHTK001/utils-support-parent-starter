package com.chua.common.support.lang.process;

import lombok.Data;

/**
* 进度条样式配置类。
* <p>
* 定义用于控制台进度条显示的字符、颜色代码及分隔符等样式属性。
* 支持多种预设风格，如 Unicode、ASCII、Python 风格以及 ANSI 颜色渐变效果。
*
* @author CH
* @since 2023-04-01
* @version 1.0.0
 */
@Data
public class ProgressBarStyle {

    /**
    * 刷新提示符，通常用于覆盖当前行。
    * 默认值为回车符 "\r"。
     */
    private String refreshPrompt;

    /**
    * 左括号或起始标记。
    * 默认值为 "["，支持 ANSI 转义序列或 Unicode 字符。
     */
    protected String leftBracket;

    /**
    * 分隔符序列，位于左右括号之间或特定元素之间。
    * 默认值为空字符串 ""。
     */
    protected String delimitingSequence;

    /**
    * 右括号或结束标记。
    * 默认值为 "]"，支持 ANSI 转义序列或 Unicode 字符。
     */
    protected String rightBracket;

    /**
    * 进度块字符，表示已完成的进度部分。
    * 默认值为 '='，也可使用 Unicode 块字符。
     */
    protected char block;

    /**
    * 空白填充字符，表示未完成的进度部分。
    * 默认值为空格 ' '。
     */
    protected char space;

    /**
    * 分数符号字符串，用于显示百分比或小数部分的视觉填充。
    * 默认值为 "                      " (16个空格)。
     */
    protected String fractionSymbols;

    /**
    * 右侧分数符号，用于百分比后的单个字符。
    * 默认值为空格 ' '。
     */
    protected char rightSideFractionSymbol;

    /**
    * 构造函数，初始化进度条样式的各个组件。
    *
    * @param refreshPrompt         刷新提示符，默认为 "\r"
    * @param leftBracket           左括号，默认为 "["
    * @param delimitingSequence    分隔符序列，默认为 ""
    * @param rightBracket          右括号，默认为 "]"
    * @param block                 进度块字符，默认为 '='
    * @param space                 空白填充字符，默认为 ' '
    * @param fractionSymbols       分数符号字符串，默认为 "                      "
    * @param rightSideFractionSymbol 右侧分数符号，默认为 ' '
     */
    public ProgressBarStyle(String refreshPrompt, String leftBracket, String delimitingSequence, String rightBracket, char block, char space, String fractionSymbols, char rightSideFractionSymbol) {
        this.refreshPrompt = refreshPrompt;
        this.leftBracket = leftBracket;
        this.delimitingSequence = delimitingSequence;
        this.rightBracket = rightBracket;
        this.block = block;
        this.space = space;
        this.fractionSymbols = fractionSymbols;
        this.rightSideFractionSymbol = rightSideFractionSymbol;
    }

    /**
    * 多彩的 Unicode 块风格进度条。
    * 使用 Unicode 字符和 ANSI 高亮颜色。
     */
    public static final ProgressBarStyle COLORFUL_UNICODE_BLOCK = new ProgressBarStyle(
            "\r",
            "\u001b[33m   ",
            "",
            "   \u001b[0m",
            ' ',
            ' ',
            "                      ",
            ' '
    );

    /**
    * 多彩的 Unicode 条形风格进度条。
    * 结合 ANSI 颜色和 Unicode 条形视觉效果。
     */
    public static final ProgressBarStyle COLORFUL_UNICODE_BAR = new ProgressBarStyle(
            "\r",
            "\u001b[33m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "    ",
            ' '
    );

    /**
    * Python 下载风格进度条。
    * 模拟 Python 下载工具的视觉效果。
     */
    public static final ProgressBarStyle PYTHON_DOWNLOAD = new ProgressBarStyle(
            "\r",
            "\u001b[38;5;208m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 灰色到绿色渐变风格进度条。
     */
    public static final ProgressBarStyle GRAY_TO_GREEN = new ProgressBarStyle(
            "\r",
            "\u001b[32m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 蓝色到青色渐变风格进度条。
     */
    public static final ProgressBarStyle BLUE_TO_CYAN = new ProgressBarStyle(
            "\r",
            "\u001b[36m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 红色到黄色渐变风格进度条。
     */
    public static final ProgressBarStyle RED_TO_YELLOW = new ProgressBarStyle(
            "\r",
            "\u001b[33m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 彩虹风格进度条。
     */
    public static final ProgressBarStyle RAINBOW = new ProgressBarStyle(
            "\r",
            "\u001b[35m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 矩阵风格进度条。
    * 模仿电影《黑客帝国》的绿色数字雨风格。
     */
    public static final ProgressBarStyle MATRIX = new ProgressBarStyle(
            "\r",
            "\u001b[92m",
            "\u001b[30m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 火焰风格进度条。
    * 使用红色调模拟火焰效果。
     */
    public static final ProgressBarStyle FIRE = new ProgressBarStyle(
            "\r",
            "\u001b[91m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 海洋风格进度条。
    * 使用蓝色调模拟海洋效果。
     */
    public static final ProgressBarStyle OCEAN = new ProgressBarStyle(
            "\r",
            "\u001b[94m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 霓虹风格进度条。
    * 使用明亮的青色调模拟霓虹灯效果。
     */
    public static final ProgressBarStyle NEON = new ProgressBarStyle(
            "\r",
            "\u001b[96m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 日落风格进度条。
    * 使用紫色/洋红色调模拟日落效果。
     */
    public static final ProgressBarStyle SUNSET = new ProgressBarStyle(
            "\r",
            "\u001b[95m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 标准 Unicode 块风格进度条。
    * 使用简单的竖线 "|" 作为边界。
     */
    public static final ProgressBarStyle UNICODE_BLOCK = new ProgressBarStyle(
            "\r",
            "|",
            "",
            "|",
            ' ',
            ' ',
            "                      ",
            ' '
    );

    /**
    * ASCII 风格进度条。
    * 使用纯 ASCII 字符构建，兼容性最好。
     */
    public static final ProgressBarStyle ASCII = new ProgressBarStyle(
            "\r",
            "[",
            "",
            "]",
            '=',
            ' ',
            ">",
            ' '
    );

    /**
    * Python Loading 风格进度条。
    * 模仿 Python alive_progress 库的加载动画风格。
     */
    public static final ProgressBarStyle PYTHON_LOADING = new ProgressBarStyle(
            "\r",
            "\u001b[95m",
            "\u001b[90m",
            "\u001b[0m",
            ' ',
            ' ',
            "   ",
            ' '
    );

    /**
    * 创建一个新的 {@link ProgressBarStyleBuilder} 实例。
    *
    * @return 返回一个进度条样式构建器对象
     */
    public static ProgressBarStyleBuilder builder() {
        return new ProgressBarStyleBuilder();
    }
}