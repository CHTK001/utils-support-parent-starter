package com.chua.common.support.task.flow;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 流程节点类型注解。
 *
 * <p>标注在 {@link FlowNodeExecutor} 实现类上，声明节点类型标识与描述信息。
 * 节点类型注册表 {@link FlowNodeRegistry} 通过 SPI 机制发现所有实现类，
 * 运行时按类型标识匹配并实例化对应的节点执行器。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * @Spi("spider")
 * @FlowNode(value = "spider", describe = "爬虫抓取")
 * public class SpiderFlowNode implements FlowNodeExecutor {
 *     @Override
 *     public void execute(FlowInstance instance) {
 *         // 节点执行逻辑
 *     }
 * }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FlowNode {

    /**
     * 节点类型标识。
     *
     * <p>与前端 ReFlow 画布节点 {@code type} 字段保持一致，
     * 同时是 JSON 图定义导入时解析节点执行器的依据。</p>
     *
     * @return 节点类型标识
     */
    String value();

    /**
     * 节点类型描述。
     *
     * <p>供前端属性面板与后端节点类型清单展示使用，
     * 说明该节点类型的功能用途。</p>
     *
     * @return 节点类型描述
     */
    String describe() default "";
}
