package com.chua.common.support.datasearch.skill.offline;

import java.util.List;

/**
 * SKILL.md 的 YAML front-matter 解析器。
 *
 * <p>各 Agent 的 SKILL.md 头部形如：</p>
 * <pre>
 * ---
 * name: create-skill
 * description: Create a new agent skill. Use when asked to create a skill.
 * ---
 * </pre>
 *
 * <p>与基类原先的"逐行取首个 {@code description:} 行"相比，本例还处理两类真实写法：</p>
 * <ul>
 *   <li><b>块标量续行</b>——{@code description: &gt;-}（折叠）或 {@code |}（保留）后，
 *       值位于其后缩进的行上，直到出现不缩进的行或块结束。只取单行会把 {@code &gt;-}
 *       本身当成描述（如 alipay-payment-integration 技能就是这种写法）；</li>
 *   <li><b>引号包裹</b>——值可被单/双引号整体包裹，需去壳。</li>
 * </ul>
 *
 * <p>本类只做"够用"的解析（键值 + 块标量 + 去引号），不实现完整 YAML 语义：
 * 不处理嵌套映射、列表、多文档与锚点，因为这些字段在 SKILL.md 头部并不出现。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
final class DshFrontMatter {

    /**
     * 技能名（front-matter 的 {@code name}）
     */
    final String name;

    /**
     * 技能描述（front-matter 的 {@code description}）
     */
    final String description;

    /**
     * 构造。
     *
     * @param name        技能名，可为 null
     * @param description 技能描述，可为 null
     */
    private DshFrontMatter(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /**
     * 解析 SKILL.md 全文，取出 front-matter 中的 name 与 description。
     *
     * <p>无 front-matter 时两者均为 null，交由调用方回退。</p>
     *
     * @param content 文件全文
     * @return 解析结果，永不为 null
     */
    static DshFrontMatter parse(String content) {
        if (content == null || content.isBlank()) {
            return new DshFrontMatter(null, null);
        }
        List<String> lines = content.lines().toList();
        if (lines.isEmpty() || !"---".equals(lines.getFirst().trim())) {
            return new DshFrontMatter(null, null);
        }
        String name = null;
        String description = null;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if ("---".equals(line.trim())) {
                break;
            }
            String trimmed = line.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            if (!"name".equals(key) && !"description".equals(key)) {
                continue;
            }
            ScalarValue value = readValue(lines, i, trimmed.substring(colon + 1).trim());
            if ("name".equals(key)) {
                name = value.text;
            } else {
                description = value.text;
            }
            i = value.lastLineIndex;
        }
        return new DshFrontMatter(name, description);
    }

    /**
     * 读取一个标量值，必要时吸收块标量的续行。
     *
     * @param lines     全文行
     * @param keyIndex  键所在行号
     * @param inline    冒号后的行内内容（可能为空或为 {@code &gt;-}/{@code |}）
     * @return 标量值与消耗到的最后一行的行号
     */
    private static ScalarValue readValue(List<String> lines, int keyIndex, String inline) {
        // 块标量：>- （折叠，换行转空格）或 | （保留换行）
        if (isBlockIndicator(inline)) {
            boolean fold = inline.startsWith(">");
            StringBuilder sb = new StringBuilder();
            int last = keyIndex;
            for (int i = keyIndex + 1; i < lines.size(); i++) {
                String raw = lines.get(i);
                if ("---".equals(raw.trim())) {
                    break;
                }
                // 块结束：出现不缩进的非空行
                if (!raw.isBlank() && raw.equals(raw.stripLeading())) {
                    break;
                }
                if (raw.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(fold ? ' ' : '\n');
                }
                sb.append(raw.strip());
                last = i;
            }
            return new ScalarValue(sb.toString().trim(), last);
        }
        return new ScalarValue(unquote(inline), keyIndex);
    }

    /**
     * 判断行内内容是否为块标量指示符。
     *
     * @param inline 冒号后的行内内容
     * @return 是块标量返回 true
     */
    private static boolean isBlockIndicator(String inline) {
        if (inline.isEmpty()) {
            return false;
        }
        char first = inline.charAt(0);
        if (first != '>' && first != '|') {
            return false;
        }
        // 允许 >- >+ |+ 等修饰符，但不允许 >-text 这类同行的实际内容
        for (int i = 1; i < inline.length(); i++) {
            char c = inline.charAt(i);
            if (c != '-' && c != '+') {
                return false;
            }
        }
        return true;
    }

    /**
     * 去掉包裹标量值的单/双引号。
     *
     * @param value 原始值
     * @return 去壳后的值
     */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    /**
     * 标量值与其消耗到的行号。
     *
     * @param text          值文本
     * @param lastLineIndex 该值最后占据的行号
     */
    private record ScalarValue(String text, int lastLineIndex) {
    }
}
