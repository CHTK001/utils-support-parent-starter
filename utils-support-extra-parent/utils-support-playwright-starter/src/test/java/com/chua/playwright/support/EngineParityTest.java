package com.chua.playwright.support;

import com.chua.playwright.support.engine.Engine;
import com.chua.playwright.support.engine.JavaEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双引擎一致性验收：同一 fixture 下，原生（Rust FFM）引擎与 JavaEngine
 * （playwright-Java 1.63.0）对同一操作序列产生<strong>完全一致</strong>的
 * 观测值或错误消息。
 *
 * <p>数值统一归一化（Integer/Long/Double 差异抹平），byte[] 归一化为占位标识
 * （渲染结果不可逐字节比较）；错误消息按精确相等断言（Playwright 驱动核实过的
 * 字符串两侧均须一致），超时类消息按公共前缀断言。</p>
 *
 * @author CH
 * @since 4.0.0
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class EngineParityTest {

    private static com.sun.net.httpserver.HttpServer server;
    private static String base;
    private static String chrome;
    private static Engine nativeEngine;
    private static Engine javaEngine;
    private static long nativeBrowser;
    private static long javaBrowser;

    /**
     * 单次操作的结果：值或错误消息。
     */
    private record Outcome(Object value, String error) {
    }

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(Playwright.isNative(), "验收失败：原生引擎未生效");
        chrome = Fixture.resolveChrome();
        assertTrue(Files.exists(Path.of(chrome)), "缺少浏览器: " + chrome);
        server = Fixture.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        nativeEngine = Playwright.getEngine();
        javaEngine = new JavaEngine();
        nativeBrowser = nativeEngine.launch(true, chrome, List.of());
        javaBrowser = javaEngine.launch(true, chrome, List.of());
    }

    @AfterAll
    static void tearDown() {
        if (nativeEngine != null && nativeBrowser > 0) {
            nativeEngine.close(nativeBrowser);
        }
        if (javaEngine != null && javaBrowser > 0) {
            javaEngine.close(javaBrowser);
        }
        if (server != null) {
            server.stop(0);
        }
    }

    // ==================== 归一化与比较辅助 ====================

    private static Object norm(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof byte[]) {
            return "bytes";
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            m.forEach((k, x) -> r.put(String.valueOf(k), norm(x)));
            return r;
        }
        if (v instanceof List<?> l) {
            List<Object> r = new ArrayList<>();
            l.forEach(x -> r.add(norm(x)));
            return r;
        }
        return v;
    }

    /**
     * 错误消息归一化：抹平两侧报错的**呈现格式**差异，只比较语义消息本身。
     *
     * <p>playwright-Java 对带堆栈的错误是 {@code Error { message='...' }} 多行格式，
     * 对 stackless 错误是裸文本；原生侧是首行消息。统一为：先取 {@code message='...'}
     * 字段（若有），再取首行，最后剥掉 {@code Error: } 前缀 —— 与 Playwright
     * 对外消息文本一致。</p>
     *
     * @param m 原始消息（可为 null）
     * @return 归一化后的消息
     */
    private static String msgNorm(String m) {
        if (m == null) {
            return null;
        }
        int i = m.indexOf("message='");
        if (i >= 0) {
            int start = i + "message='".length();
            int end = m.indexOf('\'', start);
            m = end >= 0 ? m.substring(start, end) : m.substring(start);
        }
        int nl = m.indexOf('\n');
        if (nl >= 0) {
            m = m.substring(0, nl);
        }
        m = m.trim();
        return m.startsWith("Error: ") ? m.substring("Error: ".length()) : m;
    }

    private static Outcome run(Supplier<Object> op) {
        try {
            return new Outcome(norm(op.get()), null);
        } catch (Throwable t) {
            return new Outcome(null, msgNorm(t.getMessage()));
        }
    }

    /**
     * 同一操作在两个引擎上执行，断言结果（值或精确错误消息）一致。
     *
     * @param label    断言标签
     * @param nativeOp 原生引擎操作
     * @param javaOp   Java 引擎操作
     */
    private static void assertSameOutcome(String label, Supplier<Object> nativeOp, Supplier<Object> javaOp) {
        assertEquals(run(javaOp), run(nativeOp), label);
    }

    /**
     * 两个引擎均抛出且消息均包含公共片段（用于超时类带调用日志的消息）。
     *
     * @param label   断言标签
     * @param needle  消息公共片段
     * @param nat     原生引擎操作
     * @param jv      Java 引擎操作
     */
    private static void assertBothThrowContaining(String label, String needle,
                                                  Supplier<Object> nat, Supplier<Object> jv) {
        Outcome a = run(nat);
        Outcome b = run(jv);
        assertNotNull(a.error(), label + "：原生引擎应抛出异常");
        assertNotNull(b.error(), label + "：Java 引擎应抛出异常");
        assertTrue(a.error().contains(needle), () -> label + "：原生消息应包含 [" + needle + "]，实际: " + a.error());
        assertTrue(b.error().contains(needle), () -> label + "：Java 消息应包含 [" + needle + "]，实际: " + b.error());
    }

    private static void assertBothThrow(String label, Supplier<Object> nat, Supplier<Object> jv) {
        Outcome a = run(nat);
        Outcome b = run(jv);
        assertNotNull(a.error(), label + "：原生引擎应抛出异常");
        assertNotNull(b.error(), label + "：Java 引擎应抛出异常");
    }

    private long page(Engine engine, long browser) {
        long p = engine.newPage(browser);
        Engine.ResponseData r = engine.gotoPage(p, base + "/", null);
        assertNotNull(r);
        assertEquals(200, r.status);
        return p;
    }

    private static Object rd(Engine.ResponseData r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status);
        m.put("url", r.url);
        return m;
    }

    private static String sel(String id) {
        return "[data-clickable][data-id='" + id + "']";
    }

    private static Boolean bool(Object v) {
        return (Boolean) v;
    }

    // ==================== 1. 导航与读取 ====================

    @Test
    @Order(1)
    void navigationAndReadParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            assertSameOutcome("goto 首页",
                    () -> rd(nativeEngine.gotoPage(np, base + "/", null)),
                    () -> rd(javaEngine.gotoPage(jp, base + "/", null)));
            assertSameOutcome("goto page2",
                    () -> rd(nativeEngine.gotoPage(np, base + "/page2", null)),
                    () -> rd(javaEngine.gotoPage(jp, base + "/page2", null)));
            // 回到首页供后续断言
            nativeEngine.gotoPage(np, base + "/", null);
            javaEngine.gotoPage(jp, base + "/", null);

            assertSameOutcome("title", () -> nativeEngine.title(np), () -> javaEngine.title(jp));
            assertSameOutcome("url", () -> nativeEngine.url(np), () -> javaEngine.url(jp));

            assertSameOutcome("textContent", () -> nativeEngine.textContent(np, "#heading"),
                    () -> javaEngine.textContent(jp, "#heading"));
            assertSameOutcome("innerText", () -> nativeEngine.innerText(np, "#heading"),
                    () -> javaEngine.innerText(jp, "#heading"));
            assertSameOutcome("innerHTML", () -> nativeEngine.innerHTML(np, "#editor"),
                    () -> javaEngine.innerHTML(jp, "#editor"));
            assertSameOutcome("getAttribute", () -> nativeEngine.getAttribute(np, "#navlink", "href"),
                    () -> javaEngine.getAttribute(jp, "#navlink", "href"));
            assertSameOutcome("getAttribute 缺失",
                    () -> nativeEngine.getAttribute(np, "#heading", "data-missing"),
                    () -> javaEngine.getAttribute(jp, "#heading", "data-missing"));
            assertSameOutcome("inputValue", () -> nativeEngine.inputValue(np, "#text"),
                    () -> javaEngine.inputValue(jp, "#text"));
            assertSameOutcome("inputValue select", () -> nativeEngine.inputValue(np, "#sel"),
                    () -> javaEngine.inputValue(jp, "#sel"));
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    // ==================== 2. 点击每一个按钮 / 图标 ====================

    @Test
    @Order(2)
    void clickEveryButtonIconParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            assertSameOutcome("可点击 id 清单",
                    () -> nativeEngine.evaluate(np, Fixture.CLICKABLE_IDS_JS, null),
                    () -> javaEngine.evaluate(jp, Fixture.CLICKABLE_IDS_JS, null));

            for (String id : Fixture.clickableIds()) {
                nativeEngine.click(np, sel(id), Map.of());
                javaEngine.click(jp, sel(id), Map.of());
                assertEquals(javaEngine.getAttribute(jp, sel(id), "data-count"),
                        nativeEngine.getAttribute(np, sel(id), "data-count"), "data-count: " + id);
            }

            assertSameOutcome("全部点击计数",
                    () -> nativeEngine.evaluate(np, Fixture.CLICKS_JS, null),
                    () -> javaEngine.evaluate(jp, Fixture.CLICKS_JS, null));
            assertSameOutcome("toggle 联动",
                    () -> nativeEngine.getAttribute(np, "#btn-toggle", "aria-pressed"),
                    () -> javaEngine.getAttribute(jp, "#btn-toggle", "aria-pressed"));

            // 双击
            nativeEngine.dblclick(np, "#dbl");
            javaEngine.dblclick(jp, "#dbl");
            assertSameOutcome("双击计数",
                    () -> nativeEngine.evaluate(np, "function(){ return window.__dbl || 0; }", null),
                    () -> javaEngine.evaluate(jp, "function(){ return window.__dbl || 0; }", null));

            // 悬停
            nativeEngine.hover(np, "#hov");
            javaEngine.hover(jp, "#hov");
            assertSameOutcome("悬停提示",
                    () -> nativeEngine.evaluate(np,
                            "function(){ return getComputedStyle(document.getElementById('tip')).display; }", null),
                    () -> javaEngine.evaluate(jp,
                            "function(){ return getComputedStyle(document.getElementById('tip')).display; }", null));

            // 元素句柄点击
            long nh = nativeEngine.querySelector(np, "#btn-counter");
            long jh = javaEngine.querySelector(jp, "#btn-counter");
            assertTrue(nh > 0);
            assertTrue(jh > 0);
            nativeEngine.click(nh, null, Map.of());
            javaEngine.click(jh, null, Map.of());
            assertSameOutcome("句柄点击计数",
                    () -> nativeEngine.evaluate(np, "function(){ return window.__clicks['btn-counter'] || 0; }", null),
                    () -> javaEngine.evaluate(jp, "function(){ return window.__clicks['btn-counter'] || 0; }", null));
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    // ==================== 3. fill / type / press ====================

    @Test
    @Order(3)
    void fillTypePressParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            // 各类控件填充后观测值一致
            String[] targets = {"#text", "#area", "#editor", "#color", "#date", "#range", "#num"};
            String[] values = {"new value", "typed area", "editable content", "#00FF00", "2024-06-01", "77", "42.5"};
            for (int i = 0; i < targets.length; i++) {
                String t = targets[i];
                String v = values[i];
                assertSameOutcome("fill " + t,
                        () -> {
                            nativeEngine.fill(np, t, v);
                            return nativeEngine.inputValue(np, t);
                        },
                        () -> {
                            javaEngine.fill(jp, t, v);
                            return javaEngine.inputValue(jp, t);
                        });
            }
            assertSameOutcome("contenteditable innerText",
                    () -> nativeEngine.innerText(np, "#editor"),
                    () -> javaEngine.innerText(jp, "#editor"));

            // 错误消息精确一致
            assertSameOutcome("fill 非可编辑元素",
                    () -> {
                        nativeEngine.fill(np, "#static", "x");
                        return null;
                    },
                    () -> {
                        javaEngine.fill(jp, "#static", "x");
                        return null;
                    });
            assertSameOutcome("fill number 非法值",
                    () -> {
                        nativeEngine.fill(np, "#num", "abc");
                        return null;
                    },
                    () -> {
                        javaEngine.fill(jp, "#num", "abc");
                        return null;
                    });
            assertSameOutcome("fill 按钮元素",
                    () -> {
                        nativeEngine.fill(np, "#btn-counter", "x");
                        return null;
                    },
                    () -> {
                        javaEngine.fill(jp, "#btn-counter", "x");
                        return null;
                    });

            // type 流程
            assertSameOutcome("type",
                    () -> {
                        nativeEngine.fill(np, "#text", "");
                        nativeEngine.type(np, "#text", "abc");
                        return nativeEngine.inputValue(np, "#text");
                    },
                    () -> {
                        javaEngine.fill(jp, "#text", "");
                        javaEngine.type(jp, "#text", "abc");
                        return javaEngine.inputValue(jp, "#text");
                    });

            // press Control+A → type 替换
            assertSameOutcome("press Control+A",
                    () -> {
                        nativeEngine.fill(np, "#text", "hello world");
                        nativeEngine.press(np, "#text", "Control+A");
                        nativeEngine.type(np, "#text", "X");
                        return nativeEngine.inputValue(np, "#text");
                    },
                    () -> {
                        javaEngine.fill(jp, "#text", "hello world");
                        javaEngine.press(jp, "#text", "Control+A");
                        javaEngine.type(jp, "#text", "X");
                        return javaEngine.inputValue(jp, "#text");
                    });
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    // ==================== 4. 勾选与下拉 ====================

    @Test
    @Order(4)
    void checkAndSelectParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            Supplier<Object> checks = () -> Map.of(
                    "cb", nativeEngine.evaluate(np, "function(){ return document.getElementById('cb').checked; }", null),
                    "cb2", nativeEngine.evaluate(np, "function(){ return document.getElementById('cb2').checked; }", null),
                    "r1", nativeEngine.evaluate(np, "function(){ return document.getElementById('r1').checked; }", null),
                    "r2", nativeEngine.evaluate(np, "function(){ return document.getElementById('r2').checked; }", null),
                    "sw", nativeEngine.evaluate(np, "function(){ return document.getElementById('sw').getAttribute('aria-checked'); }", null),
                    "mi", nativeEngine.evaluate(np, "function(){ return document.getElementById('mi').getAttribute('aria-checked'); }", null),
                    "tree", nativeEngine.evaluate(np, "function(){ return document.getElementById('tree1').getAttribute('aria-checked'); }", null));
            Supplier<Object> checksJava = () -> Map.of(
                    "cb", javaEngine.evaluate(jp, "function(){ return document.getElementById('cb').checked; }", null),
                    "cb2", javaEngine.evaluate(jp, "function(){ return document.getElementById('cb2').checked; }", null),
                    "r1", javaEngine.evaluate(jp, "function(){ return document.getElementById('r1').checked; }", null),
                    "r2", javaEngine.evaluate(jp, "function(){ return document.getElementById('r2').checked; }", null),
                    "sw", javaEngine.evaluate(jp, "function(){ return document.getElementById('sw').getAttribute('aria-checked'); }", null),
                    "mi", javaEngine.evaluate(jp, "function(){ return document.getElementById('mi').getAttribute('aria-checked'); }", null),
                    "tree", javaEngine.evaluate(jp, "function(){ return document.getElementById('tree1').getAttribute('aria-checked'); }", null));

            // checkbox
            nativeEngine.check(np, "#cb", false);
            javaEngine.check(jp, "#cb", false);
            assertSameOutcome("checkbox 取消", checks, checksJava);
            nativeEngine.check(np, "#cb", true);
            javaEngine.check(jp, "#cb", true);
            nativeEngine.check(np, "#cb2", true);
            javaEngine.check(jp, "#cb2", true);
            assertSameOutcome("checkbox 勾选", checks, checksJava);

            // radio
            nativeEngine.check(np, "#r2", true);
            javaEngine.check(jp, "#r2", true);
            assertSameOutcome("radio 选中", checks, checksJava);
            assertSameOutcome("radio 取消错误",
                    () -> {
                        nativeEngine.check(np, "#r2", false);
                        return null;
                    },
                    () -> {
                        javaEngine.check(jp, "#r2", false);
                        return null;
                    });
            nativeEngine.check(np, "#r1", true);
            javaEngine.check(jp, "#r1", true);
            assertSameOutcome("radio 换选", checks, checksJava);

            // aria 角色
            nativeEngine.check(np, "#sw", true);
            javaEngine.check(jp, "#sw", true);
            nativeEngine.check(np, "#mi", false);
            javaEngine.check(jp, "#mi", false);
            nativeEngine.check(np, "#tree1", true);
            javaEngine.check(jp, "#tree1", true);
            assertSameOutcome("aria 角色勾选", checks, checksJava);

            // 非勾选元素
            assertSameOutcome("非勾选元素 check",
                    () -> {
                        nativeEngine.check(np, "#text", true);
                        return null;
                    },
                    () -> {
                        javaEngine.check(jp, "#text", true);
                        return null;
                    });

            // selectOption：value / label / 多选 / 清空 / 非 select
            assertSameOutcome("select by value",
                    () -> {
                        nativeEngine.selectOption(np, "#sel", new String[]{"o2"});
                        return nativeEngine.inputValue(np, "#sel");
                    },
                    () -> {
                        javaEngine.selectOption(jp, "#sel", new String[]{"o2"});
                        return javaEngine.inputValue(jp, "#sel");
                    });
            assertSameOutcome("select by label",
                    () -> {
                        nativeEngine.selectOption(np, "#sel", new String[]{"Two"});
                        return nativeEngine.inputValue(np, "#sel");
                    },
                    () -> {
                        javaEngine.selectOption(jp, "#sel", new String[]{"Two"});
                        return javaEngine.inputValue(jp, "#sel");
                    });
            assertSameOutcome("select 返回值",
                    () -> nativeEngine.selectOption(np, "#sel", new String[]{"o3"}),
                    () -> javaEngine.selectOption(jp, "#sel", new String[]{"o3"}));
            assertSameOutcome("多选",
                    () -> nativeEngine.selectOption(np, "#msel", new String[]{"m1", "m3"}),
                    () -> javaEngine.selectOption(jp, "#msel", new String[]{"m1", "m3"}));
            assertSameOutcome("清空多选",
                    () -> {
                        nativeEngine.selectOption(np, "#msel", new String[0]);
                        return nativeEngine.evaluate(np,
                                "function(){ return document.getElementById('msel').selectedOptions.length; }", null);
                    },
                    () -> {
                        javaEngine.selectOption(jp, "#msel", new String[0]);
                        return javaEngine.evaluate(jp,
                                "function(){ return document.getElementById('msel').selectedOptions.length; }", null);
                    });
            assertSameOutcome("select 非下拉元素",
                    () -> {
                        nativeEngine.selectOption(np, "#text", new String[]{"x"});
                        return null;
                    },
                    () -> {
                        javaEngine.selectOption(jp, "#text", new String[]{"x"});
                        return null;
                    });
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    // ==================== 5. evaluate ====================

    @Test
    @Order(5)
    void evaluateParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            assertSameOutcome("函数+参数", () -> nativeEngine.evaluate(np, "function(a){ return a * 2; }", 21),
                    () -> javaEngine.evaluate(jp, "function(a){ return a * 2; }", 21));
            assertSameOutcome("普通表达式", () -> nativeEngine.evaluate(np, "1 + 1", null),
                    () -> javaEngine.evaluate(jp, "1 + 1", null));
            assertSameOutcome("对象返回", () -> nativeEngine.evaluate(np, "function(){ return {x:1, y:'two'}; }", null),
                    () -> javaEngine.evaluate(jp, "function(){ return {x:1, y:'two'}; }", null));
            assertSameOutcome("数组返回", () -> nativeEngine.evaluate(np, "function(){ return [1,'a',null]; }", null),
                    () -> javaEngine.evaluate(jp, "function(){ return [1,'a',null]; }", null));
            assertSameOutcome("undefined→null", () -> nativeEngine.evaluate(np, "function(){}", null),
                    () -> javaEngine.evaluate(jp, "function(){}", null));
            assertSameOutcome("缺省参数", () -> nativeEngine.evaluate(np, "function(v){ return typeof v; }", null),
                    () -> javaEngine.evaluate(jp, "function(v){ return typeof v; }", null));
            assertSameOutcome("arg 对象", () -> nativeEngine.evaluate(np, "function(v){ return v.k; }", Map.of("k", 7)),
                    () -> javaEngine.evaluate(jp, "function(v){ return v.k; }", Map.of("k", 7)));

            long nh = nativeEngine.querySelector(np, "#text");
            long jh = javaEngine.querySelector(jp, "#text");
            assertTrue(nh > 0);
            assertTrue(jh > 0);
            assertSameOutcome("元素 evaluate",
                    () -> nativeEngine.evaluate(nh, "function(el, v){ return el.id + ':' + v; }", "A"),
                    () -> javaEngine.evaluate(jh, "function(el, v){ return el.id + ':' + v; }", "A"));
            assertSameOutcome("元素 evaluate this.value",
                    () -> nativeEngine.evaluate(nh, "function(el){ return el.value; }", null),
                    () -> javaEngine.evaluate(jh, "function(el){ return el.value; }", null));
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    // ==================== 6. 查询与等待 ====================

    @Test
    @Order(6)
    void queryAndWaitParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            assertSameOutcome("querySelector 命中",
                    () -> nativeEngine.querySelector(np, "#btn-counter") > 0,
                    () -> javaEngine.querySelector(jp, "#btn-counter") > 0);
            assertSameOutcome("querySelector 未命中",
                    () -> nativeEngine.querySelector(np, "#does-not-exist"),
                    () -> javaEngine.querySelector(jp, "#does-not-exist"));
            assertSameOutcome("querySelectorAll 数量",
                    () -> nativeEngine.querySelectorAll(np, "[data-clickable]").size(),
                    () -> javaEngine.querySelectorAll(jp, "[data-clickable]").size());

            assertSameOutcome("waitForSelector 延迟元素",
                    () -> {
                        nativeEngine.waitForSelector(np, "#delayed", 5000L);
                        return nativeEngine.textContent(np, "#delayed");
                    },
                    () -> {
                        javaEngine.waitForSelector(jp, "#delayed", 5000L);
                        return javaEngine.textContent(jp, "#delayed");
                    });

            assertBothThrowContaining("waitForSelector 超时", "Timeout 600ms exceeded",
                    () -> {
                        nativeEngine.waitForSelector(np, "#never", 600L);
                        return null;
                    },
                    () -> {
                        javaEngine.waitForSelector(jp, "#never", 600L);
                        return null;
                    });
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    // ==================== 7. 视口 / 重载 / 前进后退 ====================

    @Test
    @Order(7)
    void viewportReloadHistoryParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            assertSameOutcome("默认视口",
                    () -> nativeEngine.evaluate(np, "function(){ return window.innerWidth; }", null),
                    () -> javaEngine.evaluate(jp, "function(){ return window.innerWidth; }", null));

            nativeEngine.setViewportSize(np, 500, 400);
            javaEngine.setViewportSize(jp, 500, 400);
            assertSameOutcome("设置后视口宽",
                    () -> nativeEngine.evaluate(np, "function(){ return window.innerWidth; }", null),
                    () -> javaEngine.evaluate(jp, "function(){ return window.innerWidth; }", null));
            assertSameOutcome("设置后视口高",
                    () -> nativeEngine.evaluate(np, "function(){ return window.innerHeight; }", null),
                    () -> javaEngine.evaluate(jp, "function(){ return window.innerHeight; }", null));

            // reload
            assertSameOutcome("reload 重置 JS 状态",
                    () -> {
                        nativeEngine.evaluate(np, "function(){ window.__marker = 'x'; }", null);
                        nativeEngine.reload(np);
                        return nativeEngine.evaluate(np, "function(){ return window.__marker; }", null);
                    },
                    () -> {
                        javaEngine.evaluate(jp, "function(){ window.__marker = 'x'; }", null);
                        javaEngine.reload(jp);
                        return javaEngine.evaluate(jp, "function(){ return window.__marker; }", null);
                    });

            // 前进后退：url 序列一致
            assertSameOutcome("goto/back/forward url 序列",
                    () -> {
                        List<String> urls = new ArrayList<>();
                        nativeEngine.gotoPage(np, base + "/page2", null);
                        urls.add(nativeEngine.url(np));
                        nativeEngine.goBack(np);
                        nativeEngine.waitForSelector(np, "#heading", 5000L);
                        urls.add(nativeEngine.url(np));
                        nativeEngine.goForward(np);
                        nativeEngine.waitForSelector(np, "#page2-marker", 5000L);
                        urls.add(nativeEngine.url(np));
                        return urls;
                    },
                    () -> {
                        List<String> urls = new ArrayList<>();
                        javaEngine.gotoPage(jp, base + "/page2", null);
                        urls.add(javaEngine.url(jp));
                        javaEngine.goBack(jp);
                        javaEngine.waitForSelector(jp, "#heading", 5000L);
                        urls.add(javaEngine.url(jp));
                        javaEngine.goForward(jp);
                        javaEngine.waitForSelector(jp, "#page2-marker", 5000L);
                        urls.add(javaEngine.url(jp));
                        return urls;
                    });
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    // ==================== 8. 渲染产物 ====================

    @Test
    @Order(8)
    void renderOutputParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            // 截图（两侧均为有效 PNG）
            byte[] nPng = nativeEngine.screenshot(np, Map.of());
            byte[] jPng = javaEngine.screenshot(jp, Map.of());
            assertPng(nPng, "原生截图");
            assertPng(jPng, "Java截图");
            byte[] nFull = nativeEngine.screenshot(np, Map.of("fullPage", true));
            byte[] jFull = javaEngine.screenshot(jp, Map.of("fullPage", true));
            assertPng(nFull, "原生全页截图");
            assertPng(jFull, "Java全页截图");

            // PDF（两侧均以 %PDF- 开头）
            String nPdf = nativeEngine.printPageToPdf(np, Map.of("format", "A4", "printBackground", true));
            String jPdf = javaEngine.printPageToPdf(jp, Map.of("format", "A4", "printBackground", true));
            assertPdf(nPdf, "原生 PDF");
            assertPdf(jPdf, "Java PDF");

            // HTML → PNG
            String html = "<html><body><h1 style=\"color:red\">PNG</h1></body></html>";
            String nImg = nativeEngine.convertHtmlToPng(np, html);
            String jImg = javaEngine.convertHtmlToPng(jp, html);
            assertPng(Base64.getDecoder().decode(nImg), "原生 HTML→PNG");
            assertPng(Base64.getDecoder().decode(jImg), "Java HTML→PNG");
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    private static void assertPng(byte[] bytes, String label) {
        assertTrue(bytes.length > 8, label + " 应非空");
        assertEquals((byte) 0x89, bytes[0], label + " 魔数");
        assertEquals((byte) 0x50, bytes[1], label + " 魔数");
        assertEquals((byte) 0x4E, bytes[2], label + " 魔数");
        assertEquals((byte) 0x47, bytes[3], label + " 魔数");
    }

    private static void assertPdf(String base64, String label) {
        byte[] pdf = Base64.getDecoder().decode(base64);
        assertTrue(pdf.length > 500, label + " 应有实质内容");
        assertEquals("%PDF-", new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1), label);
    }

    // ==================== 9. API 请求 / 批量 / 版本 ====================

    @Test
    @Order(9)
    void apiBatchVersionParity() {
        // 版本前缀一致
        assertTrue(nativeEngine.version().startsWith("1.63.0"));
        assertTrue(javaEngine.version().startsWith("1.63.0"));

        long nApi = nativeEngine.newAPIRequest();
        long jApi = javaEngine.newAPIRequest();
        assertTrue(nApi > 0);
        assertTrue(jApi > 0);

        // GET / POST 响应完全一致
        String url = base + "/api/echo?x=1";
        Engine.ApiResponseData nGet = nativeEngine.apiRequest("apiGet", url, null);
        Engine.ApiResponseData jGet = javaEngine.apiRequest("apiGet", url, null);
        assertEquals(jGet.status, nGet.status);
        assertEquals(jGet.body, nGet.body);

        Map<String, Object> payload = Map.of("k", "v");
        Engine.ApiResponseData nPost = nativeEngine.apiRequest("apipost", base + "/api/echo", payload);
        Engine.ApiResponseData jPost = javaEngine.apiRequest("apipost", base + "/api/echo", payload);
        assertEquals(jPost.status, nPost.status);
        assertEquals(jPost.body, nPost.body);

        // 未知动作消息一致
        assertSameOutcome("未知请求动作",
                () -> {
                    nativeEngine.apiRequest("banana", base + "/api/echo", null);
                    return null;
                },
                () -> {
                    javaEngine.apiRequest("banana", base + "/api/echo", null);
                    return null;
                });

        // 批量：同一序列的观测结果一致
        long np = nativeEngine.newPage(nativeBrowser);
        long jp = javaEngine.newPage(javaBrowser);
        try {
            Supplier<List<Object>> natBatch = () -> nativeEngine.batch(List.of(
                    Map.of("action", "goto", "handle", np, "params", Map.of("url", base + "/")),
                    Map.of("action", "fill", "handle", np,
                            "params", Map.of("selector", "#text", "value", "batch")),
                    Map.of("action", "evaluate", "handle", np,
                            "params", Map.of("expression",
                                    "function(){ return document.getElementById('text').value; }")),
                    Map.of("action", "click", "handle", np,
                            "params", Map.of("selector", "[data-id='btn-counter']")),
                    Map.of("action", "screenshot", "handle", np,
                            "params", Map.of("fullPage", false)),
                    Map.of("action", "close", "handle", np)), true);
            Supplier<List<Object>> javaBatch = () -> javaEngine.batch(List.of(
                    Map.of("action", "goto", "handle", jp, "params", Map.of("url", base + "/")),
                    Map.of("action", "fill", "handle", jp,
                            "params", Map.of("selector", "#text", "value", "batch")),
                    Map.of("action", "evaluate", "handle", jp,
                            "params", Map.of("expression",
                                    "function(){ return document.getElementById('text').value; }")),
                    Map.of("action", "click", "handle", jp,
                            "params", Map.of("selector", "[data-id='btn-counter']")),
                    Map.of("action", "screenshot", "handle", jp,
                            "params", Map.of("fullPage", false)),
                    Map.of("action", "close", "handle", jp)), true);
            assertEquals(norm(javaBatch.get()), norm(natBatch.get()), "批量命令结果一致");

            // 未知批量命令（stopOnError=false / true 两种模式）
            assertSameOutcome("批量未知命令(不中断)",
                    () -> {
                        List<Object> r = nativeEngine.batch(List.of(Map.of("action", "bogus")), false);
                        return ((Map<?, ?>) r.get(0)).get("error");
                    },
                    () -> {
                        List<Object> r = javaEngine.batch(List.of(Map.of("action", "bogus")), false);
                        return ((Map<?, ?>) r.get(0)).get("error");
                    });
            assertSameOutcome("批量未知命令(中断)",
                    () -> {
                        nativeEngine.batch(List.of(Map.of("action", "bogus")), true);
                        return null;
                    },
                    () -> {
                        javaEngine.batch(List.of(Map.of("action", "bogus")), true);
                        return null;
                    });
        } finally {
            nativeEngine.close(np);
            javaEngine.close(jp);
        }
    }

    // ==================== 10. 错误与关闭语义 ====================

    @Test
    @Order(10)
    void errorAndCloseParity() {
        long np = page(nativeEngine, nativeBrowser);
        long jp = page(javaEngine, javaBrowser);
        try {
            // 非法选择器：两侧均立即失败（消息由各自实现给出）
            assertBothThrow("非法选择器 click",
                    () -> {
                        nativeEngine.click(np, ">>??", Map.of());
                        return null;
                    },
                    () -> {
                        javaEngine.click(jp, ">>??", Map.of());
                        return null;
                    });
            assertBothThrow("非法选择器 querySelector",
                    () -> nativeEngine.querySelector(np, "<<<"),
                    () -> javaEngine.querySelector(jp, "<<<"));

            // 未知句柄 close：两侧静默成功
            assertSameOutcome("关闭未知句柄",
                    () -> {
                        nativeEngine.close(999_999_999L);
                        return null;
                    },
                    () -> {
                        javaEngine.close(999_999_999L);
                        return null;
                    });

            // 元素句柄关闭：后续操作两侧均失败
            long nh = nativeEngine.querySelector(np, "#btn-counter");
            long jh = javaEngine.querySelector(jp, "#btn-counter");
            assertTrue(nh > 0 && jh > 0);
            nativeEngine.close(nh);
            javaEngine.close(jh);
            assertBothThrow("关闭后元素操作",
                    () -> {
                        nativeEngine.click(nh, null, Map.of());
                        return null;
                    },
                    () -> {
                        javaEngine.click(jh, null, Map.of());
                        return null;
                    });
        } finally {
            // 页面关闭：两侧均失败
            long np2 = np;
            long jp2 = jp;
            nativeEngine.close(np2);
            javaEngine.close(jp2);
            assertBothThrow("关闭后页面读取",
                    () -> nativeEngine.title(np2),
                    () -> javaEngine.title(jp2));
        }
    }
}
