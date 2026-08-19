package com.chua.common.support.lang.ast.parser;

import com.chua.common.support.lang.ast.BTreeNode;
import com.chua.common.support.lang.ast.ExpressionParser;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.util.List;

/**
 * Neo4j Cypher 表达式解析器
 *
 * <p>支持 Neo4j Cypher 查询中 WHERE 子句的条件表达式解析和生成。</p>
 *
 * <h3>支持的语法</h3>
 * <pre>
 *   n.age &gt; 18 AND n.status = 'active'
 *   n.name CONTAINS 'test'
 *   n.id IN [1, 2, 3]
 *   n.age &gt;= 10 AND n.age &lt;= 60
 *   n.deleted IS NULL
 *   n.name STARTS WITH 'John'
 *   n.name ENDS WITH 'son'
 *   n.name =~ '.*test.*'
 *   (n.age &gt; 18 OR n.role = 'admin') AND n.deleted IS NULL
 *   n.prop.subprop = 'deep'
 * </pre>
 *
 * <h3>Cypher 特有语法映射</h3>
 * <ul>
 *   <li>n.property → COLUMN 节点（点号保留为属性路径分隔符，如 "n.name"）</li>
 *   <li>n.prop.subprop → COLUMN 节点（多级属性路径）</li>
 *   <li>CONTAINS → COMPARE("CONTAINS", left, right)</li>
 *   <li>STARTS WITH → COMPARE("STARTS WITH", left, right)</li>
 *   <li>ENDS WITH → COMPARE("ENDS WITH", left, right)</li>
 *   <li>=~ → COMPARE("=~", left, right)</li>
 *   <li>{@code [...]} → 与 {@code (...)} 等效的 IN 列表语法</li>
 * </ul>
 *
 * <h3>示例</h3>
 * <pre>{@code
 *   ExpressionParser parser = new CypherExpressionParser();
 *
 *   // 解析
 *   BTreeNode tree = parser.parse("n.age > 18 AND n.status = 'active'");
 *
 *   // 生成
 *   String cypher = parser.generate(tree);
 *   // → "(n.age > 18 AND n.status = 'active')"
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("cypher")
public class CypherExpressionParser implements ExpressionParser {

    /**
     * 解析器类型标识
     */
    private static final String TYPE = "cypher";

    /**
     * 空表达式错误消息
     */
    private static final String MSG_EXPRESSION_EMPTY = "Cypher 表达式不能为空";

    /**
     * 表达式解析不完整错误消息前缀
     */
    private static final String MSG_EXPRESSION_INCOMPLETE_PREFIX = "Cypher 表达式解析不完整，剩余: ";

    /**
     * 意外的字符错误消息前缀
     */
    private static final String MSG_UNEXPECTED_CHAR_PREFIX = "意外的字符: ";

    /**
     * 输入结束哨兵
     */
    private static final String EOF_MARKER = "EOF";

    /**
     * IS NULL 运算符
     */
    private static final String OP_IS_NULL = "IS NULL";

    /**
     * IS NOT NULL 运算符
     */
    private static final String OP_IS_NOT_NULL = "IS NOT NULL";

    /**
     * CONTAINS 运算符
     */
    private static final String OP_CONTAINS = "CONTAINS";

    /**
     * STARTS WITH 运算符
     */
    private static final String OP_STARTS_WITH = "STARTS WITH";

    /**
     * ENDS WITH 运算符
     */
    private static final String OP_ENDS_WITH = "ENDS WITH";

    /**
     * 正则匹配运算符
     */
    private static final String OP_REGEX_MATCH = "=~";

    /**
     * BETWEEN 运算符
     */
    private static final String OP_BETWEEN = "BETWEEN";

    /**
     * IN 运算符
     */
    private static final String OP_IN = "IN";

    /**
     * IS 关键字
     */
    private static final String KW_IS = "IS";

    /**
     * NOT 关键字
     */
    private static final String KW_NOT = "NOT";

    /**
     * NULL 关键字
     */
    private static final String KW_NULL = "NULL";

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

    /**
     * LIKE 关键字
     */
    private static final String KW_LIKE = "LIKE";

    /**
     * STARTS 关键字
     */
    private static final String KW_STARTS = "STARTS";

    /**
     * ENDS 关键字
     */
    private static final String KW_ENDS = "ENDS";

    /**
     * WITH 关键字
     */
    private static final String KW_WITH = "WITH";

    /**
     * 空列表占位符
     */
    private static final String EMPTY_LIST_PLACEHOLDER = "()";

    /**
     * 不等号别名
     */
    private static final String OP_NOT_EQUAL = "!=";

    /**
     * 等号
     */
    private static final String OP_EQUAL = "=";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public BTreeNode parse(String expression) {
        if (StringUtils.isEmpty(expression) || expression.isBlank()) {
            throw new IllegalArgumentException(MSG_EXPRESSION_EMPTY);
        }
        CypherParser parser = new CypherParser(expression.trim());
        BTreeNode result = parser.parseOr();
        if (!parser.isEnd()) {
            throw new IllegalArgumentException(
                    MSG_EXPRESSION_INCOMPLETE_PREFIX + parser.remaining());
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
                String op = tree.getOperator();
                String right = generate(tree.getRight());

                if (OP_IS_NULL.equals(op)) {
                    yield left + " " + OP_IS_NULL;
                }
                if (OP_IS_NOT_NULL.equals(op)) {
                    yield left + " " + OP_IS_NOT_NULL;
                }
                if (OP_CONTAINS.equalsIgnoreCase(op)
                        || OP_STARTS_WITH.equalsIgnoreCase(op)
                        || OP_ENDS_WITH.equalsIgnoreCase(op)) {
                    yield left + " " + op.toUpperCase() + " " + right;
                }
                if (OP_REGEX_MATCH.equals(op)) {
                    yield left + " " + OP_REGEX_MATCH + " " + right;
                }
                if (OP_BETWEEN.equalsIgnoreCase(op)) {
                    String low = generate(tree.getRight().getLeft());
                    String high = generate(tree.getRight().getRight());
                    yield left + " " + op + " " + low + " " + KW_AND + " " + high;
                }
                if (OP_IN.equalsIgnoreCase(op)) {
                    // 直接访问原始值，绕过 VALUE 节点的引号包裹
                    BTreeNode listNode = tree.getRight();
                    String list = listNode != null && listNode.getValue() != null
                            ? listNode.getValue().toString() : EMPTY_LIST_PLACEHOLDER;
                    // 将 (1, 2, 3) 还原为 [1, 2, 3]
                    if (list.startsWith("(") && list.endsWith(")")) {
                        list = "[" + list.substring(1, list.length() - 1) + "]";
                    }
                    yield left + " " + op + " " + list;
                }
                yield left + " " + op + " " + right;
            }
            case COLUMN -> tree.getOperator();
            case VALUE -> {
                Object v = tree.getValue();
                if (v instanceof String s) {
                    yield "'" + s + "'";
                }
                yield v == null ? KW_NULL : String.valueOf(v);
            }
            case FUNCTION -> {
                StringBuilder sb = new StringBuilder(tree.getOperator()).append("(");
                List<BTreeNode> children = tree.getChildren();
                for (int i = 0; i < children.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(generate(children.get(i)));
                }
                sb.append(")");
                yield sb.toString();
            }
            case RAW -> tree.getOperator();
        };
    }

    // ==================== 递归下降解析器 ====================

    /**
     * Cypher 表达式递归下降解析器
     *
     * <p>解析优先级：OR &lt; AND &lt; NOT &lt; 比较 &lt; 原子</p>
     *
     * <p>与 DefaultExpressionParser 的区别：
     * <ul>
     *   <li>支持点号属性路径（如 n.name、n.prop.subprop）</li>
     *   <li>支持 CONTAINS、STARTS WITH、ENDS WITH、=~ 运算符</li>
     *   <li>支持方括号列表语法 [1, 2, 3] 用于 IN 表达式</li>
     * </ul>
     *
     * @since 4.0.0.42
     */
    private static class CypherParser {

        /**
         * 输入字符串
         */
        private final String input;

        /**
         * 当前解析位置
         */
        private int pos;

        CypherParser(String input) {
            this.input = input;
            this.pos = 0;
        }

        /**
         * 解析 OR 表达式（最低优先级）
         *
         * @return OR 表达式节点
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
         * 解析 AND 表达式
         *
         * @return AND 表达式节点
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
         * 解析 NOT 表达式
         *
         * @return NOT 表达式节点
         */
        BTreeNode parseNot() {
            if (matchKeyword(KW_NOT) || matchSymbol("!")) {
                BTreeNode child = parseNot();
                return BTreeNode.not(child);
            }
            return parseComparison();
        }

        /**
         * 解析比较表达式
         *
         * @return 比较表达式节点
         */
        BTreeNode parseComparison() {
            BTreeNode left = parseAtom();
            String op = matchCompareOp();
            if (op != null) {
                if (KW_IS.equalsIgnoreCase(op)) {
                    if (matchKeyword(KW_NOT)) {
                        matchKeyword(KW_NULL);
                        return BTreeNode.compare(OP_IS_NOT_NULL, left, null);
                    }
                    matchKeyword(KW_NULL);
                    return BTreeNode.compare(OP_IS_NULL, left, null);
                }
                if (OP_IN.equalsIgnoreCase(op)) {
                    BTreeNode right = parseInList();
                    return BTreeNode.compare(OP_IN, left, right);
                }
                if (OP_BETWEEN.equalsIgnoreCase(op)) {
                    BTreeNode low = parseAtom();
                    matchKeyword(KW_AND);
                    BTreeNode high = parseAtom();
                    return BTreeNode.compare(OP_BETWEEN, left,
                            BTreeNode.compare(KW_AND, low, high));
                }
                if (OP_CONTAINS.equalsIgnoreCase(op)
                        || OP_STARTS_WITH.equalsIgnoreCase(op)
                        || OP_ENDS_WITH.equalsIgnoreCase(op)) {
                    BTreeNode right = parseAtom();
                    return BTreeNode.compare(op.toUpperCase(), left, right);
                }
                if (OP_REGEX_MATCH.equals(op)) {
                    BTreeNode right = parseAtom();
                    return BTreeNode.compare(OP_REGEX_MATCH, left, right);
                }
                BTreeNode right = parseAtom();
                return BTreeNode.compare(op, left, right);
            }
            return left;
        }

        /**
         * 解析 IN 列表
         *
         * <p>支持 Cypher 方括号语法 [1, 2, 3] 和标准圆括号语法 (1, 2, 3)</p>
         *
         * @return IN 列表节点
         */
        BTreeNode parseInList() {
            if (match('[')) {
                StringBuilder list = new StringBuilder();
                list.append('(');
                skipWhitespace();
                int count = 0;
                while (!match(']')) {
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
         * 解析原子表达式
         *
         * <p>支持括号分组、函数调用、列名和常量值</p>
         *
         * @return 原子表达式节点
         */
        BTreeNode parseAtom() {
            skipWhitespace();
            if (match('(')) {
                BTreeNode node = parseOr();
                expect(')');
                return node;
            }
            if (peekIsLetter() && peekNextIs('(')) {
                return parseFunction();
            }
            return parseColumnOrValue();
        }

        /**
         * 解析函数调用
         *
         * @return 函数调用节点
         */
        BTreeNode parseFunction() {
            String name = readIdentifier();
            expect('(');
            List<BTreeNode> args = new java.util.ArrayList<>(4);
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

        /**
         * 解析列名或常量值
         *
         * <p>Cypher 特有：支持点号属性路径（如 n.name、a.b.c）</p>
         *
         * @return 列引用或常量值节点
         */
        BTreeNode parseColumnOrValue() {
            skipWhitespace();
            if (match('\'') || match('"')) {
                char quote = input.charAt(pos - 1);
                return BTreeNode.value(readQuoted(quote));
            }
            if (peekIsDigit()
                    || (peekIs('-') && pos + 1 < input.length()
                    && input.charAt(pos + 1) >= '0'
                    && input.charAt(pos + 1) <= '9')) {
                return BTreeNode.value(readNumber());
            }
            if (matchKeyword(KW_TRUE)) {
                return BTreeNode.value(Boolean.TRUE);
            }
            if (matchKeyword(KW_FALSE)) {
                return BTreeNode.value(Boolean.FALSE);
            }
            if (matchKeyword(KW_NULL)) {
                return BTreeNode.value((Object) null);
            }
            String identifier = readPropertyPath();
            if (StringUtils.isEmpty(identifier)) {
                throw new IllegalArgumentException(MSG_UNEXPECTED_CHAR_PREFIX
                        + (pos < input.length() ? input.charAt(pos) : EOF_MARKER));
            }
            return BTreeNode.column(identifier);
        }

        // ==================== 工具方法 ====================

        /**
         * 匹配比较运算符
         *
         * <p>Cypher 特有运算符：
         * <ul>
         *   <li>CONTAINS</li>
         *   <li>STARTS WITH（双词）</li>
         *   <li>ENDS WITH（双词）</li>
         *   <li>=~（正则匹配）</li>
         * </ul>
         *
         * @return 比较运算符字符串，无匹配返回 null
         */
        String matchCompareOp() {
            skipWhitespace();

            // 双词运算符：使用预检避免部分消费
            if (peekKeyword(KW_STARTS) && peekNextKeyword(KW_WITH)) {
                matchKeyword(KW_STARTS);
                matchKeyword(KW_WITH);
                return OP_STARTS_WITH;
            }
            if (peekKeyword(KW_ENDS) && peekNextKeyword(KW_WITH)) {
                matchKeyword(KW_ENDS);
                matchKeyword(KW_WITH);
                return OP_ENDS_WITH;
            }

            // 单词运算符
            if (matchKeyword(OP_CONTAINS)) {
                return OP_CONTAINS;
            }
            if (matchKeyword(KW_LIKE)) {
                return KW_LIKE;
            }
            if (matchKeyword(OP_IN)) {
                return OP_IN;
            }
            if (matchKeyword(OP_BETWEEN)) {
                return OP_BETWEEN;
            }
            if (matchKeyword(KW_IS)) {
                return KW_IS;
            }

            // 符号运算符
            if (matchSymbol(OP_REGEX_MATCH)) {
                return OP_REGEX_MATCH;
            }
            if (matchSymbol(">=")) {
                return ">=";
            }
            if (matchSymbol("<=")) {
                return "<=";
            }
            if (matchSymbol("<>")) {
                return OP_NOT_EQUAL;
            }
            if (matchSymbol(OP_NOT_EQUAL)) {
                return OP_NOT_EQUAL;
            }
            if (matchSymbol("==")) {
                return OP_EQUAL;
            }
            if (matchSymbol(OP_EQUAL)) {
                return OP_EQUAL;
            }
            if (matchSymbol(">")) {
                return ">";
            }
            if (matchSymbol("<")) {
                return "<";
            }
            return null;
        }

        /**
         * 读取属性路径标识符
         *
         * <p>支持点号分隔的多级路径，如：
         * <ul>
         *   <li>name → "name"</li>
         *   <li>n.name → "n.name"</li>
         *   <li>a.b.c → "a.b.c"</li>
         * </ul>
         *
         * @return 属性路径字符串，若无有效标识符返回空字符串
         */
        String readPropertyPath() {
            String first = readIdentifier();
            if (StringUtils.isEmpty(first)) {
                return "";
            }
            // 检查点号属性访问
            skipWhitespace();
            if (match('.')) {
                skipWhitespace();
                String second = readIdentifier();
                if (StringUtils.isNotEmpty(second)) {
                    StringBuilder path = new StringBuilder(first)
                            .append('.').append(second);
                    // 支持链式属性访问 a.b.c
                    skipWhitespace();
                    while (match('.')) {
                        skipWhitespace();
                        String next = readIdentifier();
                        if (StringUtils.isEmpty(next)) {
                            break;
                        }
                        path.append('.').append(next);
                        skipWhitespace();
                    }
                    return path.toString();
                }
                // 点号后无标识符，回退
                pos--;
            }
            return first;
        }

        /**
         * 预检关键字（不消费输入）
         *
         * @param keyword 要预检的关键字
         * @return 匹配返回 true
         */
        boolean peekKeyword(String keyword) {
            int saved = pos;
            boolean result = matchKeyword(keyword);
            pos = saved;
            return result;
        }

        /**
         * 预检下一个关键字（不消费输入）
         *
         * <p>跳过当前单词和空白，检查下一个位置的单词是否匹配关键字。
         * 用于处理 STARTS WITH / ENDS WITH 这样的双词运算符预检。</p>
         *
         * @param keyword 要预检的关键字
         * @return 匹配返回 true
         */
        boolean peekNextKeyword(String keyword) {
            int saved = pos;
            // 跳过当前单词（到非字母数字字符为止）
            while (pos < input.length()
                    && !Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
            skipWhitespace();
            boolean result = matchKeyword(keyword);
            pos = saved;
            return result;
        }

        /**
         * 尝试匹配关键字（忽略大小写）
         *
         * @param keyword 要匹配的关键字
         * @return 匹配成功则消费并返回 true
         */
        boolean matchKeyword(String keyword) {
            skipWhitespace();
            if (pos + keyword.length() <= input.length()
                    && input.substring(pos, pos + keyword.length())
                    .equalsIgnoreCase(keyword)) {
                int end = pos + keyword.length();
                if (end < input.length()
                        && Character.isLetterOrDigit(input.charAt(end))) {
                    return false;
                }
                pos += keyword.length();
                return true;
            }
            return false;
        }

        /**
         * 尝试匹配符号字符串
         *
         * @param symbol 要匹配的符号
         * @return 匹配成功则消费并返回 true
         */
        boolean matchSymbol(String symbol) {
            skipWhitespace();
            if (pos + symbol.length() <= input.length()
                    && input.substring(pos, pos + symbol.length())
                    .equals(symbol)) {
                pos += symbol.length();
                return true;
            }
            return false;
        }

        /**
         * 尝试匹配单个字符
         *
         * @param c 要匹配的字符
         * @return 匹配成功则消费并返回 true
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
         * 期望匹配指定字符，失败则抛出异常
         *
         * @param c 期望匹配的字符
         */
        void expect(char c) {
            if (!match(c)) {
                throw new IllegalArgumentException("期望 '" + c + "'，位置 "
                        + pos + "，剩余: " + remaining());
            }
        }

        /**
         * 跳过空白字符
         */
        void skipWhitespace() {
            while (pos < input.length()
                    && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        /**
         * 读取标识符（字母、数字、下划线）
         *
         * @return 标识符字符串
         */
        String readIdentifier() {
            int start = pos;
            while (pos < input.length()
                    && (Character.isLetterOrDigit(input.charAt(pos))
                    || input.charAt(pos) == '_')) {
                pos++;
            }
            return input.substring(start, pos);
        }

        /**
         * 读取值文本（用于 IN 列表）
         *
         * @return 值文本字符串
         */
        String readValue() {
            skipWhitespace();
            if (match('\'') || match('"')) {
                char quote = input.charAt(pos - 1);
                return "'" + readQuoted(quote) + "'";
            }
            if (peekIsDigit() || (peekIs('-'))) {
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
         * 读取引号字符串
         *
         * @param quote 引号字符（' 或 "）
         * @return 引号内的字符串内容（不含引号）
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
         * 读取数字
         *
         * @return 整数或双精度浮点数
         */
        Number readNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
            }
            while (pos < input.length()
                    && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
            boolean isDecimal = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < input.length()
                        && Character.isDigit(input.charAt(pos))) {
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
         * 检查当前字符是否为字母
         *
         * @return 是字母返回 true
         */
        boolean peekIsLetter() {
            return pos < input.length()
                    && Character.isLetter(input.charAt(pos));
        }

        /**
         * 检查当前字符是否为数字
         *
         * @return 是数字返回 true
         */
        boolean peekIsDigit() {
            return pos < input.length()
                    && Character.isDigit(input.charAt(pos));
        }

        /**
         * 检查当前字符是否等于指定字符
         *
         * @param c 要比较的字符
         * @return 相等返回 true
         */
        boolean peekIs(char c) {
            return pos < input.length() && input.charAt(pos) == c;
        }

        /**
         * 检查下一个字符是否等于指定字符
         *
         * @param c 要比较的字符
         * @return 相等返回 true
         */
        boolean peekNextIs(char c) {
            return pos + 1 < input.length()
                    && input.charAt(pos + 1) == c;
        }

        /**
         * 是否已到达输入末尾
         *
         * @return 已到末尾返回 true
         */
        boolean isEnd() {
            skipWhitespace();
            return pos >= input.length();
        }

        /**
         * 获取剩余未解析的输入片段
         *
         * @return 剩余字符串
         */
        String remaining() {
            return pos < input.length() ? input.substring(pos) : "";
        }
    }
}
