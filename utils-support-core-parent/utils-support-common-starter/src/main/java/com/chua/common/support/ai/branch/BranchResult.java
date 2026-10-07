package com.chua.common.support.ai.branch;

import com.chua.common.support.collection.wrap.Wraps;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次分支求值的结果。
 *
 * <p>独占语义下至多一条分支命中：{@link #hit()} 为命中的分支标识，
 * 未命中任何分支时为空。{@link #message()} 为命中分支的拦截原因，
 * 未声明或未命中时为空。{@link #action()} 为命中分支执行
 * {@code ctx.action(...)} 写下的行动结论——本次执行最终「做什么」
 * （常用取值见 {@link com.chua.common.support.ai.rule.Action}），
 * 与 message 配对：action 答「做什么」，message 答「为什么」。</p>
 *
 * @param hit     命中的分支标识，可为 null（表示未命中）
 * @param message 命中分支的拦截原因，可为 null
 * @param action  行动结论（{@code ctx.action(...)} 写入），可为 null
 * @param vars    冻结后的全部变量
 * @author CH
 * @since 4.0.0.43
 */
public record BranchResult(String hit,
                           String message,
                           Object action,
                           Map<String, Object> vars) {

    /**
     * 构造执行结果。
     */
    public BranchResult {
        vars = Collections.unmodifiableMap(
                Wraps.linkedMap(Objects.requireNonNull(vars, "vars 不能为 null")));
    }

    /**
     * 行动结论，未写入时为空。
     *
     * @return 行动结论
     */
    public Optional<Object> actionValue() {
        return Optional.ofNullable(action);
    }

    /**
     * 是否命中任一分支。
     *
     * @return 命中返回 true
     */
    public boolean anyHit() {
        return hit != null;
    }

    /**
     * 命中的分支标识。
     *
     * @return 分支标识，未命中时为空
     */
    public Optional<String> hitId() {
        return Optional.ofNullable(hit);
    }

    /**
     * 命中分支的拦截原因。
     *
     * @return 拦截原因，未声明或未命中时为空
     */
    public Optional<String> reason() {
        return Optional.ofNullable(message);
    }

    /**
     * 读变量。
     *
     * @param name 变量名
     * @return 变量值，不存在时为空
     */
    public Optional<Object> value(String name) {
        return Optional.ofNullable(vars.get(name));
    }
}
