package com.chua.common.support.lang.ast.parser;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认表达式解析器。
 *
 * <p>支持通用表达式语法，如：
 * <ul>
 *   <li>{@code age > 18 AND status == 'active'}</li>
 *   <li>{@code name LIKE '%test%' OR id IN (1, 2, 3)}</li>
 *   <li>{@code NOT disabled AND (role = 'admin' OR role = 'super')}</li>
 * </ul>
 *
 * <p>支持的运算符：
 * <ul>
 *   <li>逻辑：AND/&amp;&amp;、OR/||、NOT/!</li>
 *   <li>比较：=、==、!=、&lt;&gt;、&gt;、&lt;、&gt;=、&lt;=</li>
 *   <li>特殊：LIKE、IN、BETWEEN、IS NULL、IS NOT NULL</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("expr")
public class DefaultExpressionParser implements ExpressionParser {

    /**
     * SPI 类型标识
     */
    private static final String TYPE = "expr";

    /**
     * 表达式不能为空时的错误提示
     */
    private static final String ERROR_EXPR_EMPTY = "表达式不能为空";

    /**
     * 表达式解析不完整时的错误前缀
     */
    private static final String ERROR_EXPR_INCOMPLETE = "表达式解析不完整，剩余: ";

    /**
     * 期望字符时的错误提示前缀
     */
    private static final String ERROR_EXPECT_CHAR_PREFIX = "期望 '";

    /**
     * 期望字符时的错误提示中段
     */
    private static final String ERROR_EXPECT_CHAR_MIDDLE = "'，位置 ";

    /**
     * 期望字符时的错误提示后段
     */
    private static final String ERROR_EXPECT_CHAR_SUFFIX = "，剩余: ";

    /**
     * 意外字符时的错误提示前缀
     */
    private static final String ERROR_UNEXPECTED_PREFIX = "意外的字符: ";

    /**
     * IS NULL 运算符
     */
    private static final String OP_IS_NULL = "IS NULL";

    /**
     * IS NOT NULL 运算符
     */
    private static final String OP_IS_NOT_NULL = "IS NOT NULL";

    /**
     * IN 运算符
     */
    private static final String OP_IN = "IN";

    /**
     * BETWEEN 运算符
     */
    private static final String OP_BETWEEN = "BETWEEN";

    /**
     * BETWEEN 内部 AND 占位
     */
    private static final String OP_AND_PLACEHOLDER = "AND";

    /**
     * 比较运算符：大于等于
     */
    private static final String OP_GTE = ">=";

    /**
     * 比较运算符：小于等于
     */
    private static final String OP_LTE = "<=";

    /**
     * 比较运算符：不等于
     */
    private static final String OP_NE = "!=";

    /**
     * 比较运算符：等于
     */
    private static final String OP_EQ = "=";

    /**
     * 比较运算符：大于
     */
    private static final String OP_GT = ">";

    /**
     * 比较运算符：小于
     */
    private static final String OP_LT = "<";

    /**
     * IS 关键字
     */
    private static final String KW_IS = "IS";

    /**
     * LIKE 关键字
     */
    private static final String KW_LIKE = "LIKE";

    /**
     * BETWEEN 关键字
     */
    private static final String KW_BETWEEN = "BETWEEN";

    /**
     * IN 关键字
     */
    private static final String KW_IN = "IN";

    /**
     * NULL 关键字
     */
    private static final String KW_NULL = "NULL";

    /**
     * NOT 关键字
     */
    private static final String KW_NOT = "NOT";

    /**
     * AND 关键字
     */
    private static final String KW_AND = "AND";

    /**
     * OR 关键字
     */
    private static final String KW_OR = "OR";

    /**
     * TRUE 关键字
     */
    private static final String KW_TRUE = "TRUE";

    /**
     * FALSE 关键字
     */
    private static final String KW_FALSE = "FALSE";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public BTreeNode parse(String expression) {
        if (StringUtils.isBlank(expression)) {
            throw new IllegalArgumentException(ERROR_EXPR_EMPTY);
        }
        Parser parser = new Parser(expression.trim());
        BTreeNode result = parser.parseOr();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException(ERROR_EXPR_INCOMPLETE + parser.remaining());
        }
        return result;
    }

    @Override
    public String generate(BTreeNode tree) {
        if (tree == null) {
            return "";
        }
        switch (tree.getType()) {
            case LOGIC:
                return generateLogic(tree);
            case NOT:
                return "(NOT " + generate(tree.getRight()) + ")";
            case COMPARE:
                return generateCompare(tree);
            case COLUMN:
                return tree.getOperator();
            case VALUE:
                return generateValue(tree);
            case FUNCTION:
                return generateFunction(tree);
            case RAW:
                return tree.getOperator();
            default:
                return "";
        }
    }

    /**
     * 生成逻辑节点文本
     *
     * @param tree 逻辑节点
     * @return 文本表示
     */
    private String generateLogic(BTreeNode tree) {
        String left = generate(tree.getLeft());
        String right = generate(tree.getRight());
        return "(" + left + " " + tree.getOperator() + " " + right + ")";
    }

    /**
     * 生成比较节点文本
     *
     * @param tree 比较节点
     * @return 文本表示
     */
    private String generateCompare(BTreeNode tree) {
        String left = generate(tree.getLeft());
        String right = generate(tree.getRight());
        return left + " " + tree.getOperator() + " " + right;
    }

    /**
     * 生成值节点文本
     *
     * @param tree 值节点
     * @return 文本表示
     */
    private String generateValue(BTreeNode tree) {
        Object v = tree.getValue();
        if (v instanceof String s) {
            return "'" + s + "'";
        }
        return String.valueOf(v);
    }

    /**
     * 生成函数节点文本
     *
     * @param tree 函数节点
     * @return 文本表示
     */
    private String generateFunction(BTreeNode tree) {
        StringBuilder sb = new StringBuilder();
        sb.append(tree.getOperator());
        sb.append("(");
        List<BTreeNode> children = tree.getChildren();
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(generate(children.get(i)));
        }
        sb.append(")");
        return sb.toString();
    }

    // ==================== 内部解析器 ====================

    /**
     * 递归下降解析器。
     *
     * <p>解析优先级：OR &lt; AND &lt; NOT &lt; 比较 &lt; 原子</p>
     *
     * @author CH
     * @since 4.0.0.42
     */
    private static class Parser {

        /**
         * 输入表达式原文
         */
        private final String input;

        /**
         * 当前解析位置
         */
        private int pos;

        Parser(String input) {
            this.input = input;
            this.pos = 0;
        }

        /**
         * 解析 OR 表达式（最低优先级）。
         *
         * @return OR 表达式根节点
         */
        BTreeNode parseOr() {
            BTreeNode left = parseAnd();
            while (matchKeyword(KW_OR) || matchSymbol("||")) {
                BTreeNode right = parseAnd();
                left = BTreeNode.or(left, right);
            }
            return left;
        }

        /**
         * 解析 AND 表达式。
         *
         * @return AND 表达式根节点
         */
        BTreeNode parseAnd() {
            BTreeNode left = parseNot();
            while (matchKeyword(KW_AND) || matchSymbol("&&")) {
                BTreeNode right = parseNot();
                left = BTreeNode.and(left, right);
            }
            return left;
        }

        /**
         * 解析 NOT 表达式。
         *
         * @return NOT 表达式根节点
         */
        BTreeNode parseNot() {
            if (matchKeyword(KW_NOT) || matchSymbol("!")) {
                BTreeNode child = parseNot();
                return BTreeNode.not(child);
            }
            return parseComparison();
        }

        /**
         * 解析比较表达式。
         *
         * @return 比较表达式根节点
         */
        BTreeNode parseComparison() {
            BTreeNode left = parseAtom();
            String op = matchCompareOp();
            if (op == null) {
                return left;
            }
            if (KW_IS.equalsIgnoreCase(op)) {
                return parseIsNull(left);
            }
            if (KW_IN.equalsIgnoreCase(op)) {
                BTreeNode right = parseInList();
                return BTreeNode.compare(OP_IN, left, right);
            }
            if (KW_BETWEEN.equalsIgnoreCase(op)) {
                return parseBetween(left);
            }
            BTreeNode right = parseAtom();
            return BTreeNode.compare(op, left, right);
        }

        /**
         * 解析 IS NULL / IS NOT NULL 子句。
         *
         * @param left 左子节点
         * @return IS NULL 表达式根节点
         */
        BTreeNode parseIsNull(BTreeNode left) {
            if (matchKeyword(KW_NOT)) {
                matchKeyword(KW_NULL);
                return BTreeNode.compare(OP_IS_NOT_NULL, left, null);
            }
            matchKeyword(KW_NULL);
            return BTreeNode.compare(OP_IS_NULL, left, null);
        }

        /**
         * 解析 BETWEEN 子句。
         *
         * @param left 左子节点
         * @return BETWEEN 表达式根节点
         */
        BTreeNode parseBetween(BTreeNode left) {
            BTreeNode low = parseAtom();
            matchKeyword(KW_AND);
            BTreeNode high = parseAtom();
            return BTreeNode.compare(OP_BETWEEN, left,
                    BTreeNode.compare(OP_AND_PLACEHOLDER, low, high));
        }

        /**
         * 解析 IN 列表：(1, 2, 3) 或 (1,2,3)。
         *
         * @return IN 列表值节点
         */
        BTreeNode parseInList() {
            expect('(');
            StringBuilder list = new StringBuilder();
            list.append('(');
            skipWhitespace();
            int count = 0;
            while (!match(')')) {
                if (count > 0) {
                    match(',');
                    skipWhitespace();
                }
                list.append(readValue());
                count++;
                skipWhitespace();
            }
            list.append(')');
            return BTreeNode.value(list.toString());
        }

        /**
         * 解析原子表达式（括号、列名、值、函数）。
         *
         * @return 原子表达式根节点
         */
        BTreeNode parseAtom() {
            skipWhitespace();
            if (match('(')) {
                BTreeNode node = parseOr();
                expect(')');
                return node;
            }
            // 函数调用
            if (peekIsLetter() && peekNextIs('(')) {
                return parseFunction();
            }
            // 列名或关键字值
            return parseColumnOrValue();
        }

        /**
         * 解析函数调用：funcName(arg1, arg2, ...)。
         *
         * @return 函数节点
         */
        BTreeNode parseFunction() {
            String name = readIdentifier();
            expect('(');
            List<BTreeNode> args = new ArrayList<>();
            skipWhitespace();
            if (!match(')')) {
                do {
                    skipWhitespace();
                    args.add(parseOr());
                    skipWhitespace();
                } while (match(','));
                expect(')');
            }
            BTreeNode[] array = args.toArray(new BTreeNode[0]);
            return BTreeNode.function(name, array);
        }

        /**
         * 解析列名或值。
         *
         * @return 列节点或值节点
         */
        BTreeNode parseColumnOrValue() {
            skipWhitespace();
            // 字符串值
            if (match('\'') || match('"')) {
                char quote = input.charAt(pos - 1);
                return BTreeNode.value(readQuoted(quote));
            }
            // 数字值
            if (isPeekNumber()) {
                return BTreeNode.value(readNumber());
            }
            // TRUE/FALSE
            if (matchKeyword(KW_TRUE)) {
                return BTreeNode.value(Boolean.TRUE);
            }
            if (matchKeyword(KW_FALSE)) {
                return BTreeNode.value(Boolean.FALSE);
            }
            // NULL
            if (matchKeyword(KW_NULL)) {
                return BTreeNode.value((Object) null);
            }
            // 列名或标识符
            String identifier = readIdentifier();
            if (identifier.isEmpty()) {
                throw new IllegalArgumentException(
                        ERROR_UNEXPECTED_PREFIX + (pos < input.length() ? input.charAt(pos) : "EOF"));
            }
            return BTreeNode.column(identifier);
        }

        /**
         * 判断当前位置是否为数字（含负数）。
         *
         * @return true 表示是数字
         */
        boolean isPeekNumber() {
            if (peekIsDigit()) {
                return true;
            }
            if (peekIs('-')
                    && pos + 1 < input.length()
                    && input.charAt(pos + 1) >= '0'
                    && input.charAt(pos + 1) <= '9') {
                return true;
            }
            return false;
        }

        // ==================== 工具方法 ====================

        /**
         * 尝试匹配关键字（不消费非完整单词）。
         *
         * @param keyword 关键字
         * @return true 表示匹配成功并已消费
         */
        boolean matchKeyword(String keyword) {
            skipWhitespace();
            if (pos + keyword.length() <= input.length()
                    && input.substring(pos, pos + keyword.length()).equalsIgnoreCase(keyword)) {
                // 确保是完整单词
                int end = pos + keyword.length();
                if (end < input.length() && Character.isLetterOrDigit(input.charAt(end))) {
                    return false;
                }
                pos += keyword.length();
                return true;
            }
            return false;
        }

        /**
         * 尝试匹配符号串。
         *
         * @param symbol 符号
         * @return true 表示匹配成功并已消费
         */
        boolean matchSymbol(String symbol) {
            skipWhitespace();
            if (pos + symbol.length() <= input.length()
                    && input.substring(pos, pos + symbol.length()).equals(symbol)) {
                pos += symbol.length();
                return true;
            }
            return false;
        }

        /**
         * 尝试匹配单字符。
         *
         * @param c 目标字符
         * @return true 表示匹配成功并已消费
         */
        boolean match(char c) {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        /**
         * 期望匹配指定字符，否则抛出异常。
         *
         * @param c 目标字符
         */
        void expect(char c) {
            if (!match(c)) {
                throw new IllegalArgumentException(
                        ERROR_EXPECT_CHAR_PREFIX + c
                                + ERROR_EXPECT_CHAR_MIDDLE + pos
                                + ERROR_EXPECT_CHAR_SUFFIX + remaining());
            }
        }

        /**
         * 尝试匹配比较运算符。
         *
         * @return 比较运算符字符串，未匹配返回 null
         */
        String matchCompareOp() {
            skipWhitespace();
            // 多字符运算符优先
            if (matchKeyword(KW_LIKE)) {
                return KW_LIKE;
            }
            if (matchKeyword(KW_IN)) {
                return KW_IN;
            }
            if (matchKeyword(KW_BETWEEN)) {
                return KW_BETWEEN;
            }
            if (matchKeyword(KW_IS)) {
                return KW_IS;
            }
            // 二元运算符
            if (matchSymbol(OP_GTE)) {
                return OP_GTE;
            }
            if (matchSymbol(OP_LTE)) {
                return OP_LTE;
            }
            if (matchSymbol("<>")) {
                return OP_NE;
            }
            if (matchSymbol(OP_NE)) {
                return OP_NE;
            }
            if (matchSymbol("==")) {
                return OP_EQ;
            }
            if (matchSymbol(OP_EQ)) {
                return OP_EQ;
            }
            if (matchSymbol(OP_GT)) {
                return OP_GT;
            }
            if (matchSymbol(OP_LT)) {
                return OP_LT;
            }
            return null;
        }

        /**
         * 跳过空白字符。
         */
        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        /**
         * 读取标识符（字母数字下划线）。
         *
         * @return 标识符字符串
         */
        String readIdentifier() {
            int start = pos;
            while (pos < input.length()
                    && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                pos++;
            }
            return input.substring(start, pos);
        }

        /**
         * 读取 IN 列表中的字面值（带原始引号）。
         *
         * @return 列表元素文本
         */
        String readValue() {
            skipWhitespace();
            if (match('\'') || match('"')) {
                char quote = input.charAt(pos - 1);
                return "'" + readQuoted(quote) + "'";
            }
            if (peekIsDigit() || peekIs('-')) {
                return String.valueOf(readNumber());
            }
            if (matchKeyword(KW_TRUE)) {
                return KW_TRUE;
            }
            if (matchKeyword(KW_FALSE)) {
                return KW_FALSE;
            }
            if (matchKeyword(KW_NULL)) {
                return KW_NULL;
            }
            return readIdentifier();
        }

        /**
         * 读取带引号字符串内容（不含引号）。
         *
         * @param quote 引号字符
         * @return 字符串内容
         */
        String readQuoted(char quote) {
            int start = pos;
            while (pos < input.length() && input.charAt(pos) != quote) {
                if (input.charAt(pos) == '\\') {
                    pos++;
                }
                pos++;
            }
            String value = input.substring(start, pos);
            if (pos < input.length()) {
                pos++;
            }
            return value;
        }

        /**
         * 读取数字（整数或浮点数）。
         *
         * @return Number 类型数字
         */
        Number readNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
            }
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
            boolean isDecimal = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            String numStr = input.substring(start, pos);
            if (isDecimal) {
                return Double.parseDouble(numStr);
            }
            return Integer.parseInt(numStr);
        }
        /**
         * 预读当前位置是否为字母。
         *
         * @return true 表示是字母
         */
        boolean peekIsLetter() {
            return pos < input.length() && Character.isLetter(input.charAt(pos));
        }

        /**
         * 预读当前位置是否为数字。
         *
         * @return true 表示是数字
         */
        boolean peekIsDigit() {
            return pos < input.length() && Character.isDigit(input.charAt(pos));
        }

        /**
         * 预读当前位置是否为指定字符。
         *
         * @param c 目标字符
         * @return true 表示匹配
         */
        boolean peekIs(char c) {
            return pos < input.length() && input.charAt(pos) == c;
        }

        /**
         * 预读下一位置是否为指定字符。
         *
         * @param c 目标字符
         * @return true 表示匹配
         */
        boolean peekNextIs(char c) {
            return pos + 1 < input.length() && input.charAt(pos + 1) == c;
        }

        /**
         * 判断是否解析到末尾。
         *
         * @return true 表示已到达末尾
         */
        boolean isEnd() {
            skipWhitespace();
            return pos >= input.length();
        }

        /**
         * 获取尚未消费的剩余输入。
         *
         * @return 剩余字符串
         */
        String remaining() {
            if (pos < input.length()) {
                return input.substring(pos);
            }
            return "";
        }
    }
}
