package com.chua.deeplearning.support.laya;

import com.chua.common.support.ai.decision.DecisionClient;
import com.chua.common.support.ai.decision.DecisionProvider;
import com.chua.common.support.ai.decision.DecisionSetting;
import com.chua.common.support.ai.rule.AiRule;
import com.chua.common.support.ai.rule.AiRuleQuery;
import com.chua.common.support.ai.rule.RuleContext;
import com.chua.common.support.ai.rule.RuleEffect;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Laya AI 决策规则。
 *
 * <p>{@link AiRule} 在 laya 模块的实现：条件由 laya-java 本地推理自动求值。
 * 规则本身仍是<b>纯数据</b>：底座客户端由执行器按需创建并复用（同 provider 一次），
 * 规则实例不持有执行期状态，可并发复用。</p>
 *
 * @param id        规则标识，同一规则集内唯一
 * @param name      规则显示名，默认与 {@code id} 相同
 * @param salience  优先级，数值越大越先求值，默认 0
 * @param effect    本规则给出的结论
 * @param queries   声明的决策问题，不可为空
 * @param actions   命中后按序执行的动作
 * @param target    下一条规则的 {@code id}，可为 null
 * @param variables 规则声明的变量，注入 {@link RuleContext} 的最高优先级层
 * @param provider  AI 底座名，默认 laya
 * @param state     AI 决策上下文，null 表示使用执行期 input
 * @param settings  AI 底座配置，键值由底座解释
 * @author CH
 * @since 4.0.0.43
 */
public record LayaAiRule(String id,
                         String name,
                         int salience,
                         RuleEffect effect,
                         List<AiRuleQuery> queries,
                         List<Consumer<RuleContext>> actions,
                         String target,
                         Map<String, Object> variables,
                         String provider,
                         Object state,
                         Map<String, Object> settings) implements AiRule {

    /**
     * 构造 Laya AI 决策规则。
     */
    public LayaAiRule {
        Objects.requireNonNull(id, "id 不能为 null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空白");
        }
        Objects.requireNonNull(name, "name 不能为 null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空白");
        }
        Objects.requireNonNull(queries, "queries 不能为 null");
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("queries 不能为空");
        }
        queries = List.copyOf(queries);
        actions = List.copyOf(Objects.requireNonNull(actions, "actions 不能为 null"));
        variables = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(variables, "variables 不能为 null")));
        effect = effect == null ? RuleEffect.NONE : effect;
        provider = provider == null || provider.isBlank() ? "laya" : provider;
        settings = settings == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(settings));
    }

    @Override
    public List<java.util.function.Predicate<RuleContext>> conditions() {
        return List.of();
    }

    @Override
    public boolean matched(RuleContext context) {
        Objects.requireNonNull(context, "context 不能为 null");
        Map<String, String> answers = new LinkedHashMap<>();
        Map<String, Object> flattened = new LinkedHashMap<>();
        for (AiRuleQuery item : queries) {
            String key = item.id();
            Object label = context.get(key);
            if (label != null) {
                answers.put(key, String.valueOf(label));
            }
            Object confidence = context.get(key + ".confidence");
            if (confidence != null) {
                flattened.put(key + ".confidence", confidence);
            }
        }
        return hit(answers, flattened);
    }

    @Override
    public boolean hit(Map<String, String> answers, Map<String, Object> flattened) {
        Objects.requireNonNull(answers, "answers 不能为 null");
        Objects.requireNonNull(flattened, "flattened 不能为 null");
        for (AiRuleQuery item : queries) {
            String answer = answers.get(item.id());
            if (answer == null) {
                return false;
            }
            if (item.expect() != null && !item.expect().equals(answer)) {
                continue;
            }
            if (item.threshold() != null) {
                Object confidence = flattened.get(item.id() + ".confidence");
                double value = confidence instanceof Number number ? number.doubleValue() : Double.NaN;
                if (Double.isNaN(value) || value < item.threshold()) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void apply(RuleContext context) {
        Objects.requireNonNull(context, "context 不能为 null");
        for (Consumer<RuleContext> action : actions) {
            action.accept(context);
        }
    }

    @Override
    public boolean hasActions() {
        return !actions.isEmpty();
    }

    @Override
    public boolean hasVariables() {
        return !variables.isEmpty();
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public DecisionProvider client() {
        DecisionSetting.DecisionSettingBuilder builder = DecisionSetting.builder().provider(provider);
        if (settings.get("modelPath") != null) {
            builder.modelPath(String.valueOf(settings.get("modelPath")));
        }
        if (settings.get("threads") instanceof Number number) {
            builder.threads(number.intValue());
        }
        if (settings.get("calibrationPath") != null) {
            builder.calibrationPath(String.valueOf(settings.get("calibrationPath")));
        }
        return DecisionClient.create(builder.build());
    }
}
