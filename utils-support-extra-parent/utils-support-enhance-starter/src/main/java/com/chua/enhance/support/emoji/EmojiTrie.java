package com.chua.enhance.support.emoji;

import java.util.HashMap;
import java.util.Map;

/**
 * Emoji Trie 树，基于前缀树（Trie）结构高效匹配和检索 emoji 字符。
 *
 * <p>支持以下操作：</p>
 * <ul>
 *   <li>{@link #addEmoji(Emoji)} — 向字典树添加 emoji</li>
 *   <li>{@link #isEmoji(char[], int, int)} — 检查字符序列是否为 emoji（完全/前缀/不匹配）</li>
 *   <li>{@link #getEmoji(String)} — 根据 Unicode 查找对应的 Emoji 对象</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public class EmojiTrie {

    /**
     * 字典树最大深度
     */
    public final int maxDepth;

    /** 根级 */
    private final Node root = new Node();

    /**
     * 创建指定容量的字典树
     *
     * @param maxDepth 最大深度
     */
    public EmojiTrie(int maxDepth) {
        this.maxDepth = maxDepth;
    }

    /**
     * 向字典树添加 emoji
     *
     * @param emoji Emoji 对象
     */
    public void addEmoji(Emoji emoji) {
        if (emoji == null || emoji.getUnicode() == null) {
            return;
        }
        char[] chars = emoji.getUnicode().toCharArray();
        Node current = root;
        for (char c : chars) {
            current = current.children.computeIfAbsent(c, k -> new Node());
        }
        current.emoji = emoji;
    }

    /**
     * 检查字符序列是否匹配 emoji
     *
     * @param text  字符数组
     * @param start 起始位置
     * @param end   结束位置
     * @return 匹配结果
     */
    public Matches isEmoji(char[] text, int start, int end) {
        if (text == null || start < 0 || end > text.length || start >= end) {
            return Matches.impossible();
        }

        Node current = root;
        for (int i = start; i < end; i++) {
            Node child = current.children.get(text[i]);
            if (child == null) {
                return Matches.impossible();
            }
            current = child;
        }

        if (current.emoji != null) {
            return Matches.exact();
        }

        if (!current.children.isEmpty()) {
            return Matches.prefix();
        }

        return Matches.impossible();
    }

    /**
     * 根据 Unicode 查找 Emoji
     *
     * @param unicode Unicode 字符串
     * @return Emoji 对象，未找到返回 空
     */
    public Emoji getEmoji(String unicode) {
        if (unicode == null) {
            return null;
        }
        return getEmoji(unicode.toCharArray(), 0, unicode.length());
    }

    /**
     * 根据字符序列查找 Emoji
     *
     * @param text  字符数组
     * @param start 起始位置
     * @param end   结束位置
     * @return Emoji 对象，未找到返回 空
     */
    public Emoji getEmoji(char[] text, int start, int end) {
        if (text == null || start < 0 || end > text.length || start >= end) {
            return null;
        }

        Node current = root;
        for (int i = start; i < end; i++) {
            Node child = current.children.get(text[i]);
            if (child == null) {
                return null;
            }
            current = child;
        }

        return current.emoji;
    }

    /**
     * 匹配结果枚举
     * @author CH
     * @since 4.0.0
     */
    public static class Matches {
        /** Exactmatch */
        private final boolean exactMatch;
        /** Prefixmatch */
        private final boolean prefixMatch;
        /** Impossiblematch */
        private final boolean impossibleMatch;

        /**
         * 创建 Matches 实例
         * @param exactMatch exact匹配
         * @param exactMatch 布尔值
         * @param exactMatch 布尔值
         * @param prefixMatch 前缀匹配
         * @param impossibleMatch impossible匹配
         */
        private Matches(boolean exactMatch, boolean prefixMatch, boolean impossibleMatch) {
            this.exactMatch = exactMatch;
            this.prefixMatch = prefixMatch;
            this.impossibleMatch = impossibleMatch;
        }

        /**
         * Exact
         *
         * @return exact的结果
         */
        public static Matches exact() {
            return new Matches(true, false, false);
        }

        /**
         * 前缀
         *
         * @return 前缀的结果
         */
        public static Matches prefix() {
            return new Matches(false, true, false);
        }

        /**
         * Impossible
         *
         * @return impossible的结果
         */
        public static Matches impossible() {
            return new Matches(false, false, true);
        }

        /**
         * exact匹配
         *
         * @return exact匹配的结果
         */
        public boolean exactMatch() {
            return exactMatch;
        }

        /**
         * 前缀匹配
         *
         * @return 前缀匹配的结果
         */
        public boolean prefixMatch() {
            return prefixMatch;
        }

        /**
         * impossible匹配
         *
         * @return impossible匹配的结果
         * @author CH
         * @since 4.0.0
         */
        public boolean impossibleMatch() {
            return impossibleMatch;
        }
    }

    private static class Node {
        Emoji emoji; // emoji
        Map<Character, Node> children = new HashMap<>(); // children
    }
}
