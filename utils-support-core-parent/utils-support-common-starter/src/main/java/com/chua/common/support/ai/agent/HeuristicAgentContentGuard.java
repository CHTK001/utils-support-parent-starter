package com.chua.common.support.ai.agent;

import com.chua.common.support.utils.PrivacyUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认启发式内容护栏：提示注入检测 + 隐私脱敏 + 凭据拦截 + 自定义敏感词审计。
 *
 * <p>检查口径（按 {@link AgentContentKind} 区分）：</p>
 * <ul>
 *   <li>{@code USER_INPUT}：注入命中默认 {@code BLOCK}（可用
 *       {@code blockInjectionOnUserInput(false)} 降级为摘除）；隐私 / 凭据脱敏；</li>
 *   <li>{@code TOOL_RESULT}：注入命中默认摘除匹配片段（工具结果本身可能仍有
 *       价值，不整体丢弃）；隐私 / 凭据脱敏；</li>
 *   <li>{@code MODEL_OUTPUT}：不做注入检测（输出侧注入语义不同）；隐私 /
 *       凭据脱敏防泄漏。</li>
 * </ul>
 *
 * <p>脱敏复用 {@link PrivacyUtils}，保持全仓统一掩码口径（如手机号
 * {@code 138****5678}）。凭据类保留前 4 位掩码；PEM 私钥整块替换。</p>
 *
 * <p>本实现是<b>启发式</b>：规则以高精确度为先（宁可少报不误报），可通过
 * {@link Builder} 按业务追加规则；不做语义级理解，高级对抗（同义改写、
 * 多语言变体）需上层接入模型判定类护栏后用 {@link AgentContentGuard#chain}
 * 组合。</p>
 *
 * @author CH
 * @since 4.0.0.52
 */
public class HeuristicAgentContentGuard implements AgentContentGuard {

    /**
     * 注入片段替换占位符
     */
    private static final String INJECTION_PLACEHOLDER = "[REDACTED]";

    /**
     * PEM 私钥整块替换占位符
     */
    private static final String PRIVATE_KEY_PLACEHOLDER = "[REDACTED-PRIVATE-KEY]";

    /**
     * 单次检查命中项上限（防御超长文本 / 病态输入）
     */
    private static final int MAX_FINDINGS = 64;

    /**
     * 内置注入检测规则：{规则名, 正则}（中英双语 + 角色伪装特征）。
     * 高精确度优先：像 act as / 扮演 这类正常写作常用语不入选，避免误伤合法指令。
     */
    private static final String[][] DEFAULT_INJECTION_RULES = {
            {"injection/ignore-instructions",
                    "(?i)ignore\\s+(all\\s+|any\\s+|the\\s+|your\\s+)*(previous|prior|above|earlier)\\s+(instructions?|rules?|prompts?|messages?|constraints)"},
            {"injection/disregard-instructions",
                    "(?i)disregard\\s+(all\\s+|the\\s+|your\\s+)*(previous|prior|above|earlier)\\s+(instructions?|rules?|guidelines?)"},
            {"injection/forget-instructions",
                    "(?i)forget\\s+(everything|all\\s+your|your)\\s+(instructions|rules|training|constraints)"},
            {"injection/reveal-system-prompt",
                    "(?i)(reveal|show|print|leak|repeat|output)\\s+(your\\s+)?(system\\s+)?(prompt|instructions?|rules?)"},
            {"injection/override-instructions",
                    "(?i)override\\s+(your\\s+)?(instructions|rules|programming|constraints)"},
            {"injection/role-overwrite",
                    "(?i)you\\s+are\\s+now\\s+(a|an|the|no\\s+longer)\\s"},
            {"injection/jailbreak",
                    "(?i)\\bjailbreak\\b|\\bdo\\s+anything\\s+now\\b|\\bDAN\\s+mode\\b"},
            {"injection/role-forgery-marker",
                    "(?i)\\[system\\]|<\\|im_start\\|>\\s*system|\"role\"\\s*:\\s*\"system\"|<<SYS>>|</?system>|(?m)^\\s*SYSTEM\\s*:"},
            {"injection/ignore-instructions-cn",
                    "忽略(之前|以前|以上|上述|前面|上面|所有|全部)的?(所有|全部)?(指令|规则|提示词?|设定|约束)"},
            {"injection/disregard-instructions-cn",
                    "无视(之前|以上|上述|前面|所有)的?(指令|规则|提示词?|设定)"},
            {"injection/reveal-system-prompt-cn",
                    "(泄露|泄漏|打印|输出|复述|告诉我)(你的)?(系统提示|系统指令|提示词|初始指令|初始提示)"},
            {"injection/role-overwrite-cn",
                    "你现在是|从现在开始你是|假装你是"},
            {"injection/jailbreak-cn",
                    "越狱模式?|破解限制"},
            {"injection/override-instructions-cn",
                    "覆盖(你的)?(指令|规则|设定)"}
    };

    /**
     * 内置隐私检测规则：{规则名, 正则}（掩码统一走 {@link PrivacyUtils}）
     */
    private static final String[][] DEFAULT_PII_RULES = {
            {"pii/phone", "(?<![0-9])1[3-9][0-9]{9}(?![0-9])"},
            {"pii/id-card", "(?<![0-9])[0-9]{17}[0-9Xx](?![0-9])"},
            {"pii/bank-card", "(?<![0-9])[0-9]{16,19}(?![0-9])"},
            {"pii/email", "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"}
    };

    /**
     * 内置凭据检测规则：{规则名, 正则}（PEM 私钥整块替换，其余保留前 4 位掩码）
     */
    private static final String[][] DEFAULT_SECRET_RULES = {
            {"secret/private-key",
                    "(?s)-----BEGIN\\s+[A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END\\s+[A-Z ]*PRIVATE KEY-----"},
            {"secret/openai-key", "(?i)\\bsk-[A-Za-z0-9_-]{16,}\\b"},
            {"secret/github-token", "\\bgh[pousr]_[A-Za-z0-9]{20,}\\b|\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"},
            {"secret/aws-key", "\\bAKIA[0-9A-Z]{16}\\b"},
            {"secret/slack-token", "\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"},
            {"secret/google-key", "\\bAIza[0-9A-Za-z_-]{35}\\b"},
            {"secret/jwt", "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"},
            {"secret/bearer", "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{16,}"}
    };

    /**
     * 注入检测规则（预编译）
     */
    private final List<Rule> injectionRules;

    /**
     * 隐私检测规则（预编译）
     */
    private final List<Rule> piiRules;

    /**
     * 凭据检测规则（预编译）
     */
    private final List<Rule> secretRules;

    /**
     * 自定义敏感词（仅审计，不改变内容）
     */
    private final List<String> sensitiveKeywords;

    /**
     * 用户输入侧注入命中是否阻断（false 降级为摘除）
     */
    private final boolean blockInjectionOnUserInput;

    /**
     * 默认护栏：启用全部内置规则，注入命中时用户输入侧阻断。
     */
    public HeuristicAgentContentGuard() {
        this(builder());
    }

    /**
     * 按构建器配置构造（构造期预编译全部规则，运行期不再编译）。
     *
     * @param builder 构建器
     */
    private HeuristicAgentContentGuard(Builder builder) {
        this.blockInjectionOnUserInput = builder.blockInjectionOnUserInput;
        this.sensitiveKeywords = List.copyOf(builder.sensitiveKeywords);

        List<Rule> injections = new ArrayList<>();
        if (builder.detectInjection) {
            for (String[] def : DEFAULT_INJECTION_RULES) {
                injections.add(new Rule(def[0], Pattern.compile(def[1]),
                        AgentContentFinding.Severity.HIGH, null));
            }
            for (String regex : builder.extraInjectionPatterns) {
                injections.add(new Rule("injection/custom", Pattern.compile(regex),
                        AgentContentFinding.Severity.HIGH, null));
            }
        }
        this.injectionRules = List.copyOf(injections);

        List<Rule> pii = new ArrayList<>();
        if (builder.maskPii) {
            for (String[] def : DEFAULT_PII_RULES) {
                pii.add(new Rule(def[0], Pattern.compile(def[1]),
                        AgentContentFinding.Severity.MEDIUM, piiMasker(def[0])));
            }
            for (String regex : builder.extraPiiPatterns) {
                pii.add(new Rule("pii/custom", Pattern.compile(regex),
                        AgentContentFinding.Severity.MEDIUM,
                        value -> PrivacyUtils.desValue(value, 2, 0, "*")));
            }
        }
        this.piiRules = List.copyOf(pii);

        List<Rule> secrets = new ArrayList<>();
        if (builder.detectSecrets) {
            for (String[] def : DEFAULT_SECRET_RULES) {
                boolean privateKey = "secret/private-key".equals(def[0]);
                secrets.add(new Rule(def[0], Pattern.compile(def[1]),
                        privateKey ? AgentContentFinding.Severity.HIGH : AgentContentFinding.Severity.MEDIUM,
                        privateKey ? value -> PRIVATE_KEY_PLACEHOLDER
                                : value -> PrivacyUtils.desValue(value, 4, 0, "*")));
            }
            for (String regex : builder.extraSecretPatterns) {
                secrets.add(new Rule("secret/custom", Pattern.compile(regex),
                        AgentContentFinding.Severity.MEDIUM,
                        value -> PrivacyUtils.desValue(value, 4, 0, "*")));
            }
        }
        this.secretRules = List.copyOf(secrets);
    }

    @Override
    public AgentContentVerdict inspect(AgentContentRequest request) {
        if (request == null || request.content() == null || request.content().isEmpty()) {
            return AgentContentVerdict.allow();
        }
        AgentContentKind kind = request.kind() == null ? AgentContentKind.USER_INPUT : request.kind();
        String content = request.content();
        List<AgentContentFinding> findings = new ArrayList<>();
        boolean blocked = false;
        boolean changed = false;

        // 1) 注入检测：用户输入与工具返回边界（模型输出不做注入检测）
        if (kind != AgentContentKind.MODEL_OUTPUT) {
            for (Rule rule : injectionRules) {
                if (findings.size() >= MAX_FINDINGS) {
                    break;
                }
                Matcher matcher = rule.pattern().matcher(content);
                boolean found = false;
                while (matcher.find() && findings.size() < MAX_FINDINGS) {
                    findings.add(AgentContentFinding.of(AgentContentFinding.Type.PROMPT_INJECTION,
                            rule.severity(), rule.name(), matcher.group()));
                    found = true;
                }
                if (found) {
                    if (kind == AgentContentKind.TOOL_RESULT || !blockInjectionOnUserInput) {
                        content = replaceAll(content, rule, INJECTION_PLACEHOLDER);
                        changed = true;
                    } else {
                        blocked = true;
                    }
                }
            }
        }

        // 2) 隐私脱敏：三个边界统一处理
        for (Rule rule : piiRules) {
            if (findings.size() >= MAX_FINDINGS) {
                break;
            }
            Matcher matcher = rule.pattern().matcher(content);
            boolean found = false;
            while (matcher.find() && findings.size() < MAX_FINDINGS) {
                findings.add(AgentContentFinding.of(AgentContentFinding.Type.PII,
                        rule.severity(), rule.name(), matcher.group()));
                found = true;
            }
            if (found) {
                content = maskAll(content, rule);
                changed = true;
            }
        }

        // 3) 凭据拦截：三个边界统一处理
        for (Rule rule : secretRules) {
            if (findings.size() >= MAX_FINDINGS) {
                break;
            }
            Matcher matcher = rule.pattern().matcher(content);
            boolean found = false;
            while (matcher.find() && findings.size() < MAX_FINDINGS) {
                findings.add(AgentContentFinding.of(AgentContentFinding.Type.SECRET,
                        rule.severity(), rule.name(), matcher.group()));
                found = true;
            }
            if (found) {
                content = maskAll(content, rule);
                changed = true;
            }
        }

        // 4) 自定义敏感词：仅审计，不改变内容
        if (!sensitiveKeywords.isEmpty()) {
            String lowered = content.toLowerCase(Locale.ROOT);
            for (String keyword : sensitiveKeywords) {
                if (keyword == null || keyword.isEmpty()) {
                    continue;
                }
                if (lowered.contains(keyword.toLowerCase(Locale.ROOT))) {
                    findings.add(AgentContentFinding.of(AgentContentFinding.Type.SENSITIVE_KEYWORD,
                            AgentContentFinding.Severity.LOW, "sensitive/keyword", keyword));
                }
            }
        }

        if (blocked) {
            return AgentContentVerdict.block(findings);
        }
        if (changed) {
            return AgentContentVerdict.redact(content, findings);
        }
        return AgentContentVerdict.allow(findings);
    }

    /**
     * 构建器入口。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 按规则名选择隐私掩码函数（统一委托 {@link PrivacyUtils}）。
     *
     * @param name 规则名
     * @return 掩码函数
     */
    private static UnaryOperator<String> piiMasker(String name) {
        if (name.endsWith("phone")) {
            return PrivacyUtils::hidePhone;
        }
        if (name.endsWith("email")) {
            return PrivacyUtils::hideEmail;
        }
        if (name.endsWith("id-card")) {
            return PrivacyUtils::hideCard;
        }
        if (name.endsWith("bank-card")) {
            return PrivacyUtils::hideBankCard;
        }
        return value -> PrivacyUtils.desValue(value, 2, 0, "*");
    }

    /**
     * 把规则命中的全部片段替换为固定占位符。
     *
     * @param content     原文本
     * @param rule        规则
     * @param replacement 占位符
     * @return 替换后的文本
     */
    private static String replaceAll(String content, Rule rule, String replacement) {
        Matcher matcher = rule.pattern().matcher(content);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    /**
     * 把规则命中的全部片段按掩码函数逐个替换。
     *
     * @param content 原文本
     * @param rule    规则
     * @return 掩码后的文本
     */
    private static String maskAll(String content, Rule rule) {
        UnaryOperator<String> masker = rule.masker() != null
                ? rule.masker()
                : value -> PrivacyUtils.desValue(value, 4, 0, "*");
        Matcher matcher = rule.pattern().matcher(content);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(builder, Matcher.quoteReplacement(masker.apply(matcher.group())));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    /**
     * 单条检测规则：预编译正则 + 严重程度 + 掩码函数（注入规则无掩码函数）。
     *
     * @param name     规则名
     * @param pattern  预编译正则
     * @param severity 严重程度
     * @param masker   掩码函数，可为 null
     */
    private record Rule(String name, Pattern pattern,
                        AgentContentFinding.Severity severity,
                        UnaryOperator<String> masker) {
    }

    /**
     * 启发式护栏构建器。
     */
    public static class Builder {

        private final List<String> extraInjectionPatterns = new ArrayList<>();
        private final List<String> extraPiiPatterns = new ArrayList<>();
        private final List<String> extraSecretPatterns = new ArrayList<>();
        private final Set<String> sensitiveKeywords = new LinkedHashSet<>();
        private boolean detectInjection = true;
        private boolean maskPii = true;
        private boolean detectSecrets = true;
        private boolean blockInjectionOnUserInput = true;

        /**
         * 是否启用注入检测（默认启用）。
         */
        public Builder detectInjection(boolean detectInjection) {
            this.detectInjection = detectInjection;
            return this;
        }

        /**
         * 是否启用隐私脱敏（默认启用）。
         */
        public Builder maskPii(boolean maskPii) {
            this.maskPii = maskPii;
            return this;
        }

        /**
         * 是否启用凭据拦截（默认启用）。
         */
        public Builder detectSecrets(boolean detectSecrets) {
            this.detectSecrets = detectSecrets;
            return this;
        }

        /**
         * 用户输入侧注入命中是否阻断（默认阻断；false 降级为摘除片段）。
         */
        public Builder blockInjectionOnUserInput(boolean blockInjectionOnUserInput) {
            this.blockInjectionOnUserInput = blockInjectionOnUserInput;
            return this;
        }

        /**
         * 追加自定义注入检测正则（高严重度）。
         */
        public Builder extraInjectionPattern(String regex) {
            if (regex != null && !regex.isEmpty()) {
                extraInjectionPatterns.add(regex);
            }
            return this;
        }

        /**
         * 追加自定义隐私检测正则（中严重度，保留前 2 位掩码）。
         */
        public Builder extraPiiPattern(String regex) {
            if (regex != null && !regex.isEmpty()) {
                extraPiiPatterns.add(regex);
            }
            return this;
        }

        /**
         * 追加自定义凭据检测正则（中严重度，保留前 4 位掩码）。
         */
        public Builder extraSecretPattern(String regex) {
            if (regex != null && !regex.isEmpty()) {
                extraSecretPatterns.add(regex);
            }
            return this;
        }

        /**
         * 追加自定义敏感词（仅审计，大小写不敏感）。
         */
        public Builder sensitiveKeyword(String... keywords) {
            if (keywords != null) {
                for (String keyword : keywords) {
                    if (keyword != null && !keyword.isEmpty()) {
                        sensitiveKeywords.add(keyword);
                    }
                }
            }
            return this;
        }

        /**
         * 构建护栏（非法正则在构建期抛出，快速失败）。
         *
         * @return 启发式护栏
         */
        public HeuristicAgentContentGuard build() {
            return new HeuristicAgentContentGuard(this);
        }
    }
}
