package com.chua.deeplearning.support.laya;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.ai.decision.DecisionBatch;
import com.chua.common.support.ai.decision.DecisionClient;
import com.chua.common.support.ai.decision.DecisionProvider;
import com.chua.common.support.ai.decision.DecisionQuery;
import com.chua.common.support.ai.decision.DecisionRequest;
import com.chua.common.support.ai.decision.DecisionSetting;
import com.chua.common.support.ai.rule.AiRule;
import com.chua.common.support.ai.rule.AiRuleQuery;
import com.chua.common.support.ai.rule.RuleContext;
import com.chua.common.support.ai.rule.RuleEffect;
import com.chua.common.support.ai.rule.RuleException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Laya AI 决策规则。
 *
 * <p>{@link AiRule} 在 laya 模块的实现：条件由 laya-java 本地推理求值。
 * 规则本身仍是<b>纯数据</b>：底座客户端在每次 {@link #matched(RuleContext)}
 * 里按需创建、用完关闭，规则实例不持有执行期状态，可并发复用。</p>
 *
 * @param id              规则标识，同一规则集内唯一
 * @param name            规则显示名，默认与 {@code id} 相同
 * @param description     规则描述，可为 null
 * @param message         命中后对外说明的原因，可为 null
 * @param salience        优先级，数值越大越先求值，默认 0
 * @param effect          本规则给出的结论
 * @param queries         声明的决策问题，不可为空
 * @param conditions      本地条件，与 AI 命中是 AND 关系
 * @param actions         命中后按序执行的动作
 * @param target          下一条规则的 {@code id}，可为 null
 * @param variables       规则声明的变量，注入 {@link RuleContext} 的最高优先级层
 * @param provider        AI 底座名，默认 laya
 * @param state           AI 决策上下文，null 表示使用执行期 input
 * @param modelPath       本地模型资产目录，可为 null
 * @param threads         推理线程数，可为 null
 * @param calibrationPath 校准文件路径，可为 null
 * @author CH
 * @since 4.0.0.43
 */
public record LayaAiRule(String id,
                         String name,
                         String description,
                         String message,
                         int salience,
                         RuleEffect effect,
                         List<AiRuleQuery> queries,
                         List<Predicate<RuleContext>> conditions,
                         List<Consumer<RuleContext>> actions,
                         String target,
                         Map<String, Object> variables,
                         String provider,
                         Object state,
                         String modelPath,
                         Integer threads,
                         String calibrationPath) implements AiRule {

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
        conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions 不能为 null"));
        actions = List.copyOf(Objects.requireNonNull(actions, "actions 不能为 null"));
        variables = Collections.unmodifiableMap(
                Wraps.linkedMap(Objects.requireNonNull(variables, "variables 不能为 null")));
        effect = effect == null ? RuleEffect.NONE : effect;
        provider = provider == null || provider.isBlank() ? "laya" : provider;
        if (threads != null && threads <= 0) {
            throw new IllegalArgumentException("threads 必须为正数，实际 " + threads);
        }
    }

    @Override
    public String modelPath() {
        return modelPath;
    }

    @Override
    public Integer threads() {
        return threads;
    }

    @Override
    public String calibrationPath() {
        return calibrationPath;
    }

    @Override
    public List<Predicate<RuleContext>> conditions() {
        return conditions;
    }

    @Override
    public boolean matched(RuleContext context) {
        Objects.requireNonNull(context, "context 不能为 null");
        // 本地条件与 AI 命中是 AND：本地是前置过滤，AI 是概率判定。
        // 决策由规则自己处理：按需向自己的底座求值，结果不写回上下文，
        // 只参与本次命中判断——写回会污染后续规则的变量层。
        for (Predicate<RuleContext> condition : conditions) {
            if (!condition.test(context)) {
                return false;
            }
        }
        List<DecisionQuery> batch = Wraps.list();
        for (AiRuleQuery item : queries) {
            if (item == null || item.query() == null) {
                throw new RuleException("AI 规则 " + id + " 存在空决策问题");
            }
            batch.add(item.query());
        }
        Object state = state() == null ? context.input() : state();
        DecisionProvider provider = client();
        if (provider == null) {
            throw new RuleException("AI 规则 " + id + " 未提供决策底座");
        }
        try {
            DecisionRequest request = DecisionRequest.of(state, batch);
            DecisionBatch answers = provider.decide(request);
            answers.answers(request);
            return hit(answers.labels(), answers.flatten());
        } catch (RuleException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RuleException("AI 规则 " + id + " 决策求值失败: " + e.getMessage(), e);
        } finally {
            if (provider instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    throw new RuleException("关闭 AI 决策底座失败(规则 " + id + "): " + e.getMessage(), e);
                }
            }
        }
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
        if (modelPath != null) {
            builder.modelPath(modelPath);
        }
        if (threads != null) {
            builder.threads(threads);
        }
        if (calibrationPath != null) {
            builder.calibrationPath(calibrationPath);
        }
        return DecisionClient.create(builder.build());
    }
}
