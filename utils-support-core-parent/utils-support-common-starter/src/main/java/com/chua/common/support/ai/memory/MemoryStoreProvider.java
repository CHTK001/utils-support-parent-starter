package com.chua.common.support.ai.memory;


/**
* 记忆存储 SPI 提供者
*
* <p>通过 SPI 机制注册自定义的 {@link MemoryStore} 实现。
* 默认使用基于工作间文件的 {@code FileMemoryStore}，
* 可替换为数据库、Redis、向量数据库等实现以支持语义检索。
*
* @author CH
* @since 2026/07/16
 */
public interface MemoryStoreProvider {

    /**
    * 创建记忆存储实例
    *
    * @param config 记忆体配置
    * @return 记忆存储实例
    */
    MemoryStore create(MemoryConfig config);
}
