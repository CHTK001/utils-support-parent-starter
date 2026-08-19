package com.chua.common.support.datasearch.video.spi.data;

/**
 * @author CH
 * @since 4.0.0.42
 */
public enum TmdbGenre {
    ACTION(28, "动作"),
    ADVENTURE(12, "冒险"),
    ANIMATION(16, "动画"),
    COMEDY(35, "喜剧"),
    CRIME(80, "犯罪"),
    DOCUMENTARY(99, "纪录"),
    DRAMA(18, "剧情"),
    FAMILY(10751, "家庭"),
    FANTASY(14, "奇幻"),
    HISTORY(36, "历史"),
    HORROR(27, "恐怖"),
    MUSIC(10402, "音乐"),
    MYSTERY(9648, "悬疑"),
    ROMANCE(10749, "爱情"),
    SCIENCE_FICTION(878, "科幻"),
    TV_MOVIE(10770, "电视电影"),
    THRILLER(53, "惊悚"),
    WAR(10752, "战争"),
    WESTERN(37, "西部");

    /** 标识 */
    private final int id;
    /** 名称 */
    private final String name;

    TmdbGenre(int id, String name) {
        this.id = id;
        this.name = name;
    }

    /**
     * 通过 id 取中文名，找不到返回 null
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

    /** 获取Id */
    public int getId() {
        return id;
    }

    /** 获取Name */
    public String getName() {
        return name;
    }
}

