package com.chua.remote.gateway.store;

/**
 * 接入码存储 SPI 加载器。
 *
 * <p>通过 {@code ServiceLoader} 发现 SPI 实现：优先使用外部实现（如 spring 生态的
 * MyBatis 实现——持久化到数据库），无外部实现时回退内存实现。</p>
 *
 * @author AtomCode
 */
public final class AccessCodeStores {

    private AccessCodeStores() {
    }

    /**
     * 加载接入码存储实现（优先级：WAL（纯 JDK 文件持久化）→ 外部实现（如 spring 生态 MyBatis）→ 内存兜底）。
     *
     * @return 存储实现
     */
    public static AccessCodeStore load() {
        AccessCodeStore external = null;
        try {
            for (AccessCodeStore candidate : java.util.ServiceLoader.load(AccessCodeStore.class)) {
                if (candidate instanceof WalAccessCodeStore) {
                    // 纯 JDK WAL 持久化——默认首选（不依赖 spring/数据库）
                    return candidate;
                }
                if (external == null && !(candidate instanceof InMemoryAccessCodeStore)) {
                    external = candidate;
                }
            }
        } catch (Exception ignored) {
            // SPI 发现失败——回退内存实现
        }
        return external != null ? external : new InMemoryAccessCodeStore();
    }
}
