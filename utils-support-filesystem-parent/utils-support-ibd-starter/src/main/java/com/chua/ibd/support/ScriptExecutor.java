package com.chua.ibd.support;

import java.nio.file.Path;
import java.util.Map;

/**
* 脚本执行器接口
*
* <p>定义脚本执行的能力，支持多种脚本语言（Python/Groovy/JS 等）。
*
* @author CH
* @since 4.0.0.42
 */
public interface ScriptExecutor {

    /**
    * 执行脚本
    *
    * @param scriptPath 脚本文件路径
    * @param context    上下文参数
    * @return 脚本执行结果
    */
    String execute(Path scriptPath, Map<String, Object> context);

    /**
    * 获取支持的脚本扩展名
    *
    * @return 扩展名（如 ".py"、".Groovy"）
    */
    String getExtension();
}
