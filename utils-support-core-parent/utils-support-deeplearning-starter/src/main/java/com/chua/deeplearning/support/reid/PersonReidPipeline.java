package com.chua.deeplearning.support.reid;

import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.model.DetectionInfo;
import com.chua.deeplearning.support.image.ImageDetector;

import java.util.ArrayList;
import java.util.List;

/**
 * 行人重识别（Person ReID）管线 — 基于特征提取模型。
 *
 * <p>与 OpenCV DNN person_reid.py 示例类似，支持：
 * <ul>
 *   <li>单张查询图 vs 图库检索</li>
 *   <li>批量特征提取</li>
 *   <li>余弦相似度 + Top-K 排序</li>
 *   <li>先检测行人再提取特征（可选）</li>
 * </ul>
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PersonReidPipeline {

    /**
     * 特征提取器
     */
    private final FeatureExtractor extractor;

    /**
     * 行人检测器（可为 null，不检测直接提特征）
     */
    private final ImageDetector detector;

    /**
     * 构造 ReID 管线。
     *
     * @param extractor 特征提取模型（如 clip-image-feature、resnet50-feature 等）
     */
    public PersonReidPipeline(FeatureExtractor extractor) {
        this(extractor, null);
    }

    /**
     * 构造 ReID 管线（含行人检测）。
     *
     * @param extractor 特征提取模型
     * @param detector  行人检测模型（可为 null）
     */
    public PersonReidPipeline(FeatureExtractor extractor, ImageDetector detector) {
        this.extractor = extractor;
        this.detector = detector;
    }

    /**
     * 图库特征条目。
     *
     * @param feature 特征向量
     * @param label   标签/路径
     * @param bbox    检测框（可为 null）
     */
    public record GalleryEntry(float[] feature, String label, DetectionInfo bbox) {}

    /**
     * 检索结果。
     *
     * @param label  标签
     * @param score  相似度（0~1）
     * @param bbox   检测框
     */
    public record SearchResult(String label, float score, DetectionInfo bbox) {}

    /**
     * 从单张图像提取特征。
     *
     * @param imageData 图像
     * @return 特征向量
     */
    public float[] extract(byte[] imageData) {
        return extractor.extract(imageData);
    }

    /**
     * 构建图库特征索引。
     *
     * @param images  图库图像列表
     * @param labels  对应标签列表
     * @return 图库特征条目
     */
    public List<GalleryEntry> buildGallery(List<byte[]> images, List<String> labels) {
        List<GalleryEntry> gallery = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            float[] feat = extractor.extract(images.get(i));
            String label = labels != null && i < labels.size() ? labels.get(i) : "gallery_" + i;
            gallery.add(new GalleryEntry(feat, label, null));
        }
        return gallery;
    }

    /**
     * 检索：查询特征 vs 图库，返回 Top-K。
     *
     * @param queryFeat 查询特征
     * @param gallery   图库
     * @param topK      返回数量
     * @return 排序结果
     */
    public List<SearchResult> search(float[] queryFeat, List<GalleryEntry> gallery, int topK) {
        if (queryFeat == null || gallery == null || gallery.isEmpty()) {
            return List.of();
        }
        float[] qNorm = normalize(queryFeat);

        // 计算全部余弦相似度
        float[][] scores = new float[gallery.size()][2];
        for (int i = 0; i < gallery.size(); i++) {
            float[] gNorm = normalize(gallery.get(i).feature());
            float sim = dot(qNorm, gNorm);
            scores[i][0] = sim;
            scores[i][1] = i;
        }

        // 降序排序
        java.util.Arrays.sort(scores, (a, b) -> Float.compare(b[0], a[0]));

        int k = Math.min(topK, scores.length);
        List<SearchResult> results = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            int idx = (int) scores[i][1];
            GalleryEntry entry = gallery.get(idx);
            results.add(new SearchResult(entry.label(), scores[i][0], entry.bbox()));
        }
        return results;
    }

    /**
     * 一键检索：查询图 vs 图库，返回 Top-K。
     *
     * @param queryImage 查询图像
     * @param gallery    图库特征条目
     * @param topK      返回数量
     * @return 排序结果
     */
    public List<SearchResult> search(byte[] queryImage, List<GalleryEntry> gallery, int topK) {
        float[] queryFeat = extractor.extract(queryImage);
        return search(queryFeat, gallery, topK);
    }

    /**
     * L2 归一化。
     */
    private static float[] normalize(float[] vec) {
        float norm = 0f;
        for (float v : vec) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-10f) {
            return vec;
        }
        float[] out = new float[vec.length];
        for (int i = 0; i < vec.length; i++) {
            out[i] = vec[i] / norm;
        }
        return out;
    }

    /**
     * 点积（归一化后等价于余弦相似度）。
     */
    private static float dot(float[] a, float[] b) {
        float sum = 0f;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }
}