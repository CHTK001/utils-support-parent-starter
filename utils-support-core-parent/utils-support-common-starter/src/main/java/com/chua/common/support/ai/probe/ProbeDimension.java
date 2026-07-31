package com.chua.common.support.ai.probe;

/**
 * 探测器维度枚举。
 *
 * <p>定义 AI 中转站真伪探测的 12 个维度，每个维度对应一种探测策略。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum ProbeDimension {

    /**
     * 模型列表扫描。
     *
     * <p>调用 GET {baseUrl}/v1/models 接口，扫描可用模型 ID 和所属组织信息。</p>
     */
    MODELS_SCAN("models_scan"),

    /**
     * 模型名矩阵嗅探。
     *
     * <p>逐一试探 20+ 厂家模型名，统计通过/拒绝情况，识别真实支持模型。</p>
     */
    MODEL_MATRIX_SNIFF("model_matrix_sniff"),

    /**
     * 错误消息分析。
     *
     * <p>检查 503/404 错误响应是否泄露渠道分组名或代理框架特征。</p>
     */
    ERROR_MESSAGE_ANALYSIS("error_message_analysis"),

    /**
     * 身份追问。
     *
     * <p>直接询问"你是什么模型"，观察返回的身份声明是否与实际一致。</p>
     */
    IDENTITY_PROBE("identity_probe"),

    /**
     * 越狱/角色扮演探测。
     *
     * <p>使用 DAN 模式、假装管理员等越狱指令，测试安全对齐能力。</p>
     */
    JAILBREAK_PROBE("jailbreak_probe"),

    /**
     * 知识截止日期探测。
     *
     * <p>询问训练数据截止日期，验证模型自述是否与实际一致。</p>
     */
    KNOWLEDGE_CUTOFF("knowledge_cutoff"),

    /**
     * 安全对齐指纹。
     *
     * <p>通过政治敏感问题和对齐指令，识别安全策略风格（如 OpenAI/Anthropic/国内模型差异）。</p>
     */
    SAFETY_ALIGNMENT("safety_alignment"),

    /**
     * 数学推理陷阱。
     *
     * <p>使用经典题目如"9.11 和 9.9 哪个大"，测试模型的数学推理能力。</p>
     */
    MATH_TRAP("math_trap"),

    /**
     * Prompt Token 注入检测。
     *
     * <p>检测 prompt_tokens 是否异常偏高，判断是否存在隐藏的 prompt 注入或 token 窃取。</p>
     */
    PROMPT_TOKEN_INJECTION("prompt_token_injection"),

    /**
     * Function Calling 探测。
     *
     * <p>测试 tools 参数是否可用，判断模型是否支持函数调用能力。</p>
     */
    FUNCTION_CALLING("function_calling"),

    /**
     * HTTP 响应头识别。
     *
     * <p>识别 New-API / One-API 等代理框架的特征 HTTP 头。</p>
     */
    HTTP_HEADERS("http_headers"),

    /**
     * 速度基准测试。
     *
     * <p>测量 tokens/second 吞吐量，作为模型真实性的辅助判断依据。</p>
     */
    SPEED_BENCHMARK("speed_benchmark");

    /**
     * 维度标识键。
     */
    private final String key;

    /**
     * 构造维度枚举。
     *
     * @param key 维度标识键
     */
    ProbeDimension(String key) {
        this.key = key;
    }

    /**
     * 获取维度标识键。
     *
     * @return 维度标识键
     */
    public String getKey() {
        return key;
    }
}
