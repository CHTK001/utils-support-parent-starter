package com.chua.playwright.support.spi;

import java.util.List;
import java.util.Map;

/**
* 浏览器自动化引擎 SPI。使用 playwright-Java（{@code com.microsoft.playwright}）作为底层引擎。
* @author CH
* @since 4.0.0
 */
public interface Engine {

    /**
     * launch。
     *
     * @param headless headless（布尔开关）
     * @param executablePath executable路径，不允许为 null
     * @param args 参数，不允许为 null
     * @return 结果数值
     */
    long launch(boolean headless, String executablePath, List<String> args);

    /**
     * 新建上下文。
     *
     * @param browserHandle browser处理，不允许为 null
     * @param options 选项，不允许为 null
     * @return 结果数值
     */
    long newContext(long browserHandle, Map<String, Object> options);

    /**
     * 新建页。
     *
     * @param targetHandle 目标处理，不允许为 null
     * @return 结果数值
     */
    long newPage(long targetHandle);

    /**
     * goto页。
     *
     * @param pageHandle 页处理，不允许为 null
     * @param url URL，不允许为 null
     * @param options 选项，不允许为 null
     * @return 响应数据 对象
     */
    ResponseData gotoPage(long pageHandle, String url, Map<String, Object> options);

    /**
     * click。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @param options 选项，不允许为 null
     */
    void click(long handle, String selector, Map<String, Object> options);

    /**
     * dblclick。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     */
    void dblclick(long handle, String selector);

    /**
     * fill。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @param value 值，不允许为 null
     */
    void fill(long handle, String selector, String value);

    /**
     * 类型。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @param text 文本，不允许为 null
     */
    void type(long handle, String selector, String text);

    /**
     * press。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @param key 键，不允许为 null
     */
    void press(long handle, String selector, String key);

    /**
     * 校验。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @param checked checked（布尔开关）
     */
    void check(long handle, String selector, boolean checked);

    /**
     * hover。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     */
    void hover(long handle, String selector);

    /**
     * 文本内容。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @return 结果字符串
     */
    String textContent(long handle, String selector);

    /**
     * inner文本。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @return 结果字符串
     */
    String innerText(long handle, String selector);

    /**
     * innerHTML。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @return 结果字符串
     */
    String innerHTML(long handle, String selector);

    /**
     * 获取Attribute。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @param name 名称，不允许为 null
     * @return 结果字符串
     */
    String getAttribute(long handle, String selector, String name);

    /**
     * input值。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @return 结果字符串
     */
    String inputValue(long handle, String selector);

    /**
     * screenshot。
     *
     * @param handle 处理，不允许为 null
     * @param options 选项，不允许为 null
     * @return 结果值
     */
    byte[] screenshot(long handle, Map<String, Object> options);

    /**
     * evaluate。
     *
     * @param handle 处理，不允许为 null
     * @param expression 方法入参 expression
     * @param arg 方法入参 arg
     * @return 对象 对象
     */
    Object evaluate(long handle, String expression, Object arg);

    /**
     * 查询Selector。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @return 结果数值
     */
    long querySelector(long handle, String selector);

    /**
     * 查询Selector全部。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @return 结果列表，无数据时为空列表
     */
    List<Long> querySelectorAll(long handle, String selector);

    /**
     * waitForSelector。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @param timeoutMs 超时时间毫秒数，不允许为 null
     */
    void waitForSelector(long handle, String selector, Long timeoutMs);

    /**
     * 设置Viewport大小。
     *
     * @param handle 处理，不允许为 null
     * @param width 宽度，不允许为 null
     * @param height 高度，不允许为 null
     */
    void setViewportSize(long handle, int width, int height);

    /**
     * 标题。
     *
     * @param handle 处理，不允许为 null
     * @return 结果字符串
     */
    String title(long handle);

    /**
     * URL。
     *
     * @param handle 处理，不允许为 null
     * @return 结果字符串
     */
    String url(long handle);

    /**
     * reload。
     *
     * @param handle 处理，不允许为 null
     */
    void reload(long handle);

    /**
     * goBack。
     *
     * @param handle 处理，不允许为 null
     * @return 响应数据 对象
     */
    ResponseData goBack(long handle);

    /**
     * goForward。
     *
     * @param handle 处理，不允许为 null
     * @return 响应数据 对象
     */
    ResponseData goForward(long handle);

    /**
     * selectOption。
     *
     * @param handle 处理，不允许为 null
     * @param selector 方法入参 selector
     * @param values 方法入参 values
     * @return 结果列表，无数据时为空列表
     */
    List<String> selectOption(long handle, String selector, String[] values);

    /**
     * 关闭。
     *
     * @param handle 处理，不允许为 null
     */
    void close(long handle);

    /**
     * 新建API请求。
     *
     * @return 结果数值
     */
    long newAPIRequest();

    /**
     * api请求。
     *
     * @param action 方法入参 action
     * @param url URL，不允许为 null
     * @param body 请求体，不允许为 null
     * @return Api响应数据 对象
     */
    ApiResponseData apiRequest(String action, String url, Object body);

    /**
     * 批次。
     *
     * @param commands 方法入参 commands
     * @param stopOnError 停止响应Error（布尔开关）
     * @return 结果列表，无数据时为空列表
     */
    List<Object> batch(List<Map<String, Object>> commands, boolean stopOnError);

    /**
     * 版本。
     *
     * @return 结果字符串
     */
    String version();

    /**
    * 将当前页面渲染为 PDF。
    * 使用 Chrome devtools 协议 的 Page.print转为pdf 接口。
    *
    * @param pageHandle 页面句柄
    * @param options    PDF 配置选项（margin, 格式化, page范围 等），可为 空
    * @return PDF 文件的 基础64 编码字符串
    */
    String printPageToPdf(long pageHandle, Map<String, Object> options);

    /**
    * 将指定的 HTML 内容渲染为 PNG 图片。
    * 通过 数据: URI 加载 HTML 到页面，然后截图生成 PNG。
    *
    * @param pageHandle 页面句柄
    * @param html       HTML 内容字符串
    * @return PNG 图片的 基础64 编码字符串
    * @author CH
    * @since 4.0.0
    */
    String convertHtmlToPng(long pageHandle, String html);
    /**
    * 响应数据类。
    *
    * @author CH
    * @since 4.0.0
    */

    class ResponseData {
        public final int status; // 状态
        public final String url; // url

        /**
        * 响应数据。
        * @param status 状态
        * @param url url
        * @author CH
        * @since 4.0.0
        */
        public ResponseData(int status, String url) {
            this.status = status;
            this.url = url;
        }
    }

    class ApiResponseData {
        public final int status; // 状态
        public final String body; // 主体

        /**
        * api响应数据。
        * @param status 状态
        * @param body 主体
        */
        public ApiResponseData(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
