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

    @Override
    /** Support */
    public boolean support(Object data) {
        return true;
    }

    @Override
    /** Render */
    public String render(Object data) {
        try {
            return Json.toPrettyJson(data);
        } catch (Exception e) {
            return data.toString();
        }
    }

    @Override
    /** 获取Order */
    public int getOrder() {
        return Integer.MAX_VALUE;
    }
}
