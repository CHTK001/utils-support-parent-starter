package com.chua.deeplearning.support.audio;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * nemo-speech 常驻 HTTP 服务（{@code serve} 子命令）会话。
 *
 * <p>每次转写新起一个 CLI 进程要重新付掉引擎 warmup 与 713MB 模型的缺页加载：冷文件缓存下本机
 * 实测单次 13–25s，热文件缓存下中位约 3.9s；常驻服务把它挪到进程启动一次，稳态同一 9.2s 音频
 * 约 2.1s（RTF≈0.23）。内存吃紧、文件缓存被挤掉时，两者差距会回到前者那种量级。</p>
 *
 * <p>会话按「CLI 可执行文件 + 模型本地路径」缓存复用，进程意外退出或空闲超时后自动重建；
 * 启动失败时调用方回退到单进程 {@code transcribe}，不影响正确性。服务只绑定 127.0.0.1，
 * 端口由临时 {@link ServerSocket} 申请，避免与宿主上其他服务冲突。</p>
 *
 * <p>开关：系统属性 {@code deeplearning.cli.nemo-speech.serve=false} 可强制退回每次新起进程；
 * {@code deeplearning.cli.nemo-speech.serve.idleSeconds} 调整空闲回收阈值（默认 600 秒）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
final class CliAsrServer {

    /** 常驻服务开关的系统属性名 */
    private static final String PROP_SERVE = "deeplearning.cli.nemo-speech.serve";

    /** 空闲回收阈值的系统属性名 */
    private static final String PROP_IDLE = "deeplearning.cli.nemo-speech.serve.idleSeconds";

    /** 会话就绪等待上限（秒） */
    private static final long READY_TIMEOUT_SECONDS = 90L;

    /** 健康探测间隔（毫秒） */
    private static final long READY_POLL_MILLIS = 500L;

    /** 单次转写请求超时（秒） */
    private static final long REQUEST_TIMEOUT_SECONDS = 120L;

    /** 默认空闲回收阈值（秒） */
    private static final long DEFAULT_IDLE_SECONDS = 600L;

    /** 错误响应体截断长度 */
    private static final int ERROR_SNIPPET_CHARS = 512;

    /** 会话缓存，键 = CLI 可执行文件 + 模型本地路径 */
    private static final Map<String, CliAsrServer> SESSIONS = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(CliAsrServer::shutdownAll, "cli-asr-serve-shutdown"));
    }

    /** 会话缓存键分隔符（不会出现在路径中） */
    private static final char KEY_DELIMITER = '\n';

    /** CLI 可执行文件 */
    private final Path exe;

    /** 模型本地路径 */
    private final String modelPath;

    /** 监听端口（仅 127.0.0.1） */
    private final int port;

    /** 服务进程 */
    private final Process process;

    /** 最近一次成功使用时间戳 */
    private volatile long lastUsedMillis;

    /**
     * 创建并等待服务就绪
     *
     * @param exe       CLI 可执行文件
     * @param modelPath 模型本地路径（GGUF）
     * @throws IOException          端口申请、进程启动或就绪等待失败
     * @throws InterruptedException 等待就绪被中断
     */
    private CliAsrServer(Path exe, String modelPath) throws IOException, InterruptedException {
        this.exe = exe;
        this.modelPath = modelPath;
        this.port = freePort();
        this.process = startProcess(exe, modelPath, port);
        this.lastUsedMillis = System.currentTimeMillis();
        awaitReady();
        log.info("[cli-asr-serve] 常驻服务就绪: {} (port={}, model={})", exe.getFileName(), port, modelPath);
    }

    /**
     * 取得可用会话；开关关闭或启动失败返回 {@code null}，由调用方回退单进程调用
     *
     * @param exe       CLI 可执行文件
     * @param modelPath 模型本地路径
     * @return 就绪的会话；不可用时为 {@code null}
     */
    static CliAsrServer acquire(Path exe, String modelPath) {
        if (!serveEnabled()) {
            return null;
        }
        String key = exe.toString() + KEY_DELIMITER + modelPath;
        CliAsrServer existing = SESSIONS.get(key);
        if (existing != null && existing.isUsable()) {
            return existing;
        }
        if (existing != null) {
            SESSIONS.remove(key, existing);
            existing.shutdown();
        }
        CliAsrServer created;
        try {
            created = new CliAsrServer(exe, modelPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[cli-asr-serve] 启动常驻服务被中断，回退单进程调用");
            return null;
        } catch (Exception e) {
            log.warn("[cli-asr-serve] 启动常驻服务失败，回退单进程调用: {}", e.getMessage());
            return null;
        }
        CliAsrServer winner = SESSIONS.putIfAbsent(key, created);
        if (winner != null) {
            created.shutdown();
            return winner;
        }
        return created;
    }

    /**
     * 通过常驻服务转写音频
     *
     * @param wav        本地 WAV 文件（PCM16）
     * @param jsonOutput 是否请求结构化输出（{@code verbose_json}）
     * @return 转写文本或 JSON 文本
     * @throws IOException 请求失败；仅传输层故障会丢弃会话，服务返回的错误状态保留会话
     */
    String transcribe(Path wav, boolean jsonOutput) throws IOException {
        byte[] audio = Files.readAllBytes(wav);
        String boundary = "----cli-asr-" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream(audio.length + 512);
        writeMultipartFile(body, boundary, "file", wav.getFileName().toString(), audio);
        writeMultipartField(body, boundary, "response_format", jsonOutput ? "verbose_json" : "text");
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        String url = "http://127.0.0.1:" + port + "/v1/audio/transcriptions";
        try {
            String text = post(url, boundary, body.toByteArray());
            lastUsedMillis = System.currentTimeMillis();
            return text;
        } catch (ServeException e) {
            // 服务已应答（含 5xx，nemo-speech 把音频格式问题也报 500），进程健在，保留会话
            throw e;
        } catch (IOException e) {
            discard();
            throw e;
        }
    }

    /**
     * 会话是否仍可复用（进程存活、未被标记损坏、未超空闲阈值）
     *
     * @return 可用返回 true
     */
    private boolean isUsable() {
        if (process == null || !process.isAlive()) {
            return false;
        }
        long idleLimit = idleMillis();
        return System.currentTimeMillis() - lastUsedMillis <= idleLimit;
    }

    /**
     * 丢弃当前会话（请求失败后调用，下次重新起进程）
     */
    private void discard() {
        SESSIONS.values().remove(this);
        shutdown();
    }

    /**
     * 停止服务进程
     */
    private void shutdown() {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        log.info("[cli-asr-serve] 常驻服务已停止: {} (port={})", exe.getFileName(), port);
    }

    /**
     * 停止全部会话（JVM 退出时）
     */
    private static void shutdownAll() {
        for (CliAsrServer session : SESSIONS.values()) {
            session.shutdown();
        }
        SESSIONS.clear();
    }

    /**
     * 拉起 serve 子进程
     *
     * @param exe       CLI 可执行文件
     * @param modelPath 模型本地路径
     * @param port      监听端口
     * @return 已启动的进程
     * @throws IOException 启动失败
     */
    private static Process startProcess(Path exe, String modelPath, int port) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                exe.toString(), "serve",
                "--asr-model", modelPath,
                "--host", "127.0.0.1",
                "--port", Integer.toString(port),
                "--no-ui");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        return process;
    }

    /**
     * 轮询 /health 直到就绪
     *
     * @throws IOException          服务启动过程中退出或返回异常
     * @throws InterruptedException 等待被中断
     * @throws IllegalStateException  超时未就绪
     */
    private void awaitReady() throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(READY_TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("serve 进程已退出 (exit=" + process.exitValue() + ")");
            }
            if (healthOk()) {
                return;
            }
            Thread.sleep(READY_POLL_MILLIS);
        }
        throw new IllegalStateException("serve 就绪超时 " + READY_TIMEOUT_SECONDS + "s: " + exe);
    }

    /**
     * 健康探测
     *
     * @return 返回 200 视为就绪
     */
    private boolean healthOk() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/health").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(2000);
            return conn.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 发送 multipart POST 并取回响应体
     *
     * @param url      请求地址
     * @param boundary multipart 分隔符
     * @param body     请求体
     * @return 响应文本
     * @throws IOException 非 200 或 IO 失败
     */
    private static String post(String url, String boundary, byte[] body) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout((int) TimeUnit.SECONDS.toMillis(REQUEST_TIMEOUT_SECONDS));
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            String text = readBody(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code != HttpURLConnection.HTTP_OK) {
                throw new ServeException("serve 返回 " + code + ": " + snippet(text));
            }
            return text;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 写入文件表单项
     *
     * @param out      请求体缓冲
     * @param boundary 分隔符
     * @param name     字段名
     * @param fileName 文件名
     * @param content  文件字节
     */
    private static void writeMultipartFile(ByteArrayOutputStream out, String boundary, String name,
                                           String fileName, byte[] content) {
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);
        out.write(head, 0, head.length);
        out.write(content, 0, content.length);
        out.write(crlf, 0, crlf.length);
    }

    /**
     * 写入普通表单项
     *
     * @param out      请求体缓冲
     * @param boundary 分隔符
     * @param name     字段名
     * @param value    取值
     */
    private static void writeMultipartField(ByteArrayOutputStream out, String boundary, String name, String value) {
        byte[] part = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(part, 0, part.length);
    }

    /**
     * 读取响应流
     *
     * @param in 流，可为空
     * @return UTF-8 文本
     * @throws IOException 读取失败
     */
    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 申请一个空闲端口
     *
     * @return 端口号
     * @throws IOException 申请失败
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 常驻服务是否启用
     *
     * @return 启用返回 true
     */
    private static boolean serveEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(PROP_SERVE, "true").trim());
    }

    /**
     * 空闲回收阈值
     *
     * @return 毫秒
     */
    private static long idleMillis() {
        String raw = System.getProperty(PROP_IDLE);
        long seconds = DEFAULT_IDLE_SECONDS;
        if (raw != null && !raw.isBlank()) {
            try {
                seconds = Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                log.warn("[cli-asr-serve] {} 非法取值 {}，沿用默认 {}s", PROP_IDLE, raw, DEFAULT_IDLE_SECONDS);
            }
        }
        return TimeUnit.SECONDS.toMillis(Math.max(1L, seconds));
    }

    /**
     * 截断错误响应体
     *
     * @param text 原文
     * @return 片段
     */
    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= ERROR_SNIPPET_CHARS ? trimmed : trimmed.substring(0, ERROR_SNIPPET_CHARS) + "...";
    }

    /**
     * 服务已应答但状态非 200。与传输层故障区分：能收到响应即说明进程健在，会话可继续复用
     */
    private static final class ServeException extends IOException {

        /**
         * 构造服务错误
         *
         * @param message 错误详情（含 HTTP 状态码与响应体片段）
         */
        private ServeException(String message) {
            super(message);
        }
    }
}
