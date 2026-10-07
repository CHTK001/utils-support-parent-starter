package com.chua.playwright.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 生产级验收测试的本地 fixture：HTTP 服务 + 可点击元素页面 + 浏览器定位。
 *
 * <p>页面覆盖每一个可点击的按钮 / 图标类型：SVG 图标按钮、emoji 图标按钮、
 * 标准按钮（计数 / 双击 / 开关 / 悬停 / 表单提交）、链接、checkbox / radio /
 * switch / menuitemcheckbox / treeitem 角色、各类表单控件与延迟出现元素。</p>
 *
 * @author CH
 * @since 4.0.0
 */
final class Fixture {

    /**
     * 浏览器候选路径（按优先级）
     */
    private static final String[] CHROME_CANDIDATES = {
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files\\Google\\Chrome Dev\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixture() {
    }

    /**
     * 解析本机可用的 Chromium 系浏览器。
     *
     * @return 第一个存在的候选路径；全缺时返回默认路径（由调用方断言存在性）
     */
    static String resolveChrome() {
        for (String candidate : CHROME_CANDIDATES) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        return CHROME_CANDIDATES[0];
    }

    /**
     * 启动本地 fixture HTTP 服务（随机端口）。
     *
     * @return 已启动的服务
     * @throws IOException 启动失败
     */
    static HttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/page2".equals(path)) {
                respond(exchange, 200, pageTwoHtml());
            } else {
                respond(exchange, 200, mainPageHtml());
            }
        });
        server.createContext("/api/echo", exchange -> {
            String method = exchange.getRequestMethod();
            String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, 200, method + "|" + query + "|" + body);
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    /**
     * 主 fixture 页面。
     *
     * @return HTML
     */
    static String mainPageHtml() {
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>Fixture Home</title>
                <style>
                  body{margin:0;font-family:sans-serif}
                  .toolbar{display:flex;gap:4px;padding:8px;background:#222}
                  .toolbar button{background:transparent;border:1px solid #555;color:#fff;padding:6px;cursor:pointer}
                  .card{padding:8px}
                  input,textarea,select{display:block;margin:6px 0}
                  #tip{display:none;margin:4px;color:#c00}
                  .long{height:2000px}
                </style>
                </head><body>
                <h1 id="heading">Fixture Home</h1>

                <div class="toolbar" id="toolbar">
                  <button data-clickable data-id="icon-save" aria-label="save"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M2 2h9l3 3v9H2z" fill="currentColor"/></svg></button>
                  <button data-clickable data-id="icon-edit" aria-label="edit"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M2 12l8-8 2 2-8 8H2z" fill="currentColor"/></svg></button>
                  <button data-clickable data-id="icon-delete" aria-label="delete"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M4 4h8v8H4z" fill="currentColor"/></svg></button>
                  <button data-clickable data-id="icon-search" aria-label="search"><svg width="16" height="16" viewBox="0 0 16 16"><circle cx="7" cy="7" r="4" fill="none" stroke="currentColor" stroke-width="2"/><path d="M10 10l4 4" stroke="currentColor" stroke-width="2"/></svg></button>
                  <button data-clickable data-id="icon-star" aria-label="star"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M8 2l2 4 4 .5-3 3 .8 4L8 11.5 4.2 13.5 5 9.5 2 6.5 6 6z" fill="currentColor"/></svg></button>
                  <button data-clickable data-id="icon-share" aria-label="share"><svg width="16" height="16" viewBox="0 0 16 16"><path d="M3 8h10M9 4l4 4-4 4" fill="none" stroke="currentColor" stroke-width="2"/></svg></button>
                </div>

                <div id="emojis" class="card">
                  <button data-clickable data-id="emoji-star">&#11088;</button>
                  <button data-clickable data-id="emoji-note">&#128221;</button>
                  <button data-clickable data-id="emoji-search">&#128269;</button>
                  <button data-clickable data-id="emoji-upload">&#128228;</button>
                </div>

                <div id="std" class="card">
                  <button data-clickable data-id="btn-counter" id="counter">counter</button>
                  <button data-clickable data-id="btn-dbl" id="dbl">dbl</button>
                  <button data-clickable data-id="btn-toggle" id="toggle" aria-pressed="false">toggle</button>
                  <button data-clickable data-id="btn-hover" id="hoverbtn">hover</button>
                  <button id="disabled-btn" disabled>disabled</button>
                  <form id="form"><input id="name" value="pre"><button type="submit" data-clickable data-id="btn-submit">submit</button></form>
                  <a href="/page2" id="navlink" data-link="page2">page2</a>
                </div>

                <div id="checks" class="card">
                  <label><input type="checkbox" id="cb" checked> cb</label>
                  <label><input type="checkbox" id="cb2"> cb2</label>
                  <label><input type="radio" name="r" id="r1" value="a" checked> a</label>
                  <label><input type="radio" name="r" id="r2" value="b"> b</label>
                  <div id="sw" role="switch" aria-checked="false" data-clickable>switch</div>
                  <div id="mi" role="menuitemcheckbox" aria-checked="true" data-clickable>menuitem</div>
                  <div id="tree1" role="treeitem" aria-checked="false" data-clickable>tree</div>
                </div>

                <div id="forms" class="card">
                  <input id="text" value="prefilled">
                  <input id="num" type="number" value="42">
                  <input id="color" type="color" value="#ff0000">
                  <input id="date" type="date" value="2024-01-15">
                  <input id="range" type="range" min="0" max="100" value="30">
                  <textarea id="area">area-text</textarea>
                  <div id="editor" contenteditable="true">editable</div>
                  <div id="static">not editable</div>
                  <select id="sel">
                    <option value="o1">One</option>
                    <option value="o2">Two</option>
                    <option value="o3">Three</option>
                  </select>
                  <select id="msel" multiple>
                    <option value="m1">M1</option>
                    <option value="m2">M2</option>
                    <option value="m3">M3</option>
                  </select>
                </div>

                <div id="tip">tip shown</div>
                <span id="hov">hover target</span>

                <div id="dynamic-slot"></div>
                <div class="long">spacer</div>

                <script>
                window.__clicks = {};
                document.querySelectorAll('[data-clickable]').forEach(function(el){
                  el.addEventListener('click', function(){
                    window.__clicks[el.dataset.id] = (window.__clicks[el.dataset.id] || 0) + 1;
                    el.dataset.count = window.__clicks[el.dataset.id];
                  });
                });
                document.getElementById('dbl').addEventListener('dblclick', function(){
                  window.__dbl = (window.__dbl || 0) + 1;
                });
                document.getElementById('toggle').addEventListener('click', function(){
                  var on = this.getAttribute('aria-pressed') === 'true';
                  this.setAttribute('aria-pressed', on ? 'false' : 'true');
                });
                var tip = document.getElementById('tip');
                document.getElementById('hov').addEventListener('mouseover', function(){ tip.style.display = 'block'; });
                document.getElementById('hov').addEventListener('mouseout', function(){ tip.style.display = 'none'; });
                document.getElementById('form').addEventListener('submit', function(e){ e.preventDefault(); });
                document.getElementById('navlink').addEventListener('click', function(e){ /* 允许默认跳转 */ });
                setTimeout(function(){
                  var d = document.createElement('div');
                  d.id = 'delayed'; d.textContent = 'later';
                  document.getElementById('dynamic-slot').appendChild(d);
                }, 300);
                </script>
                </body></html>
                """;
    }

    /**
     * 第二页（导航 / 前进后退用）。
     *
     * @return HTML
     */
    static String pageTwoHtml() {
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><title>Fixture Page Two</title></head>
                <body><h1 id="page2-marker">Page Two</h1></body></html>
                """;
    }

    /**
     * 将对象序列化为 JSON（仅测试内部使用）。
     *
     * @param value 任意对象
     * @return JSON 串
     * @throws IOException 序列化失败
     */
    static String json(Object value) throws IOException {
        return MAPPER.writeValueAsString(value);
    }

    /**
     * fixture 可点击元素数量（与页面结构同步演进）。
     */
    static final int EXPECTED_CLICKABLES = 18;

    /**
     * 提取页面 id 列表的 JS 表达式。
     */
    static final String CLICKABLE_IDS_JS =
            "function(){ return Array.prototype.map.call("
                    + "document.querySelectorAll('[data-clickable]'), function(el){ return el.dataset.id; }); }";

    /**
     * 读取全局点击计数的 JS 表达式。
     */
    static final String CLICKS_JS = "function(){ return window.__clicks; }";

    /**
     * 元素列表（供批量施加动作）。
     *
     * @return id 列表常量
     */
    static List<String> clickableIds() {
        return List.of(
                "icon-save", "icon-edit", "icon-delete", "icon-search", "icon-star", "icon-share",
                "emoji-star", "emoji-note", "emoji-search", "emoji-upload",
                "btn-counter", "btn-dbl", "btn-toggle", "btn-hover", "btn-submit",
                "sw", "mi", "tree1");
    }
}
