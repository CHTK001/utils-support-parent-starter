// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.openai.support;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.AuthHeaders;
import com.chua.common.support.ai.chat.DecisionsClient;
import com.chua.common.support.ai.chat.DecisionsProtocol;
import com.chua.common.support.ai.chat.DecisionsRequest;
import com.chua.common.support.ai.chat.DecisionsResult;
import com.chua.common.support.ai.chat.DecisionsSetting;
import com.chua.common.support.ai.chat.DecisionAnswer;
import com.chua.common.support.ai.chat.DecisionQuestion;
import com.chua.common.support.ai.decision.DecisionType;
import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.openai.core.RequestOptions;
import com.openai.core.ObjectMappers;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 打开AI 决策客户端
 *
 * <p>基于 OpenAI Java SDK 的 {@link DecisionsClient} 实现。标准服务由 SDK
 * {@link com.openai.client.OpenAIClient} 提供，但其技艺版本（4.76.0）尚未开放决策端点，因此本类
 * 使用原始 HTTP 通道（{@link com.openai.client.okhttp.OkHttpClient} + {@link HttpRequest}）
 * 直接调用决策端点，路由在两种协议之间：
 * <ul>
 *   <li>OpenAI 协议 — POST {@code /v1/decisions}，模型 {@code gpt-6-luna}，
 *       答案以 {@code answers} 数组返回</li>
 *   <li>SystemOne 协议 — POST {@code /v1/systemone}，模型 {@code jev-latest} /
 *       {@code jev-1.13.0}，答案按问题名分发</li>
 * </ul>
 *
 * <p>两种协议的请求体差异、答案结构差异都在本类内部翻译，调用方只使用
 * {@link DecisionsRequest} / {@link DecisionsResult}。
 *
 * <p>调用示例：
 * <pre>{@code
 *   DecisionsResult result = DecisionsClient.create("openai", "sk-xxx")
 *       .question(DecisionQuestion.noul("hasDuplicatedCharge", "是否重复扣费"))
 *       .question(DecisionQuestion.choice("department", "哪个部门处理？",
 *           Map.of("billing", "账单退款", "technical", "产品问题")))
 *       .decide("我被收取了两次款。");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi({"openai", "siliconflow", "sensetime", "github", "gitee", "dots", "kilo", "custom"})
public class OpenAiDecisionsClient implements DecisionsClient {

    /**
     * OpenAI 官方默认 API 地址
     */
    private static final String DEFAULT_URL = "https://api.openai.com/v1";

    /**
     * SystemOne 官方默认 API 地址
     */
    private static final String SYSTEMONE_URL = "https://api.typesafe.ai/v1";

    /**
     * OpenAI 决策默认模型
     */
    private static final String DEFAULT_MODEL = "gpt-6-luna";

    /**
     * SystemOne 决策默认模型
     */
    private static final String SYSTEMONE_MODEL = "jev-latest";

    /**
     * OpenAI 协议默认超时时间（毫秒）
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 90_000L;

    /**
     * 客户端配置
     */
    private final DecisionsSetting setting;

    /**
     * 当前模型名称
     */
    private String model;

    /**
     * 当前协议
     */
    private DecisionsProtocol protocol;

    /**
     * 当前温度参数
     */
    private Double temperature;

    /**
     * 当前最大输出 Token 数
     */
    private Integer maxTokens;

    /**
     * 当前 Top-P 采样参数
     */
    private Double topP;

    /**
     * 当前系统提示词
     */
    private String system;

    /**
     * 共享证据
     */
    private String input;

    /**
     * 问题列表
     */
    private List<DecisionQuestion> questions = Wraps.list();

    /**
     * 额外请求体参数
     */
    private Map<String, Object> extraBody;

    /**
     * 旧化的额外请求头
     */
    private Map<String, String> extraHeaders;

    /**
     * 认证方案
     */
    private String authScheme;

    /**
     * 构造 打开AI 决策客户端。
     *
     * @param setting 客户端配置
     */
    public OpenAiDecisionsClient(DecisionsSetting setting) {
        this.setting = setting;
        this.model = setting.getModel();
        this.protocol = resolveProtocol(setting);
        this.temperature = setting.getTemperature();
        this.maxTokens = setting.getMaxTokens();
        this.topP = setting.getTopP();
        this.system = setting.getSystem();
        this.extraBody = setting.getExtraBody();
        this.extraHeaders = setting.getExtraHeaders();
        this.authScheme = setting.getAuthScheme();
    }

    /**
     * 按 provider 与配置推断使用的协议。
     *
     * <p>显式配置 {@code protocol} 时以配置为准；未配置时，provider 词系中属于 SystemOne 风格
     * （"systemone" / "typesafe"）的默认走 {@code SYSTEMONE} 协议，否则走 {@code OPENAI} 协议。
     * 模型名称的词系同样可作为推断依据：{ @code SystemOne} 的决策模型为 {@code jev-latest} /
     * {@code jev-1.13.0} 系列，因此以 {@code jev} 为前缀的模型也默认走 {@code SYSTEMONE} 协议。
     *
     * @param setting 客户端配置
     * @return 协议
     */
    private static DecisionsProtocol resolveProtocol(DecisionsSetting setting) {
        if (setting == null || setting.getProtocol() != null) {
            return setting == null ? DecisionsProtocol.OPENAI : setting.getProtocol();
        }
        String provider = setting.getProvider();
        if ("systemone".equalsIgnoreCase(provider) || "typesafe".equalsIgnoreCase(provider)) {
            return DecisionsProtocol.SYSTEMONE;
        }
        String model = setting.getModel();
        if (model != null && model.toLowerCase().startsWith("jev")) {
            // jev 系列模型属于 SystemOne 端点
            return DecisionsProtocol.SYSTEMONE;
        }
        return DecisionsProtocol.OPENAI;
    }

    @Override
    public DecisionsClient provider(String provider) {
        return this;
    }

    @Override
    public DecisionsClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    public DecisionsClient protocol(DecisionsProtocol protocol) {
        this.protocol = protocol;
        return this;
    }

    @Override
    public DecisionsClient temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    @Override
    public DecisionsClient topP(Double topP) {
        this.topP = topP;
        return this;
    }

    @Override
    public DecisionsClient maxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    @Override
    public DecisionsClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    public DecisionsClient input(String input) {
        this.input = input;
        return this;
    }

    @Override
    public DecisionsClient question(DecisionQuestion question) {
        this.questions.add(question);
        return this;
    }

    @Override
    public DecisionsClient questions(List<DecisionQuestion> questions) {
        if (questions != null && !questions.isEmpty()) {
            this.questions.addAll(questions);
        }
        return this;
    }

    @Override
    public DecisionsClient extraBody(Map<String, Object> extraBody) {
        this.extraBody = extraBody;
        return this;
    }

    @Override
    public DecisionsClient extraHeaders(Map<String, String> extraHeaders) {
        this.extraHeaders = extraHeaders;
        return this;
    }

    @Override
    public DecisionsClient authScheme(String authScheme) {
        this.authScheme = authScheme;
        return this;
    }

    /**
     * 同步决策。
     *
     * <p>链式调用累积的共享证据（{@code input()}）与问题列表（{@code question()} /
     * {@code questions()}）可作为默认值使用：参数为 null / 空时自动补齐到本地状态。
     *
     * @param input 共享证据
     * @param questions 问题列表
     * @return 决策结果
     */
    @Override
    public DecisionsResult decide(String input, List<DecisionQuestion> questions) {
        String actualInput = input;
        if (actualInput == null || actualInput.isBlank()) {
            actualInput = this.input;
        }
        List<DecisionQuestion> actualQuestions = questions;
        if (actualQuestions == null || actualQuestions.isEmpty()) {
            actualQuestions = this.questions;
        }
        DecisionsRequest request = DecisionsRequest.create(actualInput, actualQuestions);
        return decide(request);
    }

    @Override
    public DecisionsResult decide(DecisionsRequest request) {
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
        return execute(request);
    }

    /**
     * 同步决策。
     *
     * @param request 决策请求
     * @return 决策结果
     */
    @Override
    public CompletableFuture<DecisionsResult> decideAsync(String input, List<DecisionQuestion> questions) {
        return CompletableFuture.supplyAsync(() -> decide(input, questions));
    }

    @Override
    public CompletableFuture<DecisionsResult> decideAsync(DecisionsRequest request) {
        return CompletableFuture.supplyAsync(() -> decide(request));
    }

    @Override
    public Map.Entry<String, String> resolveAuthHeader() {
        return AuthHeaders.resolve(setting, getApiKey());
    }

    @Override
    public DecisionsSetting getSetting() {
        return setting;
    }

    @Override
    public String getModel() {
        String actualModel = model;
        if (actualModel == null) {
            actualModel = setting.getModel();
        }
        if (actualModel == null) {
            actualModel = modelForProtocol();
        }
        return actualModel;
    }

    @Override
    public String getBaseUrl() {
        return normalizeBaseUrl();
    }

    @Override
    public String getApiKey() {
        return setting != null ? setting.getAppKey() : null;
    }

    // ==================== 执行层 ====================

    /**
     * 同步执行一次决策请求。
     *
     * <p>先构建原始 HTTP 请求，用 {@link OkHttpClient#executeAsync} 发起调用并等待完整响应
     * （包含响应体），然后解析为 {@link DecisionsResult}。
     *
     * @param request 决策请求
     * @return 决策结果
     */
    private DecisionsResult execute(DecisionsRequest request) {
        String url = buildUrl();
        long timeout = resolveTimeout();
        long startTime = System.currentTimeMillis();
        OkHttpClient client = createRawClient();
        try {
            HttpRequest httpRequest = buildHttpRequest(url, request);
            RequestOptions options = RequestOptions.builder().timeout(Duration.ofMillis(timeout)).build();
            return client.executeAsync(httpRequest, options)
                    .thenApply(response -> {
                        try {
                            return parseResponse(response, request, startTime);
                        } finally {
                            if (response != null) {
                                try {
                                    response.close();
                                } catch (Exception e) {
                                    log.debug("关闭原始 HTTP 响应失败: {}", e.getMessage());
                                }
                            }
                        }
                    })
                    .get(timeout, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("OpenAI 决策请求失败: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI 决策请求失败: " + e.getMessage(), e);
        } finally {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("关闭原始 HTTP 客户端失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 异步执行一次决策请求。
     *
     * @param request 决策请求
     * @return 决策结果异步任务
     */
    private CompletableFuture<DecisionsResult> executeAsync(DecisionsRequest request) {
        String url = buildUrl();
        long timeout = resolveTimeout();
        long startTime = System.currentTimeMillis();
        OkHttpClient client = createRawClient();
        HttpRequest httpRequest = buildHttpRequest(url, request);
        RequestOptions options = RequestOptions.builder().timeout(Duration.ofMillis(timeout)).build();
        return client.executeAsync(httpRequest, options)
                .thenApply(response -> {
                    try {
                        return parseResponse(response, request, startTime);
                    } finally {
                        if (response != null) {
                            try {
                                response.close();
                            } catch (Exception e) {
                                log.debug("关闭原始 HTTP 响应失败: {}", e.getMessage());
                            }
                        }
                    }
                })
                .whenComplete((result, error) -> {
                    try {
                        client.close();
                    } catch (Exception e) {
                        log.debug("关闭原始 HTTP 客户端失败: {}", e.getMessage());
                    }
                });
    }

    /**
     * 构建原始 HTTP 请求。
     *
     * @param url     完整的目标 URL（已包含协议路径）
     * @param request 决策请求
     * @return HttpRequest
     */
    private HttpRequest buildHttpRequest(String url, DecisionsRequest request) {
        Map<String, Object> requestJson = buildRequestBody(request);
        HttpRequest.Builder builder = HttpRequest.builder()
                .method(HttpMethod.POST)
                .baseUrl(url);
        // 认证头：认证头的写入点在 HttpRequest.Builder 上（原始通道不持有凭据）
        Map.Entry<String, String> auth = resolveAuthHeader();
        if (auth != null) {
            builder.putHeader(auth.getKey(), auth.getValue());
        }
        Map<String, String> headers = this.extraHeaders != null ? this.extraHeaders
                : (setting != null ? setting.getExtraHeaders() : null);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (auth != null && auth.getKey().equalsIgnoreCase(entry.getKey())) {
                    continue;
                }
                builder.putHeader(entry.getKey(), entry.getValue());
            }
        }
        // 注意: openai-java 4.76.0 的 HttpRequestBodies.json(JsonMapper, T) 是 Kotlin 泛型
        // 方法, Java 调用端类型推断不工作 (任何参数类型都报 "找不到符号: 方法 json(JsonMapper,T)")
        // 因此改为手动序列化, 避免拿不到 HttpRequestBody 的断言.
        HttpRequestBody body = jsonRequestBody(requestJson);
        builder.body(body);
        return builder.build();
    }

    /**
     * 将请求体 Map 序列化为 JSON 文本并包装为 {@link HttpRequestBody}。
     *
     * <p>openai-java 4.76.0 提供的是 Kotlin 泛型方法
     * {@code HttpRequestBodies.json(JsonMapper, T)}，Java 调用时类型推断不工作（所有参数类型都
     * 报 {@code 找不到符号: 方法 json(JsonMapper,Map<String,Object>)>}，因此改为手动实现。
     *
     * @param requestJson 决策请求体
     * @return 以 UTF-8 字节为内容体的 HttpRequestBody（可重复, 长度可提前计算）
     */
    private static HttpRequestBody jsonRequestBody(Map<String, Object> requestJson) {
        byte[] json;
        try {
            json = ObjectMappers.jsonMapper().writeValueAsBytes(requestJson);
        } catch (Exception e) {
            log.error("OpenAI 决策请求体序列化失败: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI 决策请求体序列化失败: " + e.getMessage(), e);
        }
        // 纯文本体, 便于 SDK 计算 Content-Length
        return new JsonRequestBody(json);
    }

    /**
     * 简单的 JSON 请求体, 直接以 UTF-8 字节的 View 提供长度与内容.
     * <p/> 接口方法 {@code writeTo} 不声明任何被检查异常, 突发 I/O 被包装为
     * {@link RuntimeException} 抛出.
     */
    private static final class JsonRequestBody implements HttpRequestBody {

        private final byte[] bytes;

        private JsonRequestBody(byte[] json) {
            this.bytes = json;
        }

        @Override
        public void writeTo(OutputStream os) {
            try {
                os.write(bytes);
            } catch (IOException e) {
                throw new RuntimeException("OpenAI 决策请求体写入失败: " + e.getMessage(), e);
            }
        }

        @Override
        public String contentType() {
            return "application/json; charset=utf-8";
        }

        @Override
        public long contentLength() {
            return bytes.length;
        }

        @Override
        public boolean repeatable() {
            return true;
        }

        @Override
        public void close() {
            // 文本体, 无需关闭
        }
    }

    // ==================== 协议翻译 ====================

    /**
     * 构建设计请求体。
     *
     * @param request 决策请求
     * @return 请求体 Map
     */
    private Map<String, Object> buildRequestBody(DecisionsRequest request) {
        DecisionsProtocol protocol = protocol();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", getModel());
        if (system != null && !system.isBlank()) {
            body.put("system", system);
        }
        if (protocol == DecisionsProtocol.SYSTEMONE) {
            // SystemOne: POST /v1/systemone, state + questions（答案按问题名分拣）
            body.put("state", request.state());
            body.put("questions", toSystemOneQuestions(request.questions()));
        } else {
            // OpenAI: POST /v1/decisions, input + questions（列表）
            body.put("input", request.input());
            body.put("questions", toOpenAiQuestions(request.questions()));
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("maxTokens", maxTokens);
        }
        if (topP != null) {
            body.put("topP", topP);
        }
        if (extraBody != null) {
            body.putAll(extraBody);
        }
        // 合并此次请求的额外参数
        if (request.extraBody() != null) {
            for (Map.Entry<String, Object> entry : request.extraBody().entrySet()) {
                if (entry.getValue() != null) {
                    body.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return body;
    }

    /**
     * 将决策问题翻译为 OpenAI 协议的 {@code questions} 数组。
     *
     * @param questions 问题列表
     * @return OpenAI 口味的 questions 数组
     */
    private List<Map<String, Object>> toOpenAiQuestions(List<DecisionQuestion> questions) {
        List<Map<String, Object>> result = new ArrayList<>(questions.size());
        for (DecisionQuestion question : questions) {
            DecisionType type = question.type();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type == DecisionType.NOUL ? "predicate" : type.toString().toLowerCase());
            m.put("name", question.name());
            m.put("instructions", question.instructions());
            if (type == DecisionType.CHOICE) {
                // criteria（选项名 -> 选项说明） -> choices [{value, description}]
                m.put("choices", toChoices(question.criteria()));
            } else if (type == DecisionType.SCORE) {
                // levels（等级说明） -> levels [{label, description}]
                m.put("levels", toLevels(question.levels()));
            }
            result.add(m);
        }
        return result;
    }

    /**
     * 将决策问题翻译为 SystemOne 协议的 {@code questions} 键值对。
     *
     * @param questions 问题列表
     * @return SystemOne 口味的 questions 映射
     */
    private Map<String, Object> toSystemOneQuestions(List<DecisionQuestion> questions) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (DecisionQuestion question : questions) {
            DecisionType type = question.type();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type == DecisionType.NOUL ? "noul" : type.toString().toLowerCase());
            m.put("name", question.name());
            m.put("instructions", question.instructions());
            if (type == DecisionType.CHOICE) {
                // 选项名 -> 选项说明（透传，答案标签以服务端返回的为准）
                m.put("criteria", question.criteria());
            } else if (type == DecisionType.SCORE) {
                // 等级说明 -> criteria [说明]，位置决定等级索引
                m.put("criteria", new ArrayList<>(question.levels()));
            } else {
                // noul: criteria（true/false -> 说明）
                m.put("criteria", question.criteria());
            }
            result.put(question.name(), m);
        }
        return result;
    }

    /**
     * 将选项表翻译为 OpenAI 口味的 choices。
     *
     * @param criteria 选项表
     * @return choices 列表
     */
    private static List<Map<String, Object>> toChoices(Map<String, String> criteria) {
        List<Map<String, Object>> result = new ArrayList<>(criteria.size());
        for (Map.Entry<String, String> entry : criteria.entrySet()) {
            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("value", entry.getKey());
            choice.put("description", entry.getValue());
            result.add(choice);
        }
        return result;
    }

    /**
     * 将等级表翻译为 OpenAI 口味的 levels。
     *
     * @param levels 等级列表
     * @return levels 列表
     */
    private static List<Map<String, Object>> toLevels(List<String> levels) {
        List<Map<String, Object>> result = new ArrayList<>(levels.size());
        int idx = 0;
        for (String level : levels) {
            Map<String, Object> item = new LinkedHashMap<>();
            // label 由位置决定：多选一从 A 开始，有序分级从 0 开始
            item.put("label", labelByIndex(idx));
            item.put("description", level);
            result.add(item);
            idx++;
        }
        return result;
    }

    /**
     * 行为的标签空间翻译：多选一从 A ~ Z，有序分级从 0 ~ 9。
     *
     * @param idx 位置索引
     * @return 标签
     */
    private static String labelByIndex(int idx) {
        // 多选一 -> A~Z，有序分级 -> 0~9
        return idx < 26 ? String.valueOf((char) ('A' + idx)) : String.valueOf(idx);
    }

    // ==================== 响应解析 ====================

    /**
     * 解析原始 HTTP 响应为决策结果。
     *
     * @param response 原始响应
     * @param request  决策请求
     * @param startTime 请求开始时间
     * @return 决策结果
     */
    private DecisionsResult parseResponse(HttpResponse response, DecisionsRequest request, long startTime) {
        String content;
        try (InputStream body = response.body()) {
            if (body == null) {
                content = null;
            } else {
                byte[] bytes = body.readAllBytes();
                content = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new RuntimeException("OpenAI 决策响应解析失败: " + e.getMessage(), e);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("OpenAI 决策响应为空: " + response.statusCode());
        }
        JsonMapper mapper = ObjectMappers.jsonMapper();
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI 决策响应 JSON 解析失败: " + e.getMessage(), e);
        }
        int status = response.statusCode();
        if (status >= 400) {
            JsonNode error = root.path("error");
            String detail = error != null && !error.isMissingNode() ? error.toString() : content;
            throw new IllegalStateException("OpenAI 决策请求失败: status=" + status + ", body=" + detail);
        }
        DecisionsProtocol protocol = protocol();
        String model = string(root, "model", null);
        Map<String, DecisionAnswer> answers = parseAnswers(root, protocol);
        AiUsage usage = parseUsage(root, protocol, model, startTime);
        return new DecisionsResult(model, protocol, answers, usage);
    }

    /**
     * 解析答案。
     *
     * @param root     协议根节点
     * @param protocol 协议
     * @return 问题名 -> 答案
     */
    private Map<String, DecisionAnswer> parseAnswers(JsonNode root, DecisionsProtocol protocol) {
        Map<String, DecisionAnswer> result = new LinkedHashMap<>();
        if (root == null) {
            return result;
        }
        // SystemOne 的答案按问题名分发（对象），OpenAI 的答案是带 name 的列表（Array）
        JsonNode answers = root.path("answers");
        if (answers.isMissingNode() || answers.isNull()) {
            answers = root.path("questions");
        }
        if (answers.isObject()) {
            for (Map.Entry<String, JsonNode> field : answers.properties()) {
                DecisionAnswer answer = parseAnswer(field.getKey(), field.getValue(), protocol);
                if (answer != null) {
                    result.put(field.getKey(), answer);
                }
            }
        } else if (answers.isArray()) {
            for (JsonNode node : answers) {
                String name = string(node, "name", null);
                DecisionAnswer answer = parseAnswer(name, node, protocol);
                if (answer != null && name != null) {
                    result.put(name, answer);
                }
            }
        }
        // 兜底：答案在 choices[*].decisions[*] 中
        if (result.isEmpty()) {
            JsonNode choices = root.path("choices");
            if (!choices.isMissingNode() && choices.isArray()) {
                for (JsonNode choice : choices) {
                    for (String key : new String[]{"decisions", "answers"}) {
                        JsonNode list = choice.path(key);
                        if (!list.isMissingNode() && list.isArray()) {
                            for (JsonNode node : list) {
                                String name = string(node, "name", null);
                                DecisionAnswer answer = parseAnswer(name, node, protocol);
                                if (answer != null && name != null) {
                                    result.put(name, answer);
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 解析单个答案。
     *
     * @param name   问题名
     * @param node   答案节点
     * @param protocol 协议
     * @return 答案
     */
    private static DecisionAnswer parseAnswer(String name, JsonNode node, DecisionsProtocol protocol) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        // 问题名：键值对时由 key 提供，列表时取 name 字段
        String type = string(node, "type", null);
        DecisionType answerType = answerType(protocol, type);
        Double probability = number(node, "probability");
        Double noul = number(node, "noul");
        if (probability == null) {
            probability = noul;
        }
        String label = string(node, "label", null);
        String choice = string(node, "choice", null);
        Double score = number(node, "score");
        Double confidence = number(node, "confidence");
        Double labelMass = number(node, "x_label_mass");
        Map<String, Double> probabilities = probabilities(node, "probabilities");
        Map<Integer, String> legend = legend(node, "legend");
        String refusal = string(node, "refusal", null);

        if (refusal != null && !refusal.isBlank()) {
            return DecisionAnswer.refusal(name);
        }
        // 未识别类型时，兜底：<label> 上存在即结论
        if (answerType == null) {
            if (label != null && !label.isBlank()) {
                answerType = guessAnswerType(node, label, choice, score, probabilities);
            }
            if (answerType == null) {
                return DecisionAnswer.refusal(name);
            }
        }
        // NOUL → probability
        if (answerType == DecisionType.NOUL) {
            if (probability == null) {
                probability = 0.5;
            }
            if (label == null || label.isBlank()) {
                label = probability >= 0.5d ? DecisionAnswer.YES_LABEL : DecisionAnswer.NO_LABEL;
            }
            if (probabilities == null || probabilities.isEmpty()) {
                probabilities = new LinkedHashMap<>();
                probabilities.put(DecisionAnswer.YES_LABEL, probability);
                probabilities.put(DecisionAnswer.NO_LABEL, 1 - probability);
            }
            return new DecisionAnswer(name, DecisionType.NOUL, label, probability,
                    null, null, null, probabilities, null, labelMass);
        }
        // CHOICE → choice
        if (answerType == DecisionType.CHOICE) {
            if (choice == null && probabilities != null && !probabilities.isEmpty()) {
                String best = null;
                Double bestProbability = null;
                for (Map.Entry<String, Double> entry : probabilities.entrySet()) {
                    if (best == null || (entry.getValue() != null
                            && (bestProbability == null || entry.getValue() > bestProbability))) {
                        best = entry.getKey();
                        bestProbability = entry.getValue();
                    }
                }
                choice = best;
            }
            if (choice == null) {
                return DecisionAnswer.refusal(name);
            }
            if (label == null || label.isBlank()) {
                label = choice;
            }
            return new DecisionAnswer(name, DecisionType.CHOICE, label, null, null, choice,
                    confidence, probabilities, null, labelMass);
        }
        // SCORE → score
        if (answerType == DecisionType.SCORE) {
            if (label == null && score != null) {
                label = String.valueOf(Math.round(score));
            }
            return new DecisionAnswer(name, DecisionType.SCORE, label, null, score, null,
                    confidence, probabilities, legend, labelMass);
        }
        return DecisionAnswer.refusal(name);
    }

    /**
     * 未携带 type 时，由答案字段推断问题类型。
     *
     * @param label   标签
     * @param choice  选项
     * @param score   期望分
     * @param probabilities 概率分布
     * @return 问题类型
     */
    private static DecisionType guessAnswerType(JsonNode node, String label, String choice,
                                                Double score, Map<String, Double> probabilities) {
        if (choice != null) {
            return DecisionType.CHOICE;
        }
        if (score != null) {
            return DecisionType.SCORE;
        }
        if (label != null && (DecisionAnswer.YES_LABEL.equalsIgnoreCase(label)
                || DecisionAnswer.NO_LABEL.equalsIgnoreCase(label))) {
            return DecisionType.NOUL;
        }
        if (probabilities != null && !probabilities.isEmpty()
                && probabilities.containsKey(DecisionAnswer.YES_LABEL)) {
            return DecisionType.NOUL;
        }
        JsonNode noul = node.path("noul");
        if (!noul.isMissingNode() && !noul.isNull()) {
            return DecisionType.NOUL;
        }
        return null;
    }

    /**
     * 协议答案类型的映射。
     *
     * @param protocol 协议
     * @param type 答案类型
     * @return 统一的问题类型
     */
    private static DecisionType answerType(DecisionsProtocol protocol, String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        String lowercase = type.trim().toLowerCase();
        if (protocol == DecisionsProtocol.SYSTEMONE) {
            return switch (lowercase) {
                case "noul" -> DecisionType.NOUL;
                case "predicate" -> DecisionType.NOUL;
                case "choice" -> DecisionType.CHOICE;
                case "score" -> DecisionType.SCORE;
                case "refusal" -> null;
                default -> null;
            };
        }
        return switch (lowercase) {
            case "predicate" -> DecisionType.NOUL;
            case "noul" -> DecisionType.NOUL;
            case "choice" -> DecisionType.CHOICE;
            case "score" -> DecisionType.SCORE;
            case "refusal" -> null;
            default -> null;
        };
    }

    /**
     * 解析概率分布。
     *
     * @param node 答案节点
     * @param field 字段名
     * @return 标签（key）-> 概率
     */
    private static Map<String, Double> probabilities(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        Map<String, Double> result = new LinkedHashMap<>();
        if (fieldNode.isObject()) {
            for (Map.Entry<String, JsonNode> entry : fieldNode.properties()) {
                Double value = number(entry.getValue());
                if (value != null) {
                    result.put(entry.getKey(), value);
                }
            }
        } else if (fieldNode.isArray()) {
            for (JsonNode v : fieldNode) {
                String key = string(v, "value", null);
                if (key == null || key.isBlank()) {
                    key = string(v, "label", null);
                }
                Double value = number(v, "probability");
                if (key != null && !key.isBlank() && value != null) {
                    result.put(key, value);
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 解析等级标签表（等级索引 -> 等级说明）。
     *
     * @param node 答案节点
     * @param field 字段名
     * @return 等级索引 -> 等级说明
     */
    private static Map<Integer, String> legend(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        Map<Integer, String> result = new LinkedHashMap<>();
        if (fieldNode.isObject()) {
            for (Map.Entry<String, JsonNode> entry : fieldNode.properties()) {
                Integer index = stringToInt(entry.getKey(), null);
                if (index == null) {
                    continue;
                }
                result.put(index, field(entry.getValue()));
            }
        } else if (fieldNode.isArray()) {
            int index = 0;
            for (JsonNode v : fieldNode) {
                if (!v.isMissingNode() && !v.isNull()) {
                    result.put(index, v.asText());
                }
                index++;
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * 解析过程中解析标签。
     *
     * @param key 字段名
     * @param value 数值
     * @return 数值或空
     */
    private static Integer stringToInt(String key, Integer value) {
        if (key == null || key.isBlank()) {
            return value;
        }
        try {
            return Integer.valueOf(key);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * 解析用量信息。
     *
     * @param root 根节点
     * @param protocol 协议
     * @param model 模型
     * @param startTime 开始时间
     * @return 用量
     */
    private static AiUsage parseUsage(JsonNode root, DecisionsProtocol protocol, String model, long startTime) {
        if (root == null) {
            return null;
        }
        JsonNode usageNode = root.path("usage");
        if (usageNode.isMissingNode() || usageNode.isNull()) {
            return null;
        }
        // OpenAI 的用量以 prompt_tokens / completion_tokens 为单位
        // SystemOne 的用量以 input_tokens / output_tokens 为单位
        Integer inputTokens = getInteger(usageNode, "prompt_tokens");
        Integer outputTokens = getInteger(usageNode, "completion_tokens");
        Integer totalTokens = getInteger(usageNode, "total_tokens");
        if (protocol == DecisionsProtocol.SYSTEMONE) {
            // 重新按 SystemOne 意图取值
            if (inputTokens == null) {
                inputTokens = getInteger(usageNode, "input_tokens");
            }
            if (outputTokens == null) {
                outputTokens = getInteger(usageNode, "output_tokens");
            }
        }
        if (inputTokens == null && outputTokens == null) {
            return null;
        }
        // 折叠：总 Token 数 = 输入 + 输出
        if (totalTokens == null) {
            if (inputTokens != null && outputTokens != null) {
                totalTokens = inputTokens + outputTokens;
            } else {
                totalTokens = inputTokens == null ? outputTokens : inputTokens;
            }
        }
        return AiUsage.builder()
                .model(model)
                .provider("openai")
                .startTime(startTime)
                .durationMillis(System.currentTimeMillis() - startTime)
                .firstTokenLatencyMillis(System.currentTimeMillis() - startTime)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .build();
    }

    // ==================== 工具方法 ====================

    /**
     * 规范化 API 基础地址。
     *
     * @return 规范化后的 URL
     */
    private String normalizeBaseUrl() {
        String url = setting.getBaseUrl();
        if (url == null || url.isBlank()) {
            url = defaultBaseUrlForProtocol();
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * 按协议返回缺省 API 基础地址。
     *
     * @return 缺省基础地址
     */
    private DecisionsProtocol protocol() {
        if (this.protocol == null) {
            this.protocol = resolveProtocol(setting);
        }
        return this.protocol;
    }

    /**
     * default Base Url For Protocol
     * @return 返回值
    */
    private String defaultBaseUrlForProtocol() {
        String provider = setting.getProvider();
        if (protocol() == DecisionsProtocol.SYSTEMONE) {
            return SYSTEMONE_URL;
        }
        if (provider != null && ("systemone".equalsIgnoreCase(provider)
                || "typesafe".equalsIgnoreCase(provider))) {
            return SYSTEMONE_URL;
        }
        return DEFAULT_URL;
    }

    /**
     * 按协议返回缺省模型。
     *
     * @return 缺省模型
     */
    private String modelForProtocol() {
        return protocol() == DecisionsProtocol.SYSTEMONE ? SYSTEMONE_MODEL : DEFAULT_MODEL;
    }

    /**
     * 拼接协议完整地址。
     *
     * <p>协议路径以 {@code /v1/decisions} / {@code /v1/systemone} 定义，基础地址可能已经
     * 携带了 {@code /v1} 前缀，此时只追加最后的端点名称（比如 {@code https://api.openai.com/v1}
     * + {@code decisions}），避免路径重复。
     *
     * @return 完整地址
     */
    private String buildUrl() {
        String baseUrl = normalizeBaseUrl();
        DecisionsProtocol protocol = protocol();
        if (protocol == null) {
            protocol = DecisionsProtocol.OPENAI;
        }
        String path = protocol.path();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            return baseUrl;
        }
        String[] pathSegments = path.split("/");
        // 基础地址已经包含协议路径前缀时，只追加最后一位
        if (pathSegments.length > 1 && baseUrlEndsWithPrefix(baseUrl, pathSegments)) {
            return baseUrl + "/" + pathSegments[pathSegments.length - 1];
        }
        return baseUrl + "/" + path;
    }

    /**
     * 基础地址是否已经包含协议路径的全部前缀。
     *
     * @param baseUrl 基础地址
     * @param pathSegments 协议路径片段
     * @return true 表示已包含
     */
    private boolean baseUrlEndsWithPrefix(String baseUrl, String[] pathSegments) {
        String[] baseSegments = baseUrl.split("/");
        int startIndex = baseSegments.length - (pathSegments.length - 1);
        if (startIndex < 0) {
            return false;
        }
        for (int i = 0; i < pathSegments.length - 1; i++) {
            if (!baseSegments[startIndex + i].equals(pathSegments[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析请求超时时间。
     *
     * @return 超时时间（毫秒）
     */
    private long resolveTimeout() {
        if (setting != null && setting.getTimeoutMillis() != null && setting.getTimeoutMillis() > 0L) {
            return setting.getTimeoutMillis();
        }
        return DEFAULT_TIMEOUT_MILLIS;
    }

    /**
     * 解析代理地址。
     *
     * @param proxyStr 代理地址字符串
     * @return Proxy 对象，失败时返回空
     */
    private static Proxy resolveProxy(String proxyStr) {
        if (proxyStr == null || proxyStr.isBlank()) {
            return null;
        }
        Proxy.Type proxyType;
        String hostPort;
        if (proxyStr.startsWith("socks5://") || proxyStr.startsWith("socks://")) {
            proxyType = Proxy.Type.SOCKS;
            hostPort = proxyStr.substring(proxyStr.indexOf("://") + 3);
        } else if (proxyStr.startsWith("http://")) {
            proxyType = Proxy.Type.HTTP;
            hostPort = proxyStr.substring(7);
        } else {
            proxyType = Proxy.Type.HTTP;
            hostPort = proxyStr;
        }
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 80;
        return new Proxy(proxyType, new InetSocketAddress(host, port));
    }

    /**
     * 创建原始 HTTP 客户端。
     *
     * <p>原始通道不持有凭据——认证由 Header 承担，Http Base 地址由 HttpRequest 承担。
     *
     * @return HttpClient
     */
    private OkHttpClient createRawClient() {
        OkHttpClient.Builder builder = OkHttpClient.builder()
                .timeout(Duration.ofMillis(resolveTimeout()));
        if (setting != null) {
            String proxyStr = setting.getProxy();
            if (proxyStr != null && !proxyStr.isBlank()) {
                builder.proxy(resolveProxy(proxyStr));
            }
        }
        return builder.build();
    }

    /**
     * 取字符串值。
     *
     * @param node 节点
     * @param key 字段名
     * @param def 默认值
     * @return 字符串或默认值
     */
    private static String string(JsonNode node, String key, String def) {
        if (node == null) {
            return def;
        }
        JsonNode value = node.path(key);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return def;
        }
        return value.asText();
    }

    /**
     * 取数值。
     *
     * @param value 节点
     * @return 数值或空
     */
    private static Double number(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return value.asDouble();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取数值。
     *
     * @param node 节点
     * @param key 字段名
     * @return 数值或空
     */
    private static Double number(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(key);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return value.asDouble();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 取整数。
     *
     * @param node 节点
     * @param key 字段名
     * @return 数值或空
     */
    private static Integer getInteger(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(key);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        try {
            return value.asInt();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 顺序 JSON 字段。
     *
     * @param node 节点
     * @return 字符串或空
     */
    private static String field(JsonNode node) {
        return node != null && !node.isMissingNode() && !node.isNull() ? node.asText() : null;
    }
}