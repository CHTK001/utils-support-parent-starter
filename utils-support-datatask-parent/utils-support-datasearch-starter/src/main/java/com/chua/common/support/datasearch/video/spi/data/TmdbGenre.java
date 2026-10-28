package com.chua.common.support.datasearch.video.spi.data;

/**
 * TMDB 影片类型枚举。
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum TmdbGenre {
    /** 动作片 */
    ACTION(28, "动作"),
    /** 冒险片 */
    ADVENTURE(12, "冒险"),
    /** 动画片 */
    ANIMATION(16, "动画"),
    /** 喜剧片 */
    COMEDY(35, "喜剧"),
    /** 犯罪片 */
    CRIME(80, "犯罪"),
    /** 纪录片 */
    DOCUMENTARY(99, "纪录"),
    /** 剧情片 */
    DRAMA(18, "剧情"),
    /** 家庭片 */
    FAMILY(10751, "家庭"),
    /** 奇幻片 */
    FANTASY(14, "奇幻"),
    /** 历史片 */
    HISTORY(36, "历史"),
    /** 恐怖片 */
    HORROR(27, "恐怖"),
    /** 音乐片 */
    MUSIC(10402, "音乐"),
    /** 悬疑片 */
    MYSTERY(9648, "悬疑"),
    /** 爱情片 */
    ROMANCE(10749, "爱情"),
    /** 科幻片 */
    SCIENCE_FICTION(878, "科幻"),
    /** 电视电影 */
    TV_MOVIE(10770, "电视电影"),
    /** 惊悚片 */
    THRILLER(53, "惊悚"),
    /** 战争片 */
    WAR(10752, "战争"),
    /** 西部片 */
    WESTERN(37, "西部");

    /**
     * 标识
    */
    private final int id;
    /**
     * 名称
    */
    private final String name;

    /**
     * 构造方法，创建 TmdbGenre 实例。
     *
     * @param id ID，不允许为 null
     * @param name 名称，不允许为 null
     */
    TmdbGenre(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * 通过 标识 取中文名，找不到返回 空
     * @param id 标识
     * @return 获取名称byid的结果
     */
    public static String getNameById(int id) {
        TmdbGenre[] values = TmdbGenre.values();

        for (TmdbGenre g : values) {
            if (g.id == id) {
                return g.name;
            }
        }
        return null;
    }

    /**
     * 获取标识
     *
     * @return 获取id的结果
     */
    public int getId() {
        return id;
    }

    /**
     * 获取名称
     *
     * @return 获取名称的结果
     */
    public String getName() {
        return name;
    }
}

