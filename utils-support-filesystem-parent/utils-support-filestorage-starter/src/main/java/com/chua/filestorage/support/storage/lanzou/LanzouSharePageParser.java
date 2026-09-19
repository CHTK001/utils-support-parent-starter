package com.chua.filestorage.support.storage.lanzou;

import com.chua.common.support.lang.json.Json;
import com.chua.common.support.lang.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 蓝奏云分享页解析器（获取下载直链）。
 *
 * <p>通过登录态接口 {@code task=22} 拿到分享短链 {@code is_newd/f_id} 后，访问分享页。
 * 分享页存在两种形态，本解析器均已覆盖：</p>
 *
 * <h3>形态一：有访问密码（down_p 函数）</h3>
 * <p>主页面内嵌 {@code function down_p()}，其中 {@code skdklds} 即 AJAX 所需的 sign。
 * 直接 POST {@code /ajaxm.php}（{@code action=downprocess&sign=...&p=密码}）获取直链。</p>
 *
 * <h3>形态二：无访问密码（iframe 二级页）</h3>
 * <p>主页面不含下载逻辑，只有一个二级 iframe（{@code <iframe src="/fn?...">}）。
 * 真正的 AJAX 参数位于 iframe 页面内，且变量名每次随机生成，故解析策略为：
 * 提取 {@code data:{...}} 整块 → 逐项判断值是字面量还是变量引用 →
 * 变量引用则回页面按 {@code var 名='值'} 求值 → 组装为表单原样提交。</p>
 *
 * <p>接口返回 {@code {"zt":1,"dom":"https://...","url":"yyy","inf":"文件名"}}，
 * 直链为 {@code dom + "/file/" + url}。{@code zt != 1} 时 {@code inf} 为错误信息。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class LanzouSharePageParser {

    /**
     * 二级 iframe 提取，无密码分享页使用。
     */
    private static final Pattern IFRAME_PATTERN =
            Pattern.compile("<iframe[^>]*\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /**
     * AJAX 提交地址提取，蓝奏云固定为 /ajaxm.PHP。
     */
    private static final Pattern AJAX_URL_PATTERN =
            Pattern.compile("url\\s*:\\s*['\"](/ajaxm\\.php[^'\"]*)['\"]");

    /**
     * AJAX 数据 块提取。
     */
    private static final Pattern DATA_BLOCK_PATTERN =
            Pattern.compile("data\\s*:\\s*\\{([^{}]*?action[^{}]*?)}", Pattern.DOTALL);

    /**
     * 有密码形态下的 标志 提取（skdklds='...'）。
     */
    private static final Pattern SIGN_PATTERN =
            Pattern.compile("skdklds\\s*=\\s*['\"]([^'\"]+)['\"]");

    /**
     * 数据 块内 键:值 提取（值可带引号或不带）。
     */
    private static final Pattern KV_PATTERN =
            Pattern.compile("(\\w+)\\s*:\\s*['\"]?([^,'\"}]+?)['\"]?\\s*(?:,|})");

    /**
     * 页面内变量定义提取，用于还原随机变量名。
     */
    private static final Pattern VAR_PATTERN =
            Pattern.compile("var\\s+(\\w+)\\s*=\\s*['\"]([^'\"]*)['\"]");

    /** 创建 lanzou共享pageparser 实例 */
    private LanzouSharePageParser() {
    }

    /**
    * 解析分享页，返回下载直链信息。
    *
    * @param http         蓝奏 HTTP 客户端（用于访问 iframe 二级页，自动处理 WAF 挑战）
    * @param mainHtml     主分享页 HTML（已通过 WAF 挑战）
    * @param shareBaseUrl 分享页 URL（用于拼接相对地址与 Referer）
    * @param password     分享密码（公开分享传 空 或空串）
    * @return 直链信息
    */
    static LanzouShareInfo parse(LanzouHttp http, String mainHtml, String shareBaseUrl, String password) {
        // 形态二：无密码，iframe 二级页
        String iframeSrc = matchFirst(IFRAME_PATTERN, mainHtml, 1);
        if (iframeSrc != null) {
            String iframeUrl = toAbsolute(iframeSrc, shareBaseUrl);
            String iframeHtml = http.get(iframeUrl, shareBaseUrl);
            return parseIframe(http, iframeHtml, iframeUrl, password);
        }
        // 形态一：有密码，down_p 函数
        String sign = matchFirst(SIGN_PATTERN, mainHtml, 1);
        if (sign != null) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("action", "downprocess");
            params.put("sign", sign);
            if (password != null && !password.isEmpty()) {
                params.put("p", password);
            }
            String resp = http.post(origin(shareBaseUrl) + "/ajaxm.php", params, shareBaseUrl);
            return resolveDownload(resp);
        }
        throw new LanzouException("蓝奏云分享页结构无法识别（未找到下载入口）");
    }

    /**
     * 解析 iframe 二级页拿到直链。
     * @param http http
     * @param iframeHtml iframehtml
     * @param iframeUrl iframeurl
     * @param password 密码
     * @return 解析iframe的结果
     */
    private static LanzouShareInfo parseIframe(LanzouHttp http, String iframeHtml, String iframeUrl, String password) {
        String ajaxPath = matchFirst(AJAX_URL_PATTERN, iframeHtml, 1);
        if (ajaxPath == null) {
            throw new LanzouException("蓝奏云分享二级页未找到下载接口地址");
        }
        Map<String, String> params = resolveAjaxParams(iframeHtml, password);
        String resp = http.post(toAbsolute(ajaxPath, iframeUrl), params, iframeUrl);
        return resolveDownload(resp);
    }

    /**
     * 解析 AJAX 数据 块，将变量引用还原为实际值。
     *
     * <p>取值规则：值为字面量（被引号包裹或为纯数字）则直接用；否则视为 JS 变量名，
     * 在页面中按 {@code var 名='值'} 求值；加密分享但 数据 块无 p 字段时补充密码。</p>
     * @param html HTML
     * @param password 密码
     * @return resolveAJAX参数的结果
     */
    private static Map<String, String> resolveAjaxParams(String html, String password) {
        Map<String, String> params = new LinkedHashMap<>();
        String block = matchFirst(DATA_BLOCK_PATTERN, html, 1);
        if (block == null) {
            params.put("action", "downprocess");
            return params;
        }
        Matcher kv = KV_PATTERN.matcher(block);
        while (kv.find()) {
            String key = kv.group(1);
            if (key == null || key.isEmpty()) {
                continue;
            }
            String raw = kv.group(2).trim();
            String value = lookupVar(html, raw);
            if (value == null) {
                value = raw;
            }
            params.put(key, value);
        }
        if (password != null && !password.isEmpty() && !params.containsKey("p")) {
            params.put("p", password);
        }
        return params;
    }

    /**
     * 在页面内查找变量定义值。
     * @param html HTML
     * @param name 名称
     * @return lookupVar的结果
     */
    private static String lookupVar(String html, String name) {
        Matcher m = VAR_PATTERN.matcher(html);
        while (m.find()) {
            if (name.equals(m.group(1))) {
                return m.group(2);
            }
        }
        return null;
    }

    /**
     * 解析下载接口响应为直链信息。
     * @param resp resp
     * @return resolveDownload的结果
     */
    private static LanzouShareInfo resolveDownload(String resp) {
        JsonObject json = Json.getJsonObject(resp);
        if (json == null || json.isEmpty()) {
            throw new LanzouException("蓝奏云下载接口返回异常: " + resp);
        }
        int state = json.getType("zt", 0, Integer.class);
        if (state != 1) {
            String message = json.getType("inf", "", String.class);
            if (message.isEmpty()) {
                message = json.getType("info", "未知错误", String.class);
            }
            throw new LanzouException("蓝奏云下载接口失败: " + message);
        }
        String domain = json.getType("dom", "", String.class);
        String path = json.getType("url", "", String.class);
        String name = json.getType("inf", "", String.class);
        return LanzouShareInfo.builder()
                .fileName(name)
                .downloadUrl(domain + "/file/" + path)
                .build();
    }

    /**
     * 提取第一个匹配分组。
     * @param p p
     * @param html HTML
     * @param group 群体
     * @return 匹配第一个的结果
     */
    private static String matchFirst(Pattern p, String html, int group) {
        if (html == null) {
            return null;
        }
        Matcher m = p.matcher(html);
        return m.find() ? m.group(group) : null;
    }

    /**
     * 拼接绝对地址。
     * @param path 路径
     * @param base 基础
     * @return 转为absolute的结果
     */
    private static String toAbsolute(String path, String base) {
        if (path.startsWith("http")) {
            return path;
        }
        return origin(base) + (path.startsWith("/") ? "" : "/") + path;
    }

    /**
     * 提取协议+域名。
     * @param url url
     * @return origin的结果
     */
    private static String origin(String url) {
        int idx = url.indexOf("//");
        if (idx < 0) {
            return url;
        }
        int end = url.indexOf("/", idx + 2);
        return end < 0 ? url : url.substring(0, end);
    }
}
