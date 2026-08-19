package com.chua.enhance.support.emoji;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Emoji 管理器，负责 emoji 数据的注册、检索和管理。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>维护全局 emoji 数据集 {@link #data()}</li>
 *   <li>维护别名到 Emoji 的快速索引 {@link #EMOJIS_BY_ALIAS}</li>
 *   <li>维护字典树 {@link #EMOJI_TRIE} 用于高效匹配</li>
 *   <li>提供 {@link #addEmoji(Emoji)} 动态注册新 emoji</li>
 * </ul>
 *
 * @author CH
 * @since 1.0.0
 */
public class EmojiManager {

    /**
     * Emoji 字典树
     */
    public static final EmojiTrie EMOJI_TRIE = new EmojiTrie(10);

    /** Emoji_data */
    private static final List<Emoji> EMOJI_DATA = new ArrayList<>();
    /**
     * 别名到 Emoji 的映射
     */
    public static final Map<String, Emoji> EMOJIS_BY_ALIAS = new ConcurrentHashMap<>();

    static {
        initDefaultEmojis();
    }

    /** 初始化DefaultEmojis */
    private static void initDefaultEmojis() {
    }

    /**
     * 获取全部 emoji 数据
     *
     * @return emoji 列表
     */
    public static List<Emoji> data() {
        return EMOJI_DATA;
    }

    /**
     * 获取表情符号正则
     *
     * @return Pattern
     */
    public static Pattern getEmoticonRegexPattern() {
        return Pattern.compile("");
    }

    /**
     * 注册 emoji
     *
     * @param emoji Emoji 对象
     */
    public static void addEmoji(Emoji emoji) {
        if (emoji != null) {
            EMOJI_DATA.add(emoji);
            if (emoji.getAliases() != null) {
                for (String alias : emoji.getAliases()) {
                    EMOJIS_BY_ALIAS.put(alias, emoji);
                }
            }
            EMOJI_TRIE.addEmoji(emoji);
        }
    }
}