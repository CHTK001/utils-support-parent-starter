package com.chua.modelscope.support;

/**
 * 模型scope（魔搭）服务常量。
 *
 * <p>ModelScope 提供 OpenAI 兼容的 API-Inference 端点以及自有 Hub 接口。
 * 本模块所有客户端均以这些常量为默认地址，便于集中覆盖。
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ModelscopeConstants {

    /**
     * API-推理 公网基地址（打开AI 兼容子集）
     */
    public static final String DEFAULT_INFERENCE_BASE_URL = "https://api-inference.modelscope.cn";

    /**
     * Hub 公网基地址（模型/数据集托管，Git LFS）
     */
    public static final String DEFAULT_HUB_BASE_URL = "https://www.modelscope.cn";

    /**
     * 打开AI 兼容 对话/completions 路径
     */
    public static final String PATH_CHAT_COMPLETIONS = "/v1/chat/completions";

    /**
     * 打开AI 兼容图像生成路径
     */
    public static final String PATH_IMAGES_GENERATIONS = "/v1/images/generations";

    /**
     * 模型清单路径
     */
    public static final String PATH_MODELS = "/v1/models";

    /**
     * 通用推理路径（按 模型 路由）模板
     */
    public static final String PATH_MODEL_INFER = "/v1/models/%s/infer";

    /**
     * 通用异步任务提交路径模板
     */
    public static final String PATH_MODEL_INFER_ASYNC = "/v1/models/%s/async-infer";

    /**
     * 异步任务查询路径模板
     */
    public static final String PATH_MODEL_TASK = "/v1/models/%s/tasks/%s";

    /**
     * 读超时（毫秒）：长上下文/长音频/大图像推理可能耗时较长
     */
    public static final long READ_TIMEOUT_MILLIS = 300_000L;

    /**
     * 连接超时（毫秒）
     */
    public static final long CONNECT_TIMEOUT_MILLIS = 15_000L;

    /**
     * modelscope常量。
     */
    private ModelscopeConstants() {
    }
}
