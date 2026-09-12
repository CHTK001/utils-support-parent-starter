package com.chua.common.support.objects.creator;

import java.util.Map;

/**
* 脚本函数式接口。
*
* <p>{@link Quick#execute(String)} 将脚本片段包装为 {@link QuickScript} 实现类后编译执行，
* 片段内可通过 {@code quick}（当前 Quick 实例）与 {@code variables}（绑定变量快照）访问上下文。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* Quick quick = Quick.create().variable("x", 10).variable("y", 20);
* Object result = quick.execute("return variables.get(\"x\") + variables.get(\"y\");");
* }</pre>(\"x\") + variables.get(\"y\");");
* }</pre>
*
* @author CH
* @since 4.0.0.42
* @see Quick#execute(String)
 */
@FunctionalInterface
public interface QuickScript {

    /**
    * 执行脚本。
    *
    * @param quick     当前 Quick 实例，可访问已绑定的常量/变量/Bean
    * @param variables 绑定变量快照（常量 + 变量）
    * @return 执行结果，可为 空
     */
    Object run(Quick quick, Map<String, Object> variables);
}