package com.chua.common.support.collection;

/**
 * 集合生命周期状态枚举。
 *
 * <h3>状态转换</h3>
 * <pre>
 *   UNLOADED ──→ LOADING ──→ LOADED ──→ UNLOADED (过期回收/手动释放)
 *      ↑            │          │
 *      │         (加载失败)   ↓
 *      └────────────┘      CLOSED (手动关闭，不可逆)
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @version 1.0.0
 * @see LazyExpiringList
 */
public enum ListState {

    /** 未加载状态，首次访问将触发懒加载 */
    UNLOADED,

    /** 加载中，其他线程阻塞等待 */
    LOADING,

    /** 已加载，数据可访问 */
    LOADED,

    /**
     * 已关闭，不可逆，任何访问将抛出 IllegalStateException
     */
    CLOSED
}
