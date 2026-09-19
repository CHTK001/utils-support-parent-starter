package com.chua.deeplearning.support.audio;

import com.chua.deeplearning.support.engine.CliModelRunner;
import com.chua.deeplearning.support.translator.ITranslator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CliAsrServer} 会话选择与请求组装的离线单测。
 *
 * <p>不依赖网络、nemo-speech 二进制与 713MB GGUF：只验证开关判定、空闲阈值解析、
 * multipart 请求体字节布局、错误片段截断，以及各类"不适用"场景必须回退单进程路径
 * （返回 {@code null} 而不是抛异常）。真实端到端（常驻服务复用、进程被杀后自愈、
 * 坏音频保留会话）另由本机联调验证。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class CliAsrServerTest {

    /** 常驻服务开关属性 */
    private static final String PROP_SERVE = "deeplearning.cli.nemo-speech.serve";

    /** 空闲回收阈值属性 */
    private static final String PROP_IDLE = "deeplearning.cli.nemo-speech.serve.idleSeconds";

    /** 用例开始前已有的系统属性取值，用例结束后原样恢复 */
    private final String serveBefore = System.getProperty(PROP_SERVE);
    /** 同上 */
    private final String idleBefore = System.getProperty(PROP_IDLE);

    /**
     * 每个用例结束后恢复测试开始时保存的系统属性。
     */
    @AfterEach
    void restoreProperties() {
        restore(PROP_SERVE, serveBefore);
        restore(PROP_IDLE, idleBefore);
    }

    /**
     * 恢复单个系统属性：原值为空则清除，否则写回。
     *
     * @param key 属性名
     * @param value 用例前的原值，可为 null
     */
    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    // ── 开关与阈值 ────────────────────────────────────────────────────

    /**
     * 测试：常驻服务开关缺省启用，仅 false（忽略大小写与空白）禁用。
     *
     * @throws Exception 反射调用失败时抛出
     */
    @Test
    void serveEnabled_defaultOnAndOnlyFalseDisables() throws Exception {
        System.clearProperty(PROP_SERVE);
        assertTrue((Boolean) invokeStatic("serveEnabled"), "缺省应启用常驻服务");
        System.setProperty(PROP_SERVE, "false");
        assertEquals(Boolean.FALSE, invokeStatic("serveEnabled"));
        System.setProperty(PROP_SERVE, " FALSE ");
        assertEquals(Boolean.FALSE, invokeStatic("serveEnabled"), "取值应容忍大小写与空白");
        System.setProperty(PROP_SERVE, "true");
        assertTrue((Boolean) invokeStatic("serveEnabled"));
    }

    /**
     * 测试：空闲阈值解析秒数，非正夹到 1 秒，非法回落默认 600 秒。
     *
     * @throws Exception 反射调用失败时抛出
     */
    @Test
    void idleMillis_parsesPropertyAndClampsNonPositive() throws Exception {
        System.setProperty(PROP_IDLE, "30");
        assertEquals(30_000L, (Long) invokeStatic("idleMillis"));
        System.setProperty(PROP_IDLE, "0");
        assertEquals(1_000L, (Long) invokeStatic("idleMillis"), "非正取值应夹到 1s 而不是禁用回收");
        System.setProperty(PROP_IDLE, "abc");
        assertEquals(600_000L, (Long) invokeStatic("idleMillis"), "非法取值回落默认 600s");
        System.clearProperty(PROP_IDLE);
        assertEquals(600_000L, (Long) invokeStatic("idleMillis"));
    }

    /**
     * 测试：freePort 返回一个当前可绑定的合法端口。
     *
     * @throws Exception 反射调用或端口探测失败时抛出
     */
    @Test
    void freePort_returnsBindablePort() throws Exception {
        int port = (Integer) invokeStatic("freePort");
        assertTrue(port > 0 && port < 65536, "port=" + port);
        try (ServerSocket probe = new ServerSocket(port)) {
            assertEquals(port, probe.getLocalPort(), "该端口应未被占用");
        }
    }

    // ── multipart 请求体字节布局 ──────────────────────────────────────

    /**
     * 测试：multipart 文件段与字段段的字节布局符合表单编码。
     *
     * @throws Exception 反射调用失败时抛出
     */
    @Test
    void multipartParts_matchFormEncoding() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        invokeWriteMultipartFile(out, "BND", "file", "a.wav", new byte[]{1, 2, 3});
        invokeWriteMultipartField(out, "BND", "response_format", "text");
        // latin1 逐字节透传，便于断言二进制边界
        String body = out.toString(StandardCharsets.ISO_8859_1);
        assertEquals("--BND\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n"
                        + "Content-Type: application/octet-stream\r\n"
                        + "\r\n"
                        + "\u0001\u0002\u0003\r\n"
                        + "--BND\r\n"
                        + "Content-Disposition: form-data; name=\"response_format\"\r\n"
                        + "\r\n"
                        + "text\r\n",
                body);
        assertTrue(body.startsWith("--BND\r\n"), "首行必须是分隔符");
        assertTrue(body.endsWith("\r\n"), "末段必须以 CRLF 收尾，供闭合分隔符拼接");
    }

    /**
     * 测试：错误片段裁剪两端空白并把超长内容截断到 512 字符加省略号。
     *
     * @throws Exception 反射调用失败时抛出
     */
    @Test
    void snippet_trimsAndTruncates() throws Exception {
        assertEquals("", invokeStatic("snippet", new Class[]{String.class}, (Object) null));
        assertEquals("keep", invokeStatic("snippet", new Class[]{String.class}, "  keep  "),
                "短文本应裁掉两端空白");
        String longText = "x".repeat(900);
        String cut = (String) invokeStatic("snippet", new Class[]{String.class}, longText);
        assertEquals(512 + "...".length(), cut.length(), "超长响应体应截断到 512 字符加省略号");
        assertTrue(cut.endsWith("..."));
    }

    // ── 不适用场景必须回退，而不是抛异常 ──────────────────────────────

    /**
     * 测试：开关禁用时 acquire 返回 null，交由单进程路径。
     */
    @Test
    void acquire_returnsNullWhenDisabled() {
        System.setProperty(PROP_SERVE, "false");
        assertNull(CliAsrServer.acquire(Path.of("nemo-speech.exe"), "model.gguf"));
    }

    /**
     * 测试：CLI 进程无法启动时 acquire 返回 null 而不是抛异常。
     *
     * @param dir 临时目录
     */
    @Test
    void acquire_returnsNullWhenCliCannotStart(@TempDir Path dir) {
        System.clearProperty(PROP_SERVE);
        Path missing = dir.resolve("definitely-not-a-real-cli.exe");
        assertNull(CliAsrServer.acquire(missing, dir.resolve("model.gguf").toString()),
                "起进程失败应返回 null 让调用方回退单进程");
    }

    /**
     * 测试：模型不是本地文件或缺失时服务端转写路径跳过并返回 null。
     *
     * @param dir 临时目录
     * @throws Exception 反射调用或文件写入失败时抛出
     */
    @Test
    void transcribeViaServer_skipsUnlessModelIsALocalFile(@TempDir Path dir) throws Exception {
        Path wav = Files.write(dir.resolve("a.wav"), new byte[]{82, 73, 70, 70});
        CliAsrTranslator translator =
                new CliAsrTranslator(CliModelRunner.nemoSpeech(), "parakeet-v3");
        Path missing = dir.resolve("nope");
        assertNull(viaServer(translator, missing, wav, null), "无模型取值应走单进程");
        assertNull(viaServer(translator, missing, wav, "parakeet-tdt"), "CLI 短名不是本地文件，应走单进程");
        Path gguf = Files.write(dir.resolve("exists.gguf"), new byte[]{1});
        assertNull(viaServer(translator, missing, wav, gguf.toString()),
                "模型已本地化但 CLI 起不来时也应返回 null，交给单进程路径报错");
    }

    /**
     * 测试：空音频字节直接抛出非法参数异常。
     */
    @Test
    void translate_emptyBytes_throwsIllegalArgument() {
        ITranslator<byte[], String> translator =
                new CliAsrTranslator(CliModelRunner.nemoSpeech(), "parakeet-v3");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> translator.translate(new byte[0]));
        assertEquals("音频字节为空", ex.getMessage());
    }

    // ── reflection helpers ────────────────────────────────────────────

    /**
     * 反射调用翻译器的服务端转写私有方法。
     *
     * @param translator 待测翻译器
     * @param exe CLI 可执行文件路径
     * @param wav 音频文件路径
     * @param model 模型取值，可为 null
     * @return 转写结果或 null（表示应回退单进程）
     * @throws Exception 反射调用失败时抛出
     */
    private static Object viaServer(CliAsrTranslator translator, Path exe, Path wav, String model)
            throws Exception {
        Method m = CliAsrTranslator.class.getDeclaredMethod(
                "transcribeViaServer", Path.class, Path.class, String.class);
        m.setAccessible(true);
        return m.invoke(translator, exe, wav, model);
    }

    /**
     * 反射调用服务端写 multipart 文件段的私有方法。
     *
     * @param out 输出流
     * @param boundary 分隔符
     * @param name 表单字段名
     * @param fileName 文件名
     * @param content 文件内容字节
     * @throws Exception 反射调用失败时抛出
     */
    private static void invokeWriteMultipartFile(ByteArrayOutputStream out, String boundary, String name,
                                                 String fileName, byte[] content) throws Exception {
        method("writeMultipartFile", ByteArrayOutputStream.class, String.class, String.class, String.class,
                byte[].class).invoke(null, out, boundary, name, fileName, content);
    }

    /**
     * 反射调用服务端写 multipart 普通字段段的私有方法。
     *
     * @param out 输出流
     * @param boundary 分隔符
     * @param name 表单字段名
     * @param value 字段值
     * @throws Exception 反射调用失败时抛出
     */
    private static void invokeWriteMultipartField(ByteArrayOutputStream out, String boundary, String name,
                                                  String value) throws Exception {
        method("writeMultipartField", ByteArrayOutputStream.class, String.class, String.class, String.class)
                .invoke(null, out, boundary, name, value);
    }

    /**
     * 取 CliAsrServer 的声明方法（含私有）。
     *
     * @param name 方法名
     * @param types 参数类型列表
     * @return 反射方法句柄
     * @throws Exception 方法不存在时抛出
     */
    private static Method method(String name, Class<?>... types) throws Exception {
        Method m = CliAsrServer.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    /**
     * 反射调用 CliAsrServer 的无参静态方法。
     *
     * @param name 方法名
     * @return 方法返回值
     * @throws Exception 反射调用失败时抛出
     */
    private static Object invokeStatic(String name) throws Exception {
        return method(name).invoke(null);
    }

    /**
     * 反射调用 CliAsrServer 的带参静态方法。
     *
     * @param name 方法名
     * @param types 参数类型列表
     * @param args 实参列表
     * @return 方法返回值
     * @throws Exception 反射调用失败时抛出
     */
    private static Object invokeStatic(String name, Class<?>[] types, Object... args) throws Exception {
        return method(name, types).invoke(null, args);
    }
}
