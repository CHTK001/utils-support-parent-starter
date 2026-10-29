package com.chua.common.support.ai.chat;

import com.chua.common.support.pool.PooledObjectClient;
import com.chua.common.support.spi.ServiceProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * AI 决策客户端接口
 *
 * <p>与 {@link ChatClient} 对应的决策侧客户端：按 provider 取（SPI），一次调用完成
 * 「用 AI 回答有界的决策问题」。支持 OpenAI 协议的 {@code /v1/decisions}
 * （模型 {@code gpt-6-luna}）与 TypeSafe SystemOne 协议的 {@code /v1/systemone}
 * （模型 {@code jev-latest}），两种协议共享同一套 {@link DecisionQuestion}
 * 与 {@link DecisionAnswer}，在实现类内部翻译。
 *
 * <p>链式配置示例：
 * <pre>{@code
 *   DecisionsResult result = DecisionsClient.create("openai", "sk-xxx")
 *       .question(DecisionQuestion.choice("department", "哪个部门处理？",
 *           Map.of("billing", "账单退款", "technical", "产品问题")))
 *       .decide("我被收取了两次款。");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface DecisionsClient extends AutoCloseable, PooledObjectClient<DecisionsClient> {

    /**
     * 日志：静态工厂方法在兜底分支使用
     */
    org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(DecisionsClient.class);

    /**
     * OpenAI 兜底扩展名
     */
    String OPENAI_EXTENSION = "openai";

    /**
     * OpenAI 默认 API 地址
     */
    String DEFAULT_URL = "https://api.openai.com/v1";

    /**
     * SystemOne 默认 API 地址
     */
    String SYSTEMONE_URL = "https://api.typesafe.ai/v1";

    /**
     * 通过完整配置创建 AI 决策客户端
     *
     * <p>provider 名在 SPI 中未注册（或实例化失败）时，只要设置里给出了
     * {@code baseUrl}，就按 OpenAI 兼容协议兜底走 {@code openai} 扩展：
     * 对端只配了 endpoint 而 SPI 清单没跟上的平台，不至于静默落到「未配置主 Client」。
     * 没给 baseUrl 时保持原行为返回空，由调用方报错，避免拿缺省 endpoint 乱发请求。</p>
     *
     * @param setting 客户端配置，包含 provider、apiKey、baseUrl 等
     * @return DecisionsClient 实例
     */
    static DecisionsClient create(DecisionsSetting setting) {
        ServiceProvider<DecisionsClient> serviceProvider = ServiceProvider.of(DecisionsClient.class);
        DecisionsClient client = serviceProvider.getNewExtension(setting.getProvider(), setting);
        if (client == null) {
            String baseUrl = setting.getBaseUrl();
            if (baseUrl != null && !baseUrl.isBlank()) {
                LOG.warn("[DecisionsClient] 供应商 {} 未注册 SPI 扩展，按 OpenAI 兼容协议兜底 baseUrl={}",
                        setting.getProvider(), baseUrl);
                client = serviceProvider.getNewExtension(OPENAI_EXTENSION, setting);
            }
        }
        return client;
    }

    /**
     * 创建指定 provider 的 AI 决策客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @return DecisionsClient 实例
     */
    static DecisionsClient create(String provider, String apiKey) {
        return create(DecisionsSetting.builder()
                .provider(provider).appKey(apiKey).build());
    }

    /**
     * 创建指定 provider、密钥和自定义地址的 AI 决策客户端
     *
     * @param provider AI 服务商名称
     * @param apiKey   API 密钥
     * @param baseUrl  自定义 API 地址
     * @return DecisionsClient 实例
     */
    static DecisionsClient create(String provider, String apiKey, String baseUrl) {
        return create(DecisionsSetting.builder()
                .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
     * 设置 AI 服务商
     *
     * @param provider 服务商名称
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient provider(String provider) {
        return this;
    }

    /**
     * 设置模型名称
     *
     * @param model 模型名称，如 "gpt-6-luna"、"jev-latest"
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient model(String model) {
        return this;
    }

    /**
     * 设置系统提示词
     *
     * @param system 系统提示词内容
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient system(String system) {
        return this;
    }

    /**
     * 设置温度参数
     *
     * <p>控制生成文本的随机性，取值范围 [0.0, 2.0]。
     * 值越低输出越确定，值越高输出越多样。
     *
     * @param temperature 温度值
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient temperature(double temperature) {
        return this;
    }

    /**
     * 设置最大输出 Token 数
     *
     * @param maxTokens 最大 Token 数量
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient maxTokens(int maxTokens) {
        return this;
    }

    /**
     * 设置 Top-P 采样参数
     *
     * <p>核采样参数，控制生成文本的多样性，取值范围 [0.0, 1.0]。
     *
     * @param topP Top-P 值
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient topP(Double topP) {
        return this;
    }

    /**
     * 设置决策协议
     *
     * @param protocol 协议，默认 {@link DecisionsProtocol#OPENAI}
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient protocol(DecisionsProtocol protocol) {
        return this;
    }

    /**
     * 添加决策问题
     *
     * <p>向当前请求追加一个问题（或一个多问题的列表），
     * 问题标识在同一请求内必须唯一。
     *
     * @param question 问题定义
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient question(DecisionQuestion question) {
        return this;
    }

    /**
     * 添加决策问题列表
     *
     * @param questions 问题定义列表
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient questions(List<DecisionQuestion> questions) {
        return this;
    }

    /**
     * 设置共享证据
     *
     * <p>OpenAI 协议对应 {@code input}，SystemOne 协议对应 {@code state}。
     *
     * @param input 共享证据 / 问题上下文
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient input(String input) {
        return this;
    }

    /**
     * 设置额外请求体参数
     *
     * @param extraBody 额外请求体参数键值映射
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient extraBody(Map<String, Object> extraBody) {
        return this;
    }

    /**
     * 设置自定义 HTTP 请求头
     *
     * @param headers 自定义请求头键值映射
     * @return 当前客户端实例，支持链式调用
     */
    default DecisionsClient extraHeaders(Map<String, String> headers) {
        return this;
    }

    /**
     * 设置认证方案
     *
     * @param authScheme 认证方案
     * @return 当前客户端实例，支持链式调用
     * @see AuthHeaders
     */
    default DecisionsClient authScheme(String authScheme) {
        return this;
    }

    /**
     * 解析当前客户端应发送的认证头
     *
     * @return 认证头键值对；免鉴权或无 Key 时返回 null
     */
    default Map.Entry<String, String> resolveAuthHeader() {
        return AuthHeaders.resolve(getSetting(), getApiKey());
    }

    /**
     * 同步决策
     *
     * <p>发送请求并等待完整响应，返回完整结论与 token 用量信息。
     * 实现类应在此实现所有协议细节（OpenAI / SystemOne 都行，
     * 由 {@link DecisionsSetting} 中的 {@code protocol} 决定）。
     *
     * @param input 共享证据
     * @param questions 问题列表
     * @return 决策结果
     */
    DecisionsResult decide(String input, List<DecisionQuestion> questions);

    /**
     * 同步决策（携带完整请求）
     *
     * <p>默认实现：从请求中提取共享证据与问题列表，委托给
     * {@link #decide(String, List)}，由各实现处理模型与协议细节。
     *
     * @param request 决策请求
     * @return 决策结果
     */
    default DecisionsResult decide(DecisionsRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为 null");
        }
        String input = request.input();
        if (input == null || input.isBlank()) {
            input = request.state();
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("input / state 不能为 null 或空白");
        }
        List<DecisionQuestion> questions = request.questions();
        if (questions == null || questions.isEmpty()) {
            throw new IllegalArgumentException("questions 不能为 null 或空");
        }
        return decide(input, questions);
    }

    /**
     * 异步决策
     *
     * <p>默认实现：把同步决策包装回 {@link CompletableFuture}，
     * 流化调用方在实现类覆写本方法以提供真正的异步（非阻塞）行为。
     *
     * @param input 共享证据
     * @param questions 问题列表
     * @return 决策结果异步任务
     */
    default CompletableFuture<DecisionsResult> decideAsync(String input, List<DecisionQuestion> questions) {
        return CompletableFuture.supplyAsync(() -> decide(input, questions));
    }

    /**
     * 异步决策（携带完整请求）
     *
     * @param request 决策请求
     * @return 决策结果异步任务
     */
    default CompletableFuture<DecisionsResult> decideAsync(DecisionsRequest request) {
        return CompletableFuture.supplyAsync(() -> decide(request));
    }

    /**
     * 关闭客户端
     *
     * <p>释放底层资源，如 HTTP 连接池等。
     */
    @Override
    default void close() {
    }

    /**
     * 获取客户端配置
     *
     * @return DecisionsSetting 配置对象
     */
    default DecisionsSetting getSetting() {
        return null;
    }

    /**
     * 获取当前模型名称
     *
     * @return 模型名称，未设置时返回 null
     */
    default String getModel() {
        DecisionsSetting setting = getSetting();
        return setting != null ? setting.getModel() : null;
    }

    /**
     * 获取 API Key
     *
     * @return API 密钥
     */
    default String getApiKey() {
        DecisionsSetting setting = getSetting();
        return setting != null ? setting.getAppKey() : null;
    }

    /**
     * 获取 API Base URL
     *
     * @return API 地址
     */
    default String getBaseUrl() {
        DecisionsSetting setting = getSetting();
        return setting != null ? setting.getBaseUrl() : null;
    }
}
