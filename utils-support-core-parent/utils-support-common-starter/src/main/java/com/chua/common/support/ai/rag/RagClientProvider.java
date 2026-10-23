package com.chua.common.support.ai.rag;

/**
 * RAG 客户端提供者 —— 按当前上下文解析可用的 {@link RagClient}。
 *
 * <p>与 {@link RagClientFactory} 的区别：工厂按给定配置「造」一个客户端，
 * 提供者按运行时上下文「取」一个已经装配好的客户端。多租户场景下，
 * 同一配置在不同账号下可能对应各自的向量索引，因此取用必须走上下文，
 * 不能把单个实例固化为容器单例。</p>
 *
 * <p>其它模块只依赖本接口，实现由上层框架（如 spring-support-ai-starter）
 * 注册为 Spring Bean；{@code current()} 每次调用都应返回与当前上下文匹配的实例。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface RagClientProvider {

    /**
     * 当前上下文下可用的 RAG 客户端。
     *
     * @return RAG 客户端；未配置或不可用时返回 {@code null}
     */
    RagClient current();
}
