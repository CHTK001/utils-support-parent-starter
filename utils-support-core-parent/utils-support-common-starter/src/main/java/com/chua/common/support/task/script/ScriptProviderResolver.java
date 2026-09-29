package com.chua.common.support.task.script;

import java.util.Set;

/**
 * 脚本引擎发现接口。
 *
 * <p>把「去哪里找 {@link ScriptProvider} 实现」从引擎本身剥离出来：默认实现走仓库自带的
 * {@code ServiceProvider} SPI，Spring 宿主下由 {@code utils-support-spring-starter}
 * 换成基于 {@code SpringFactoriesLoader} 的实现，从而与 Spring 自带的 SPI 机制统一。
 * 换哪种实现都不影响引擎代码，也不要求引擎模块依赖 Spring。</p>
 *
 * @since 4.0.0.43
 * @author CH
 */
public interface ScriptProviderResolver {

    /**
     * 按引擎名取脚本引擎实例。
     *
     * @param engine 引擎名，如 groovy / java
     * @return 引擎实例；未注册返回 null
     */
    ScriptProvider resolve(String engine);

    /**
     * 枚举全部已注册引擎名。
     *
     * <p>供上层（如调度台的语言下拉）驱动可选项：装了新引擎自动出现，不用改前端。</p>
     *
     * @return 引擎名集合，无实现时返回空集合
     */
    Set<String> engines();
}
