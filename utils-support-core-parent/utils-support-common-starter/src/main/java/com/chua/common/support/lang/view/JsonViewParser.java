package com.chua.common.support.lang.view;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.spi.annotations.Spi;

/**
 * JSON 视图解析器，将数据渲染为格式化 JSON。
 * <p>支持任意 POJO、Map、List，输出带缩进的 JSON 文本。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("json")
public class JsonViewParser implements ViewParser {

    /**
     * 空数据占位文本
     */
    private static final String EMPTY_PLACEHOLDER = "(null)";

    /**
     * 判断是否支持渲染指定数据。
     *
     * @param data 待渲染的数据
     * @return 始终支持（作为兜底渲染器）
     */
    @Override
    public boolean support(Object data) {
        return true;
    }

    /**
     * 将数据渲染为格式化 JSON。
     *
     * @param data 待渲染的数据
     * @return 格式化 JSON；序列化失败时回退为 {@code toString()}，空数据返回 {@value #EMPTY_PLACEHOLDER}
     */
    @Override
    public String render(Object data) {
        if (data == null) {
            return EMPTY_PLACEHOLDER;
        }
        try {
            return Json.toPrettyJson(data);
        } catch (Exception e) {
            return data.toString();
        }
    }

    /**
     * 获取解析器顺序。
     *
     * @return 最大顺序值（最后兜底）
     */
    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}