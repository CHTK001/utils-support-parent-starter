package com.chua.deeplearning.support.needle;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.ai.chat.ChatMessage;
import com.chua.common.support.ai.chat.ChatSyncResponse;
import com.chua.common.support.ai.chat.ChatTool;
import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.lang.json.Json5;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.needle.NeedleNative;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Needle 推理引擎的本地客户端（工具调用 / 结构化抽取）。
 *
 * <p><b>注意：Needle 不是对话模型，本类也不是对话客户端。</b>
 * 官方明确说明引擎只产生结构化结果，不生成自由文本：对域外输入返回空的
 * {@code function_calls} 作为拒答，没有自由文本兜底。因此
 * {@link #chatSync(String)} 返回的是引擎的原始 JSON envelope，
 * <b>不是自然语言回答</b>。挂在 {@link ChatClient} 上只是为了复用该 SPI，
 * 调用方应按结构化数据解析返回值。</p>
 *
 * <p>envelope 的关键字段：</p>
 * <ul>
 *   <li>{@code type} — {@code call} 表示模型给出工具调用或拒答</li>
 *   <li>{@code function_calls} — 工具调用数组；<b>为空数组即拒答</b></li>
 *   <li>{@code confidence} — 0-1 的校准分，可自行设阈值决定是否执行</li>
 *   <li>{@code validation.ungrounded} — 未在输入中找到依据的字段名</li>
 * </ul>
 *
 * <h3>部署要求</h3>
 * <p>引擎动态库与权重归档是<b>两个独立文件</b>（引擎约 1.2MB，
 * 权重 {@code needle3.cact} 约 34MB），都必须自行提供，不能只给其一。
 * 详见 {@code utils-support-native-needle} 的 README。</p>
 *
 * <h3>输入参数</h3>
 * <ul>
 *   <li>{@link #chatSync(String)} — 用户指令文本（必填）</li>
 *   <li>{@link #system(String)} — 系统提示词 / 环境事实，如
 *       {@code "date: 2026-07-21 Tue 14:30; locale: zh-CN"}</li>
 *   <li>{@link #tools(List)} — 工具声明；{@link ChatTool} 的
 *       name / description / parameters 与引擎所需格式一一对应</li>
 *   <li>{@link #model(String)} — 模型标识，仅作展示；实际权重由加载的
 *       {@code .cact} 归档决定</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   ChatClient client = ChatClient.create("needle", "")
 *       .system("date: 2026-07-21 Tue 14:30")
 *       .tools(List.of(setLightsTool));
 *   String envelope = client.chatSync("turn off the bedroom lights");
 * }</pre>
 *
 * <p><b>多轮语义：</b>引擎会把新指令当作上一轮的后续并沿用历史参数（实测连续
 * 三条互不相关的指令都返回同一个 room）。{@link ChatClient#history(List)}
 * 在本实现中是空操作，无法建立显式上下文，故 {@link #chatSync(String)}
 * 在每次调用前 {@code reset()}，使每条指令相互独立。</p>
 *
 * <p><b>线程模型：</b>引擎是进程级单例且权重不可卸载，
 * {@code utils-support-native-needle} 已把所有原生调用串行化，
 * 多线程会排队而非并行。</p>
 *
 * <p><b>模型能力：</b>官方 README 自述其内置基础模型在六个评测套件中五个不达标
 * （漏掉用户明说的调用、编造未给出的值），实测英文工具调用正常、中文明显偏弱。
 * 另有 CPU 上解码较慢（本次实测约 1-2 tokens/s），不适合延迟敏感场景。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("needle")
public class NeedleChatClient implements ChatClient {

    /**
     * 默认最大生成 令牌 数
     */
    private static final int DEFAULT_MAX_TOKENS = 256;

    /**
     * 默认模型标识；实际权重由加载的 .cact 归档决定，此名仅作展示
     */
    private static final String DEFAULT_MODEL = "needle3";

    /**
     * 系统提示词（环境事实）
     */
    private String system;

    /**
     * 当前模型名称
     */
    private String model;

    /**
     * 最大生成 令牌 数
     */
    private int maxTokens = DEFAULT_MAX_TOKENS;

    /**
     * 工具声明。{@link ChatTool} 的 name / description / parameters
     * 与引擎所需的工具 JSON 格式一一对应，序列化后直接交给引擎。
     */
    private final List<ChatTool> tools = new ArrayList<>();

    /**
     * 构造 Needle 对话客户端。
     *
     * @param setting 客户端配置（可为 空）
     */
    public NeedleChatClient(ChatClientSetting setting) {
        if (setting != null) {
            this.model = setting.getModel();
            this.system = setting.getSystem();
            if (setting.getMaxTokens() != null) {
                this.maxTokens = setting.getMaxTokens();
            }
        }
    }

    @Override
    /**
     * 系统
    */
    public ChatClient system(String system) {
        this.system = system;
        return this;
    }

    @Override
    /**
     * 模型
    */
    public ChatClient model(String model) {
        this.model = model;
        return this;
    }

    @Override
    /**
     * Tools
    */
    public ChatClient tools(List<ChatTool> tools) {
        this.tools.clear();
        if (tools != null) {
            this.tools.addAll(tools);
        }
        return this;
    }

    @Override
    /**
     * Tool
    */
    public ChatClient tool(ChatTool tool) {
        if (tool != null) {
            this.tools.add(tool);
        }
        return this;
    }

    @Override
    /**
     * 对话同步
    */
    public String chatSync(String prompt) {
        return chatSync(prompt, 0);
    }

    @Override
    /**
     * 对话同步
     *
     * <p>返回引擎的原始 JSON envelope，不是自然语言回答；调用方需按结构化
     * 数据解析（见类注释）。每次调用前会清空引擎历史，使各条指令相互独立。</p>
     *
     * @param prompt 用户指令文本
     * @param timeoutMillis 超时毫秒；本实现不透传该参数（原生调用是阻塞的）
     * @return 引擎的原始 JSON envelope
     * @throws IllegalStateException 引擎动态库未部署时抛出
     */
    public String chatSync(String prompt, long timeoutMillis) {
        requireReady();
        NeedleNative.init(system, toolsJson(), model);
        // 引擎会把新指令当作上一轮后续并沿用历史参数（实测连续三条互不相关的
        // 指令都返回同一个 room）；而 history(List) 是空操作、无法建立显式
        // 上下文，故每条指令前 reset，避免串味。
        NeedleNative.reset();
        return NeedleNative.complete(prompt, maxTokens);
    }

    /**
     * 把工具声明序列化为引擎所需的 JSON 数组。
     *
     * @return 工具 JSON；未声明工具时返回空数组字面量
     */
    private String toolsJson() {
        if (tools.isEmpty()) {
            return "[]";
        }
        return Json5.toJson(tools);
    }

    /**
     * 校验引擎已部署，未部署时给出可定位的错误。
     *
     * @throws IllegalStateException 动态库未加载时抛出
     */
    private void requireReady() {
        if (!NeedleNative.isLoaded()) {
            Throwable cause = NeedleNative.getLoadError();
            throw new IllegalStateException("needle 引擎不可用，请按 utils-support-native-needle "
                    + "的 README 提供引擎动态库与 .cact 权重后重试："
                    + (cause == null ? "动态库未加载" : cause.getMessage()), cause);
        }
    }

    @Override
    /**
     * 对话同步with响应
    */
    public ChatSyncResponse chatSyncWithResponse(String prompt) {
        String text = chatSync(prompt);
        return ChatSyncResponse.builder()
                .text(text)
                .build();
    }

    @Override
    /**
     * 历史
    */
    public ChatClient history(List<ChatMessage> messages) {
        return this;
    }

    @Override
    /**
     * 模型
     */
    public List<ModelDefinition> models() {
        ModelDefinition definition = ModelDefinition.builder()
                .id(model != null ? model : DEFAULT_MODEL)
                .name("Needle 3")
                .provider("cactus-compute")
                .description("45M 参数本地工具调用模型：文本进、结构化工具调用出，不生成自由文本")
                .capabilities(List.of("tool-calling", "extraction", "json"))
                .build();
        return List.of(definition);
    }
}
