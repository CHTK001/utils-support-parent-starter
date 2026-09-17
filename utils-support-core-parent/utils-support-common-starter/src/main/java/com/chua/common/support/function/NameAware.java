package com.chua.common.support.function;


/**
* 名称感知接口
*
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface NameAware {

    /**
    * 获取名称数组
    *
    * @return 名称数组
    */
    String[] named();
}
