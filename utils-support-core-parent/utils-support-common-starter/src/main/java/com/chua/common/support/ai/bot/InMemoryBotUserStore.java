package com.chua.common.support.ai.bot;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
* 内存版 Bot 用户存储
* <p>
* 使用 ConcurrentHashMap 实现线程安全的内存用户存储，
* 适用于测试和轻量场景。
* </p>
*
* @author CH
* @since 2026/07/18
 */
public class InMemoryBotUserStore implements BotUserStore {

    /** 用户数据存储 */
    private final ConcurrentHashMap<String, BotUserInfo> users
            = new ConcurrentHashMap<>();

    @Override
    /** Upsert */
    public void upsert(BotUserInfo user) {
        if (user != null && user.getUserId() != null) {
            users.put(user.getUserId(), user);
        }
    }

    @Override
    /** 查找ByUserId */
    public Optional<BotUserInfo> findByUserId(String userId) {
        return Optional.ofNullable(users.get(userId));
    }

    @Override
    /** 查找All */
    public List<BotUserInfo> findAll() {
        return List.copyOf(users.values());
    }

    @Override
    /** 删除 */
    public void delete(String userId) {
        users.remove(userId);
    }

    @Override
    /** 计算数量 */
    public long count() {
        return users.size();
    }
}
