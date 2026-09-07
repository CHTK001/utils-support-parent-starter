package com.chua.remote.controller;

import com.chua.remote.core.codec.FrameCodec;
import com.chua.remote.core.transport.RemoteTransport;
import com.chua.remote.protocol.frame.Frame;
import com.chua.remote.protocol.frame.MessageType;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.ext.web.Router;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class WebControllerServer {

    private final int port;
    private final String gatewayUrl;
    private final Vertx vertx;
    private HttpServer httpServer;
    private final Map<String, GatewayBridge> bridges = new ConcurrentHashMap<>();

    public WebControllerServer(int port, String gatewayUrl) {
        this.port = port;
        this.gatewayUrl = gatewayUrl;
        this.vertx = Vertx.vertx();
    }

    public void start() {
        Router router = Router.router(vertx);
        router.route("/").handler(ctx -> ctx.redirect("/index.html"));
        router.route("/index.html").handler(ctx -> {
            ctx.response().putHeader("Content-Type", "text/html; charset=utf-8")
                    .end(INDEX_HTML);
        });
        router.route("/verify").handler(ctx -> {
            ctx.request().bodyHandler(buffer -> {
                String body = buffer.toString();
                proxyVerify(ctx, body);
            });
        });

        httpServer = vertx.createHttpServer();
        httpServer.webSocketHandler(this::handleWebSocket);
        httpServer.requestHandler(router);
        httpServer.listen(port, "0.0.0.0", ar -> {
            if (ar.succeeded()) {
                log.info("Web控制端已启动: port={}, gateway={}", port, gatewayUrl);
            } else {
                log.error("Web控制端启动失败", ar.cause());
            }
        });
    }

    public void stop() {
        bridges.values().forEach(GatewayBridge::close);
        bridges.clear();
        if (httpServer != null) {
            httpServer.close();
        }
        vertx.close();
    }

    private void handleWebSocket(ServerWebSocket ws) {
        String bridgeId = ws.textHandlerID();
        log.info("浏览器WebSocket已连接: id={}", bridgeId);

        GatewayBridge bridge = new GatewayBridge(bridgeId, ws);
        bridges.put(bridgeId, bridge);

        ws.binaryMessageHandler(buffer -> bridge.onBrowserBinary(buffer));
        ws.textMessageHandler(text -> bridge.onBrowserText(text));
        ws.closeHandler(v -> {
            bridge.close();
            bridges.remove(bridgeId);
            log.info("浏览器WebSocket已断开: id={}", bridgeId);
        });
        ws.exceptionHandler(e -> log.warn("浏览器WebSocket异常: id={}", bridgeId, e));
    }

    private void proxyVerify(io.vertx.ext.web.RoutingContext ctx, String body) {
        vertx.executeBlocking(promise -> {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(gatewayUrl + "/verify"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
                java.net.http.HttpResponse<String> resp = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                promise.complete(resp);
            } catch (Exception e) {
                promise.fail(e);
            }
        }, ar -> {
            if (ar.succeeded()) {
                java.net.http.HttpResponse<String> resp =
                        (java.net.http.HttpResponse<String>) ar.result();
                ctx.response().putHeader("Content-Type", "application/json")
                        .end(resp.body());
            } else {
                ctx.response().setStatusCode(500)
                        .end("{\"success\":false,\"message\":\"" + ar.cause().getMessage() + "\"}");
            }
        });
    }

    private static class GatewayBridge {
        private final String id;
        private final ServerWebSocket browserWs;
        private RemoteTransport gatewayTransport;
        private volatile boolean connected;

        GatewayBridge(String id, ServerWebSocket browserWs) {
            this.id = id;
            this.browserWs = browserWs;
        }

        void connectToGateway(String clientId, String serverUrl) {
            if (connected) return;
            this.gatewayTransport = new RemoteTransport(clientId, serverUrl);
            gatewayTransport.on(MessageType.DATA, this::onGatewayData);
            gatewayTransport.on(MessageType.SSH, this::onGatewaySSH);
            gatewayTransport.on(MessageType.SIGNAL, this::onGatewaySignal);
            gatewayTransport.connect();
            connected = true;
            log.info("已连接到网关: bridgeId={}, clientId={}", id, clientId);
        }

        void onBrowserBinary(io.vertx.core.buffer.Buffer buffer) {
            if (!connected || gatewayTransport == null) return;
            byte[] bytes = buffer.getBytes();
            Frame frame = FrameCodec.decode(bytes);
            if (frame != null) {
                gatewayTransport.send(frame);
            }
        }

        void onBrowserText(String text) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                Map<String, Object> msg = mapper.readValue(text, Map.class);
                String action = (String) msg.get("action");
                if ("connect".equals(action)) {
                    String clientId = (String) msg.get("clientId");
                    String serverUrl = (String) msg.getOrDefault("serverUrl", gatewayUrl);
                    connectToGateway(clientId, serverUrl);
                } else if ("ssh".equals(action)) {
                    handleSSHFromBrowser(msg);
                } else if ("ctrl".equals(action)) {
                    handleCtrlFromBrowser(msg);
                }
            } catch (Exception e) {
                log.debug("浏览器文本消息解析失败: {}", e.getMessage());
            }
        }

        void onGatewayData(Frame frame) {
            byte[] encoded = FrameCodec.encode(frame);
            browserWs.writeBinaryMessage(io.vertx.core.buffer.Buffer.buffer(encoded));
        }

        void onGatewaySSH(Frame frame) {
            try {
                Map<String, String> meta = frame.getMetadata() != null ? frame.getMetadata() : Map.of();
                String stream = meta.getOrDefault("stream", "stdout");
                String data = "";
                if (frame.getPayload() != null && frame.getPayload().length > 0) {
                    data = new String(frame.getPayload(), StandardCharsets.UTF_8);
                }
                Map<String, Object> msg = Map.of(
                        "type", "ssh",
                        "stream", stream,
                        "data", data,
                        "sshSessionId", meta.getOrDefault("sshSessionId", ""),
                        "sshAction", meta.getOrDefault("sshAction", "output"));
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                browserWs.writeTextMessage(mapper.writeValueAsString(msg));
            } catch (Exception e) {
                log.debug("SSH帧转JSON失败", e);
            }
        }

        void onGatewaySignal(Frame frame) {
            try {
                Map<String, String> meta = frame.getMetadata() != null ? frame.getMetadata() : Map.of();
                Map<String, Object> msg = Map.of(
                        "type", "signal",
                        "kind", meta.getOrDefault("kind", ""),
                        "sessionId", frame.getSessionId() != null ? frame.getSessionId() : "");
                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                browserWs.writeTextMessage(mapper.writeValueAsString(msg));
            } catch (Exception e) {
                log.debug("信令帧转JSON失败", e);
            }
        }

        void handleSSHFromBrowser(Map<String, Object> msg) {
            if (!connected || gatewayTransport == null) return;
            String sessionId = (String) msg.getOrDefault("sessionId", id);
            String sshAction = (String) msg.getOrDefault("sshAction", "start");
            Map<String, String> meta = new ConcurrentHashMap<>();
            meta.put("sshAction", sshAction);
            meta.put("sshSessionId", sessionId);
            if (msg.containsKey("host")) meta.put("host", (String) msg.get("host"));
            if (msg.containsKey("port")) meta.put("port", String.valueOf(msg.get("port")));
            if (msg.containsKey("username")) meta.put("username", (String) msg.get("username"));
            if (msg.containsKey("password")) meta.put("password", (String) msg.get("password"));
            if (msg.containsKey("cols")) meta.put("cols", String.valueOf(msg.get("cols")));
            if (msg.containsKey("rows")) meta.put("rows", String.valueOf(msg.get("rows")));
            byte[] payload = new byte[0];
            if (msg.containsKey("data")) {
                payload = Base64.getDecoder().decode((String) msg.get("data"));
            }
            Frame frame = FrameCodec.sshFrame(sessionId, payload, meta);
            gatewayTransport.send(frame);
        }

        void handleCtrlFromBrowser(Map<String, Object> msg) {
            if (!connected || gatewayTransport == null) return;
            String sessionId = (String) msg.getOrDefault("sessionId", id);
            byte[] payload = new byte[0];
            if (msg.containsKey("data")) {
                payload = Base64.getDecoder().decode((String) msg.get("data"));
            }
            Map<String, String> meta = new ConcurrentHashMap<>();
            msg.forEach((k, v) -> {
                if (!"action".equals(k) && !"data".equals(k) && !"sessionId".equals(k)) {
                    meta.put(k, String.valueOf(v));
                }
            });
            Frame frame = Frame.builder()
                    .type(MessageType.CTRL)
                    .sessionId(sessionId)
                    .payload(payload)
                    .metadata(meta)
                    .build();
            gatewayTransport.send(frame);
        }

        void close() {
            if (gatewayTransport != null) {
                gatewayTransport.disconnect();
            }
            connected = false;
        }
    }

    private static final String INDEX_HTML = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <title>远控控制端</title>
                <style>
                    *{box-sizing:border-box;margin:0;padding:0}
                    body{font-family:'Segoe UI',sans-serif;background:#1a1a2e;color:#e0e0e0;height:100vh;display:flex;flex-direction:column}
                    .header{background:#16213e;padding:12px 24px;display:flex;align-items:center;gap:16px;border-bottom:1px solid #0f3460}
                    .header h2{color:#e94560;font-size:18px}
                    .main{flex:1;display:flex;overflow:hidden}
                    .sidebar{width:280px;background:#16213e;padding:16px;border-right:1px solid #0f3460;display:flex;flex-direction:column;gap:12px;overflow-y:auto}
                    .sidebar label{font-size:12px;color:#8892b0;margin-bottom:2px;display:block}
                    .sidebar input,.sidebar select{width:100%;padding:8px;background:#0f3460;border:1px solid #233554;color:#e0e0e0;border-radius:4px;font-size:13px}
                    .sidebar input:focus,.sidebar select:focus{outline:none;border-color:#e94560}
                    .sidebar .check-row{display:flex;align-items:center;gap:8px}
                    .sidebar .check-row input[type=checkbox]{width:auto}
                    .btn{padding:10px;border:none;border-radius:4px;cursor:pointer;font-size:13px;font-weight:600}
                    .btn-primary{background:#e94560;color:#fff}.btn-primary:hover{background:#c81d4e}
                    .btn-danger{background:#ff4757;color:#fff}.btn-danger:hover{background:#d63031}
                    .btn-sm{padding:6px 12px;font-size:12px}
                    .status{padding:8px;border-radius:4px;font-size:12px;text-align:center}
                    .status.ok{background:#0f3460;color:#00b894}.status.err{background:#0f3460;color:#e94560}
                    .content{flex:1;display:flex;flex-direction:column;overflow:hidden}
                    .tab-bar{display:flex;background:#16213e;border-bottom:1px solid #0f3460}
                    .tab{padding:10px 20px;cursor:pointer;font-size:13px;color:#8892b0;border-bottom:2px solid transparent}
                    .tab.active{color:#e94560;border-bottom-color:#e94560}
                    .tab-content{flex:1;display:none;overflow:hidden}.tab-content.active{display:flex;flex-direction:column}
                    #terminal{flex:1;background:#0c0c0c;padding:12px;font-family:'Cascadia Code','Fira Code',monospace;font-size:13px;overflow-y:auto;white-space:pre-wrap;color:#00ff41}
                    #terminal .prompt{color:#e94560}.#terminal .output{color:#e0e0e0}.#terminal .error{color:#ff4757}
                    #desktop{flex:1;display:flex;align-items:center;justify-content:center;background:#0c0c0c}
                    #desktop canvas{max-width:100%;max-height:100%;object-fit:contain}
                    #desktop .placeholder{color:#555;font-size:14px}
                    .input-bar{display:flex;background:#16213e;border-top:1px solid #0f3460;padding:8px}
                    .input-bar input{flex:1;padding:8px;background:#0f3460;border:1px solid #233554;color:#e0e0e0;border-radius:4px;font-family:monospace}
                    .input-bar input:focus{outline:none;border-color:#e94560}
                </style>
            </head>
            <body>
                <div class="header">
                    <h2>Remote Control</h2>
                    <div id="connStatus" class="status">未连接</div>
                </div>
                <div class="main">
                    <div class="sidebar">
                        <div>
                            <label>被控端 ID</label>
                            <input type="text" id="agentId" placeholder="agent-xxx">
                        </div>
                        <div>
                            <label>验证码</label>
                            <input type="text" id="verifyCode" placeholder="0000">
                        </div>
                        <div>
                            <label>连接方式</label>
                            <select id="connMethod">
                                <option value="ssh">SSH (WebSocket)</option>
                                <option value="rdp">远程桌面 (WebRTC)</option>
                            </select>
                        </div>
                        <div class="check-row">
                            <input type="checkbox" id="reverseTunnel">
                            <label style="margin:0">开启反向隧道</label>
                        </div>
                        <button class="btn btn-primary" onclick="doConnect()" id="btnConnect">连接</button>
                        <button class="btn btn-danger" onclick="doDisconnect()" id="btnDisconnect" disabled>断开</button>
                        <div id="verifyResult" style="display:none;padding:8px;border-radius:4px;font-size:12px"></div>
                    </div>
                    <div class="content">
                        <div class="tab-bar">
                            <div class="tab active" onclick="switchTab('ssh',this)">SSH 终端</div>
                            <div class="tab" onclick="switchTab('desktop',this)">远程桌面</div>
                        </div>
                        <div id="tab-ssh" class="tab-content active">
                            <div id="terminal"></div>
                            <div class="input-bar">
                                <input type="text" id="cmdInput" placeholder="输入命令..." onkeydown="if(event.key==='Enter')sendCmd()">
                                <button class="btn btn-sm btn-primary" onclick="sendCmd()" style="margin-left:8px">发送</button>
                            </div>
                        </div>
                        <div id="tab-desktop" class="tab-content">
                            <div id="desktop"><span class="placeholder">等待远程桌面数据...</span></div>
                        </div>
                    </div>
                </div>
                <script>
                let ws = null;
                let sshSessionId = null;
                const term = document.getElementById('terminal');
                const cmdInput = document.getElementById('cmdInput');
                const canvas = document.createElement('canvas');
                const ctx = canvas.getContext('2d');
                const desktopDiv = document.getElementById('desktop');

                function switchTab(name, el) {
                    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                    document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
                    el.classList.add('active');
                    document.getElementById('tab-' + name).classList.add('active');
                }

                function showResult(ok, msg) {
                    const el = document.getElementById('verifyResult');
                    el.style.display = 'block';
                    el.style.background = ok ? '#0f3460' : '#0f3460';
                    el.style.color = ok ? '#00b894' : '#e94560';
                    el.textContent = msg;
                }

                async function doConnect() {
                    const agentId = document.getElementById('agentId').value;
                    const verifyCode = document.getElementById('verifyCode').value;
                    const connMethod = document.getElementById('connMethod').value;
                    const reverseTunnel = document.getElementById('reverseTunnel').checked;
                    if (!agentId || !verifyCode) { showResult(false, '请填写ID和验证码'); return; }
                    try {
                        const resp = await fetch('/verify', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                            body: 'agentId=' + encodeURIComponent(agentId)
                                + '&verifyCode=' + encodeURIComponent(verifyCode)
                                + '&reverseTunnelEnabled=' + reverseTunnel
                        });
                        const data = await resp.json();
                        if (!data.success) { showResult(false, '验证失败: ' + data.message); return; }
                        showResult(true, '验证通过! 连接中...');
                        ws = new WebSocket('ws://' + location.host + '/ws');
                        ws.binaryType = 'arraybuffer';
                        ws.onopen = () => {
                            ws.send(JSON.stringify({action:'connect', clientId: agentId, serverUrl: data.gatewayWsUrl || 'ws://localhost:9000'}));
                            document.getElementById('connStatus').textContent = '已连接';
                            document.getElementById('connStatus').className = 'status ok';
                            document.getElementById('btnConnect').disabled = true;
                            document.getElementById('btnDisconnect').disabled = false;
                            if (connMethod === 'ssh') {
                                sshSessionId = 'ssh-' + Date.now();
                                ws.send(JSON.stringify({
                                    action: 'ssh', sshAction: 'start', sessionId: sshSessionId,
                                    host: data.targetHost || '127.0.0.1', port: data.targetPort || 22,
                                    username: data.targetUsername || '', password: data.targetPassword || '',
                                    cols: Math.floor(term.clientWidth / 8), rows: Math.floor(term.clientHeight / 16)
                                }));
                            }
                        };
                        ws.onmessage = (e) => {
                            if (typeof e.data === 'string') {
                                const msg = JSON.parse(e.data);
                                if (msg.type === 'ssh') handleSSHMessage(msg);
                                else if (msg.type === 'signal') handleSignalMessage(msg);
                            } else {
                                handleBinaryFrame(new Uint8Array(e.data));
                            }
                        };
                        ws.onclose = () => {
                            document.getElementById('connStatus').textContent = '已断开';
                            document.getElementById('connStatus').className = 'status err';
                            document.getElementById('btnConnect').disabled = false;
                            document.getElementById('btnDisconnect').disabled = true;
                        };
                    } catch(ex) { showResult(false, '异常: ' + ex.message); }
                }

                function doDisconnect() {
                    if (sshSessionId && ws) {
                        ws.send(JSON.stringify({action:'ssh', sshAction:'stop', sessionId: sshSessionId}));
                    }
                    if (ws) { ws.close(); ws = null; }
                    sshSessionId = null;
                    document.getElementById('connStatus').textContent = '未连接';
                    document.getElementById('connStatus').className = 'status';
                    document.getElementById('btnConnect').disabled = false;
                    document.getElementById('btnDisconnect').disabled = true;
                }

                function handleSSHMessage(msg) {
                    if (msg.sshAction === 'output' || msg.stream) {
                        const span = document.createElement('span');
                        span.className = msg.stream === 'stderr' ? 'error' : 'output';
                        span.textContent = msg.data;
                        term.appendChild(span);
                        term.scrollTop = term.scrollHeight;
                    }
                }

                function handleSignalMessage(msg) {
                    console.log('signal:', msg);
                }

                function handleBinaryFrame(bytes) {
                    if (bytes.length > 2 && bytes[0] === 0xFF && bytes[1] === 0xD8) {
                        renderJPEG(bytes);
                    }
                }

                function renderJPEG(bytes) {
                    const blob = new Blob([bytes], {type:'image/jpeg'});
                    const url = URL.createObjectURL(blob);
                    const img = new Image();
                    img.onload = () => {
                        if (canvas.width !== img.width || canvas.height !== img.height) {
                            canvas.width = img.width;
                            canvas.height = img.height;
                            desktopDiv.innerHTML = '';
                            desktopDiv.appendChild(canvas);
                        }
                        ctx.drawImage(img, 0, 0);
                        URL.revokeObjectURL(url);
                    };
                    img.src = url;
                }

                function sendCmd() {
                    const cmd = cmdInput.value;
                    if (!cmd || !ws || !sshSessionId) return;
                    ws.send(JSON.stringify({action:'ssh', sshAction:'input', sessionId: sshSessionId,
                        data: btoa(unescape(encodeURIComponent(cmd + '\\n')))}));
                    const prompt = document.createElement('span');
                    prompt.className = 'prompt';
                    prompt.textContent = '$ ' + cmd + '\\n';
                    term.appendChild(prompt);
                    cmdInput.value = '';
                }
                </script>
            </body>
            </html>
            """;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String gatewayHttp = args.length > 1 ? args[1] : "http://localhost:9001";
        String gatewayWs = args.length > 2 ? args[2] : "ws://localhost:9000";
        WebControllerServer server = new WebControllerServer(port, gatewayHttp);
        server.start();
    }
}
