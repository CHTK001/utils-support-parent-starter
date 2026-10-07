package com.chua.playwright.support;

import com.chua.playwright.support.engine.Engine;
import com.chua.playwright.support.engine.Engine.ResponseData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产级验收测试：原生（Rust FFM）引擎覆盖 {@link Engine} 的全部 35 个方法，
 * 并对 fixture 页面上<strong>每一个可点击的按钮 / 图标</strong>逐一点击断言。
 *
 * <p>fixture 页面（见 {@link Fixture}）包含 SVG 图标按钮、emoji 图标按钮、
 * 标准按钮、链接、checkbox / radio / switch / menuitemcheckbox / treeitem
 * 与全部常见表单控件；所有断言使用 Playwright 驱动核实过的精确错误消息。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class NativeEngineProductionTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47};

    private static HttpServerHandle server;
    private static String base;
    private static String chrome;
    private static Engine engine;
    private static long browser;

    /**
     * 本地 HTTP fixture 句柄（避免直接依赖 jdk.httpserver 导入的可读性）。
     */
    private static final class HttpServerHandle {
        private final com.sun.net.httpserver.HttpServer server;

        private HttpServerHandle(com.sun.net.httpserver.HttpServer server) {
            this.server = server;
        }

        void stop() {
            server.stop(0);
        }

        int port() {
            return server.getAddress().getPort();
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(Playwright.isNative(), "验收失败：原生（Rust FFM）引擎未生效");
        chrome = Fixture.resolveChrome();
        assertTrue(Files.exists(Path.of(chrome)), "缺少浏览器: " + chrome);
        server = new HttpServerHandle(Fixture.start());
        base = "http://127.0.0.1:" + server.port();
        engine = Playwright.getEngine();
        browser = engine.launch(true, chrome, List.of());
        assertTrue(browser > 0);
    }

    @AfterAll
    static void tearDown() {
        if (engine != null && browser > 0) {
            engine.close(browser);
        }
        if (server != null) {
            server.stop();
        }
    }

    // ==================== 辅助 ====================

    /**
     * 新建页面并导航到 fixture 首页。
     *
     * @return 页面句柄
     */
    private long newPage() {
        long p = engine.newPage(browser);
        ResponseData r = engine.gotoPage(p, base + "/", null);
        assertNotNull(r, "goto 首页应返回响应");
        assertEquals(200, r.status);
        assertEquals(base + "/", r.url);
        return p;
    }

    private Object ev(long p, String expression) {
        return engine.evaluate(p, expression, null);
    }

    private static Number num(Object v) {
        assertInstanceOf(Number.class, v);
        return (Number) v;
    }

    private static void assertPng(byte[] bytes) {
        assertTrue(bytes.length > 8);
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            assertEquals(PNG_MAGIC[i], bytes[i], "PNG 魔数");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> clicks(Object v) {
        assertInstanceOf(Map.class, v);
        return (Map<String, Object>) v;
    }

    private String selector(String id) {
        return "[data-clickable][data-id='" + id + "']";
    }

    // ==================== 引擎与版本 ====================

    @Test
    @Order(1)
    void nativeEngineSelectedAndVersioned() {
        assertTrue(Playwright.isNative());
        assertEquals("1.63.0-native", engine.version());
    }

    // ==================== 生命周期 ====================

    @Test
    @Order(2)
    void lifecycleLaunchNewContextNewPageAndClose() {
        long b = engine.launch(true, chrome, List.of("--disable-gpu"));
        try {
            long ctx = engine.newContext(b, Map.of());
            assertTrue(ctx > 0);
            long p = engine.newPage(ctx);
            assertTrue(p > 0);
            ResponseData r = engine.gotoPage(p, base + "/", null);
            assertNotNull(r);
            assertEquals(200, r.status);
            // 关闭上下文 → 其页面句柄失效
            engine.close(ctx);
            assertThrows(PlaywrightException.class, () -> engine.title(p), "上下文关闭后页面句柄应失效");
        } finally {
            engine.close(b);
        }
        // 浏览器关闭后句柄失效
        assertThrows(PlaywrightException.class, () -> engine.newPage(b), "浏览器关闭后句柄应失效");
        // 未知句柄 close 静默成功（与 JavaEngine 一致）
        engine.close(999_999_999L);
    }

    // ==================== 点击：每一个按钮 / 图标 ====================

    @Test
    @Order(10)
    void clickEveryButtonAndIcon() {
        long p = newPage();
        try {
            Object ids = ev(p, Fixture.CLICKABLE_IDS_JS);
            List<?> idList = assertInstanceOf(List.class, ids);
            assertEquals(Fixture.EXPECTED_CLICKABLES, idList.size(), "fixture 可点击元素数量");
            assertEquals(Fixture.clickableIds(), idList, "可点击元素 id 清单");

            // 逐个点击每一个按钮 / 图标
            for (Object id : idList) {
                engine.click(p, selector(String.valueOf(id)), Map.of());
            }

            // 全部点击事件均已触发且各恰好一次
            Map<String, Object> clicks = clicks(ev(p, Fixture.CLICKS_JS));
            assertEquals(idList.size(), clicks.size(), "点击计数条目数");
            for (Object id : idList) {
                assertEquals(1, num(clicks.get(String.valueOf(id))).intValue(), "点击计数: " + id);
            }

            // 逐元素读取 data-count 属性（getAttribute 路径逐个验证）
            for (Object id : idList) {
                assertEquals("1", engine.getAttribute(p, selector(String.valueOf(id)), "data-count"),
                        "data-count: " + id);
            }

            // 开关类元素的属性联动
            assertEquals("true", engine.getAttribute(p, "#btn-toggle", "aria-pressed"), "toggle 按钮点击后应置为 true");
            // 禁用按钮状态只读断言（disabled 属性存在 → 空串）
            assertEquals("", engine.getAttribute(p, "#disabled-btn", "disabled"), "禁用按钮应带 disabled 属性");
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(11)
    void clickViaElementHandle() {
        long p = newPage();
        try {
            long h = engine.querySelector(p, "#btn-counter");
            assertTrue(h > 0);
            engine.click(h, null, Map.of());
            assertEquals(1, num(ev(p, "function(){ return window.__clicks['btn-counter'] || 0; }")).intValue());
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(12)
    void dblclickFiresEventListener() {
        long p = newPage();
        try {
            engine.dblclick(p, "#dbl");
            assertEquals(1, num(ev(p, "function(){ return window.__dbl || 0; }")).intValue());
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(13)
    void linkClickNavigatesToPageTwo() {
        long p = newPage();
        try {
            engine.click(p, "[data-link]", Map.of());
            engine.waitForSelector(p, "#page2-marker", 5000L);
            assertEquals(base + "/page2", engine.url(p));
            assertEquals("Fixture Page Two", engine.title(p));
        } finally {
            engine.close(p);
        }
    }

    // ==================== 导航 ====================

    @Test
    @Order(20)
    void gotoTitleUrlReads() {
        long p = newPage();
        try {
            assertEquals("Fixture Home", engine.title(p));
            assertEquals(base + "/", engine.url(p));
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(21)
    void reloadClearsJavaScriptState() {
        long p = newPage();
        try {
            ev(p, "function(){ window.__marker = 'x'; }");
            assertEquals("x", ev(p, "function(){ return window.__marker; }"));
            engine.reload(p);
            assertNull(ev(p, "function(){ return window.__marker; }"), "reload 后 JS 状态应重置");
            assertEquals(base + "/", engine.url(p));
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(22)
    void goBackAndGoForward() {
        long p = newPage();
        try {
            ResponseData toTwo = engine.gotoPage(p, base + "/page2", null);
            assertNotNull(toTwo);
            assertEquals(200, toTwo.status);
            assertEquals(base + "/page2", toTwo.url);

            engine.goBack(p);
            engine.waitForSelector(p, "#heading", 5000L);
            assertEquals(base + "/", engine.url(p));

            engine.goForward(p);
            engine.waitForSelector(p, "#page2-marker", 5000L);
            assertEquals(base + "/page2", engine.url(p));
        } finally {
            engine.close(p);
        }
    }

    // ==================== 填充 / 输入 / 按键 ====================

    @Test
    @Order(30)
    void fillAllFillableControlTypes() {
        long p = newPage();
        try {
            engine.fill(p, "#text", "new value");
            assertEquals("new value", engine.inputValue(p, "#text"));

            engine.fill(p, "#area", "typed area");
            assertEquals("typed area", engine.inputValue(p, "#area"));

            engine.fill(p, "#editor", "editable content");
            assertEquals("editable content", engine.innerText(p, "#editor"));

            // color 小写化（与 Playwright 一致）
            engine.fill(p, "#color", "#00FF00");
            assertEquals("#00ff00", engine.inputValue(p, "#color"));

            engine.fill(p, "#date", "2024-06-01");
            assertEquals("2024-06-01", engine.inputValue(p, "#date"));

            engine.fill(p, "#range", "77");
            assertEquals("77", engine.inputValue(p, "#range"));

            engine.fill(p, "#num", "42.5");
            assertEquals("42.5", engine.inputValue(p, "#num"));
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(31)
    void fillErrorMessagesMatchPlaywright() {
        long p = newPage();
        try {
            PlaywrightException notEditable = assertThrows(PlaywrightException.class,
                    () -> engine.fill(p, "#static", "x"));
            assertEquals("Element is not an <input>, <textarea>, <select> or [contenteditable] "
                    + "and does not have a role allowing [aria-readonly]",
                    notEditable.getMessage());

            PlaywrightException badNumber = assertThrows(PlaywrightException.class,
                    () -> engine.fill(p, "#num", "abc"));
            assertEquals("Cannot type text into input[type=number]", badNumber.getMessage());

            PlaywrightException buttonTag = assertThrows(PlaywrightException.class,
                    () -> engine.fill(p, "#btn-counter", "x"));
            assertEquals("Element is not an <input>, <textarea>, <select> or [contenteditable] "
                    + "and does not have a role allowing [aria-readonly]",
                    buttonTag.getMessage());
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(40)
    void typeAppendsCharacters() {
        long p = newPage();
        try {
            engine.fill(p, "#text", "");
            engine.type(p, "#text", "abc");
            assertEquals("abc", engine.inputValue(p, "#text"));
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(41)
    void pressControlASelectsAllThenTypeReplaces() {
        long p = newPage();
        try {
            engine.fill(p, "#text", "hello world");
            engine.press(p, "#text", "Control+A");
            engine.type(p, "#text", "X");
            assertEquals("X", engine.inputValue(p, "#text"), "Ctrl+A 全选后输入应整体替换");
        } finally {
            engine.close(p);
        }
    }

    // ==================== 勾选语义 ====================

    @Test
    @Order(50)
    void checkboxCheckAndUncheck() {
        long p = newPage();
        try {
            assertTrue((Boolean) ev(p, "function(){ return document.getElementById('cb').checked; }"));
            engine.check(p, "#cb", false);
            assertFalse((Boolean) ev(p, "function(){ return document.getElementById('cb').checked; }"));
            engine.check(p, "#cb", true);
            assertTrue((Boolean) ev(p, "function(){ return document.getElementById('cb').checked; }"));
            engine.check(p, "#cb2", true);
            assertTrue((Boolean) ev(p, "function(){ return document.getElementById('cb2').checked; }"));
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(51)
    void radioGroupSemanticsAndError() {
        long p = newPage();
        try {
            engine.check(p, "#r2", true);
            assertTrue((Boolean) ev(p, "function(){ return document.getElementById('r2').checked; }"));
            assertFalse((Boolean) ev(p, "function(){ return document.getElementById('r1').checked; }"),
                    "同组另一单选应被取消勾选");

            PlaywrightException e = assertThrows(PlaywrightException.class, () -> engine.check(p, "#r2", false));
            assertEquals("Cannot uncheck radio button. Radio buttons can only be unchecked by selecting "
                    + "another radio button in the same group.", e.getMessage());

            // 选择同组另一单选即为“取消勾选”原单选
            engine.check(p, "#r1", true);
            assertTrue((Boolean) ev(p, "function(){ return document.getElementById('r1').checked; }"));
            assertFalse((Boolean) ev(p, "function(){ return document.getElementById('r2').checked; }"));
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(52)
    void ariaRoleCheckStates() {
        long p = newPage();
        try {
            assertFalse((Boolean) ev(p, "function(){ return document.getElementById('sw').getAttribute('aria-checked') === 'true'; }"));
            engine.check(p, "#sw", true);
            assertTrue((Boolean) ev(p, "function(){ return document.getElementById('sw').getAttribute('aria-checked') === 'true'; }"),
                    "role=switch 应可勾选");

            engine.check(p, "#mi", false);
            assertFalse((Boolean) ev(p, "function(){ return document.getElementById('mi').getAttribute('aria-checked') === 'true'; }"),
                    "role=menuitemcheckbox 应可取消勾选");

            engine.check(p, "#tree1", true);
            assertTrue((Boolean) ev(p, "function(){ return document.getElementById('tree1').getAttribute('aria-checked') === 'true'; }"),
                    "role=treeitem 应可勾选");
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(53)
    void checkOnPlainTextInputThrows() {
        long p = newPage();
        try {
            PlaywrightException e = assertThrows(PlaywrightException.class, () -> engine.check(p, "#text", true));
            assertEquals("Not a checkbox or radio button", e.getMessage());
        } finally {
            engine.close(p);
        }
    }

    // ==================== 悬停 ====================

    @Test
    @Order(60)
    void hoverTriggersMouseoverHandler() {
        long p = newPage();
        try {
            assertEquals("none", ev(p, "function(){ return getComputedStyle(document.getElementById('tip')).display; }"));
            engine.hover(p, "#hov");
            assertEquals("block", ev(p, "function(){ return getComputedStyle(document.getElementById('tip')).display; }"),
                    "hover 后提示应显示");
        } finally {
            engine.close(p);
        }
    }

    // ==================== 下拉选择 ====================

    @Test
    @Order(70)
    void selectOptionFlows() {
        long p = newPage();
        try {
            assertEquals(List.of("o2"), engine.selectOption(p, "#sel", new String[]{"o2"}));
            assertEquals("o2", engine.inputValue(p, "#sel"));

            // 按 label 匹配（valueOrLabel 语义）
            assertEquals(List.of("o2"), engine.selectOption(p, "#sel", new String[]{"Two"}));
            assertEquals("o2", engine.inputValue(p, "#sel"));

            assertEquals(List.of("m1", "m3"), engine.selectOption(p, "#msel", new String[]{"m1", "m3"}));

            // 空数组 → 取消全部选择
            assertEquals(List.of(), engine.selectOption(p, "#msel", new String[0]));
            assertEquals(0, num(ev(p, "function(){ return document.getElementById('msel').selectedOptions.length; }")).intValue());

            PlaywrightException e = assertThrows(PlaywrightException.class,
                    () -> engine.selectOption(p, "#text", new String[]{"x"}));
            assertEquals("Element is not a <select> element", e.getMessage());
        } finally {
            engine.close(p);
        }
    }

    // ==================== 读取 ====================

    @Test
    @Order(80)
    void readsTextHtmlAttributesAndValues() {
        long p = newPage();
        try {
            assertEquals("Fixture Home", engine.textContent(p, "#heading"));
            assertEquals("Fixture Home", engine.innerText(p, "#heading"));
            assertEquals("editable", engine.innerHTML(p, "#editor"));
            assertEquals("/page2", engine.getAttribute(p, "#navlink", "href"));
            assertNull(engine.getAttribute(p, "#heading", "data-missing"), "缺失属性应返回 null");
            assertEquals("prefilled", engine.inputValue(p, "#text"));
            assertEquals("area-text", engine.inputValue(p, "#area"));
            assertEquals("o1", engine.inputValue(p, "#sel"));
        } finally {
            engine.close(p);
        }
    }

    // ==================== 截图 / PDF / HTML 渲染 ====================

    @Test
    @Order(90)
    void screenshotPlainAndFullPage() {
        long p = newPage();
        try {
            byte[] png = engine.screenshot(p, Map.of());
            assertPng(png);
            byte[] full = engine.screenshot(p, Map.of("fullPage", true));
            assertPng(full);
            assertTrue(full.length > png.length, "全页截图应大于视口截图");
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(190)
    void printPageToPdfRendersPdf() {
        long p = newPage();
        try {
            String b64 = engine.printPageToPdf(p, Map.of(
                    "format", "A4", "landscape", false, "printBackground", true, "scale", 1.0));
            byte[] pdf = Base64.getDecoder().decode(b64);
            assertTrue(pdf.length > 500, "PDF 应有实质内容");
            assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.ISO_8859_1));
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(200)
    void convertHtmlToPngRendersPng() {
        long p = newPage();
        try {
            String b64 = engine.convertHtmlToPng(p,
                    "<html><body><h1 style=\"color:red\">PNG</h1></body></html>");
            byte[] png = Base64.getDecoder().decode(b64);
            assertPng(png);
        } finally {
            engine.close(p);
        }
    }

    // ==================== evaluate ====================

    @Test
    @Order(100)
    void evaluateFunctionExpressionAndElementFlows() {
        long p = newPage();
        try {
            // 函数 + 参数
            assertEquals(42, num(engine.evaluate(p, "function(a){ return a * 2; }", 21)).intValue());
            // 普通表达式
            assertEquals(2, num(engine.evaluate(p, "1 + 1", null)).intValue());
            // 对象返回
            Object obj = engine.evaluate(p, "function(){ return {x:1, y:'two'}; }", null);
            assertInstanceOf(Map.class, obj);
            Map<?, ?> map = (Map<?, ?>) obj;
            assertEquals(1, num(map.get("x")).intValue());
            assertEquals("two", map.get("y"));
            // undefined → null
            assertNull(engine.evaluate(p, "function(){}", null));
            // 缺省参数：playwright-java 对 null 实参省略不传 → v 为 undefined（与 JavaEngine 对齐）
            assertEquals("undefined", engine.evaluate(p, "function(v){ return typeof v; }", null));
            // 元素句柄 evaluate：元素作为第一个参数（Playwright 语义）
            long h = engine.querySelector(p, "#text");
            assertTrue(h > 0);
            assertEquals("text:A", engine.evaluate(h, "function(el, v){ return el.id + ':' + v; }", "A"));
            assertEquals("prefilled", engine.evaluate(h, "function(el){ return el.value; }", null));
            engine.close(h); // 元素句柄释放（静默）
        } finally {
            engine.close(p);
        }
    }

    // ==================== 查询与等待 ====================

    @Test
    @Order(110)
    void querySelectorAndAll() {
        long p = newPage();
        try {
            long h = engine.querySelector(p, "#btn-counter");
            assertTrue(h > 0);
            assertEquals(-1, engine.querySelector(p, "#does-not-exist"));

            List<Long> all = engine.querySelectorAll(p, "[data-clickable]");
            assertEquals(Fixture.EXPECTED_CLICKABLES, all.size());
            long domCount = num(ev(p,
                    "function(){ return document.querySelectorAll('[data-clickable]').length; }")).longValue();
            assertEquals(domCount, all.size(), "querySelectorAll 数量应与 DOM 一致");
            engine.close(h);
        } finally {
            engine.close(p);
        }
    }

    @Test
    @Order(120)
    void waitForSelectorDelayedElementAndTimeout() {
        long p = newPage();
        try {
            engine.waitForSelector(p, "#delayed", 5000L);
            assertEquals("later", engine.textContent(p, "#delayed"));

            PlaywrightException e = assertThrows(PlaywrightException.class,
                    () -> engine.waitForSelector(p, "#never", 600L));
            assertTrue(e.getMessage().contains("Timeout 600ms exceeded"),
                    () -> "超时消息应包含 Playwright 语义: " + e.getMessage());
        } finally {
            engine.close(p);
        }
    }

    // ==================== 视口 ====================

    @Test
    @Order(130)
    void setViewportSizeTakesEffect() {
        long p = newPage();
        try {
            assertEquals(1280, num(ev(p, "function(){ return window.innerWidth; }")).intValue(),
                    "默认视口宽度");
            engine.setViewportSize(p, 500, 400);
            assertEquals(500, num(ev(p, "function(){ return window.innerWidth; }")).intValue());
            assertEquals(400, num(ev(p, "function(){ return window.innerHeight; }")).intValue());
        } finally {
            engine.close(p);
        }
    }

    // ==================== 元素 / 页面 / 上下文关闭 ====================

    @Test
    @Order(160)
    void closeElementPageAndContextInvalidatesHandles() {
        long p = newPage();
        try {
            long h = engine.querySelector(p, "#btn-counter");
            assertTrue(h > 0);
            engine.close(h); // 元素句柄释放：静默成功
        } finally {
            engine.close(p);
        }
        assertThrows(PlaywrightException.class, () -> engine.title(p), "页面关闭后句柄应失效");
    }

    // ==================== API 请求 ====================

    @Test
    @Order(170)
    void apiRequestGetPostPutAndUnknownAction() {
        long h = engine.newAPIRequest();
        assertTrue(h > 0);

        Engine.ApiResponseData get = engine.apiRequest("apiGet", base + "/api/echo?x=1", null);
        assertEquals(200, get.status);
        assertTrue(get.body.contains("GET"), get.body);
        assertTrue(get.body.contains("x=1"), get.body);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("k", "v");
        Engine.ApiResponseData post = engine.apiRequest("apipost", base + "/api/echo", payload);
        assertEquals(200, post.status);
        assertTrue(post.body.contains("POST"), post.body);
        assertTrue(post.body.contains("{\"k\":\"v\"}"), post.body);

        Engine.ApiResponseData put = engine.apiRequest("PUT", base + "/api/echo", "raw");
        assertEquals(200, put.status);
        assertTrue(put.body.contains("PUT"), put.body);
        assertTrue(put.body.contains("raw"), put.body);

        PlaywrightException e = assertThrows(PlaywrightException.class,
                () -> engine.apiRequest("banana", base + "/api/echo", null));
        assertEquals("不支持的请求动作: banana", e.getMessage());
    }

    // ==================== 批量 ====================

    @Test
    @Order(180)
    void batchCommandSequence() {
        long p = engine.newPage(browser);
        Map<String, Object> gotoCmd = Map.of("action", "goto", "handle", p, "params", Map.of("url", base + "/"));
        Map<String, Object> fillCmd = Map.of("action", "fill", "handle", p,
                "params", Map.of("selector", "#text", "value", "batch"));
        Map<String, Object> evalCmd = Map.of("action", "evaluate", "handle", p,
                "params", Map.of("expression", "function(){ return document.getElementById('text').value; }"));
        Map<String, Object> clickCmd = Map.of("action", "click", "handle", p,
                "params", Map.of("selector", "[data-id='btn-counter']"));
        Map<String, Object> evalClicks = Map.of("action", "evaluate", "handle", p,
                "params", Map.of("expression", "function(){ return window.__clicks['btn-counter'] || 0; }"));
        Map<String, Object> shotCmd = Map.of("action", "screenshot", "handle", p,
                "params", Map.of("fullPage", false));
        Map<String, Object> closeCmd = Map.of("action", "close", "handle", p);

        List<Object> results = engine.batch(List.of(
                gotoCmd, fillCmd, evalCmd, clickCmd, evalClicks, shotCmd, closeCmd), true);
        assertEquals(7, results.size());

        Map<?, ?> r0 = (Map<?, ?>) results.get(0);
        assertEquals(200, num(r0.get("status")).intValue());
        assertEquals(base + "/", r0.get("url"));
        assertEquals(Map.of(), results.get(1), "fill → 空结果");
        assertEquals("batch", results.get(2), "evaluate → 原始值");
        assertEquals(Map.of(), results.get(3), "click → 空结果");
        assertEquals(1, num(results.get(4)).intValue(), "批量点击后计数");
        assertInstanceOf(byte[].class, results.get(5), "批量截图应为 byte[]");
        assertPng((byte[]) results.get(5));
        assertEquals(Map.of(), results.get(6), "close → 空结果");

        // 批量内关闭的页面已失效
        assertThrows(PlaywrightException.class, () -> engine.title(p));

        // stopOnError=false：未知命令进入 error 条目，后续命令继续
        long p2 = engine.newPage(browser);
        try {
            List<Object> partial = engine.batch(List.of(
                    Map.of("action", "bogus"),
                    Map.of("action", "goto", "handle", p2, "params", Map.of("url", base + "/"))), false);
            assertEquals(2, partial.size());
            Map<?, ?> failed = (Map<?, ?>) partial.get(0);
            assertEquals("不支持的批量命令: bogus", failed.get("error"));
            Map<?, ?> after = (Map<?, ?>) partial.get(1);
            assertEquals(200, num(after.get("status")).intValue());

            // stopOnError=true：立即抛出
            PlaywrightException e = assertThrows(PlaywrightException.class,
                    () -> engine.batch(List.of(Map.of("action", "bogus")), true));
            assertEquals("不支持的批量命令: bogus", e.getMessage());
        } finally {
            engine.close(p2);
        }
    }

    // ==================== 错误路径 ====================

    @Test
    @Order(210)
    void invalidSelectorFailsFast() {
        long p = newPage();
        try {
            assertThrows(PlaywrightException.class, () -> engine.click(p, ">>??", Map.of()));
            assertThrows(PlaywrightException.class, () -> engine.querySelector(p, "<<<"));
        } finally {
            engine.close(p);
        }
    }
}
