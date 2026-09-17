package com.chua.common.support.ai.bot;

import java.util.List;
import java.util.Optional;

/**
* Bot 用户存储 SPI 接口
* <p>
* 支持持久化用户数据（SQLite/H2/DuckDB 等实现）。
* </p>
*
* @author CH
* @since 2026/07/18
 */
public interface BotUserStore extends AutoCloseable {

    /**
    * 新增或更新用户
    *
    * @param user 用户信息
    */
    void upsert(BotUserInfo user);

    /**
    * 更新用户（仅当用户存在时）
    *
    * @param user 用户信息
    */
    default void update(BotUserInfo user) {
        if (user != null && user.getUserId() != null) {
            Optional<BotUserInfo> existing = findByUserId(user.getUserId());
            if (existing.isPresent()) {
                upsert(user);
            }
        }
    }

    /**
    * 根据 userId 查找用户
    *
    * @param userId 用户 ID
    * @return 用户信息，不存在则 empty
    */
    Optional<BotUserInfo> findByUserId(String userId);

    /**
    * 查询所有用户
    *
    * @return 用户列表
    */
    List<BotUserInfo> findAll();

    /**
    * 删除用户
    *
    * @param userId 用户 ID
    */
    void delete(String userId);

    /**
    * 统计用户数
    *
    * @return 用户总数
    */
    long count();

    @Override
    /** 关闭 */
    default void close() {
        // 默认空实现，子类可选择性覆盖
    }
}
