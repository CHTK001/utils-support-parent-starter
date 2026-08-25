package com.chua.deeplearning.support.onnx.audio;

import com.chua.common.support.vector.Vector;
import com.chua.common.support.vector.VectorStorage;
import com.chua.deeplearning.support.audio.FileVectorStorage;
import com.chua.deeplearning.support.onnx.audio.CampplusEmbedding;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 声纹识别管线（CAM++ 神经模型，192 维嵌入）。
 *
 * <p>与人脸识别体系同构的双方法 API：</p>
 * <ul>
 *   <li><b>入库</b> {@link #enroll(String, Path)}：音频 → CAM++ 特征提取 →
 *       192 维向量 → {@link VectorStorage} 落盘</li>
 *   <li><b>检索</b> {@link #search(Path, int)}：音频 → 特征提取 →
 *       库内余弦相似度比对 → TopK 匹配结果</li>
 * </ul>
 *
 * <p><b>用法</b>：</p>
 *
 * <pre>{@code
 *   VoiceprintPipeline vp = VoiceprintPipeline.create();
 *   vp.enroll("alice", Path.of("alice.wav"));
 *   List<Match> top = vp.search(Path.of("query.wav"), 3);
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class VoiceprintPipeline {

    /**
     * 向量入库设施（独享实例）
     */
    private final VectorStorage storage;

    /**
     * CAM++ 神经声纹提取器
     */
    private final CampplusEmbedding campplus;

    /**
     * 默认构造：文件持久化向量库 + CAM++ 神经声纹。
     */
    public VoiceprintPipeline() {
        this.storage = FileVectorStorage.create(192, defaultDirectory());
        this.campplus = CampplusEmbedding.load();
        log.info("[Voiceprint] init: CAM++ neural backend, dim=192");
    }

    /**
     * 指定向量库构造。
     *
     * @param storage 向量存储（维度须为 192）
     */
    public VoiceprintPipeline(VectorStorage storage) {
        if (storage.dimension() != 192) {
            throw new IllegalArgumentException(
                    "向量库维度须为 192，当前: " + storage.dimension());
        }
        this.storage = storage;
        this.campplus = CampplusEmbedding.load();
        log.info("[Voiceprint] init: CAM++ neural backend, dim=192, external storage");
    }

    /**
     * 创建声纹管线实例。
     *
     * @return 管线实例
     */
    public static VoiceprintPipeline create() {
        return new VoiceprintPipeline();
    }

    /**
     * 方法一·入库：注册说话人声纹（重复 id 覆盖旧声纹）。
     *
     * @param speakerId 说话人标识
     * @param samplePath 参考音频
     * @throws Exception 提取或落库失败
     */
    public void enroll(String speakerId, Path samplePath) throws Exception {
        float[] samples = AudioUtils.loadMono16k(samplePath);
        float[] fp = campplus.extract(samples);
        if (!storage.add(speakerId, fp)) {
            throw new IllegalStateException("声纹入库失败: " + speakerId);
        }
        log.info("[Voiceprint] enrolled {}: dim={}", speakerId, fp.length);
    }

    /**
     * 方法二·检索：待测音频与库内全部声纹做余弦相似度比对。
     *
     * <p>1:1 验证场景：search(1) 后检查 top1 的 id 与相似度阈值即可。</p>
     *
     * @param samplePath 待测音频
     * @param topK 返回条数
     * @return 按相似度降序的匹配列表
     * @throws Exception 提取或检索失败
     */
    public List<Match> search(Path samplePath, int topK) throws Exception {
        float[] samples = AudioUtils.loadMono16k(samplePath);
        float[] probe = campplus.extract(samples);
        List<Vector> hits = storage.search(probe, topK);
        List<Match> matches = new ArrayList<>(hits.size());
        for (Vector v : hits) {
            matches.add(new Match(v.id(),
                    AudioUtils.cosine(probe, v.data())));
        }
        return matches;
    }

    /**
     * 已注册声纹数量。
     *
     * @return 数量
     */
    public int size() {
        return storage.size();
    }

    /**
     * 匹配结果。
     *
     * @param speakerId 说话人标识
     * @param similarity 余弦相似度 [-1,1]
     */
    public record Match(String speakerId, double similarity) {
    }

    /**
     * 声纹库默认持久化目录。
     *
     * @return 目录路径
     */
    private static Path defaultDirectory() {
        String prop = System.getProperty("deeplearning.model.cache-dir");
        Path base = (prop != null && !prop.isBlank())
                ? Path.of(prop.trim()) : Path.of(System.getProperty("java.io.tmpdir"));
        return base.resolve("chua-voiceprints");
    }
}
