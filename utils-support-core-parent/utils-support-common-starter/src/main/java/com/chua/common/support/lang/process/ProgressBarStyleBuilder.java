package com.chua.common.support.lang.process;

import lombok.Data;

/**
 * 进度条样式构建器。
 * <p>
 * 用于构建和配置控制台进度条的显示样式，包括颜色、分隔符、填充字符等。
 * 支持 ANSI 转义序列以实现彩色输出。
 *
 * @author CH
 * @since 2023-04-01
 * @version 1.0.0
 */
@Data
public class ProgressBarStyleBuilder {

    /**
     * ANSI 转义码前缀常量。
     */
    private static final String ESC_CODE = "\u001b[";

    /**
     * 默认进度条样式实例。
     * 初始化为：刷新提示符"\r"，左括号"["，定界序列""，右括号"]"，块字符'='，空格字符' '，
     * 右侧分数符号'>'，右侧空格字符' '。
     */
    private final ProgressBarStyle style = new ProgressBarStyle("\r", "[", "", "]", '=', ' ', ">", ' ');

    /**
     * ANSI 颜色代码。
     * 值为 0 表示未设置颜色，非 0 值表示具体的颜色编号（0-255）。
     */
    private byte colorCode = 0;

    /**
     * 设置刷新提示符。
     *
     * @param refreshPrompt 刷新提示符字符串，默认为"\r"。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder refreshPrompt(String refreshPrompt) {
        style.setRefreshPrompt(refreshPrompt);
        return this;
    }

    /**
     * 设置左括号字符。
     *
     * @param leftBracket 左括号字符串，支持 ANSI 或 Unicode 字符，默认为"["。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder leftBracket(String leftBracket) {
        style.setLeftBracket(leftBracket);
        return this;
    }

    /**
     * 设置定界序列。
     *
     * @param delimitingSequence 定界序列字符串，默认为空字符串""。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder delimitingSequence(String delimitingSequence) {
        style.setDelimitingSequence(delimitingSequence);
        return this;
    }

    /**
     * 设置右括号字符。
     *
     * @param rightBracket 右括号字符串，支持 ANSI 或 Unicode 字符，默认为"]"。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder rightBracket(String rightBracket) {
        style.setRightBracket(rightBracket);
        return this;
    }

    /**
     * 设置进度块字符。
     *
     * @param block 进度块字符，默认为'='。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder block(char block) {
        style.setBlock(block);
        return this;
    }

    /**
     * 设置空白填充字符。
     *
     * @param space 空白填充字符，默认为' '。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder space(char space) {
        style.setSpace(space);
        return this;
    }

    /**
     * 设置分数部分符号字符串。
     *
     * @param fractionSymbols 分数符号字符串，默认为多个空格。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder fractionSymbols(String fractionSymbols) {
        style.setFractionSymbols(fractionSymbols);
        return this;
    }

    /**
     * 设置右侧分数符号字符。
     *
     * @param rightSideFractionSymbol 右侧分数符号字符，默认为' '。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder rightSideFractionSymbol(char rightSideFractionSymbol) {
        style.setRightSideFractionSymbol(rightSideFractionSymbol);
        return this;
    }

    /**
     * 设置 ANSI 颜色代码。
     *
     * @param code ANSI 颜色代码，范围在 0 到 255 之间。
     *             值为 0 表示不启用颜色。
     * @return 当前 Builder 实例，用于链式调用。
     */
    public ProgressBarStyleBuilder colorCode(byte code) {
        this.colorCode = code;
        return this;
    }

    /**
     * 构建最终的进度条样式对象。
     * <p>
     * 如果已设置颜色代码且左括号中已包含 ANSI 转义码，则抛出异常。
     * 否则，将颜色控制码添加到左括号前和右括号后。
     *
     * @return 构建完成的 ProgressBarStyle 对象。
     * @throws IllegalArgumentException 当颜色已定义但左括号已包含 ANSI 转义码时抛出。
     */
    public ProgressBarStyle build() {
        boolean colorDefined = colorCode != 0;

        if (colorDefined && style.getLeftBracket().contains(ESC_CODE)) {
            throw new IllegalArgumentException("颜色代码已定义，但左括号中已包含 ANSI 转义码");
        }

        String prefix;
        String postfix;

        if (colorDefined) {
            prefix = ESC_CODE + colorCode + "m";
            postfix = ESC_CODE + "0m";
        } else {
            prefix = "";
            postfix = "";
        }

        style.setLeftBracket(prefix + style.getLeftBracket());
        style.setRightBracket(style.getRightBracket() + postfix);
        return style;
    }

}
