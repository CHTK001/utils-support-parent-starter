package com.chua.common.support.datasearch.skill.model;

import lombok.Builder;
import lombok.Data;

/**
 * 在线技能市场条目。
 *
 * <p>由各 {@code SkillOnlineProvider} 市场源归一化产出，只承载市场真实返回的字段；
 * 源侧没有的指标保持默认值（0 / null），不做推算。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
public class SkillMarketListing {

    /**
     * 市场内唯一标识（含源前缀，如 {@code skillsmp:123}）
     */
    private String id;

    /**
     * 市场源标识（skillsmp / skills-sh / github 等）
     */
    private String source;

    /**
     * 本次搜索内的名次（从 1 开始，跨页累加）
     */
    private Integer rank;

    /**
     * 技能名称
     */
    private String name;

    /**
     * 技能描述
     */
    private String description;

    /**
     * 作者（GitHub owner 或市场署名）
     */
    private String author;

    /**
     * 技能来源地址，安装时按此地址解析 GitHub 仓库与目录
     */
    private String url;

    /**
     * 仓库内技能目录：源侧给出完整相对路径时为其本身，只能给出末级目录名时为该名
     */
    private String path;

    /**
     * Star 数，源侧无该指标时为 0
     */
    private long stars;

    /**
     * Fork 数，源侧无该指标时为 0
     */
    private long forks;

    /**
     * 安装次数，源侧无该指标时为 0
     */
    private long installs;

    /**
     * 内容语言，源侧无该指标时为 null
     */
    private String contentLanguage;
}
