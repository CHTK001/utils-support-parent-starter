package com.chua.deeplearning.support.onnx.audio.whisper;

import com.chua.common.support.ai.audio.AudioClient;
import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.AudioResponse;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

/**
 * AudioClient SPI 端到端测试。
 *
 * <p>验证：
 * <ol>
 *   <li>local 桩 provider 可调用并返回固定文案</li>
 *   <li>whisper provider 通过 SPI 工厂加载并完成 30s 离线 ASR</li>
 *   <li>异步模式 createTask + queryTask 返回 AudioResponse</li>
 * </ol>
 *
 * <p>用法：
 * <pre>{@code
 *   java AudioClientTest
 *   java AudioClientTest "C:/path/to/audio.wav"
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class AudioClientTest {

    /**
     * 程序退出码：成功
     */
    private static final int EXIT_CODE_SUCCESS = 0;

    /**
     * 程序退出码：失败
     */
    private static final int EXIT_CODE_FAILURE = 1;

    /**
     * local 桩 provider 名称
     */
    private static final String PROVIDER_LOCAL = "local";

    /**
     * whisper provider 名称
     */
    private static final String PROVIDER_WHISPER = "whisper";

    /**
     * 默认测试音频（10 秒合成音）
     */
    private static final Path DEFAULT_AUDIO = Path.of(
            "C:/Users/Administrator/AppData/Local/Temp/whisper_long.wav");

    public static void main(String[] args) {
        Path audio = args.length > 0 ? Path.of(args[0]) : DEFAULT_AUDIO;
        AudioClientTest runner = new AudioClientTest();
        boolean allPassed = runner.runTest(audio);
        System.exit(allPassed ? EXIT_CODE_SUCCESS : EXIT_CODE_FAILURE);
    }

    public boolean runTest(Path audio) {
        boolean localOk = testLocalStub(audio);
        boolean whisperOk = testWhisperProvider(audio);
        boolean asyncOk = testAsyncFlow(audio);
        return localOk && whisperOk && asyncOk;
    }

    public boolean testLocalStub(Path audio) {
        try (AudioClient client = AudioClient.create(PROVIDER_LOCAL, "")) {
            String text = client.transcribe(audio);
            boolean passed = text != null && text.contains("本地桩");
            log.info("[PASS={}] local 桩返回: {}", passed, text);
            return passed;
        } catch (Exception e) {
            log.error("[FAIL] local stub 异常: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean testWhisperProvider(Path audio) {
        try (AudioClient client = AudioClient.create(
                AudioClientSetting.builder()
                        .provider(PROVIDER_WHISPER)
                        .build())) {
            long start = System.currentTimeMillis();
            String text = client
                    .model("whisper-tiny")
                    .language("en")
                    .transcribe(audio);
            long elapsed = System.currentTimeMillis() - start;
            boolean passed = text != null && !text.isEmpty();
            log.info("[PASS={}] whisper 同步返回 {}ms: '{}'", passed, elapsed, text);
            return passed;
        } catch (Exception e) {
            log.error("[FAIL] whisper 同步异常: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean testAsyncFlow(Path audio) {
        try (AudioClient client = AudioClient.create(
                AudioClientSetting.builder()
                        .provider(PROVIDER_WHISPER)
                        .build())) {
            String taskId = client.audio(audio).createTask(audio);
            AudioResponse resp = client.queryTask(taskId);
            boolean passed = resp != null
                && resp.getStatus() == AudioResponse.Status.SUCCESS
                && resp.getTranscript() != null
                && !resp.getTranscript().isEmpty();
            log.info("[PASS={}] async taskId={} status={} text='{}'",
                    passed, taskId, resp.getStatus(), resp.getTranscript());
            return passed;
        } catch (Exception e) {
            log.error("[FAIL] async flow 异常: {}", e.getMessage(), e);
            return false;
        }
    }
}