package com.chua.common.support.lang.loader;


/**
* 初始化加载器接口
* <p>
* 定义组件、资源或模块的初始化行为。实现此接口的类通常用于在系统启动或特定生命周期阶段执行初始化逻辑，
* 并返回初始化后的目标对象或配置信息。
* </p>
*
* @param <T> 初始化完成后返回的结果类型
* @author CH
* @since 4.0.0.42
 */
public interface InitLoader<T> {
    /**
    * 执行初始化逻辑
    * <br>
    * 加载并初始化相关资源或组件，返回初始化后的实例。
    *
    * @return 初始化后的结果对象
    */
    T init();
}
