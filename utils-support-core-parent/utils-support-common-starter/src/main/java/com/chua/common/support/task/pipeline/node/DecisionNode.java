package com.chua.common.support.task.pipeline.node;

import com.chua.common.support.task.pipeline.core.PipelineContext;
import com.chua.common.support.task.pipeline.core.PipelineNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.NullUnmarked;

/**
 * 判断节点。
 *
 * <p>支持分支逻辑的节点，根据条件判断结果选择不同的执行路径。
 * 通过 {@link #when(boolean, String)} 方法注册 true/false 分支指向的下一节点。</p>
 *
 * <p>用法示例：</p>
 * <pre>{@code
 * .decision("check", ctx -> ctx.getCurrentData() != null)
 *     .when(true, "process")
 *     .when(false, "skip")
 *     .then()
 * }</pre>
 *
 * @author CH
 */
@NullUnmarked
public class DecisionNode implements PipelineNode {

    /**
     * 节点唯一标识
     */
    private final String id;

    /**
     * 条件判断逻辑
     */
    private final Predicate<PipelineContext<?>> condition;

    /**
     * 分支映射：true -&gt; 节点ID，false -&gt; 节点ID
     */
    private final Map<Boolean, String> branches;

    /**
     * 构造判断节点。
     *
     * @param id        节点唯一标识
     * @param condition 条件判断逻辑
     */
    public DecisionNode(String id, Predicate<PipelineContext<?>> condition) {
        this.id = id;
        this.condition = condition;
        this.branches = new LinkedHashMap<>();
    }

    /**
     * 获取节点 ID。
     *
     * @return 节点 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取分支映射。
     *
     * @return true/false -&gt; 下一节点 ID 的映射
     */
    public Map<Boolean, String> getBranches() {
        return branches;
    }

    /**
     * 注册分支。
     *
     * @param result     条件结果（true 或 false）
     * @param nextNodeId 该结果对应的下一节点 ID
     * @return this
     */
    public DecisionNode when(boolean result, String nextNodeId) {
        this.branches.put(result, nextNodeId);
        return this;
    }

    @Override
    public void execute(PipelineContext<?> context) {
        context.setCurrentNodeId(id);
        boolean result = condition.test(context);
        String nextId = branches.get(result);
        if (nextId != null) {
            context.setNextNodeId(nextId);
        }
    }
}
