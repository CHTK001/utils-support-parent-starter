package com.chua.common.support.datasearch.skill.impl;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.datasearch.skill.model.SkillMarketListing;
import com.chua.common.support.datasearch.skill.spi.SkillOnlineProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * skillsmp 技能市场在线提供器。
 *
 * <p>通过 SkillsMP 公开 API 搜索技能市场，以 Skill 形式暴露给 AI 技能系统。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("skillsmp")
public class SkillsmpSkillProvider extends SkillsmpProvider implements SkillOnlineProvider {

    @Override
    /**
     * 名称
    */
    public String name() {
        return NAME;
    }

    @Override
    /**
     * 获取Skills
    */
    public List<SkillDefinition> getSkills() {
        return List.of(searchSkill());
    }

    @Override
    /**
     * 搜索
    */
    public List<SkillDefinition> search(String keyword) {
        return List.of(searchSkill());
    }

    @Override
    /**
     * 搜索 skillsmp 技能清单。
     *
     * @param keyword 搜索关键词
     * @param page    页码
     * @param limit   每页条数
     * @param sortBy  排序方式
     * @return 市场技能条目列表
     */
    @SuppressWarnings("unchecked")
    public List<SkillMarketListing> searchMarket(String keyword, int page, int limit, String sortBy) {
        try {
            Map<String, Object> apiResult = callApi(keyword, Math.max(1, page), Math.max(1, limit), sortBy);
            List<Map<String, Object>> items = (List<Map<String, Object>>) apiResult.get("items");
            if (items == null || items.isEmpty()) {
                return Collections.emptyList();
            }
            List<SkillMarketListing> listings = new ArrayList<>(items.size());
            int rank = (Math.max(1, page) - 1) * Math.max(1, limit) + 1;
            for (Map<String, Object> item : items) {
                listings.add(SkillMarketListing.builder()
                        .id(NAME + ":" + text(item.get("id")))
                        .source(NAME)
                        .rank(rank++)
                        .name(text(item.get("name")))
                        .description(text(item.get("description")))
                        .author(text(item.get("author")))
                        .url(text(item.get("githubUrl")))
                        .path(dirFromUrl(text(item.get("githubUrl"))))
                        .stars(number(item.get("stars")))
                        .forks(number(item.get("forks")))
                        .contentLanguage(text(item.get("contentLanguage")))
                        .build());
            }
            return listings;
        } catch (Exception e) {
            log.warn("skillsmp 市场清单搜索失败: keyword={}", keyword, e);
            return Collections.emptyList();
        }
    }

    /**
     * 从 GitHub 浏览地址截出仓库内技能目录。
     *
     * @param url 形如 {@code https://github.com/owner/repo/tree/branch/a/b} 的地址
     * @return 仓库内相对路径，格式不符时返回 null
     */
    private static String dirFromUrl(String url) {
        if (url == null) {
            return null;
        }
        int index = url.indexOf("/tree/");
        if (index < 0) {
            return null;
        }
        String rest = url.substring(index + "/tree/".length());
        int slash = rest.indexOf('/');
        if (slash < 0 || slash == rest.length() - 1) {
            return null;
        }
        return rest.substring(slash + 1);
    }

    /**
     * 安全取文本。
     *
     * @param value 原始值
     * @return 文本值，入参为 null 时返回 null
     */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 安全取整数指标。
     *
     * @param value 原始值
     * @return 长整型值，非数字或 null 时返回 0
     */
    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    @Override
    /**
     * Install
    */
    public boolean install(String clientId, String skillId) {
        return super.install(clientId, skillId);
    }

    @Override
    /**
     * Uninstall
    */
    public boolean uninstall(String clientId, String skillId) {
        return super.uninstall(clientId, skillId);
    }
}