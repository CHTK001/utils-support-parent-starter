package com.chua.common.support.ai.chat;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
* AI 对话客户端配置
*
* <p>封装与 AI 大模型通信所需的全部配置参数，包括认证信息、模型参数、
* 网络代理等。通过 Builder 模式构建，支持部分字段可选。
*
* @author CH
* @since 2026/07/15
 */
@Data
@Builder
public class ChatClientSetting {

    /**
    * AI 服务商名称
    *
    * <p>用于 SPI 查找对应的 {@link ChatClient} 实现，
    * 如 "openai"、"deepseek"、"zhipu" 等。
    */
    private String provider;

    /**
    * API 请求基础地址
    *
    * <p>服务端 API 的完整基础 URL，例如 "https://api.openai.com/v1"。
    * 若为空则使用实现类提供的默认地址。
    */
    private String baseUrl;

    /**
    * API 密钥
    *
    * <p>用于身份认证的 API Key。
    */
    private String appKey;

    /**
    * API 密钥（备用）
    *
    * <p>部分服务商需要额外密钥或签名密钥。
    */
    private String appSecret;

    /**
    * 默认模型名称
    *
    * <p>未通过链式调用指定模型时使用的默认值，
    * 如 "gpt-3.5-turbo"、"deepseek-chat"。
    */
    private String model;

    /**
    * 默认温度参数
    *
    * <p>控制生成文本的随机性，取值范围 [0.0, 2.0]。
    * 未通过链式调用指定时使用此值。
    */
    private Double temperature;

    /**
    * 默认最大输出 Token 数
    *
    * <p>限制每次请求生成的最大 Token 数量。
    * 未通过链式调用指定时使用此值。
    */
    private Integer maxTokens;

    /**
    * 默认 Top-P 采样参数
    *
    * <p>核采样参数，控制生成文本的多样性。
    * 值越小生成内容越确定。
    */
    private Double topP;

    /**
    * 默认系统提示词
    *
    * <p>未通过链式调用指定时使用的系统提示词。
    */
    private String system;

    /**
    * 默认工具（函数调用）定义列表
    *
    * <p>未通过链式调用指定时使用的工具列表。
    */
    private List<ChatTool> tools;

    /**
    * 默认工具选择策略（tool_choice）
    *
    * <p>取值约定：auto / none / required / 指定工具名称。
    */
    private String toolChoice;

    /**
    * 默认停止序列
    */
    private List<String> stop;

    /**
    * 默认随机种子
    */
    private Long seed;

    /**
    * 默认响应格式
    *
    * <p>取值约定：text / json_object / json_schema。
    */
    private String responseFormat;

    /**
    * HTTP 代理地址
    *
    * <p>格式示例：
    * <ul>
    *   <li>HTTP 代理：http://127.0.0.1:7890</li>
    *   <li>SOCKS5 代理：socks5://127.0.0.1:1080</li>
    * </ul>
    */
    private String proxy;

    /**
    * 自定义 HTTP 请求头
    *
    * <p>每次请求都会携带这些额外的 HTTP 头，用于服务商要求的自定义认证头、
    * 路由头等场景。优先级高于 SDK 默认头。</p>
    */
    private Map<String, String> extraHeaders;

    /**
    * 是否使用 GPU（本地推理引擎专用：llama.cpp / onnxruntime / pytorch 等）
    *
    * <p>null 表示跟随 {@code deeplearning.device} 系统属性（默认 auto）。</p>
    */
    private Boolean useGpu;

    /**
    * 分配给 GPU 的层数（llama.cpp 专用；-1 = 全部层，0 = 纯 CPU）
    *
    * <p>仅当 {@link #useGpu} 为 true 时生效。</p>
    */
    private Integer gpuLayers;

    /**
    * 上下文窗口大小（llama.cpp / 多数本地推理引擎）
    */
    private Integer ctxSize;

    /**
    * Top-K 采样参数（llama.cpp 专用）
    */
    private Integer topK;

    /**
    * 推理线程数（llama.cpp 专用；null = 自动按 CPU 核心数）
    */
    private Integer threads;

    /**
    * 设备设置（auto / cpu / gpu / cuda），仅本地推理引擎使用
    *
    * <p>优先级：显式设置 &gt; 系统属性 {@code deeplearning.device}。null 表示跟随系统属性。</p>
    */
    private String deviceSetting;
}
