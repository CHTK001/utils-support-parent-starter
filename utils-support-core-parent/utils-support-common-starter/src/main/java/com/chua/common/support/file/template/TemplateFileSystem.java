package com.chua.common.support.file.template;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
* 文件模板系统接口，定义模板解析与合并的统一抽象。
*
* <p>模板文件包含变量占位符（如 {@code ${name}}），通过传入数据  进行替换。
* 支持 XML、HTML、文本等各类模板格式。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* FileTemplateSystem template = FileTemplateSystem.create("xml");
*
* Map<String, Object> data = new HashMap<>();
* data.put("name", "张三");
* data.put("age", 25);
*
* try (InputStream in = new FileInputStream("template.xml");
*      OutputStream out = new FileOutputStream("output.xml")) {
*     template.resolve(in, out, data);
* }
* }</pre>
*
* @author CH
* @since 1.0.0
 */
public interface TemplateFileSystem {

    /**
    * 填充模板变量并输出结果。
    *
    * @param inputStream       输入流，包含待处理的模板内容。
    * @param outputStream      输出流，用于写入解析后的结果。
    * @param templateData      包含模板变量键值对的数据映射表。
    */
    void resolve(InputStream inputStream, OutputStream outputStream, Map<String, Object> templateData);
}
