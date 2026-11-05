// [P3C A20 豁免] A20/A30/A31: 含日志/界面/SQL 字符串字面量等非业务魔法值场景，豁免。
package com.chua.deeplearning.support.onnx.embedding.embeddinggemma;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.deeplearning.support.engine.ModelRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于 EmbeddingClient 客户端的端点验证（onnx 引擎，embeddinggemma-2-embedding）。
 * <p/>
 * 客户端通过 SPI 获取 onnx IdentificationEngine，按模型标识在 ModelRegistry 解析
 * {@link com.chua.deeplearning.support.translator.ITranslator}：内部 {@code LazyDjlTranslator} 在首次推理时
 * 调用 {@code resolveModelPath(modelId)}，反射注入 {@code EmbeddingGemmaTranslator.setModelPath}。
 * <p/>
 * 模型资源来自项目内的 dlmodel 离线副本（含 {@code model_q4.onnx} + {@code model_q4.onnx_data} +
 * {@code tokenizer.json}），客户端首次推理前把注册表的相对路径覆盖为 dlmodel 绝对路径（模拟
 * 「服务器本地已放好模型」）；测试结束时恢复默认相对路径，避免影响同进程的其他测试。
 *
 * @author CH
 * @since 4.0.0.51
 */
class ClientEndToEndTest {

    /** 模型标识 */
    private static final String MODEL_ID = "embeddinggemma-2-embedding";

    /** 离线模型目录 （项目内 dlmodel），null 表示未找到 */
    private static Path localModelDir;

    /** 原始注册表条目，用于测试后恢复 */
    private static ModelRegistry.Entry originalEntry;

    @BeforeAll
    static void setUp() {
        localModelDir = resolveLocalModelDir();
        assertNotNull(localModelDir,
                "离线模型目录(dlmodel, 含 model_q4.onnx + model_q4.onnx_data + tokenizer.json) 未找到，端点测试不可用");
    }

    @AfterAll
    static void restoreRegistry() {
        if (originalEntry == null) {
            return;
        }
        ModelRegistry.register(originalEntry.modelId(), originalEntry.translatorClassName(),
                originalEntry.inputType(), originalEntry.outputType(), originalEntry.capabilityInterface(),
                originalEntry.relativePath(), originalEntry.downloadUrl(), originalEntry.downloadMirrors(),
                originalEntry.downloadFiles(), originalEntry.compress(), originalEntry.downloadFileName(),
                originalEntry.hardwareConfig());        System.out.println("[restore] 注册表相对路径恢复为: " + originalEntry.relativePath()); // [P3C E1 豁免]

    }

    /**
     * 创建客户端：{@code EmbeddingClient.create} 触发 SPI 发现模型并采集注册表后，
     * 将注册表条目({@code relativePath})覆盖为离线模型目录的绝对路径。
     */
    private EmbeddingClient newClient() {
        EmbeddingClient client = EmbeddingClient.create("onnx", "");
        registerOfflineModelDir(client);
        return client;
    }

    private void registerOfflineModelDir(EmbeddingClient client) {
        ModelRegistry.Entry entry = ModelRegistry.get(MODEL_ID);
        assertNotNull(entry, MODEL_ID + " 应在注册表中");
        if (originalEntry == null) {
            originalEntry = ModelRegistry.get(MODEL_ID);
        }
        ModelRegistry.register(MODEL_ID, entry.translatorClassName(), entry.inputType(), entry.outputType(),
                entry.capabilityInterface(), localModelDir.toAbsolutePath().normalize().toString(),
                entry.downloadUrl(), entry.downloadMirrors(), entry.downloadFiles(),
                entry.compress(), entry.downloadFileName(), entry.hardwareConfig());        System.out.println("[offline] " + MODEL_ID + " relativePath -> " + entry.relativePath() // [P3C E1 豁免]

                + " (模型目录: " + localModelDir + ")");
    }

    @Test
    void embeddingClientModels() {
        EmbeddingClient client = EmbeddingClient.create("onnx", "");
        List<ModelDefinition> models = client.models();
        assertNotNull(models, "onnx 客户端的模型列表");
        assertTrue(models.stream().anyMatch(d -> MODEL_ID.equals(d.getId())),
                "onnx 客户端模型列表应包含 " + MODEL_ID + "（String -> float[]）: " + models.size() + " 个模型");        System.out.println("[Models] onnx 客户端共有 " + models.size() + " 个模型"); // [P3C E1 豁免]

    }

    @Test
    void embeddingClientWorks() {
        EmbeddingClient client = newClient();

        float[] vec = client.model(MODEL_ID).embedding("Hello world");
        assertNotNull(vec);
        assertEquals(768, vec.length, "输出应为 768 维句向量");
        double norm = 0.0;
        for (float v : vec) {
            norm += (double) v * v;
        }
        assertTrue(Math.abs(Math.sqrt(norm) - 1.0) < 1e-4, "L2 norm 应 ~1, actual " + Math.sqrt(norm));        System.out.println("[3] vec.length=768 L2_norm=" + Math.sqrt(norm)); // [P3C E1 豁免]


        float[] a = client.model(MODEL_ID).embedding("你好世界");
        double n2 = 0.0;
        for (float v : a) {
            n2 += (double) v * v;
        }
        assertEquals(768, a.length, "中文句向量也应为 768 维");
        assertTrue(Math.abs(Math.sqrt(n2) - 1.0) < 1e-4, "中文句向量的 L2 norm 也应 ~1");        System.out.println("[4] chinese vec.length=768 L2_norm=" + Math.sqrt(n2)); // [P3C E1 豁免]


        float[] b = client.model(MODEL_ID).embedding("Hello world");
        assertEquals(vec.length, b.length);
        for (int i = 0; i < vec.length; i++) {
            assertEquals(vec[i], b[i], "第 " + i + " 维不一致");
        }        System.out.println("[5] deterministic YES: String -> float[] (client)"); // [P3C E1 豁免]

    }

    @Test
    void embeddingClientNullTextThrows() {
        EmbeddingClient client = newClient();
        assertThrows(IllegalArgumentException.class, () -> client.model(MODEL_ID).embedding(null));        System.out.println("[8] null text -> IllegalArgumentException"); // [P3C E1 豁免]

    }

    @Test
    void embeddingClientBatchWorks() {
        EmbeddingClient client = newClient();
        float[][] vs = client.model(MODEL_ID).embeddingBatch(new String[]{"Hello world", "你好世界"});
        assertEquals(2, vs.length);
        for (float[] v : vs) {
            assertEquals(768, v.length);
            double n2 = 0.0;
            for (float x : v) {
                n2 += (double) x * x;
            }
            assertTrue(Math.abs(Math.sqrt(n2) - 1.0) < 1e-4, "每行向量的 L2 norm 应 ~1");
        }        System.out.println("[7] batch OK: " + vs.length + " x " + vs[0].length); // [P3C E1 豁免]

    }

    /**
     * 解析项目内已下载的离线模型目录，候选按优先级：
     * <ol>
     *   <li>{@code ../utils-support-deeplearning-safetensors-starter/dlmodel}（模块内）</li>
     *   <li>{@code ../../utils-support-deeplearning-safetensors-starter/dlmodel}（parent 内）</li>
     *   <li>{{@code /D/ch/project/.../safetensors-starter/dlmodel}（落盘绝对路径）</li>
     * </ol>
     */
    private static Path resolveLocalModelDir() {
        String[] candidates = {
                // onnx-starter 模块下 ../safetensors-starter/dlmodel
                "../utils-support-deeplearning-safetensors-starter/dlmodel",
                // 从 parent 目录运行时
                "../../utils-support-deeplearning-safetensors-starter/dlmodel",
                "D:/ch/project/utils-support-parent-starter/"
                        + "utils-support-deeplearning-parent/utils-support-deeplearning-safetensors-starter/dlmodel"
        };
        for (String candidate : candidates) {
            Path p = Path.of(candidate).toAbsolutePath().normalize();
            if (Files.isRegularFile(p.resolve("model_q4.onnx"))
                    && Files.isRegularFile(p.resolve("model_q4.onnx_data"))
                    && Files.isRegularFile(p.resolve("tokenizer.json"))) {
                return p;
            }
        }
        return null;
    }
}