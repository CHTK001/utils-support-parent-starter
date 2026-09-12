package com.chua.common.support.lang.template;

import java.util.List;
import java.util.Map;

/**
* 模板匹配提取的公共契约（与具体格式无关）。
*
* <p>该接口定义“用一份模板从一份输入文本中抽取变量”的能力，不绑定 JSON / XML 等具体格式，
* 具体实现（如 {@code JsonTemplateExtractor}）负责解析各自格式并定位占位符。</p>
*
* <p>模板中以 {@code {变量名}} 形式声明待抽取槽位，支持两种形态：</p>
* <ul>
*     <li>整值：模板值整体为占位符 —— 提取整个值（保留原始类型）；</li>
*     <li>内嵌：模板值为“前缀{变量名}后缀” —— 截取变量部分。</li>
* </ul>
*
* @author CH
* @since 4.0.0.42
* @see TemplateExtractResult
* @see TemplateVar
 */
public interface TemplateExtractor {

    /**
    * 便捷提取：基于模板与输入，返回变量名到值的映射。
    *
    * @param template 模板文本，其中值可包含 {@code {变量名}} 占位符
    * @param input    输入文本，字段顺序/格式可与模板不同
    * @return 已提取的变量名到值的映射，值保留原始类型
     */
    Map<String, Object> extract(String template, String input);

    /**
    * 提取为结果对象，区分“已成功提取的变量”与“模板声明但输入中缺失的变量”。
    *
    * @param template 模板文本
    * @param input    输入文本
    * @return 提取结果，可查询 {@link TemplateExtractResult#isSuccess()} 与 {@link TemplateExtractResult#missing()}
     */
    TemplateExtractResult extractResult(String template, String input);

    /**
    * 提取为有序的变量列表，每个变量携带变量名、值及在输入中的路径。
    *
    * <p>顺序与模板中占位符出现顺序一致，适合需要顺序或溯源的消费场景；
    * 缺失变量不会出现在列表中。</p>
    *
    * @param template 模板文本
    * @param input    输入文本
    * @return 已成功提取的 TemplateVar 有序列表
     */
    List<TemplateVar> extractVars(String template, String input);

    /**
    * 判断输入是否完整匹配模板（即所有占位符变量均能在输入中定位）。
    *
    * @param template 模板文本
    * @param input    输入文本
    * @return 无缺失变量返回 true，存在缺失返回 false
     */
    boolean matches(String template, String input);
}
