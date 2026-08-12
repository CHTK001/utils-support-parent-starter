package com.chua.deeplearning.support.onnx.audio.whisper;

import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Whisper 端到端测试。
 * <p>
 * 用法：
 * <pre>
 *   java WhisperTest
 *   java WhisperTest "C:/path/to/audio.wav"
 * </pre>
 * 环境变量：WHISPER_MODEL_DIR — 模型目录（默认从 jar classpath 解压）
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class WhisperTest {

    public static void main(String[] args) throws Exception {
        Path modelDir;
        String envDir = System.getenv("WHISPER_MODEL_DIR");
        if (envDir != null && Files.isDirectory(Path.of(envDir))) {
            modelDir = Path.of(envDir);
        } else {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir"), "chua-dl-models", "audio", "asr", "whisper-tiny");
            NativeLoader.of("whisper-resources")
                    .from(WhisperTest.class.getClassLoader())
                    .basePath("audio/asr/whisper-tiny/")
                    .toTarget(tmp)
                    .glob("*")
                    .withMd5(true)
                    .extractOnly(true)
                    .load();
            modelDir = tmp;
        }
        log.info("model dir: {}", modelDir);

        Path audioPath = args.length > 0 ? Path.of(args[0]) : Paths.get("C:/Users/Administrator/AppData/Local/Temp/whisper_long.wav");
        log.info("audio: {}", audioPath);
        if (!Files.exists(audioPath)) {
            log.error("audio file not found: {}", audioPath);
            System.exit(1);
        }

        WhisperTranslator translator = new WhisperTranslator();
        translator.prepare(modelDir);
        log.info("==> Transcribing...");
        long start = System.currentTimeMillis();
        String text;
        try {
            text = translator.transcribe(audioPath);
        } catch (Throwable t) {
            log.error("==> transcribe failed: {}", t.getMessage(), t);
            return;
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("==> Time: {}ms", elapsed);
        log.info("==> Text: '{}'", text);
    }
}
