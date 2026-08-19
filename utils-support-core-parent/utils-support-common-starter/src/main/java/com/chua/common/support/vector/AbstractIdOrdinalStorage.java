package com.chua.common.support.vector;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 id→序数(ordinal) 双向映射的向量存储抽象基类。
 *
 * <p>封装三类通用能力，供各向量存储实现（如 jvector 的 MEMORY / ON_DISK /
 * LARGER_THAN_MEMORY 策略）复用：</p>
 * <ul>
 *   <li><strong>id 去重守卫</strong> — {@link #tryRegister(String, int)} 在分配前检查重复 id，
 *       重复时返回 {@code false}（对应 add 返回失败），避免各策略重复实现
 *       {@code idToOrd.containsKey} 守卫</li>
 *   <li><strong>id ↔ 序数双向映射</strong> — {@link #idToOrd} / {@link #ordToId}，
 *       序数即图索引节点号（列表下标），供 HNSW 等图索引搜索时反查 id</li>
 *   <li><strong>swap-remove 支持</strong> — {@link #moveOrdinal(String, int, int)} 在删除时把
 *       末尾元素重映射到被删位置，保持序数紧凑</li>
 * </ul>
 *
 * <p>约定：序数（ordinal）与向量列表下标一致，新元素按 {@code vectors.size()} 分配，删除后列表
 * 保持紧凑，因此 {@code ord == listIndex} 恒成立。</p>
 *
 * @since 4.0.0.42
 */
public abstract class AbstractIdOrdinalStorage {

    /**
     * id → 序数（列表下标）映射。
     */
    protected final Map<String, Integer> idToOrd = new ConcurrentHashMap<>();

    /**
     * 序数（列表下标）→ id 反向映射。
     */
    protected final Map<Integer, String> ordToId = new ConcurrentHashMap<>();

    /**
     * 判断 id 是否已存在（去重守卫）。
     *
     * @param id 向量标识
     * @return true 表示已存在
     */
    protected boolean containsId(String id) {
        return idToOrd.containsKey(id);
    }

    /**
     * 查询 id 对应的序数。
     *
     * @param id 向量标识
     * @return 序数，不存在时返回 null
     */
    protected Integer ordinalOf(String id) {
        return idToOrd.get(id);
    }

    /**
     * 查询序数对应的 id。
     *
     * @param ord 序数
     * @return id，不存在时返回 null
     */
    protected String idOf(int ord) {
        return ordToId.get(ord);
    }

    /**
     * 注册 id → 序数映射；若 id 已存在则拒绝并返回 false。
     *
     * <p>调用方约定：{@code ord} 应为当前向量列表下标（{@code vectors.size()}），
     * 注册成功后再向列表追加元素，保证 {@code ord == listIndex}。</p>
     *
     * @param id  向量标识
     * @param ord 分配序数（当前列表大小）
     * @return true 表示注册成功；false 表示 id 重复
     */
    protected boolean tryRegister(String id, int ord) {
        if (containsId(id)) {
            return false;
        }
        idToOrd.put(id, ord);
        ordToId.put(ord, id);
        return true;
    }

    /**
     * swap-remove 辅助：将末尾元素（fromOrd）重映射到被删除位置（toOrd）。
     *
     * @param id      被移动元素的 id
     * @param fromOrd 原序数（末尾）
     * @param toOrd   新序数（被删除位置）
     */
    protected void moveOrdinal(String id, int fromOrd, int toOrd) {
        idToOrd.put(id, toOrd);
        ordToId.remove(fromOrd);
        ordToId.put(toOrd, id);
    }

    /**
     * 注销 id 与序数的双向映射。
     *
     * @param id  向量标识
     * @param ord 序数
     */
    protected void unregister(String id, int ord) {
        idToOrd.remove(id);
        ordToId.remove(ord);
    }

    /**
     * 清空全部 id ↔ 序数映射（配合 clear() 调用）。
     */
    protected void resetOrdinals() {
        idToOrd.clear();
        ordToId.clear();
    }
}
