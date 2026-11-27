package com.chua.common.support.browser.cdp;

import com.chua.common.support.browser.BrowserPage;
import com.chua.common.support.utils.StringUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CDP 实时画面嵌入服务（可交互）
 *
 * <p>无头浏览器本身没有界面，三方页面要「看到并操作」它，需要把画面推出去、
 * 把用户操作传回来。本服务把这两件事打包成一个<strong>可直接嵌入的地址</strong>：</p>
 * <ul>
 *   <li>画面：CDP screencast → MJPEG 流，展示在内置查看器页面里；</li>
 *   <li>交互：查看器把鼠标移动 / 点击 / 滚轮 / 键盘 / 粘贴事件回传，服务端用
 *       CDP {@code Input.*} 派发给无头浏览器，等效于直接操作那个页面。</li>
 * </ul>
 *
 * <p>三方前端<strong>零 JS 接入</strong>——拿到地址后用一个 iframe 嵌入即可：</p>
 * <pre>{@code
 * // 后端：把三方传来的目标地址换成可嵌入地址（可缓存复用同一 token）
 * CdpLiveServer live = CdpLiveServer.start(18080);
 * String embedUrl = live.view("https://example.com");
 * // 返回给前端：embedUrl
 * }</pre>
 * <pre>{@code
 * <!-- 三方前端：一行 iframe，画面实时、可点击、可输入、可滚动 -->
 * <iframe src="http://host:18080/live/s/xxxx" style="width:100%;height:600px"></iframe>
 * }</pre>
 *
 * <p>说明：</p>
 * <ul>
 *   <li>默认返回 {@code http://localhost:端口} 形式的地址；部署在网关或其它主机后面时，
 *       用 {@link #baseUrl(String)} 改成对外可见的地址前缀（路径不变）。</li>
 *   <li>每个嵌入地址对应一个独立标签页；同一地址重复打开 MJPEG 流时旧流自动让位。</li>
 *   <li>会话空闲（无画面观看、无操作）超过 {@link #idleTimeout(java.time.Duration)} 会被自动回收。</li>
 *   <li>拿到嵌入地址即可操作对应浏览器，请自行在网关层做鉴权。</li>
 *   <li>本类是可选的便捷封装：只需要只读画面时，用 {@link CdpMjpegStream} + 自己的
 *       Controller 更轻；本服务不依赖 Spring，可嵌入任意 Java 进程。</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0.51
 */
@Slf4j
public final class CdpLiveServer implements AutoCloseable {

    /**
     * 查看器页面：画面 + 交互脚本；占位符为相对地址，网关转发时无需改写
     */
    private static final String VIEWER_HTML = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>远程浏览器</title>
            <style>
            html,body{margin:0;padding:0;height:100%;background:#0b0f14;overflow:hidden}
            #stage{position:relative;width:100vw;height:100vh;outline:none;cursor:default;touch-action:none}
            #screen{position:absolute;inset:0;width:100%;height:100%;object-fit:contain;
                    user-select:none;-webkit-user-drag:none;pointer-events:none}
            </style>
            </head>
            <body>
            <div id="stage" tabindex="0"><img id="screen" src="__STREAM__" alt=""></div>
            <script>
            (function(){
              var stage=document.getElementById('stage');
              var img=document.getElementById('screen');
              var INPUT='__INPUT__';
              var STREAM=img.getAttribute('src');
              // 目标页面视口（CSS 像素）。screencast 帧会被降采样，帧坐标必须按
              // 「视口/帧尺寸」放大后才能发给 CDP Input，否则点击/滚轮整体偏向左上角。
              var VW=__VW__, VH=__VH__;
              stage.focus();
              function scale(){
                var r=img.getBoundingClientRect();
                var nw=img.naturalWidth||VW||1280, nh=img.naturalHeight||VH||900;
                var s=Math.min(r.width/nw,r.height/nh)||1;
                return {s:s, ox:r.left+(r.width-nw*s)/2, oy:r.top+(r.height-nh*s)/2, nw:nw, nh:nh};
              }
              // 帧坐标 → 页面 CSS 坐标的换算系数（帧被降采样时大于 1；视口未知按 1:1）
              function cssFactor(g){
                if(VW>0&&VH>0&&g.nw>0&&g.nh>0){ return {fx:VW/g.nw, fy:VH/g.nh}; }
                return {fx:1, fy:1};
              }
              function pos(e){
                var g=scale(), f=cssFactor(g);
                var x=Math.round((e.clientX-g.ox)/g.s*f.fx), y=Math.round((e.clientY-g.oy)/g.s*f.fy);
                var mw=f.fx>1?VW:g.nw, mh=f.fy>1?VH:g.nh;
                return {x:Math.max(0,Math.min(mw,x)), y:Math.max(0,Math.min(mh,y))};
              }
              var last=0, timer=null, pending=null;
              function throttle(e){
                pending=e;
                if(timer) return;
                timer=setTimeout(function(){ timer=null; send('mm',pos(pending)); }, 40);
              }
              // 串行化上报：mousedown/mouseup 走不同连接时可能乱序到达，服务端按到达序
              // 派发，乱序会把「按下→抬起」颠倒成无效点击。用 promise 链保证前一个
              // 事件有结果后才发下一个；单事件 3s 兜底，避免一次挂起卡死后续输入。
              var chain=Promise.resolve();
              function send(type,data){
                var body='t='+type;
                for(var k in data){ body+='&'+k+'='+encodeURIComponent(data[k]); }
                // 高频事件（移动/滚轮）不排队：它们各自带坐标，乱序无害，排队反而推高延迟
                if(type==='mm'||type==='wh'){
                  fetch(INPUT,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
                    .catch(function(){});
                  return;
                }
                chain=chain.then(function(){
                  return new Promise(function(resolve){
                    var done=false;
                    var finish=function(){ if(!done){ done=true; resolve(); } };
                    fetch(INPUT,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
                      .then(finish,finish);
                    setTimeout(finish,3000);
                  });
                });
              }
              var KEYS=['Enter','Backspace','Delete','Tab','Escape','ArrowUp','ArrowDown',
                        'ArrowLeft','ArrowRight','Home','End','PageUp','PageDown'];
              stage.addEventListener('mousedown',function(e){
                e.preventDefault(); stage.focus(); send('md',pos(e));
              });
              stage.addEventListener('mouseup',function(e){ e.preventDefault(); send('mu',pos(e)); });
              stage.addEventListener('mousemove',throttle);
              stage.addEventListener('wheel',function(e){
                e.preventDefault();
                // 滚轮位移与坐标同理：要换算成页面 CSS 像素（帧被降采样时放大 fx/fy 倍）
                var p=pos(e), g=scale(), f=cssFactor(g);
                send('wh',{x:p.x,y:p.y,dx:e.deltaX*f.fx/g.s,dy:e.deltaY*f.fy/g.s});
              },{passive:false});
              stage.addEventListener('contextmenu',function(e){ e.preventDefault(); });
              stage.addEventListener('keydown',function(e){
                var k=e.key;
                if(k===' '){ e.preventDefault(); send('kd',{key:'space'}); return; }
                if(k.length===1){ e.preventDefault(); send('txt',{text:k}); return; }
                if(KEYS.indexOf(k)>=0){ e.preventDefault(); send('kd',{key:k.toLowerCase()}); }
              });
              stage.addEventListener('paste',function(e){
                var t=e.clipboardData&&e.clipboardData.getData('text');
                if(t){ e.preventDefault(); send('txt',{text:t}); }
              });
              // 流自愈：首帧迟迟不到或连接报错时，加缓存参数重连；拿到画面后不再抢流。
              // 不能用 img.complete 判定成功——加载失败时它同样为 true，404 流会因此永不重试；
              // 这里用 naturalWidth>0 判定「已拿到帧」。
              var backoff=1000, trying=false;
              function reconnect(){
                if(trying||img.naturalWidth>0){ return; }
                trying=true;
                img.src=STREAM+(STREAM.indexOf('?')<0?'?':'&')+'r='+Date.now();
                setTimeout(function(){
                  trying=false;
                  if(img.naturalWidth===0){ backoff=Math.min(backoff*2,8000); reconnect(); }
                },backoff);
              }
              img.addEventListener('error',function(){
                if(!trying){ setTimeout(reconnect,300); }
              });
              setTimeout(function(){ reconnect(); },3000);
            })();
            </script>
            </body>
            </html>
            """;

    /**
     * 会话空闲回收周期（秒）
     */
    private static final long REAPER_PERIOD_SECONDS = 60L;

    /**
     * HTTP 服务
     */
    private volatile HttpServer server;

    /**
     * 空闲会话回收器
     */
    private volatile ScheduledExecutorService reaper;

    /**
     * 实际监听端口
     */
    private volatile int port;

    /**
     * 对外地址前缀
     */
    private volatile String baseUrlPrefix;

    /**
     * 会话空闲超时（毫秒）
     */
    private volatile long idleTimeoutMillis = 30L * 60L * 1000L;

    /**
     * 全部会话：token → 会话
     */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * 线程序号
     */
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    /**
     * 禁止外部实例化，统一走 {@link #start(int)}
     */
    private CdpLiveServer() {
    }

    /**
     * 启动嵌入服务（随机可用端口）
     *
     * @return 服务实例
     * @throws IOException 端口监听失败
     */
    public static CdpLiveServer start() throws IOException {
        return start(0);
    }

    /**
     * 启动嵌入服务
     *
     * @param port 监听端口，0 表示随机可用端口
     * @return 服务实例
     * @throws IOException 端口监听失败
     */
    public static CdpLiveServer start(int port) throws IOException {
        CdpLiveServer live = new CdpLiveServer();
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/live", live::handle);
        // [P3C C1 修复] 显式线程池替代 Executors 缓存池（有界队列语义等价：SynchronousQueue + 60s 回收）
        httpServer.setExecutor(new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "chua-cdp-live-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }));
        httpServer.start();
        live.server = httpServer;
        live.port = httpServer.getAddress().getPort();
        live.baseUrlPrefix = "http://localhost:" + live.port;
        // [P3C C1 修复] 显式线程池替代 Executors 包装
        live.reaper = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "chua-cdp-live-reaper");
            thread.setDaemon(true);
            return thread;
        });
        live.reaper.scheduleAtFixedRate(live::reapIdleSessions,
                REAPER_PERIOD_SECONDS, REAPER_PERIOD_SECONDS, TimeUnit.SECONDS);
        log.info("[cdp-live] 实时画面嵌入服务已启动: {}", live.baseUrlPrefix);
        return live;
    }

    /**
     * 为目标地址创建（或复用）一个可嵌入的查看地址
     *
     * <p>每次调用都会打开一个新的无头浏览器标签页并导航到 {@code url}；
     * 返回值直接放进三方页面的 {@code <iframe src="...">} 即可。</p>
     *
     * @param url 目标地址，如 {@code https://example.com}；不能为空
     * @return 可嵌入地址，如 {@code http://localhost:18080/live/s/xxxx}
     * @throws IllegalArgumentException 地址为空
     * @throws IllegalStateException    服务已关闭或页面打开 / 导航失败
     */
    public String view(String url) {
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("目标地址 url 不能为空");
        }
        ensureRunning();
        return view(Cdp.open(url), UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * 把调用方自备的页面注册为可嵌入的查看地址
     *
     * <p>适用于调用方要自管浏览器页面生命周期的场景（如先注入脚本、再导航）。
     * 页面归调用方所有：本服务只负责把画面推出去、把用户操作传回来；会话被
     * 空闲回收或经 {@link #close(String)} 关闭时会调用 {@code page.close()}，
     * 调用方的页面关闭逻辑应当幂等。</p>
     *
     * @param page  目标页面，不能为空
     * @param token 会话令牌（路径标识），建议直接用调用方的会话 id，便于统一回收
     * @return 可嵌入地址，如 {@code http://localhost:18080/live/s/xxxx}
     * @throws IllegalArgumentException 页面或令牌为空
     * @throws IllegalStateException    服务已关闭
     */
    public String view(BrowserPage page, String token) {
        if (page == null || StringUtils.isBlank(token)) {
            throw new IllegalArgumentException("页面 page 与令牌 token 不能为空");
        }
        ensureRunning();
        sessions.put(token, new Session(token, page));
        return baseUrl() + "/live/s/" + token;
    }

    /**
     * 关闭并移除一个会话（幂等；令牌不存在时无效果）
     *
     * @param token 会话令牌
     */
    public void close(String token) {
        if (StringUtils.isBlank(token)) {
            return;
        }
        Session session = sessions.get(token);
        if (session != null) {
            closeSession(session);
        }
    }

    /**
     * 实际监听端口
     *
     * @return 端口（{@link #start(int)} 传 0 时为随机分配的端口）
     */
    public int port() {
        return port;
    }

    /**
     * 设置对外地址前缀
     *
     * <p>默认 {@code http://localhost:端口}。服务部署在网关、代理或其它主机后面时，
     * 传入外部可见的前缀（如 {@code https://gw.example.com/browser}），
     * {@link #view(String)} 返回的地址会以其开头。</p>
     *
     * @param baseUrl 地址前缀，结尾的 {@code /} 会被忽略
     * @return 当前实例，支持链式调用
     */
    public CdpLiveServer baseUrl(String baseUrl) {
        if (StringUtils.isNotBlank(baseUrl)) {
            this.baseUrlPrefix = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
        }
        return this;
    }

    /**
     * 设置会话空闲回收时间
     *
     * @param idleTimeout 空闲超时；页面在超时内无画面观看、无操作即被关闭
     * @return 当前实例，支持链式调用
     */
    public CdpLiveServer idleTimeout(java.time.Duration idleTimeout) {
        if (idleTimeout != null && !idleTimeout.isNegative() && !idleTimeout.isZero()) {
            this.idleTimeoutMillis = idleTimeout.toMillis();
        }
        return this;
    }

    /**
     * 关闭服务并回收全部会话页面
     *
     * <p>幂等：重复调用无效果。共享浏览器进程由 {@link Cdp#close()} 单独管理。</p>
     */
    @Override
    public void close() {
        ScheduledExecutorService reaper = this.reaper;
        this.reaper = null;
        if (reaper != null) {
            reaper.shutdownNow();
        }
        HttpServer httpServer = this.server;
        this.server = null;
        if (httpServer != null) {
            httpServer.stop(0);
        }
        sessions.values().forEach(this::closeSession);
        log.info("[cdp-live] 实时画面嵌入服务已关闭");
    }

    /**
     * 请求总入口
     *
     * @param exchange HTTP 交换
     */
    private void handle(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        try {
            if ("/live/view".equals(path)) {
                handleDirectView(exchange);
            } else if (path.startsWith("/live/s/")) {
                handleSession(exchange, path.substring("/live/s/".length()));
            } else {
                respondText(exchange, 404, "not found");
            }
        } catch (Exception e) {
            log.debug("[cdp-live] 处理请求失败: {}", e.getMessage());
            try {
                respondText(exchange, 500, "internal error");
            } catch (Exception ignored) {
                // 响应已提交或连接已断开
            }
        }
    }

    /**
     * 处理 {@code GET /live/view?target=...}：直接创建会话并跳转到查看器
     *
     * @param exchange HTTP 交换
     * @throws IOException 写响应失败
     */
    private void handleDirectView(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseForm(exchange.getRequestURI().getRawQuery());
        String embed = view(query.getOrDefault("target", ""));
        exchange.getResponseHeaders().set("Location", embed);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    /**
     * 处理会话级请求：查看器页面 / MJPEG 流 / 输入事件
     *
     * @param exchange HTTP 交换
     * @param rest     {@code /live/s/} 之后的路径（token 或 token/stream、token/input）
     * @throws IOException 写响应失败
     */
    private void handleSession(HttpExchange exchange, String rest) throws IOException {
        String token;
        String action;
        if (rest.endsWith("/stream")) {
            token = rest.substring(0, rest.length() - "/stream".length());
            action = "stream";
        } else if (rest.endsWith("/input")) {
            token = rest.substring(0, rest.length() - "/input".length());
            action = "input";
        } else {
            token = rest;
            action = "page";
        }
        Session session = sessions.get(token);
        if (session == null) {
            respondText(exchange, 404, "session not found");
            return;
        }
        touch(session);
        switch (action) {
            case "page" -> handleViewerPage(exchange, session);
            case "stream" -> handleStream(exchange, session);
            case "input" -> handleInput(exchange, session);
            default -> respondText(exchange, 404, "not found");
        }
    }

    /**
     * 输出查看器页面
     *
     * @param exchange HTTP 交换
     * @param session  会话
     * @throws IOException 写响应失败
     */
    private void handleViewerPage(HttpExchange exchange, Session session) throws IOException {
        int[] viewport = viewport(session.page);
        String html = VIEWER_HTML
                .replace("__STREAM__", "/live/s/" + session.token + "/stream")
                .replace("__INPUT__", "/live/s/" + session.token + "/input")
                .replace("__VW__", String.valueOf(viewport[0]))
                .replace("__VH__", String.valueOf(viewport[1]));
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } finally {
            exchange.close();
        }
    }

    /**
     * 读取页面视口尺寸（CSS 像素）
     *
     * <p>查看器用它把「降采样帧坐标」换算回「页面 CSS 坐标」再交给 CDP Input，
     * 否则点击/滚轮会整体偏向左上角（帧被降采样到多大比例就偏多少）。
     * 页面尚未就绪或查询失败时返回 0，查看器自动退回 1:1 映射。</p>
     *
     * @param page 目标页面
     * @return {@code [宽, 高]}，失败时两个元素均为 0
     */
    private static int[] viewport(BrowserPage page) {
        try {
            String raw = page.evaluate("(function(){return (window.innerWidth||0)+'x'+(window.innerHeight||0);})()");
            if (raw != null) {
                int sep = raw.indexOf('x');
                if (sep > 0) {
                    return new int[]{Integer.parseInt(raw.substring(0, sep).trim()),
                            Integer.parseInt(raw.substring(sep + 1).trim())};
                }
            }
        } catch (Exception e) {
            log.debug("[cdp-live] 读取页面视口失败: {}", e.getMessage());
        }
        return new int[]{0, 0};
    }

    /**
     * 输出 MJPEG 实时画面流
     *
     * <p>同一会话重复观看时，旧流自动停止，画面跟随最新观看者。
     * 客户端断开由写失败感知，随后只停画面推送，页面保留供下次观看。</p>
     *
     * @param exchange HTTP 交换
     * @param session  会话
     * @throws IOException 写响应头失败
     */
    private void handleStream(HttpExchange exchange, Session session) throws IOException {
        CdpMjpegStream stream;
        synchronized (session.lock) {
            CdpMjpegStream previous = session.stream.getAndSet(null);
            if (previous != null) {
                previous.close();
            }
            stream = CdpMjpegStream.start(session.page);
            session.stream.set(stream);
        }
        exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=frame");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            stream.transferTo(out);
        } catch (IOException e) {
            log.debug("[cdp-live] 实时画面连接中断: {}", e.getMessage());
        } finally {
            stream.close();
            synchronized (session.lock) {
                if (session.stream.get() == stream) {
                    session.stream.set(null);
                }
            }
            exchange.close();
        }
    }

    /**
     * 处理查看器回传的交互事件
     *
     * <p>同一会话的事件串行派发，保证「按下→释放」等顺序不被 HTTP 并发打乱。</p>
     *
     * @param exchange HTTP 交换
     * @param session  会话
     * @throws IOException 写响应失败
     */
    private void handleInput(HttpExchange exchange, Session session) throws IOException {
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        String type = form.getOrDefault("t", "");
        try {
            synchronized (session.lock) {
                dispatch(session.page, type, form);
            }
        } catch (Exception e) {
            log.debug("[cdp-live] 派发交互事件失败: {}", e.getMessage());
        } finally {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        }
    }

    /**
     * 把一个交互事件派发给页面
     *
     * @param page  目标页面
     * @param type  事件类型：mm/md/mu/wh/kd/txt
     * @param form  表单参数
     */
    private static void dispatch(BrowserPage page, String type, Map<String, String> form) {
        switch (type) {
            case "mm" -> page.mouseMove(intValue(form, "x"), intValue(form, "y"));
            case "md" -> page.mouseDown(intValue(form, "x"), intValue(form, "y"));
            case "mu" -> page.mouseUp(intValue(form, "x"), intValue(form, "y"));
            case "wh" -> {
                // 不再每次滚动都 bringToFront：那是同步 CDP 往返（实测 14~21ms），
                // 滚轮会被拖成逐格卡顿；页面在开流时已激活，后台标签页由调用方自己保证
                page.mouseWheel(intValue(form, "x"), intValue(form, "y"),
                        doubleValue(form, "dx"), doubleValue(form, "dy"));
            }
            case "kd" -> {
                String key = form.getOrDefault("key", "");
                if (key.length() == 1) {
                    page.insertText(key);
                } else if (!key.isEmpty()) {
                    page.pressKey(key);
                }
            }
            case "txt" -> page.insertText(form.getOrDefault("text", ""));
            default -> {
                // 未知事件忽略，保持前向兼容
            }
        }
    }

    /**
     * 回收空闲会话
     */
    private void reapIdleSessions() {
        long deadline = System.currentTimeMillis() - idleTimeoutMillis;
        sessions.values().forEach(session -> {
            if (session.lastActivity < deadline) {
                log.debug("[cdp-live] 回收空闲会话: {}", session.token);
                closeSession(session);
            }
        });
    }

    /**
     * 关闭并移除一个会话
     *
     * @param session 会话
     */
    private void closeSession(Session session) {
        if (sessions.remove(session.token) == null) {
            return;
        }
        CdpMjpegStream stream = session.stream.getAndSet(null);
        if (stream != null) {
            stream.close();
        }
        try {
            session.page.close();
        } catch (Exception e) {
            log.debug("[cdp-live] 关闭会话页面失败: {}", e.getMessage());
        }
    }

    /**
     * 更新会话活跃时间
     *
     * @param session 会话
     */
    private static void touch(Session session) {
        session.lastActivity = System.currentTimeMillis();
    }

    /**
     * 校验服务仍在运行
     */
    private void ensureRunning() {
        if (server == null) {
            throw new IllegalStateException("CdpLiveServer 已关闭");
        }
    }

    /**
     * 拼接对外地址
     *
     * @return 形如 {@code http://localhost:18080} 的前缀（无结尾斜杠）
     */
    private String baseUrl() {
        String prefix = baseUrlPrefix;
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    /**
     * 输出纯文本响应
     *
     * @param exchange HTTP 交换
     * @param status   状态码
     * @param message  文本
     * @throws IOException 写响应失败
     */
    private static void respondText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } finally {
            exchange.close();
        }
    }

    /**
     * 解析查询串或表单体
     *
     * @param raw 原始文本（{@code a=1&b=2} 形式），可为空
     * @return 参数表
     */
    private static Map<String, String> parseForm(String raw) {
        var result = new LinkedHashMap<String, String>();
        if (StringUtils.isEmpty(raw)) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                result.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        URLDecoder.decode(value, StandardCharsets.UTF_8));
            } catch (Exception e) {
                result.put(key, value);
            }
        }
        return result;
    }

    /**
     * 读取整数参数
     *
     * @param form         参数表
     * @param name         参数名
     * @return 整数值，缺失或非法返回 0
     */
    private static int intValue(Map<String, String> form, String name) {
        try {
            return (int) Math.round(doubleValue(form, name));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 读取数值参数
     *
     * @param form         参数表
     * @param name         参数名
     * @return 数值，缺失或非法返回 0
     */
    private static double doubleValue(Map<String, String> form, String name) {
        try {
            return Double.parseDouble(form.getOrDefault(name, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 一个可嵌入的浏览会话
     */
    private static final class Session {

        /**
         * 会话令牌（路径标识）
         */
        private final String token;

        /**
         * 目标页面
         */
        private final BrowserPage page;

        /**
         * 事件派发与流切换的锁
         */
        private final Object lock = new Object();

        /**
         * 当前 MJPEG 流
         */
        private final AtomicReference<CdpMjpegStream> stream = new AtomicReference<>();

        /**
         * 最近活跃时间
         */
        private volatile long lastActivity = System.currentTimeMillis();

        /**
         * 构造会话
         *
         * @param token 会话令牌
         * @param page  目标页面
         */
        private Session(String token, BrowserPage page) {
            this.token = token;
            this.page = page;
        }
    }
}
