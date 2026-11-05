// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.common.support.ai.chat.indicator;

import com.chua.common.support.ai.chat.ChatClientIndicator;
import com.chua.common.support.ai.chat.ChatClientSetting;
import com.chua.common.support.utils.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 各 AI 服务商 {@link ChatClientIndicator} 的统一解析入口。
 *
 * <p>服务商的指标数据可能来自四类端点，按优先级依次尝试：</p>
 * <ol>
 *   <li><b>用户显式配置</b>：{@link ChatClientSetting#getMetricsUrl()} 非空时走
 *       {@link HttpChatClientIndicator}，由配置决定数据源；</li>
 *   <li><b>官方模型 / 定价 API</b>：OpenRouter、Pollinations、Vercel AI Gateway
 *       等提供了公开、无需额外配置的机器可读端点；</li>
 *   <li><b>Models.dev 聚合</b>：OpenAI、Anthropic、Google、DeepSeek、通义、智谱、
 *       Moonshot、MiniMax 等官方价格由 models.dev 汇总，按其厂商键过滤；</li>
 *   <li><b>Vercel AI Gateway 兜底</b>：models.dev 未收录的厂商（如腾讯混元）
 *       用 Vercel 的公开模型目录补足。</li>
 * </ol>
 *
 * <p>四类来源都没有覆盖的厂商（百度千帆、讯飞星火、华为盘古、百川、360 智脑等
 * 仅提供网页定价、没有公开机器可读目录）返回 {@code null}。调用方（
 * {@code ChatClient#indicator()} 与后端 {@code sync-pricing}）必须把
 * {@code null} 当作「该服务商暂无可同步的官方指标源」，回退到本地指标库快照，
 * 而不是把缺失当成 0 或伪造价格。</p>
 *
 * <p>服务商标识存在大量别名（{@code qwen}/{@code dashscope}/{@code alibaba}、
 * {@code moonshot}/{@code kimi}、{@code doubao}/{@code volcengine} 等），
 * 本类集中维护别名 → 官方来源的映射，避免每个 {@code ChatClient} 各写一份
 * {@code switch} 后逐渐漂移。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ChatClientIndicators {

    /**
     * 服务商标识 → 官方指标来源规格（别名已展开）
     */
    private static final Map<String, ProviderSpec> SPECS = buildSpecs();

    private ChatClientIndicators() {
    }

    /**
     * 按客户端配置解析指标实现。
     *
     * <p>显式配置的 {@code metrics_url} 优先级最高；未配置时按
     * {@link ChatClientSetting#getProvider()} 查表。无法识别或没有可用数据源时
     * 返回 {@code null}。</p>
     *
     * @param setting 客户端配置，可为 null
     * @return 指标实现；无可用来源时返回 null
     */
    public static ChatClientIndicator of(ChatClientSetting setting) {
        if (setting == null) {
            return null;
        }
        if (StringUtils.isNotBlank(setting.getMetricsUrl())) {
            return new HttpChatClientIndicator(setting);
        }
        return ofProvider(setting.getProvider(), setting);
    }

    /**
     * 按服务商标识解析指标实现。
     *
     * @param provider 服务商标识（大小写、下划线不敏感）
     * @param setting  客户端配置，可为 null（此时仅使用服务商默认端点）
     * @return 指标实现；无可用来源时返回 null
     */
    public static ChatClientIndicator ofProvider(String provider, ChatClientSetting setting) {
        ProviderSpec spec = specOf(provider);
        if (spec == null || spec.kind == Kind.NONE) {
            return null;
        }
        ChatClientSetting effective = setting != null
                ? setting
                : ChatClientSetting.builder().provider(provider).build();
        return switch (spec.kind) {
            case OPENROUTER -> new OpenRouterChatClientIndicator(effective);
            case POLLINATIONS -> new PollinationsChatClientIndicator(effective);
            case VERCEL -> new VercelAiGatewayChatClientIndicator(effective, spec.vercelOwner);
            case MODELS_DEV -> new ModelsDevChatClientIndicator(spec.modelsDevKey);
            case NONE -> null;
        };
    }

    /**
     * 服务商标识是否配置了可用的官方指标来源。
     *
     * @param provider 服务商标识
     * @return 有来源返回 true
     */
    public static boolean supports(String provider) {
        ProviderSpec spec = specOf(provider);
        return spec != null && spec.kind != Kind.NONE;
    }

    /**
     * 取服务商对应的 Models.dev 厂商键。
     *
     * @param provider 服务商标识
     * @return Models.dev 键；未映射时返回 null
     */
    public static String modelsDevKeyOf(String provider) {
        ProviderSpec spec = specOf(provider);
        return spec == null ? null : spec.modelsDevKey;
    }

    /**
     * 取服务商官方定价页地址（用于前端溯源展示）。
     *
     * @param provider 服务商标识
     * @return 官方定价页；未知时返回 null
     */
    public static String officialPricingUrl(String provider) {
        ProviderSpec spec = specOf(provider);
        return spec == null ? null : spec.officialPricingUrl;
    }

    /**
     * 取服务商展示名称。
     *
     * @param provider 服务商标识
     * @return 展示名称；未知时原样返回入参
     */
    public static String displayName(String provider) {
        ProviderSpec spec = specOf(provider);
        return spec == null ? provider : spec.displayName;
    }

    /**
     * 全部受支持的服务商标识（主标识，不含别名）。
     *
     * @return 只读标识集合
     */
    public static Set<String> supportedProviders() {
        Set<String> result = new LinkedHashSet<>(64);
        for (ProviderSpec spec : SPECS.values()) {
            if (spec.kind != Kind.NONE && spec.primary) {
                result.add(spec.id);
            }
        }
        return Set.copyOf(result);
    }

    /**
     * 按标识（含别名）查规格。
     *
     * @param provider 服务商标识
     * @return 规格；未知时返回 null
     */
    public static ProviderSpec specOf(String provider) {
        String key = normalize(provider);
        return key.isEmpty() ? null : SPECS.get(key);
    }

    /**
     * 归一化服务商标识：小写、去空白、下划线转连字符。
     * @param provider 字符串
     * @return 返回值
    */
    private static String normalize(String provider) {
        if (provider == null) {
            return "";
        }
        return provider.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 构建别名索引。
     * @return 返回值
    */
    private static Map<String, ProviderSpec> buildSpecs() {
        List<ProviderSpec> specs = new ArrayList<>(64);
        specs.add(ProviderSpec.modelsDev("openai", "OpenAI",
                "https://openai.com/api/pricing/", "openai",
                "openai", "openai-response", "openai-compatible", "github", "gitee", "github-copilot"));
        specs.add(ProviderSpec.modelsDev("anthropic", "Anthropic",
                "https://www.anthropic.com/pricing", "anthropic",
                "anthropic", "claude"));
        specs.add(ProviderSpec.modelsDev("google", "Google Gemini",
                "https://ai.google.dev/gemini-api/docs/pricing", "google",
                "google", "gemini", "google-ai-studio", "google-vertex", "vertex", "vertexai"));
        specs.add(ProviderSpec.modelsDev("xai", "xAI Grok",
                "https://docs.x.ai/docs/models", "xai",
                "xai", "grok"));
        specs.add(ProviderSpec.modelsDev("mistral", "Mistral AI",
                "https://mistral.ai/pricing", "mistral",
                "mistral", "mistralai", "codestral"));
        specs.add(ProviderSpec.modelsDev("groq", "Groq",
                "https://groq.com/pricing", "groq", "groq"));
        specs.add(ProviderSpec.modelsDev("together", "Together AI",
                "https://www.together.ai/pricing", "togetherai",
                "together", "togetherai"));
        specs.add(ProviderSpec.modelsDev("cerebras", "Cerebras",
                "https://www.cerebras.ai/pricing", "cerebras", "cerebras"));
        specs.add(ProviderSpec.modelsDev("cohere", "Cohere",
                "https://cohere.com/pricing", "cohere", "cohere"));
        specs.add(ProviderSpec.modelsDev("perplexity", "Perplexity",
                "https://docs.perplexity.ai/getting-started/pricing", "perplexity",
                "perplexity", "pplx"));
        specs.add(ProviderSpec.modelsDev("huggingface", "Hugging Face",
                "https://huggingface.co/pricing", "huggingface",
                "huggingface", "hf"));
        specs.add(ProviderSpec.modelsDev("nvidia", "NVIDIA NIM",
                "https://build.nvidia.com/", "nvidia",
                "nvidia", "nvidia-nim", "nim"));
        specs.add(ProviderSpec.modelsDev("deepseek", "DeepSeek",
                "https://api-docs.deepseek.com/quick_start/pricing", "deepseek",
                "deepseek", "seek"));
        specs.add(ProviderSpec.modelsDev("qwen", "通义千问",
                "https://help.aliyun.com/zh/model-studio/models", "alibaba",
                "qwen", "dashscope", "alibaba", "bailian", "tongyi", "qianwen"));
        specs.add(ProviderSpec.modelsDev("zhipu", "智谱 GLM",
                "https://open.bigmodel.cn/pricing", "zhipuai",
                "zhipu", "glm", "bigmodel", "chatglm"));
        specs.add(ProviderSpec.modelsDev("zai", "Z.ai",
                "https://z.ai/pricing", "zai",
                "zai", "z-ai"));
        specs.add(ProviderSpec.modelsDev("moonshot", "Moonshot Kimi",
                "https://platform.moonshot.cn/docs/pricing/chat", "moonshotai",
                "moonshot", "kimi", "moonshotai"));
        specs.add(ProviderSpec.modelsDev("minimax", "MiniMax",
                "https://www.minimaxi.com/document/price", "minimax",
                "minimax", "minimaxi", "hailuo"));
        specs.add(ProviderSpec.modelsDev("stepfun", "阶跃星辰",
                "https://platform.stepfun.com/docs/pricing/details", "stepfun",
                "stepfun", "step"));
        specs.add(ProviderSpec.modelsDev("sensenova", "商汤 SenseNova",
                "https://platform.sensenova.cn/pricing", "sensenova",
                "sensenova", "sensetime"));
        specs.add(ProviderSpec.modelsDev("doubao", "火山引擎豆包",
                "https://www.volcengine.com/docs/82379/1099320", "volcengine",
                "volcengine", "doubao", "ark", "bytedance"));
        specs.add(ProviderSpec.modelsDev("modelscope", "ModelScope 魔搭",
                "https://modelscope.cn/docs", "modelscope",
                "modelscope", "damo"));
        specs.add(ProviderSpec.modelsDev("qiniu", "七牛云 AI",
                "https://www.qiniu.com/products/ai", "qiniu-ai",
                "qiniu", "qiniu-ai"));
        specs.add(ProviderSpec.modelsDev("azure", "Azure OpenAI",
                "https://azure.microsoft.com/en-us/pricing/details/cognitive-services/openai-service/", "azure",
                "azure", "microsoft", "azure-openai"));
        specs.add(ProviderSpec.modelsDev("aws-bedrock", "AWS Bedrock",
                "https://aws.amazon.com/bedrock/pricing/", "amazon-bedrock",
                "aws-bedrock", "bedrock", "amazon", "amazon-bedrock"));
        specs.add(ProviderSpec.modelsDev("siliconflow", "SiliconFlow 硅基流动",
                "https://siliconflow.cn/pricing", "siliconflow",
                "siliconflow", "silicon"));
        specs.add(ProviderSpec.modelsDev("ai302", "AI302",
                "https://ai302.com/", "302ai",
                "ai302", "302ai"));
        specs.add(ProviderSpec.modelsDev("poe", "Poe",
                "https://poe.com/", "poe", "poe"));
        specs.add(ProviderSpec.modelsDev("longcat", "LongCat",
                "https://longcat.chat/", "longcat", "longcat"));
        specs.add(ProviderSpec.modelsDev("kilo", "Kilo",
                "https://kilo.ai/", "kilo", "kilo"));
        specs.add(ProviderSpec.modelsDev("commandcode", "CommandCode",
                "https://commandcode.ai/", "commandcode", "commandcode"));
        specs.add(ProviderSpec.modelsDev("opencode", "OpenCode Zen",
                "https://opencode.ai/", "opencode",
                "opencode", "zen"));
        specs.add(ProviderSpec.modelsDev("agnes", "Agnes AI",
                "https://agnes-ai.cn/", "agnes", "agnes"));

        specs.add(ProviderSpec.direct("openrouter", "OpenRouter",
                "https://openrouter.ai/models", Kind.OPENROUTER, "openrouter"));
        specs.add(ProviderSpec.direct("pollinations", "Pollinations",
                "https://gen.pollinations.ai/models?reliability=all", Kind.POLLINATIONS,
                "pollinations"));
        specs.add(ProviderSpec.direct("vercel", "Vercel AI Gateway",
                "https://vercel.com/docs/ai-gateway", Kind.VERCEL,
                "vercel", "ai-gateway", "vercel-ai-gateway"));
        specs.add(ProviderSpec.direct("models.dev", "Models.dev",
                "https://models.dev", Kind.MODELS_DEV,
                "models.dev", "modelsdev"));

        // 腾讯混元官方无公开机器可读价格目录；Vercel AI Gateway 收录了腾讯混元全系
        specs.add(ProviderSpec.vercel("tencent", "腾讯混元",
                "https://cloud.tencent.com/document/product/1729/97731", "tencent",
                "tencent", "hunyuan", "tencent-hunyuan"));

        // 仅提供网页定价、暂无公开机器可读目录的厂商：明确标为不支持，
        // 避免凭空抓取网页表格导致价格误读。
        specs.add(ProviderSpec.unsupported("qianfan", "百度千帆",
                "https://cloud.baidu.com/doc/qianfan/s/wmh4sv6ya",
                "qianfan", "baidu", "baidu-wenxin", "wenxin", "ernie"));
        specs.add(ProviderSpec.unsupported("spark", "讯飞星火",
                "https://www.xfyun.cn/doc/spark/",
                "spark", "flytek", "xunfei", "iflytek"));
        specs.add(ProviderSpec.unsupported("baichuan", "百川智能",
                "https://platform.baichuan-ai.com/price", "baichuan"));
        specs.add(ProviderSpec.unsupported("huawei", "华为盘古",
                "https://www.huaweicloud.com/pricing.html",
                "huawei", "huaweicloud", "pangu"));
        specs.add(ProviderSpec.unsupported("360ai", "360 智脑",
                "https://ai.360.cn/", "360ai", "360"));
        specs.add(ProviderSpec.unsupported("newapi", "NewAPI",
                null, "newapi"));
        specs.add(ProviderSpec.unsupported("dots", "Dots Studio",
                null, "dots"));
        specs.add(ProviderSpec.unsupported("bai", "b.ai",
                null, "bai", "b.ai"));
        specs.add(ProviderSpec.unsupported("custom", "自定义供应商",
                null, "custom"));
        specs.add(ProviderSpec.unsupported("ollama", "Ollama 本地模型",
                "https://ollama.com/", "ollama"));
        specs.add(ProviderSpec.unsupported("onnx", "ONNX 本地模型",
                null, "onnx"));
        specs.add(ProviderSpec.unsupported("local", "本地推理",
                null, "python", "pytorch", "local", "llama.cpp"));

        Map<String, ProviderSpec> index = new LinkedHashMap<>(128);
        for (ProviderSpec spec : specs) {
            for (String alias : spec.aliases) {
                index.putIfAbsent(normalize(alias), spec);
            }
        }
        return index;
    }

    /**
     * 官方指标来源类型。
     */
    public enum Kind {
        /**
         * OpenRouter 官方模型 API
         */
        OPENROUTER,
        /**
         * Pollinations 官方模型 API
         */
        POLLINATIONS,
        /**
         * Vercel AI Gateway 官方模型 API
         */
        VERCEL,
        /**
         * Models.dev 聚合目录
         */
        MODELS_DEV,
        /**
         * 暂无公开机器可读来源
         */
        NONE
    }

    /**
     * 单个服务商的指标来源规格。
     */
    public static final class ProviderSpec {

        /**
         * 主标识
         */
        private final String id;
        /**
         * 展示名称
         */
        private final String displayName;
        /**
         * 官方定价页（可为 null）
         */
        private final String officialPricingUrl;
        /**
         * 来源类型
         */
        private final Kind kind;
        /**
         * Models.dev 厂商键（仅 MODELS_DEV 有效）
         */
        private final String modelsDevKey;
        /**
         * Vercel AI Gateway 厂商标识（仅 VERCEL 有效）
         */
        private final String vercelOwner;
        /**
         * 别名（含主标识）
         */
        private final Set<String> aliases;
        /**
         * 是否为该来源的主标识（用于枚举受支持服务商）
         */
        private final boolean primary;

        private ProviderSpec(String id, String displayName, String officialPricingUrl, Kind kind,
                             String modelsDevKey, String vercelOwner,
                             Set<String> aliases, boolean primary) {
            this.id = id;
            this.displayName = displayName;
            this.officialPricingUrl = officialPricingUrl;
            this.kind = kind;
            this.modelsDevKey = modelsDevKey;
            this.vercelOwner = vercelOwner;
            this.aliases = aliases;
            this.primary = primary;
        }

        /**
         * 构建 Models.dev 来源。
         */
        private static ProviderSpec modelsDev(String id, String displayName, String url,
                                              String modelsDevKey, String... aliases) {
            return new ProviderSpec(id, displayName, url, Kind.MODELS_DEV,
                    modelsDevKey, null, aliasSet(aliases), true);
        }

        /**
         * 构建官方直连来源。
         */
        private static ProviderSpec direct(String id, String displayName, String url,
                                           Kind kind, String... aliases) {
            return new ProviderSpec(id, displayName, url, kind, null, null,
                    aliasSet(aliases), true);
        }

        /**
         * 构建 Vercel AI Gateway 来源。
         */
        private static ProviderSpec vercel(String id, String displayName, String url,
                                           String owner, String... aliases) {
            return new ProviderSpec(id, displayName, url, Kind.VERCEL, null, owner,
                    aliasSet(aliases), true);
        }

        /**
         * 构建「暂无机器可读来源」条目（保留官方定价页用于展示）。
         */
        private static ProviderSpec unsupported(String id, String displayName, String url,
                                                String... aliases) {
            return new ProviderSpec(id, displayName, url, Kind.NONE, null, null,
                    aliasSet(aliases), true);
        }

        /**
         * 别名去重并保持顺序。
         * @param aliases 参数
         * @return 返回值
        */
        private static Set<String> aliasSet(String... aliases) {
            Set<String> set = new LinkedHashSet<>(aliases.length + 1);
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    set.add(alias.trim());
                }
            }
            return Set.copyOf(set);
        }

        /**
         * 主标识。
         *
         * @return 标识
         */
        public String id() {
            return id;
        }

        /**
         * 展示名称。
         *
         * @return 名称
         */
        public String displayName() {
            return displayName;
        }

        /**
         * 官方定价页。
         *
         * @return URL，可为 null
         */
        public String officialPricingUrl() {
            return officialPricingUrl;
        }

        /**
         * 来源类型。
         *
         * @return 类型
         */
        public Kind kind() {
            return kind;
        }

        /**
         * Models.dev 厂商键。
         *
         * @return 键，可为 null
         */
        public String modelsDevKey() {
            return modelsDevKey;
        }

        /**
         * Vercel AI Gateway 厂商标识。
         *
         * @return owner，可为 null
         */
        public String vercelOwner() {
            return vercelOwner;
        }

        /**
         * 别名集合。
         *
         * @return 别名
         */
        public Set<String> aliases() {
            return aliases;
        }
    }
}