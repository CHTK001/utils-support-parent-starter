package com.chua.common.support.lang.cmd;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 命令行软件的版本号，从 {@code --version} 之类的输出中解析而来，支持比较与区间判断。
 *
 * <p>不同 CLI 的版本输出格式五花八门，本类用一个宽松策略应对：
 * 先从文本中抓取第一段形如 {@code 1.2.3} 的数字序列作为主版本，再抓取紧随其后的
 * 非空白内容作为限定符（如 {@code -rc-6}、{@code -beta.1}）。因此以下格式都能正确解析：</p>
 *
 * <ul>
 *   <li>{@code ffmpeg version 6.0 Copyright (c)} → 6.0</li>
 *   <li>{@code v1.2.3} → 1.2.3</li>
 *   <li>{@code 4.0.0-rc-6} → 4.0.0-rc-6</li>
 *   <li>{@code tshark 3.6.2 (Git v3.6.2)} → 3.6.2</li>
 *   <li>{@code nvidia-smi 535.104.05} → 535.104.05</li>
 * </ul>
 *
 * <p>若某个软件的版本输出格式特殊，可通过 {@link #parse(String, Pattern)} 传入自定义正则，
 * 正则需包含第一个捕获组用于提取完整版本串。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * CliVersion v = CliVersion.parse("ffmpeg version 6.0 Copyright");
 * v.major();                    // 6
 * v.atLeast(CliVersion.of(5, 1)); // true
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class CliVersion implements Comparable<CliVersion> {

    /**
     * 默认版本提取正则：一段以数字开头、以点/下划线/连字符分隔的数字序列
     */
    private static final Pattern DEFAULT_PATTERN = Pattern.compile("\\d+(?:[._-]\\d+)*");

    /**
     * 数字段分隔符
    */
    private static final Pattern SEGMENT_SEPARATOR = Pattern.compile("[._-]");

    /**
     * 限定符起始字符
    */
    private static final char[] QUALIFIER_PREFIXES = {'-', '+', '_'};

    /**
     * 原始版本字符串
    */
    private final String raw;
    /**
     * 数字段，如 4.0.0 → [4, 0, 0]
    */
    private final int[] numbers;
    /**
     * 限定符，如 rc-6、beta.1，无则为空字符串
    */
    private final String qualifier;

    /**
     * 创建版本实例
     *
     * @param raw       原始版本字符串
     * @param numbers   数字段
     * @param qualifier 限定符
     */
    private CliVersion(String raw, int[] numbers, String qualifier) {
        this.raw = raw;
        this.numbers = numbers;
        this.qualifier = qualifier == null ? "" : qualifier;
    }

    /**
     * 从任意文本中解析版本号，使用默认正则。
     *
     * @param text 待解析文本，如 {@code --version} 的输出
     * @return 版本实例，解析失败时返回 {@link #unknown()}
     */
    @Nonnull
    public static CliVersion parse(@Nullable String text) {
        return parse(text, DEFAULT_PATTERN);
    }

    /**
     * 从任意文本中按指定正则解析版本号。
     *
     * @param text    待解析文本
     * @param pattern 提取正则，需包含第一个捕获组
     * @return 版本实例，解析失败时返回 {@link #unknown()}
     */
    @Nonnull
    public static CliVersion parse(@Nullable String text, @Nonnull Pattern pattern) {
        if (text == null || text.isBlank()) {
            return unknown();
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return unknown();
        }
        String versionPart = matcher.group(1) != null ? matcher.group(1) : matcher.group();
        String qualifier = extractQualifier(text, matcher.end());
        return new CliVersion(versionPart + qualifier, splitNumbers(versionPart), qualifier);
    }

    /**
     * 按数字段构造版本号。
     *
     * @param numbers 数字段，如 {@code of(5, 1)} 表示 5.1
     * @return 版本实例
     */
    @Nonnull
    public static CliVersion of(int... numbers) {
        if (numbers == null || numbers.length == 0) {
            return unknown();
        }
        int[] copy = Arrays.copyOf(numbers, numbers.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < copy.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(copy[i]);
        }
        return new CliVersion(sb.toString(), copy, "");
    }

    /**
     * 获取无法解析的版本占位实例，其所有数字段为 0，且小于任何正常版本。
     *
     * @return 未知版本实例
     */
    @Nonnull
    public static CliVersion unknown() {
        return new CliVersion("", new int[0], "");
    }

    /**
     * 从匹配结束位置向后提取限定符。
     *
     * <p>仅当紧跟限定符起始字符（{@code -}、{@code +}、{@code _}）时才认定为限定符，
     * 避免把 {@code 6.0 Copyright} 中的 {@code Copyright} 误判为版本的一部分。</p>
     *
     * @param text 原始文本
     * @param end  数字序列结束下标
     * @return 限定符，无则为空字符串
     */
    private static String extractQualifier(String text, int end) {
        if (end >= text.length()) {
            return "";
        }
        char next = text.charAt(end);
        boolean isPrefix = false;
        for (char prefix : QUALIFIER_PREFIXES) {
            if (next == prefix) {
                isPrefix = true;
                break;
            }
        }
        if (!isPrefix) {
            return "";
        }
        int start = end + 1;
        int stop = start;
        while (stop < text.length() && !Character.isWhitespace(text.charAt(stop))) {
            stop++;
        }
        return text.substring(start, stop);
    }

    /**
     * 把版本字符串拆分为数字段，非数字部分会被丢弃。
     *
     * @param versionPart 版本数字部分，如 "4.0.0"
     * @return 数字段数组
     */
    private static int[] splitNumbers(String versionPart) {
        String[] segments = SEGMENT_SEPARATOR.split(versionPart);
        List<Integer> list = new ArrayList<>(segments.length);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            try {
                list.add(Integer.parseInt(segment));
            } catch (NumberFormatException ignored) {
                // 非数字段（如 "rc"）不参与主版本比较，由限定符逻辑处理
            }
        }
        int[] result = new int[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    /**
     * 获取原始版本字符串。
     *
     * @return 原始版本字符串，未知版本返回空字符串
     */
    @Nonnull
    public String raw() {
        return raw;
    }

    /**
     * 获取主版本号，不存在时返回 0。
     *
     * @return 主版本号
     */
    public int major() {
        return numbers.length > 0 ? numbers[0] : 0;
    }

    /**
     * 获取次版本号，不存在时返回 0。
     *
     * @return 次版本号
     */
    public int minor() {
        return numbers.length > 1 ? numbers[1] : 0;
    }

    /**
     * 获取修订版本号，不存在时返回 0。
     *
     * @return 修订版本号
     */
    public int patch() {
        return numbers.length > 2 ? numbers[2] : 0;
    }

    /**
     * 获取限定符，如 {@code rc-6}、{@code beta.1}，正式版返回空字符串。
     *
     * @return 限定符
     */
    @Nonnull
    public String qualifier() {
        return qualifier;
    }

    /**
     * 判断是否为正式版（无限定符）。
     *
     * @return 无限定符返回 true
     */
    public boolean isRelease() {
        return qualifier.isEmpty();
    }

    /**
     * 判断是否为未能解析的未知版本。
     *
     * @return 未知版本返回 true
     */
    public boolean isUnknown() {
        return numbers.length == 0;
    }

    /**
     * 判断当前版本是否满足最低版本要求。
     *
     * @param minimum 最低版本
     * @return 当前版本大于或等于最低版本时返回 true
     */
    public boolean atLeast(@Nonnull CliVersion minimum) {
        return compareTo(minimum) >= 0;
    }

    /**
     * 判断当前版本是否低于指定版本。
     *
     * @param other 对比版本
     * @return 当前版本较小时返回 true
     */
    public boolean below(@Nonnull CliVersion other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(@Nonnull CliVersion other) {
        int max = Math.max(numbers.length, other.numbers.length);
        for (int i = 0; i < max; i++) {
            int left = i < numbers.length ? numbers[i] : 0;
            int right = i < other.numbers.length ? other.numbers[i] : 0;
            if (left != right) {
                return Integer.compare(left, right);
            }
        }
        return compareQualifier(qualifier, other.qualifier);
    }

    /**
     * 比较限定符，正式版高于任何预发布版本。
     *
     * @param left  左侧限定符
     * @param right 右侧限定符
     * @return 负整数、零或正整数
     */
    private static int compareQualifier(String left, String right) {
        if (left.equals(right)) {
            return 0;
        }
        int leftRank = qualifierRank(left);
        int rightRank = qualifierRank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        return compareQualifierSegment(left, right);
    }

    /**
     * 计算限定符的成熟度等级，数值越大越接近正式版。
     *
     * @param qualifier 限定符
     * @return 等级数值
     */
    private static int qualifierRank(String qualifier) {
        if (qualifier.isEmpty()) {
            return 6;
        }
        String lower = qualifier.toLowerCase();
        if (lower.startsWith("snapshot")) {
            return 0;
        }
        if (lower.startsWith("alpha")) {
            return 1;
        }
        if (lower.startsWith("beta")) {
            return 2;
        }
        if (lower.startsWith("milestone") || lower.startsWith("preview") || lower.startsWith("pre")) {
            return 3;
        }
        if (lower.startsWith("rc") || lower.startsWith("cr")) {
            return 4;
        }
        if (lower.startsWith("sp")) {
            return 7;
        }
        return 5;
    }

    /**
     * 逐段比较限定符，纯数字段按数值比较，避免 rc-10 被字符串比较误判为小于 rc-6。
     *
     * @param left  左侧限定符
     * @param right 右侧限定符
     * @return 负整数、零或正整数
     */
    private static int compareQualifierSegment(String left, String right) {
        String[] leftParts = SEGMENT_SEPARATOR.split(left);
        String[] rightParts = SEGMENT_SEPARATOR.split(right);
        int max = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < max; i++) {
            String l = i < leftParts.length ? leftParts[i] : "";
            String r = i < rightParts.length ? rightParts[i] : "";
            Integer ln = tryParseInt(l);
            Integer rn = tryParseInt(r);
            if (ln != null && rn != null) {
                if (!ln.equals(rn)) {
                    return Integer.compare(ln, rn);
                }
                continue;
            }
            int cmp = l.compareToIgnoreCase(r);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /**
     * 尝试解析整数，失败返回 null。
     *
     * @param value 待解析字符串
     * @return 整数值，解析失败返回 null
     */
    @Nullable
    private static Integer tryParseInt(String value) {
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CliVersion)) {
            return false;
        }
        CliVersion that = (CliVersion) o;
        return Arrays.equals(numbers, that.numbers) && qualifier.equals(that.qualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(numbers), qualifier);
    }

    @Override
    public String toString() {
        return raw.isEmpty() ? "unknown" : raw;
    }
}
