package com.chua.spider.support;

import com.chua.spider.support.model.SpiderResult;

/**
 * 爬虫管道 SPI 接口。
 *
 * <p>负责消费/处理 {@link SpiderResult}，是爬取数据的最终出口。
 * 一个爬虫可配置多个 Pipeline，按顺序依次执行。常见实现：
 * <ul>
 *   <li>控制台输出 - 调试时直接打印结果</li>
 *   <li>文件存储 - 保存为 JSON、CSV 等格式文件</li>
 *   <li>数据库存储 - 写入 MySQL、MongoDB、Elasticsearch 等</li>
 *   <li>消息队列 - 发送到 Kafka、RabbitMQ 等</li>
 *   <li>AI 处理 - 调用 ChatClient 进行内容总结/分类后存储</li>
 * </ul>
 *
 * @author CH
 * @since 2026/07/17
 */
public interface SpiderPipeline {

    /**
     * 处理一个爬取结果。
     *
     * <p>接收经过 Parser 和 AiParser 处理后的 Result，
     * 执行最终的消费逻辑（存储、输出、转发等）。
     * 此方法不应抛出异常，内部应捕获并记录错误。
     *
     * @param result 爬取结果，包含标题、内容、结构化数据、AI 总结等
     */
    void process(SpiderResult result);

    /**
     * 管道初始化回调。
     *
     * <p>在开始爬取前调用，用于初始化资源（如打开文件、建立数据库连接等）。
     * 默认空实现。
     */
    default void init() {
    }

    /**
     * 管道销毁回调。
     *
     * <p>在爬取结束后调用，用于释放资源（如关闭文件、断开数据库连接等）。
     * 默认空实现。
     */
    default void destroy() {
    }
}
