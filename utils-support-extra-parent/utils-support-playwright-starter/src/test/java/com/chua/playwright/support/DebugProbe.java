package com.chua.playwright.support;

import com.chua.playwright.support.engine.Engine;

import java.util.List;
import java.util.Map;

/**
 * 交互行为诊断探针（main 入口，surefire 不匹配 *Test 不会执行）。
 *
 * <p>逐一探查：点击全部可点击元素后的 data-count、checkbox/radio/aria 勾选状态时间线、
 * 元素解析与命中测试、hover 与 tooltip 布局。</p>
 */
public final class DebugProbe {

    private DebugProbe() {
    }

    private static Object ev(Engine e, long p, String expr) {
        return e.evaluate(p, expr, null);
    }

    private static Object evEl(Engine e, long h, String expr) {
        return e.evaluate(h, expr, null);
    }

    public static void main(String[] args) throws Exception {
        String chrome = Fixture.resolveChrome();
        var server = Fixture.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Engine e = Playwright.getEngine();
        System.out.println("native=" + Playwright.isNative() + " chrome=" + chrome);
        long b = e.launch(true, chrome, List.of());

        // ---- A: 逐个点击 + 逐个 data-count ----
        long p = e.newPage(b);
        e.gotoPage(p, base + "/", null);
        Object ids = ev(e, p, Fixture.CLICKABLE_IDS_JS);
        System.out.println("A ids=" + ids);
        for (Object id : (List<?>) ids) {
            String sel = "[data-clickable][data-id='" + id + "']";
            try {
                e.click(p, sel, Map.of());
                System.out.println("A clicked " + id);
            } catch (Exception ex) {
                System.out.println("A CLICK FAIL " + id + ": " + ex.getMessage());
            }
        }
        for (Object id : (List<?>) ids) {
            String sel = "[data-clickable][data-id='" + id + "']";
            try {
                System.out.println("A count " + id + " = " + e.getAttribute(p, sel, "data-count"));
            } catch (Exception ex) {
                System.out.println("A GETATTR FAIL " + id + ": " + ex.getMessage());
            }
        }
        e.close(p);

        // ---- B: checkbox 时间线 ----
        p = e.newPage(b);
        e.gotoPage(p, base + "/", null);
        System.out.println("B cb(getElementById)=" + ev(e, p,
                "function(){ return document.getElementById('cb').checked; }"));
        long h = e.querySelector(p, "#cb");
        System.out.println("B handle=" + h);
        if (h > 0) {
            System.out.println("B resolved=" + evEl(e, h,
                    "function(el){ return {id: el.id, type: el.type, checked: !!el.checked}; }"));
        }
        try {
            e.check(p, "#cb", false);
            System.out.println("B check(cb,false) OK");
        } catch (Exception ex) {
            System.out.println("B check(cb,false) EX: " + ex.getMessage());
        }
        System.out.println("B clickLog=" + ev(e, p, "function(){ return window.__clickLog; }"));
        System.out.println("B cb immediate=" + ev(e, p,
                "function(){ return document.getElementById('cb').checked; }"));
        Thread.sleep(300);
        System.out.println("B cb after300ms=" + ev(e, p,
                "function(){ return document.getElementById('cb').checked; }"));
        e.close(p);

        // ---- C: radio 时间线 ----
        p = e.newPage(b);
        e.gotoPage(p, base + "/", null);
        long rh = e.querySelector(p, "#r2");
        System.out.println("C r2 rect=" + evEl(e, rh,
                "function(el){ var r = el.getBoundingClientRect();"
                        + " var hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);"
                        + " return {x: r.x, y: r.y, w: r.width, h: r.height, sy: window.scrollY,"
                        + " vh: window.innerHeight, hit: hit ? (hit.id || hit.tagName) : null}; }"));
        try {
            e.check(p, "#r2", true);
            System.out.println("C check(r2,true) OK");
        } catch (Exception ex) {
            System.out.println("C check(r2,true) EX: " + ex.getMessage());
        }
        System.out.println("C clickLog=" + ev(e, p, "function(){ return window.__clickLog; }"));
        System.out.println("C r1=" + ev(e, p, "function(){ return document.getElementById('r1').checked; }")
                + " r2=" + ev(e, p, "function(){ return document.getElementById('r2').checked; }"));
        Thread.sleep(300);
        System.out.println("C after300ms r1=" + ev(e, p, "function(){ return document.getElementById('r1').checked; }")
                + " r2=" + ev(e, p, "function(){ return document.getElementById('r2').checked; }"));
        e.close(p);

        // ---- D: aria switch ----
        p = e.newPage(b);
        e.gotoPage(p, base + "/", null);
        try {
            e.check(p, "#sw", true);
            System.out.println("D check(sw,true) OK");
        } catch (Exception ex) {
            System.out.println("D check(sw,true) EX: " + ex.getMessage());
        }
        System.out.println("D sw aria=" + e.getAttribute(p, "#sw", "aria-checked"));
        e.close(p);

        // ---- E: hover / tip ----
        p = e.newPage(b);
        e.gotoPage(p, base + "/", null);
        long hh = e.querySelector(p, "#hov");
        System.out.println("E hov=" + evEl(e, hh,
                "function(el){ var r = el.getBoundingClientRect();"
                        + " var hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);"
                        + " return {x: r.x, y: r.y, w: r.width, h: r.height, sy: window.scrollY,"
                        + " tip: getComputedStyle(document.getElementById('tip')).display,"
                        + " hit: hit ? (hit.id || hit.tagName) : null}; }"));
        try {
            e.hover(p, "#hov");
            System.out.println("E hover OK");
        } catch (Exception ex) {
            System.out.println("E hover EX: " + ex.getMessage());
        }
        System.out.println("E tip after=" + ev(e, p,
                "function(){ return getComputedStyle(document.getElementById('tip')).display; }"));
        e.close(p);

        e.close(b);
        server.stop(0);
        System.exit(0);
    }
}
