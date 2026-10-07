package com.chua.deeplearning.support.audio;

import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.deeplearning.support.engine.CliModelRunner;
import com.chua.deeplearning.support.engine.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * parakeet-v3 生产级验收。
 *
 * <p>分两层，避免把 680MB 模型变成构建硬前置：</p>
 * <ul>
 *   <li><b>契约层</b>（注册/SPI/入参校验/能力清单/settings 链式）——始终执行，不依赖模型与 CLI</li>
 *   <li><b>推理层</b>（CLI 定位/模型命中/真实转写）——需前置就绪，否则 abort 跳过并说明原因，
 *       保证无网/无模型的机器仍能构建。强制执行：{@code -Dparakeet.asr.test=true}</li>
 * </ul>
 *
 * <p>前置就绪判定：CLI 位于 {@code clis/nemo-speech/bin/nemo-speech[.exe]}，
 * 模型位于 {@code download/parakeet-v3/*.gguf}，音频为 {@code %TEMP%/parakeet-test.wav}
 * （三者均在 {@code %TEMP%/chua-dl-models} 或 tmp 下，可由 {@code deeplearning.model.cache-dir} 重定向）。</p>
 */
class CliAsrParakeetV3Test {

    /**
     * 测试音频（16kHz 单声道 PCM16 WAV）
     */
    private static final Path AUDIO = Paths.get(System.getProperty("java.io.tmpdir"), "parakeet-test.wav");

    /**
     * 强制执行推理层（默认缺失前置即跳过）
     */
    private static final String FORCE = "parakeet.asr.test";

    // ============ 契约层：不依赖模型/CLI，始终执行 ============

    @Test
    void registered() {
        ModelRegistry.discoverAll();
        ModelRegistry.Entry e = ModelRegistry.get("parakeet-v3");
        assertNotNull(e, "parakeet-v3 未注册");
        assertEquals(String.class, e.outputType());
        assertEquals(byte[].class, e.inputType());
        assertNotNull(e.downloadUrl());
        assertTrue(e.downloadUrl().endsWith(".gguf"), e.downloadUrl());
        assertNotNull(e.downloadMirrors(), "缺少备用镜像");
        assertFalse(e.downloadMirrors().isEmpty(), "备用镜像为空");
    }

    @Test
    void spiAliases() {
        for (String alias : new String[]{"parakeet", "parakeet-v3", "nemo-speech", "nemo-speech-asr"}) {
            VirtualClient c = VirtualClient.create(alias, "");
            assertNotNull(c, "SPI 别名未注册: " + alias);
            assertInstanceOf(CliAsrAudioClient.class, c, alias);
            c.close();
        }
    }

    @Test
    void modelsList() {
        try (VirtualClient c = VirtualClient.create("parakeet-v3", "")) {
            assertFalse(c.models().isEmpty());
            assertEquals("parakeet-v3", c.models().get(0).getId());
        }
    }

    @Test
    void settingsChain() {
        AudioClientSetting setting = AudioClientSetting.builder().provider("parakeet-v3").build();
        assertEquals("parakeet-v3", setting.getProvider());
        try (VirtualClient c = VirtualClient.create("parakeet-v3", "")) {
            assertSame(c, c.model("parakeet-v3"));
            assertSame(c, c.language("en"));
            assertSame(c, c.sampleRate(16000));
            assertSame(c, c.format("wav"));
            assertSame(c, c.audio(AUDIO));
        }
    }

    /**
     * 空/缺失音频必须在调用 CLI 前就抛错（快速失败，不依赖模型）
     */
    @Test
    void emptyAudioRejected() {
        CliAsrTranslator t = new CliAsrTranslator(CliModelRunner.nemoSpeech(), "parakeet-v3");
        assertThrows(IllegalArgumentException.class, () -> t.translate(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> t.translate(null));
    }

    /**
     * 未配置音频输入时 {@code queryTask} 应失败而不是 NPE
     */
    @Test
    void queryTaskWithoutAudio() {
        try (VirtualClient c = VirtualClient.create("parakeet-v3", "")) {
            String taskId = c.createTask(null);
            assertNotNull(taskId);
            AudioResponse resp = c.queryTask(taskId);
            assertNotNull(resp);
            assertEquals(AudioResponse.Status.FAILED, resp.getStatus());
            assertNotNull(resp.getErrorMessage());
        }
    }

    /**
     * 未提供音频时 transcribe 必须抛异常
     */
    @Test
    void transcribeWithoutAudio() {
        try (VirtualClient c = VirtualClient.create("parakeet-v3", "")) {
            assertThrows(RuntimeException.class, () -> c.transcribe(null));
        }
    }

    @Test
    void translatorName() {
        CliAsrTranslator t = new CliAsrTranslator(CliModelRunner.nemoSpeech(), "parakeet-v3");
        assertTrue(t.name().contains("parakeet-v3"), t.name());
    }

    /**
     * 工具方法 {@code CliAsrTranslator.utf8}：null 安全
     */
    @Test
    void utf8Helper() {
        assertArrayEquals(new byte[0], CliAsrTranslator.utf8(null));
        assertArrayEquals("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8), CliAsrTranslator.utf8("abc"));
    }

    /**
     * JSON 输出构造器的 name 形态（与纯文本构造器区分）
     */
    @Test
    void jsonOutputConstructor() {
        CliAsrTranslator t = new CliAsrTranslator(CliModelRunner.nemoSpeech(), "parakeet-v3", true);
        assertTrue(t.name().contains("nemo-speech"), t.name());
        assertTrue(t.name().contains("parakeet-v3"), t.name());
    }

    // ============ 契约层：其余可调用入口（链式无副作用/工具方法） ============

    /**
     * 链式 setter 全覆盖：必须返回自身（链式可编排），且不得抛异常
     */
    @Test
    void allChainSettersReturnThis() {
        try (VirtualClient c = VirtualClient.create("parakeet-v3", "")) {
            assertSame(c, c.sampleRate(16000));
            assertSame(c, c.format("wav"));
            assertSame(c, c.prompt("p"));
            assertSame(c, c.temperature(0.0));
            assertSame(c, c.seed(42L));
            assertSame(c, c.language("en"));
            assertSame(c, c.model("parakeet-v3"));
        }
    }

    /**
     * 三种音频注入入口（byte[] / InputStream / Path）分别落到 setting 对应字段
     */
    @Test
    void allAudioEntrancesSetSetting() throws Exception {
        AudioClientSetting bytes = AudioClientSetting.builder().provider("parakeet-v3").build();
        VirtualClient cb = VirtualClient.create("parakeet-v3", "");
        cb.audio(new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, settingOf(cb).getAudio(), "byte[] 注入未生效");
        cb.close();

        VirtualClient cp = VirtualClient.create("parakeet-v3", "");
        cp.audio(AUDIO);
        assertEquals(AUDIO, settingOf(cp).getAudioPath(), "Path 注入未生效");
        cp.close();

        VirtualClient cs = VirtualClient.create("parakeet-v3", "");
        cs.audio(new java.io.ByteArrayInputStream(new byte[]{9, 9}));
        assertNotNull(settingOf(cs).getAudioInput(), "InputStream 注入未生效");
        cs.close();
        assertNotNull(bytes.getProvider());
    }

    /**
     * 读取 client 内部 setting（反射，避免为此加公共 getter）
     */
    private static AudioClientSetting settingOf(VirtualClient c) throws ReflectiveOperationException {
        var f = c.getClass().getDeclaredField("setting");
        f.setAccessible(true);
        return (AudioClientSetting) f.get(c);
    }

    /**
     * 读取 client 内部 setting 的 audio 字节（兼容旧用法）
     */
    private static byte[] readAudio(VirtualClient c) {
        try {
            return settingOf(c).getAudio();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ============ 推理层：需 CLI + 模型就绪 ============

    @Test
    void cliLocated() {
        assumeReady("CLI 未安装");
        Path exe = CliModelRunner.locate(CliModelRunner.nemoSpeech());
        assertTrue(Files.isRegularFile(exe), exe.toString());
    }

    @Test
    void modelCached() {
        assumeReady("模型未就绪");
        ModelRegistry.discoverAll();
        Path local = ModelRegistry.ensureDownloaded("parakeet-v3");
        assertNotNull(local);
        assertTrue(Files.isRegularFile(local), String.valueOf(local));
    }

    @Test
    void transcribe() throws Exception {
        assumeReady("推理前置未就绪");
        byte[] wav = Files.readAllBytes(AUDIO);
        CliAsrTranslator t = new CliAsrTranslator(CliModelRunner.nemoSpeech(), "parakeet-v3");
        String text = t.translate(wav);
        assertNotNull(text);
        assertFalse(text.isBlank(), "转写结果为空");
    }

    @Test
    void clientTranscribe() {
        assumeReady("推理前置未就绪");
        try (VirtualClient c = VirtualClient.create("parakeet-v3", "")) {
            String text = c.transcribe(AUDIO);
            assertNotNull(text);
            assertFalse(text.isBlank());
        }
    }

    /**
     * 异步契约：createTask(音频路径) 记录的路径必须被 queryTask 采用并转写成功。
     * <p>回归锚点：此前 createTask 忽略入参，queryTask 去 resolve setting 导致 FAILED。</p>
     */
    @Test
    void createTaskWithPathThenQuerySucceeds() {
        assumeReady("推理前置未就绪");
        try (VirtualClient c = VirtualClient.create("parakeet-v3", "")) {
            String taskId = c.createTask(AUDIO);
            assertNotNull(taskId);
            assertTrue(taskId.startsWith("cli-asr-"), taskId);
            AudioResponse resp = c.queryTask(taskId);
            assertNotNull(resp);
            assertEquals(AudioResponse.Status.SUCCESS, resp.getStatus(),
                    () -> "异步转写失败: " + resp.getErrorMessage());
            assertNotNull(resp.getTranscript());
            assertFalse(resp.getTranscript().isBlank());
            assertEquals(taskId, resp.getTaskId());
            // 第二次查询命中缓存，结果一致
            AudioResponse again = c.queryTask(taskId);
            assertEquals(resp.getTranscript(), again.getTranscript());
        }
    }

    /**
     * 字节入口走完整推理（audio(byte[]) 注入 → transcribeBytes）
     */
    @Test
    void audioBytesEntranceTranscribes() throws Exception {
        assumeReady("推理前置未就绪");
        byte[] wav = Files.readAllBytes(AUDIO);
        try (VirtualClient c = VirtualClient.create("parakeet-v3", "")) {
            c.audio(wav);
            String text = ((CliAsrAudioClient) c).transcribeBytes(wav);
            assertNotNull(text);
            assertFalse(text.isBlank());
        }
    }

    /**
     * 推理前置就绪时执行，否则 abort（除非 {@code -Dparakeet.asr.test=true} 强制）。
     *
     * @param reason 缺失原因
     */
    private static void assumeReady(String reason) {
        boolean forced = Boolean.parseBoolean(System.getProperty(FORCE, "false"));
        boolean cli = hasCli();
        boolean model = hasModel();
        boolean audio = Files.isRegularFile(AUDIO);
        boolean ready = cli && model && audio;
        if (!ready && forced) {
            fail("强制模式（-" + FORCE + "=true）但前置缺失: cli=" + cli
                    + ", model=" + model + ", audio=" + audio + ", audioPath=" + AUDIO);
        }
        assumeTrue(ready, reason + "（cli=" + cli + ", model=" + model + ", audio=" + audio
                + "）；可用 -D" + FORCE + "=true 强制");
    }

    /**
     * CLI 是否已安装到缓存目录
     */
    private static boolean hasCli() {
        Path bin = CliModelRunner.cacheRoot().resolve("clis").resolve("nemo-speech").resolve("bin");
        Path exe = bin.resolve(CliModelRunner.isWindows() ? "nemo-speech.exe" : "nemo-speech");
        return Files.isRegularFile(exe);
    }

    /**
     * GGUF 是否已缓存（不触发下载）
     */
    private static boolean hasModel() {
        Path dir = CliModelRunner.cacheRoot().resolve("download").resolve("parakeet-v3");
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var s = Files.list(dir)) {
            return s.anyMatch(p -> p.getFileName().toString().endsWith(".gguf"));
        } catch (Exception e) {
            return false;
        }
    }
}