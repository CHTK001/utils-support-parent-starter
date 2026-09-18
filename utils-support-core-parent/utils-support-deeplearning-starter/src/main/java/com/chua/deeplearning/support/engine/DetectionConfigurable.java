package com.chua.deeplearning.support.engine;

import java.util.Map;

/**
* 可配置 Translator 注入点。
* <p>
* 允许调用方在 Translator 首次实例化前注入运行参数（如 阈值、iou阈值、
* candidates 等），各 Translator 自行声明支持的键与默认值；未注入时使用
* 各自的准确默认值。Translator 完成初始化后注入将被忽略并告警。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public interface DetectionConfigurable {

    /**
    * 注入运行参数（仅首次实例化前生效）。
    *
    * @param options 参数键值对（阈值 / iou阈值 / 输入大小 / candidates 等）
    */
    void configure(Map<String, Object> options);
}
