package com.chua.datasource.support.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从 SELECT 语句文本里解析出「结果集会返回哪些字段」。
 *
 * <p><b>为什么要它</b>：同步 / 查询配置界面需要在一张千万级表上显示「这段 SQL 会返回哪几列」。
 * 走 {@code PreparedStatement#getMetaData()} 或真执行一次查询都要连库；而字段名单其实是
 * 语句文本里写死的（{@code SELECT a, b AS c FROM t}），本地解析即可得到，既快又不依赖库可用性。
 * 只有 {@code SELECT *} 与「没有别名的表达式」这类文本里看不出名字的情况才必须回库。</p>
 *
 * <p><b>能力边界</b>：只做单条 SELECT 的<b>投影列表</b>切分，不是完整 SQL 解析器——
 * 不展开 {@code *}、不推导表达式类型、不解析 CTE。实现的判定原则是「不确定就说不确定」：
 * 只要有一项拿不准，就把 {@link Result#resolvable()} 置为 {@code false}，由调用方回落到
 * 数据库元数据，绝不猜一个可能和驱动返回值不一致的列名（列名不一致会让整条同步链路取不到值）。</p>
 *
 * <p>支持的写法：行注释与块注释、字符串字面量、
 * 三种引用符（{@code `x`}、{@code "x"}、{@code [x]}）、{@code DISTINCT}、
 * SQL Server 的 {@code SELECT TOP n}、子查询 / 函数里的逗号（按括号深度切分）、
 * {@code AS} 别名与省略 {@code AS} 的尾部别名。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public final class SqlSelectFields {

    /**
     * 不能当作「省略 AS 的别名」的单词（它们是表达式的一部分或结束符）
     */
    private static final Set<String> NON_ALIAS_WORDS = Set.of(
            "null", "true", "false", "unknown", "end", "asc", "desc",
            "and", "or", "not", "then", "else", "when", "case", "interval", "from");

    /**
     * SELECT 之后、投影列表之前可跳过的修饰词
     */
    private static final Set<String> SELECT_MODIFIERS = Set.of(
            "distinct", "distinctrow", "all", "straight_join", "sql_no_cache", "sql_cache");

    /**
     * 工具类禁止实例化。
     */
    private SqlSelectFields() {
    }

    /**
     * 解析结果。
     *
     * @param fields     投影项，按语句中的顺序
     * @param resolvable 是否可以直接当作结果集字段名使用（无 {@code *}、每项都能定名）
     * @author CH
     * @since 4.0.0.43
     */
    public record Result(List<Field> fields, boolean resolvable) {

        /**
         * 取可直接使用的字段名列表。
         *
         * @return 可解析时返回字段名，否则返回空列表
         */
        public List<String> names() {
            if (!resolvable) {
                return List.of();
            }
            List<String> names = new ArrayList<>(fields.size());
            for (Field field : fields) {
                names.add(field.label());
            }
            return names;
        }
    }

    /**
     * 单个投影项。
     *
     * @param label      结果集里应出现的字段名（别名优先）
     * @param expression 该项的原始文本
     * @param named      名字是否确定（{@code false} 表示文本里看不出驱动会返回什么名字）
     * @param star       是否为 {@code *} / {@code t.*}
     * @author CH
     * @since 4.0.0.43
     */
    public record Field(String label, String expression, boolean named, boolean star) {
    }

    /**
     * 解析一段 SELECT 的投影字段。
     *
     * @param sql SQL 文本
     * @return 解析结果；语句不是 SELECT 时 {@link Result#resolvable()} 为 {@code false}
     */
    public static Result parse(String sql) {
        if (sql == null || sql.isBlank()) {
            return new Result(List.of(), false);
        }
        String text = sql;
        int i = skipNoise(text, 0);
        int[] keyword = readWord(text, i);
        if (keyword == null || !"select".equalsIgnoreCase(text.substring(keyword[0], keyword[1]))) {
            return new Result(List.of(), false);
        }
        int cursor = keyword[1];
        cursor = skipNoise(text, cursor);
        // SELECT DISTINCT / ALL / TOP n [PERCENT] [WITH TIES]
        while (true) {
            int[] word = readWord(text, cursor);
            if (word == null) {
                break;
            }
            String lower = text.substring(word[0], word[1]).toLowerCase(Locale.ROOT);
            if (SELECT_MODIFIERS.contains(lower)) {
                cursor = skipNoise(text, word[1]);
                continue;
            }
            if ("top".equals(lower)) {
                cursor = skipTopClause(text, word[1]);
                cursor = skipNoise(text, cursor);
                continue;
            }
            break;
        }
        int fromStart = findTopLevelWord(text, cursor, "from");
        String listText = fromStart < 0 ? text.substring(cursor) : text.substring(cursor, fromStart);
        listText = stripTrailingSemicolon(listText.trim());
        if (listText.isBlank()) {
            return new Result(List.of(), false);
        }
        List<String> items = splitTopLevel(listText);
        List<Field> fields = new ArrayList<>(items.size());
        boolean resolvable = true;
        for (String item : items) {
            Field field = parseItem(item);
            fields.add(field);
            if (field.star() || !field.named()) {
                resolvable = false;
            }
        }
        return new Result(List.copyOf(fields), resolvable && !fields.isEmpty());
    }

    /**
     * 解析单个投影项（别名优先）。
     *
     * @param item 投影项文本
     * @return 字段描述
     */
    private static Field parseItem(String item) {
        String expression = item.trim();
        // 1) 显式 AS 别名（取顶层最后一个 AS）
        int asIndex = lastTopLevelWord(expression, "as");
        if (asIndex >= 0) {
            String label = unquote(expression.substring(asIndex + 2).trim());
            String body = expression.substring(0, asIndex).trim();
            if (!label.isEmpty() && !body.isEmpty()) {
                return new Field(label, expression, true, isStar(body));
            }
        }
        // 2) 通配符：* 或 t.*
        if (isStar(expression)) {
            return new Field(expression, expression, false, true);
        }
        // 3) 纯列引用（a / t.a / `库`.`表`.`列`）
        String plain = plainColumnLabel(expression);
        if (plain != null) {
            return new Field(plain, expression, true, false);
        }
        // 4) 省略 AS 的尾部别名：count(*) c / a + b total
        Field implicit = implicitAlias(expression);
        if (implicit != null) {
            return implicit;
        }
        // 5) 表达式且无别名：不同驱动给的名字不一样，宁可回库
        return new Field(expression, expression, false, false);
    }

    /**
     * 识别省略 {@code AS} 的尾部别名。
     *
     * <p>只接受「表达式已完整 + 一个裸标识符」的形态：前一段必须以右括号、引用符或标识符字符
     * 结尾（说明不是一个未完成的运算），且末尾标识符不是 SQL 关键字。</p>
     *
     * @param expression 投影项文本
     * @return 带别名的字段；不像别名时返回 null
     */
    private static Field implicitAlias(String expression) {
        int end = expression.length();
        if (end < 3) {
            return null;
        }
        // 末尾必须是一个标识符
        if (!isIdentifierChar(expression.charAt(end - 1))) {
            return null;
        }
        int tailStart = end;
        while (tailStart > 0 && isIdentifierChar(expression.charAt(tailStart - 1))) {
            tailStart--;
        }
        if (tailStart == 0 || !isIdentifierStart(expression.charAt(tailStart))) {
            return null;
        }
        // 别名与表达式之间必须有空白，否则是连在一起的单个标识符
        if (!Character.isWhitespace(expression.charAt(tailStart - 1))) {
            return null;
        }
        String alias = expression.substring(tailStart, end);
        if (NON_ALIAS_WORDS.contains(alias.toLowerCase(Locale.ROOT))) {
            return null;
        }
        int bodyEnd = tailStart;
        while (bodyEnd > 0 && Character.isWhitespace(expression.charAt(bodyEnd - 1))) {
            bodyEnd--;
        }
        if (bodyEnd <= 0) {
            return null;
        }
        // 表达式部分必须是「已结束」的：右括号 / 字面量收尾 / 标识符收尾
        char last = expression.charAt(bodyEnd - 1);
        if (!(last == ')' || last == '\'' || last == '"' || last == '`' || last == ']'
                || isIdentifierChar(last))) {
            return null;
        }
        return new Field(alias, expression, true, false);
    }

    /**
     * 判断投影项是否为通配符。
     *
     * @param expression 投影项文本
     * @return {@code *} 或 {@code x.*} 返回 true
     */
    private static boolean isStar(String expression) {
        String trimmed = expression.trim();
        if ("*".equals(trimmed)) {
            return true;
        }
        return trimmed.endsWith(".*") && plainColumnLabel(trimmed.substring(0, trimmed.length() - 2)) != null;
    }

    /**
     * 纯列引用取最后一段作为字段名。
     *
     * @param expression 投影项文本
     * @return 列名；不是纯列引用返回 null
     */
    private static String plainColumnLabel(String expression) {
        String trimmed = expression.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        List<String> segments = new ArrayList<>(3);
        int i = 0;
        int length = trimmed.length();
        while (i < length) {
            while (i < length && Character.isWhitespace(trimmed.charAt(i))) {
                i++;
            }
            String segment;
            char first = trimmed.charAt(i);
            if (first == '`' || first == '"' || first == '[') {
                char close = first == '[' ? ']' : first;
                int closeIndex = trimmed.indexOf(close, i + 1);
                if (closeIndex < 0) {
                    return null;
                }
                segment = trimmed.substring(i + 1, closeIndex);
                i = closeIndex + 1;
            } else if (isIdentifierStart(first)) {
                int start = i;
                while (i < length && isIdentifierChar(trimmed.charAt(i))) {
                    i++;
                }
                segment = trimmed.substring(start, i);
            } else {
                return null;
            }
            if (segment.isBlank()) {
                return null;
            }
            // 段内只允许一个词（防止把 "a + b" 当列名）
            if (segment.chars().anyMatch(ch -> Character.isWhitespace((char) ch))) {
                return null;
            }
            segments.add(segment);
            while (i < length && Character.isWhitespace(trimmed.charAt(i))) {
                i++;
            }
            if (i >= length) {
                break;
            }
            if (trimmed.charAt(i) != '.') {
                return null;
            }
            i++;
        }
        return segments.isEmpty() ? null : segments.get(segments.size() - 1);
    }

    /**
     * 去掉标识符外层引用符。
     *
     * @param value 原始文本
     * @return 去掉引用符后的文本
     */
    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() < 2) {
            return trimmed;
        }
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        if ((first == '`' && last == '`') || (first == '"' && last == '"')
                || (first == '[' && last == ']')) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 跳过 SQL Server 的 {@code TOP n [PERCENT] [WITH TIES]}。
     *
     * @param text  SQL 文本
     * @param start 起点（TOP 之后）
     * @return 跳过后的位置
     */
    private static int skipTopClause(String text, int start) {
        int i = skipNoise(text, start);
        if (i < text.length() && text.charAt(i) == '(') {
            i = matchingParen(text, i) + 1;
        } else {
            while (i < text.length() && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                i++;
            }
        }
        i = skipNoise(text, i);
        int[] word = readWord(text, i);
        if (word != null && "percent".equalsIgnoreCase(text.substring(word[0], word[1]))) {
            i = skipNoise(text, word[1]);
            word = readWord(text, i);
        }
        if (word != null && "with".equalsIgnoreCase(text.substring(word[0], word[1]))) {
            int[] ties = readWord(text, skipNoise(text, word[1]));
            if (ties != null && "ties".equalsIgnoreCase(text.substring(ties[0], ties[1]))) {
                i = ties[1];
            }
        }
        return i;
    }

    /**
     * 找到顶层（不在括号 / 引号 / 注释里）的指定关键字。
     *
     * @param text        文本
     * @param start       起点
     * @param keywordWord 目标关键字（小写）
     * @return 关键字起始位置；未找到返回 -1
     */
    private static int findTopLevelWord(String text, int start, String keywordWord) {
        int i = start;
        int depth = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                int next = skipQuoted(text, i);
                if (next < 0) {
                    return -1;
                }
                i = next;
                continue;
            }
            if (ch == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                i = skipToLineEnd(text, i + 2);
                continue;
            }
            if (ch == '#') {
                i = skipToLineEnd(text, i + 1);
                continue;
            }
            if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i = skipBlockComment(text, i + 2);
                continue;
            }
            if (ch == '(') {
                depth++;
                i++;
                continue;
            }
            if (ch == ')') {
                depth = Math.max(0, depth - 1);
                i++;
                continue;
            }
            if (depth == 0 && Character.isLetter(ch)) {
                int[] word = readWord(text, i);
                if (word != null && keywordWord.equalsIgnoreCase(text.substring(word[0], word[1]))) {
                    return word[0];
                }
                i = word == null ? i + 1 : word[1];
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * 找到顶层最后一个指定关键字（用于 {@code AS} 别名）。
     *
     * @param text        文本
     * @param keywordWord 目标关键字（小写）
     * @return 关键字起始位置；未找到返回 -1
     */
    private static int lastTopLevelWord(String text, String keywordWord) {
        int found = -1;
        int from = 0;
        while (true) {
            int index = findTopLevelWord(text, from, keywordWord);
            if (index < 0) {
                return found;
            }
            found = index;
            from = index + keywordWord.length();
        }
    }

    /**
     * 按顶层逗号切分投影列表。
     *
     * @param listText 投影列表文本
     * @return 各项文本
     */
    private static List<String> splitTopLevel(String listText) {
        List<String> items = new ArrayList<>(8);
        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < listText.length()) {
            char ch = listText.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                i = skipQuoted(listText, i);
                continue;
            }
            if (ch == '-' && i + 1 < listText.length() && listText.charAt(i + 1) == '-') {
                i = skipToLineEnd(listText, i + 2);
                continue;
            }
            if (ch == '#') {
                i = skipToLineEnd(listText, i + 1);
                continue;
            }
            if (ch == '/' && i + 1 < listText.length() && listText.charAt(i + 1) == '*') {
                i = skipBlockComment(listText, i + 2);
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            } else if (ch == ',' && depth == 0) {
                String item = listText.substring(start, i).trim();
                if (!item.isEmpty()) {
                    items.add(item);
                }
                start = i + 1;
            }
            i++;
        }
        String tail = listText.substring(Math.min(start, listText.length())).trim();
        if (!tail.isEmpty()) {
            items.add(tail);
        }
        return items;
    }

    /**
     * 跳过一段带引号的内容（引号类型由起始字符决定）。
     *
     * @param text  文本
     * @param start 起始引号位置
     * @return 结束引号之后的位置；引号未闭合返回 -1
     */
    private static int skipQuoted(String text, int start) {
        char open = text.charAt(start);
        char close = open == '[' ? ']' : open;
        int i = start + 1;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == close) {
                // SQL 里连续两个引号表示一个引号字符（'it''s'），继续往后找
                if (open != '[' && i + 1 < text.length() && text.charAt(i + 1) == close) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            if (ch == '\\' && open != '[' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * 跳到行尾（换行符之后）。
     *
     * @param text  文本
     * @param start 起点
     * @return 换行符之后的位置
     */
    private static int skipToLineEnd(String text, int start) {
        int index = text.indexOf('\n', start);
        return index < 0 ? text.length() : index + 1;
    }

    /**
     * 跳过块注释。
     *
     * @param text  文本
     * @param start 注释内部起点
     * @return 注释结束之后的位置
     */
    private static int skipBlockComment(String text, int start) {
        int index = text.indexOf("*/", start);
        return index < 0 ? text.length() : index + 2;
    }

    /**
     * 找到与指定左括号匹配的右括号。
     *
     * @param text  文本
     * @param open  左括号位置
     * @return 右括号位置；未闭合时返回文本长度减一
     */
    private static int matchingParen(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
                int next = skipQuoted(text, i);
                if (next < 0) {
                    return text.length() - 1;
                }
                i = next;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return text.length() - 1;
    }

    /**
     * 跳过空白与注释。
     *
     * @param text  文本
     * @param start 起点
     * @return 第一个有效字符位置
     */
    private static int skipNoise(String text, int start) {
        int i = start;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (ch == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                i = skipToLineEnd(text, i + 2);
                continue;
            }
            if (ch == '#') {
                i = skipToLineEnd(text, i + 1);
                continue;
            }
            if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i = skipBlockComment(text, i + 2);
                continue;
            }
            break;
        }
        return i;
    }

    /**
     * 读取一个裸标识符 / 关键字。
     *
     * @param text  文本
     * @param start 起点
     * @return 起止位置的二元数组；当前位置不是标识符起始字符时返回 null
     */
    private static int[] readWord(String text, int start) {
        if (start >= text.length() || !isIdentifierStart(text.charAt(start))) {
            return null;
        }
        int i = start;
        while (i < text.length() && isIdentifierChar(text.charAt(i))) {
            i++;
        }
        return new int[]{start, i};
    }

    /**
     * 判断是否为标识符起始字符。
     *
     * @param ch 字符
     * @return 字母或下划线返回 true
     */
    private static boolean isIdentifierStart(char ch) {
        return Character.isLetter(ch) || ch == '_' || ch == '$';
    }

    /**
     * 判断是否为标识符组成字符。
     *
     * @param ch 字符
     * @return 字母、数字、下划线或 $ 返回 true
     */
    private static boolean isIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    /**
     * 去掉语句末尾的分号。
     *
     * @param text 文本
     * @return 去掉末尾分号后的文本
     */
    private static String stripTrailingSemicolon(String text) {
        String trimmed = text.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
