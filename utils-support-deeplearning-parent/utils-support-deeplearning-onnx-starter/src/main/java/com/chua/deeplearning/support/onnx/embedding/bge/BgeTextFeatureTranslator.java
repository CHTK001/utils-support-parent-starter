package com.chua.deeplearning.support.onnx.embedding.bge;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * BGE 文本嵌入 Translator（registry 路径，ORT 原生 + huggingface Tokenizer）。
 *
 * <p>替代错误的 ClipTextFeatureTranslator 注册（bge 模型需要
 * {@code input_ids + attention_mask + token_type_ids} 三输入，CLIP 只提供 input_ids）。
 * 模型 + tokenizer.json 由 模型 jar（如 bge-small-zh/en）提供，NAT加载 解压。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class BgeTextFeatureTranslator implements ITranslator<String, float[]> {

    /**
     * 默认最大长度
    */
    private static final int DEFAULT_MAX_LEN = 512;

    /**
     * 翻译器
    */
    private final BgeEmbeddingTranslator translator;
    /**
     * 分词器
    */
    private HuggingFaceTokenizer tokenizer;
    /**
     * 是否已加载
    */
    private volatile boolean loaded;

    /**
     * 创建 bge文本特征translator 实例
    */
    public BgeTextFeatureTranslator() {
        this.translator = new BgeEmbeddingTranslator();
    }

    /**
     * Prepare
    */
    private synchronized void prepare() throws Exception {
        if (loaded) {
            return;
        }
        // 依次尝试 jar 内 bge 资源（zh/en 通用）或 registry 下载缓存
        Path modelDir = null;
        // 1. 尝试 jar 内嵌资源
        String[] candidates = {
                "nlp/embedding/bge-small-en-v1.5/",
                "nlp/embedding/bge-small-zh-v1.5/"
        };
        for (String base : candidates) {
            Path tmpDir = Files.createTempDirectory("bge-registry-");
            tmpDir.toFile().deleteOnExit();
            Path dir = tmpDir.resolve("bge");
            Files.createDirectories(dir);
            try {
                NativeLoader.of("bge-registry-" + base.hashCode())
                        .from(BgeTextFeatureTranslator.class.getClassLoader())
                        .basePath(base)
                        .toTarget(dir)
                        .glob("*")
                        .withMd5(true)
                        .extractOnly(true)
                        .load();
                Path model = dir.resolve("model.onnx");
                Path tk = dir.resolve("tokenizer.json");
                if (Files.isRegularFile(model) && Files.isRegularFile(tk)) {
                    translator.loadLocal(model.toString());
                    tokenizer = HuggingFaceTokenizer.builder()
                            .optTokenizerPath(tk)
                            .optPadding(true)
                            .optMaxLength(DEFAULT_MAX_LEN)
                            .build();
                    modelDir = dir;
                    break;
                }
            } catch (Throwable ignore) {
                // NativeLoader 在 jar 内资源缺失时抛的是 UnsatisfiedLinkError（属于 Error 而非
                // Exception），必须连 Error 一起吞掉，否则会绕过下面的 registry 探测直接失败。
                // 尝试下一个候选
            }
        }
 // 2. 若 jar 内未找到，尝试从 模型registry 下载缓存读取（新模型如 bge-基础-zh）
        if (modelDir == null) {
            // 2.1 显式指定优先（系统属性，便于多模型并存时精确选择）
            List<String> candidateIds = new ArrayList<>(2);
            String explicit = System.getProperty("bge.registry.model", "");
            if (!explicit.isBlank()) {
                candidateIds.add(explicit.trim());
            }
            // 2.2 未指定时按注册表常见顺序探测：只认「已注册 / 已下载」的模型，命中即用。
            //     不依赖外部系统属性，避免部署方必须额外配置才能用上本地模型。
            candidateIds.add("bge-large-zh-embedding");
            candidateIds.add("bge-small-zh-embedding");
            for (String registryModelId : candidateIds) {
                try {
                    // 优先用「服务器本地已注册」的外部模型目录：模型已经放在盘上时，
                    // 不应再去下载缓存或联网。resolveModelPath 只认模板自身登记的
                    // classpath / 下载路径，看不到 registerExternalDirectory 注册的目录。
                    Path resolved = com.chua.deeplearning.support.engine.ModelRegistry
                            .findExternalDirectory(registryModelId);
                    if (resolved == null) {
                        resolved = com.chua.deeplearning.support.engine.ModelRegistry
                                .resolveModelPath(registryModelId);
                    }
                    if (resolved == null) {
                        continue;
                    }
                    Path dir = Files.isDirectory(resolved) ? resolved : resolved.getParent();
                    if (dir == null) {
                        continue;
                    }
                    Path model = dir.resolve("model.onnx");
                    Path tk = dir.resolve("tokenizer.json");
                    if (!Files.isRegularFile(model) || !Files.isRegularFile(tk)) {
                        continue;
                    }
                    translator.loadLocal(model.toString());
                    tokenizer = HuggingFaceTokenizer.builder()
                            .optTokenizerPath(tk)
                            .optPadding(true)
                            .optMaxLength(DEFAULT_MAX_LEN)
                            .build();
                    modelDir = dir;
                    log.info("[BGE] 使用 registry 模型: id={} dir={}", registryModelId, dir);
                    break;
                } catch (Throwable ignore) {
                    // 同上：模型加载失败可能抛 Error，不能只捕获 Exception
                    // 尝试下一个候选
                }
            }
        }
        if (modelDir == null || tokenizer == null) {
            throw new IllegalStateException("BGE 模型资源未就绪（jar 内缺少 bge-small-en/zh 模型，"
                    + "且 registry 中未找到 bge-large-zh-embedding / bge-small-zh-embedding 的 "
                    + "model.onnx + tokenizer.json；如需指定其它模型请设置系统属性 bge.registry.model）");
        }
        loaded = true;
    }

    @Override
    /**
     * 名称
    */
    public String name() {
        return "bge-text-feature";
    }

    @Override
    /**
     * Translate
    */
    public float[] translate(String input) {
        try {
            prepare();
            var encoding = tokenizer.encode(input);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();
            int seqLen = Math.min(ids.length, DEFAULT_MAX_LEN);
            long[] idsTrim = new long[seqLen];
            long[] maskTrim = new long[seqLen];
            System.arraycopy(ids, 0, idsTrim, 0, seqLen);
            System.arraycopy(mask, 0, maskTrim, 0, seqLen);
            return translator.embed(idsTrim, maskTrim);
        } catch (Exception e) {
            throw new RuntimeException("[bge-text-feature] embedding failed: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭底层 ONNX 会话。
     */
    public synchronized void close() {
        translator.close();
        tokenizer = null;
        loaded = false;
    }
}
