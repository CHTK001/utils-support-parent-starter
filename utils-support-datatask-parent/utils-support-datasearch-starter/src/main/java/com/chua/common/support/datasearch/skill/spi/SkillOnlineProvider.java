package com.chua.common.support.datasearch.skill.spi;

import com.chua.common.support.ai.skill.SkillDefinition;
import com.chua.common.support.datasearch.skill.model.SkillMarketListing;

import java.util.Collections;
import java.util.List;

/**
 * 在线技能提供者接口。
 *
 * <p>负责从在线技能市场（SkillsMP、ClawHub、SkillHub 等）搜索技能。
 * 每个在线市场源一个实现。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface SkillOnlineProvider {

    /**
     * 获取提供者名称（市场源标识，如 skillsmp/clawhub/skillhub）。
     *
     * @return 提供者名称
     */
    String name();

    /**
     * 获取该市场源注册的全部技能定义。
     *
     * @return 技能定义列表
     */
    default List<SkillDefinition> getSkills() {
        return Collections.emptyList();
    }

    /**
     * 按关键词搜索在线技能。
     *
     * @param keyword 搜索关键词
     * @return 搜索结果技能定义列表
     */
    default List<SkillDefinition> search(String keyword) {
        return Collections.emptyList();
    }

    /**
     * 搜索该市场源的技能清单，供控制台的在线市场搜索使用。
     *
     * <p>与 {@link #search(String)} 的区别：本方法返回可安装的市场条目元信息，
     * 而非面向模型的工具体；源侧没有公开查询接口时返回空列表，不伪造数据。</p>
     *
     * @param keyword 搜索关键词，可为空表示浏览该源榜单
     * @param page    页码，从 1 开始
     * @param limit   每页条数
     * @param sortBy  排序方式（stars/forks/updated），源侧不支持时由实现自行忽略
     * @return 市场技能条目列表
     */
    default List<SkillMarketListing> searchMarket(String keyword, int page, int limit, String sortBy) {
        return Collections.emptyList();
    }

    /**
     * 安装技能。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 是否安装成功
     */
    default boolean install(String clientId, String skillId) {
        return false;
    }

    /**
     * 卸载技能。
     *
     * @param clientId 客户端标识
     * @param skillId  技能标识
     * @return 是否卸载成功
     */
    default boolean uninstall(String clientId, String skillId) {
        return false;
    }
}
