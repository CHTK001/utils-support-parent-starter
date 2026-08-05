package com.chua.common.support.lang.view;


/**
 * 视图解析器 SPI，将结构化数据渲染为终端可读的文本视图。
 * <p>用于 SSH 等 CLI 环境下的数据展示，支持表格、键值对、列表等布局。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface ViewParser {

    /**
     * 判断是否支持解析该数据类型。
     *
     * @param data 待渲染数据
     * @return 支持返回 true
     */
    boolean support(Object data);

    /**
     * 将数据渲染为终端文本视图。
     *
     * @param data 待渲染数据
     * @return 文本视图字符串，不含末尾换行
     */
    String render(Object data);

    /**
     * 获取排序值，越小优先级越高。
     *
     * @return 排序值
     */
    default int getOrder() {
        return 0;
    }
}
