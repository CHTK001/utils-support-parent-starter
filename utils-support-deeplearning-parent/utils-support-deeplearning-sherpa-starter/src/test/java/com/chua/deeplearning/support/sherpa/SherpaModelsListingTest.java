package com.chua.deeplearning.support.sherpa;

import com.chua.common.support.ai.audio.AudioClientSetting;
import com.chua.common.support.ai.audio.TextToAudioClientSetting;
import com.chua.common.support.ai.chat.ModelDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPI 客户端 {@code models()} 枚举行为的验收测试。
 *
 * <p>重点验证：枚举结果必须**明确区分离线模型与实时（流式）模型**。
 * 若界面只展示模型名，使用者极易把流式模型选给离线识别器，
 * 进而在原生层解码失败或输出乱码。</p>
 *
 * <p>本测试只操作临时目录里的空占位文件，不加载原生库、不下载模型。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class SherpaModelsListingTest {

    /**
     * 临时模型根目录。
     */
    private Path root;

    /**
     * 原 {@code sherpa.onnx.model.dir} 取值，用于用例后恢复。
     */
    private String previousRoot;

    /**
     * 构建临时模型目录结构：
     * <ul>
     *   <li>{@code sensevoice/} —— 离线（单模型）</li>
     *   <li>{@code streaming-zipformer/} —— 实时（三模型）</li>
     *   <li>{@code vits-zh/} —— 合成</li>
     *   <li>{@code broken/} —— 缺 tokens，应被过滤</li>
     * </ul>
     *
     * @throws IOException 创建失败
     */
    @BeforeEach
    void setUp() throws IOException {
        previousRoot = System.getProperty(SherpaModelResolver.PROP_MODEL_DIR);
        root = Files.createTempDirectory("sherpa-models");

        createModel("sensevoice", "model.int8.onnx", "tokens.txt");
        createModel("streaming-zipformer",
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.int8.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt");
        createModel("vits-zh", "model.onnx", "tokens.txt", "lexicon.txt", "speakers.txt");
        // 缺 tokens.txt：结构不完整，不应出现在清单里
        createModel("broken", "model.int8.onnx");

        System.setProperty(SherpaModelResolver.PROP_MODEL_DIR, root.toString());
    }

    /**
     * 恢复系统属性并清理临时目录。
     */
    @AfterEach
    void tearDown() {
        if (previousRoot == null) {
            System.clearProperty(SherpaModelResolver.PROP_MODEL_DIR);
        } else {
            System.setProperty(SherpaModelResolver.PROP_MODEL_DIR, previousRoot);
        }
        deleteRecursively(root);
    }

    /**
     * 识别客户端应列出离线与实时模型，且描述里能看出属于哪一类。
     */
    @Test
    void shouldLabelOfflineAndStreamingModels() {
        try (SherpaAudioClient client = new SherpaAudioClient(AudioClientSetting.builder().build())) {
            List<ModelDefinition> models = client.models();

            assertThat(models).extracting(ModelDefinition::getId)
                    .containsExactly("sensevoice", "streaming-zipformer");

            ModelDefinition offline = byId(models, "sensevoice");
            assertThat(offline.getDescription()).contains("离线");

            ModelDefinition streaming = byId(models, "streaming-zipformer");
            assertThat(streaming.getDescription()).contains("实时");
        }
    }

    /**
     * 合成模型不应混进识别客户端的清单（能力不同，选中会失败）。
     */
    @Test
    void shouldExcludeTtsModelsFromAsrListing() {
        try (SherpaAudioClient client = new SherpaAudioClient(AudioClientSetting.builder().build())) {
            assertThat(client.models()).extracting(ModelDefinition::getId)
                    .doesNotContain("vits-zh");
        }
    }

    /**
     * 结构不完整的目录应被过滤，避免给使用者一个必然失败的选项。
     */
    @Test
    void shouldSkipIncompleteModelDir() {
        try (SherpaAudioClient client = new SherpaAudioClient(AudioClientSetting.builder().build())) {
            assertThat(client.models()).extracting(ModelDefinition::getId)
                    .doesNotContain("broken");
        }
    }

    /**
     * 未配置模型根目录时，清单应为空而不是抛异常。
     */
    @Test
    void shouldReturnEmptyWhenRootNotConfigured() {
        System.clearProperty(SherpaModelResolver.PROP_MODEL_DIR);
        try (SherpaAudioClient client = new SherpaAudioClient(AudioClientSetting.builder().build())) {
            assertThat(client.models()).isEmpty();
        }
    }

    /**
     * 合成客户端应只列出 TTS 模型，并标注为合成能力。
     */
    @Test
    void shouldListTtsModelsInTtsClient() {
        try (SherpaTextToAudioClient client =
                     new SherpaTextToAudioClient(TextToAudioClientSetting.builder().build())) {
            List<ModelDefinition> models = client.models();

            assertThat(models).extracting(ModelDefinition::getId).containsExactly("vits-zh");
            assertThat(models.getFirst().getDescription()).contains("合成");
        }
    }

    /**
     * 按标识取模型定义。
     *
     * @param models 模型列表
     * @param id     模型标识
     * @return 命中的定义
     */
    private static ModelDefinition byId(List<ModelDefinition> models, String id) {
        return models.stream()
                .filter(def -> id.equals(def.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到模型: " + id));
    }

    /**
     * 创建模型目录与占位文件。
     *
     * @param name  目录名
     * @param files 文件名
     * @throws IOException 创建失败
     */
    private void createModel(String name, String... files) throws IOException {
        Path dir = root.resolve(name);
        Files.createDirectories(dir);
        for (String file : files) {
            Files.write(dir.resolve(file), new byte[]{0});
        }
    }

    /**
     * 递归删除目录。
     *
     * @param dir 目录
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败不影响结论
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
