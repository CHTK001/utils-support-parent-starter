package com.chua.common.support.lang.ast.parser;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.spi.annotations.Spi;
import org.jspecify.annotations.NullUnmarked;

/**
 * 默认表达式解析器
 *
 * <p>支持通用表达式语法，如：
 * <ul>
 *   <li>age > 18 AND status == 'active'</li>
 *   <li>name LIKE '%test%' OR id IN (1, 2, 3)</li>
 *   <li>NOT disabled AND (role = 'admin' OR role = 'super')</li>
 * </ul>
 *
 * <p>支持的运算符：
 * <ul>
 *   <li>逻辑：AND/&&、OR/||、NOT/!</li>
 *   <li>比较：=、==、!=、<>、>、<、>=、<=</li>
 *   <li>特殊：LIKE、IN、BETWEEN、IS NULL、IS NOT NULL</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/16
 */
@NullUnmarked
@SuppressWarnings("NullAway")
@Spi("expr")
public class DefaultExpressionParser implements ExpressionParser {

    /**
     * 类型
     */
    private static final String TYPE = "expr";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public BTreeNode parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("表达式不能为空");
        }
        Parser parser = new Parser(expression.trim());
        BTreeNode result = parser.parseOr();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException("表达式解析不完整，剩余: " + parser.remaining());
        }
        return result;
    }

    @Override
    public String generate(BTreeNode tree) {
        if (tree == null) {
            return "";
        }
        return switch (tree.getType()) {
            case LOGIC -> {
                String left = generate(tree.getLeft());
                String right = generate(tree.getRight());
                yield "(" + left + " " + tree.getOperator() + " " + right + ")";
            }
            case NOT -> "(NOT " + generate(tree.getRight()) + ")";
            case COMPARE -> {
                String left = generate(tree.getLeft());
                String right = generate(tree.getRight());
                yield left + " " + tree.getOperator() + " " + right;
            }
            case COLUMN -> tree.getOperator();
            case VALUE -> {
                Object v = tree.getValue();
                yield v instanceof String s ? "'" + s + "'" : String.valueOf(v);
            }
            case FUNCTION -> {
                StringBuilder sb = new StringBuilder(tree.getOperator()).append("(");
                for (int i = 0; i < tree.getChildren().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(generate(tree.getChildren().get(i)));
                }
                sb.append(")");
                yield sb.toString();
            }
            case RAW -> tree.getOperator();
        };
    }

    // ==================== 内部解析器 ====================

    /**
     * 递归下降解析器
     *
     * <p>解析优先级：OR < AND < NOT < 比较 < 原子
     */
    private static class Parser {

        /**
         * 输入路径
         */
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
            this.pos = 0;
        }

        /** 解析 OR 表达式（最低优先级） */
        BTreeNode parseOr() {
            BTreeNode left = parseAnd();
            while (matchKeyword("OR") || matchSymbol("||")) {
                BTreeNode right = parseAnd();
                left = BTreeNode.or(left, right);
            }
            return left;
        }

        /** 解析 AND 表达式 */
        BTreeNode parseAnd() {
            BTreeNode left = parseNot();
            while (matchKeyword("AND") || matchSymbol("&&")) {
                BTreeNode right = parseNot();
                left = BTreeNode.and(left, right);
            }
            return left;
        }

        /** 解析 NOT 表达式 */
        BTreeNode parseNot() {
            if (matchKeyword("NOT") || matchSymbol("!")) {
                BTreeNode child = parseNot();
                return BTreeNode.not(child);
            }
            return parseComparison();
        }

        /** 解析比较表达式 */
        BTreeNode parseComparison() {
            BTreeNode left = parseAtom();
            String op = matchCompareOp();
            if (op != null) {
                if ("IS".equalsIgnoreCase(op)) {
                    if (matchKeyword("NOT")) {
                        matchKeyword("NULL");
                        return BTreeNode.compare("IS NOT NULL", left, null);
                    }
                    matchKeyword("NULL");
                    return BTreeNode.compare("IS NULL", left, null);
                }
                if ("IN".equalsIgnoreCase(op)) {
                    BTreeNode right = parseInList();
                    return BTreeNode.compare("IN", left, right);
                }
                if ("BETWEEN".equalsIgnoreCase(op)) {
                    BTreeNode low = parseAtom();
                    matchKeyword("AND");
                    BTreeNode high = parseAtom();
                    return BTreeNode.compare("BETWEEN", left,
                            BTreeNode.compare("AND", low, high));
                }
                BTreeNode right = parseAtom();
                return BTreeNode.compare(op, left, right);
            }
            return left;
        }

        /** 解析 IN 列表：(1, 2, 3) 或 (1,2,3) */
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

        /** 解析原子表达式（括号、列名、值、函数） */
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

        /** 解析函数调用：funcName(arg1, arg2, ...) */
        BTreeNode parseFunction() {
            String name = readIdentifier();
            expect('(');
            var args = new java.util.ArrayList<BTreeNode>();
            skipWhitespace();
            if (!match(')')) {
                do {
                    skipWhitespace();
                    args.add(parseOr());
                    skipWhitespace();
                } while (match(','));
                expect(')');
            }
            return BTreeNode.function(name, args.toArray(new BTreeNode[0]));
        }

        /** 解析列名或值 */
        BTreeNode parseColumnOrValue() {
            skipWhitespace();
            // 字符串值
            if (match('\'') || match('"')) {
                char quote = input.charAt(pos - 1);
                return BTreeNode.value(readQuoted(quote));
            }
            // 数字值
            if (peekIsDigit() || (peekIs('-') && pos + 1 < input.length() && input.charAt(pos + 1) >= '0' && input.charAt(pos + 1) <= '9')) {
                return BTreeNode.value(readNumber());
            }
            // TRUE/FALSE
            if (matchKeyword("TRUE")) {
                return BTreeNode.value(Boolean.TRUE);
            }
            if (matchKeyword("FALSE")) {
                return BTreeNode.value(Boolean.FALSE);
            }
            // NULL
            if (matchKeyword("NULL")) {
                return BTreeNode.value((Object) null);
            }
            // 列名或标识符
            String identifier = readIdentifier();
            if (identifier.isEmpty()) {
                throw new IllegalArgumentException("意外的字符: " + (pos < input.length() ? input.charAt(pos) : "EOF"));
            }
            return BTreeNode.column(identifier);
        }

        // ==================== 工具方法 ====================

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

        boolean matchSymbol(String symbol) {
            skipWhitespace();
            if (pos + symbol.length() <= input.length()
                    && input.substring(pos, pos + symbol.length()).equals(symbol)) {
                pos += symbol.length();
                return true;
            }
            return false;
        }

        boolean match(char c) {
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }

        void expect(char c) {
            if (!match(c)) {
                throw new IllegalArgumentException("期望 '" + c + "'，位置 " + pos
                        + "，剩余: " + remaining());
            }
        }

        String matchCompareOp() {
            skipWhitespace();
            // 多字符运算符优先
            if (matchKeyword("LIKE")) {
                return "LIKE";
            }
            if (matchKeyword("IN")) {
                return "IN";
            }
            if (matchKeyword("BETWEEN")) {
                return "BETWEEN";
            }
            if (matchKeyword("IS")) {
                return "IS";
            }
            // 二元运算符
            if (matchSymbol(">=")) {
                return ">=";
            }
            if (matchSymbol("<=")) {
                return "<=";
            }
            if (matchSymbol("<>")) {
                return "!=";
            }
            if (matchSymbol("!=")) {
                return "!=";
            }
            if (matchSymbol("==")) {
                return "=";
            }
            if (matchSymbol("=")) {
                return "=";
            }
            if (matchSymbol(">")) {
                return ">";
            }
            if (matchSymbol("<")) {
                return "<";
            }
            return null;
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        String readIdentifier() {
            int start = pos;
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                pos++;
            }
            return input.substring(start, pos);
        }

        String readValue() {
            skipWhitespace();
            if (match('\'') || match('"')) {
                char quote = input.charAt(pos - 1);
                return "'" + readQuoted(quote) + "'";
            }
            if (peekIsDigit() || (peekIs('-'))) {
                return String.valueOf(readNumber());
            }
            if (matchKeyword("TRUE")) {
                return "TRUE";
            }
            if (matchKeyword("FALSE")) {
                return "FALSE";
            }
            if (matchKeyword("NULL")) {
                return "NULL";
            }
            return readIdentifier();
        }

        String readQuoted(char quote) {
            int start = pos;
            while (pos < input.length() && input.charAt(pos) != quote) {
                if (input.charAt(pos) == '\\') {
                    pos++; // 跳过转义
                }
                pos++;
            }
            String value = input.substring(start, pos);
            if (pos < input.length()) {
                // 跳过闭合引号
                pos++;
            }
            return value;

        }

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
            return isDecimal ? Double.parseDouble(numStr) : Integer.parseInt(numStr);
        }

        boolean peekIsLetter() {
            return pos < input.length() && Character.isLetter(input.charAt(pos));
        }

        boolean peekIsDigit() {
            return pos < input.length() && Character.isDigit(input.charAt(pos));
        }

        boolean peekIs(char c) {
            return pos < input.length() && input.charAt(pos) == c;
        }

        boolean peekNextIs(char c) {
            return pos + 1 < input.length() && input.charAt(pos + 1) == c;
        }

        boolean isEnd() {
            skipWhitespace();
            return pos >= input.length();
        }

        String remaining() {
            return pos < input.length() ? input.substring(pos) : "";
        }
    }
}
