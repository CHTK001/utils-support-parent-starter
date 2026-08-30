package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.model.HardwareConfig;

import java.util.List;

/**
 * ONNX 模块模型集中注册器。
 * <p>通过 SPI 被主框架加载；类名字符串注册 + 懒加载 Translator。
 * relativePath 支持文件系统路径，或 classpath: 前缀（JAR 内嵌）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class OnnxModelRegistrar implements ModelRegistrar {

    static {
        registerAll();
    }

    @Override
    /** 注册 */
    public void register(ModelRegistry registry) {
        registerAll();
    }

    /** 注册All */
    private static void registerAll() {
        // 通用动作识别：识别图片中人物动作（跑步、跳跃等），输出动作类别+置信度；适用安防监控、体育分析
        reg("common-action", "com.chua.deeplearning.support.onnx.action.CommonActionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/action/common/action.onnx", "https://huggingface.co/onnx-community/action-recognition/resolve/main/onnx/model.onnx", false, null);
        // 视频动作检测(C3D)：ResNetC3D 视频动作检测，输入视频输出动作序列（9类：举手/吃喝/吸烟/打电话/玩手机/趴桌睡觉/跌倒/洗手/拍照）；适用安防监控、行为分析
        reg("c3d-action-detection", "com.chua.deeplearning.support.onnx.action.C3DActionDetectionTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.image.ActionDetector.class, "vision/action/c3d/model.onnx");
        // 年龄识别(GoogleNet)：根据人脸图像估算年龄，输出年龄区间；适用人脸属性分析、年龄统计
        // 年龄识别(VGG)：VGG 架构的人脸年龄估算，精度更高但计算量更大；适用人脸属性分析
        reg("vgg-age-recognition", "com.chua.deeplearning.support.onnx.age.VggAgeRecognitionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/age/vgg_ilsvrc_16_age_imdb_wiki.onnx");
        // 年龄+种族+性别多任务识别：单模型同时输出年龄区间、种族、性别；适用人脸属性综合分析、安防
        reg("age-race-gender", "com.chua.deeplearning.support.onnx.agegender.AgeRaceGenderTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/attribute/age_gender/AgeRaceGenderNet_v1.onnx");
        // 动漫风格迁移(AnimeGANv3)：将真实照片转换为宫崎骏/新海诚等动漫风格，v3 版质量更高；适用图片特效、二次元创作
        reg("anime-gan-v3", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV3Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/animegan/AnimeGANv3_Hayao_36.onnx");
        reg("anime-gan-v3-shinkai", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV3Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/animegan/AnimeGANv3_Shinkai_37.onnx");
        reg("anime-gan-v3-ghibli", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV3Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/animegan/AnimeGANv3_large_Ghibli_c1_e299.onnx");
        // 水下图像增强(LU2Net)：基于轻量级U-Net的水下图像增强，改善水下颜色失真和对比度，适用于水下摄影、水下监控
                reg("lu2net", "com.chua.deeplearning.support.onnx.lu2net.Lu2NetTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/lu2net/lu2net.onnx");
        reg("anime-gan-v2-hayao", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV2Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/style_transfer/animegan2/hayao.onnx");
        // 动漫风格迁移(AnimeGANv2-Shinkai)：将照片转换为新海诚风格动画（唯美、光影感强）；适用动漫化、风景美化
        reg("anime-gan-v2-shinkai", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV2Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/style_transfer/animegan2/shinkai.onnx");
        // 动漫风格迁移(AnimeGANv2-Paprika)：将照片转换为今敏/Paprika 风格（梦幻、色彩浓郁）；适用艺术创作、特效处理
        reg("anime-gan-v2-paprika", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV2Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/style_transfer/animegan2/paprika.onnx");
        // 动漫风格迁移(AnimeGANv2-FacePortrait)：专为人像优化的动漫化模型，人脸细节保留更好；适用人像动漫化、头像制作
        reg("anime-gan-v2-face-portrait", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV2NchwTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/style_transfer/animegan2/face_portrait_v2.onnx");
        // 图像质量评估(NIMA)：对图片美学质量打分（1-10分），评分越高越美观；适用图片筛选、封面推荐、摄影评分
        reg("nima", "com.chua.deeplearning.support.onnx.assessment.NimaTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/assessment/nima/nima.onnx", "https://huggingface.co/onnx-community/NIMA/resolve/main/onnx/model.onnx", false, null);
        // 动漫/真人分类：判断图片是二次元动漫还是真实照片；适用内容路由、图片分类、预处理过滤
        reg("anime-real-cls", "com.chua.deeplearning.support.onnx.classification.AnimeRealClsTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/anime/anime_real_cls/mobilenetv3_v1.4_dist/model.onnx");
        // 通用标签分类(CLIP Tagger)：为图片打多个标签（如"猫"、"风景"、"室内"），基于 CLIP 零样本；适用图片归档、标签推荐
        // 自动标签（WD-v1-4 MoAT Tagger V2）：6k+ tags 动漫/通用图像打标，嵌入 289MB，ImageClassifier；适用自动标注`n        reg("moat-tagger-v2", "com.chua.deeplearning.support.onnx.classification.MoatTaggerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/tagging/wd-v1-4-moat/model.onnx");`n        reg("cl-tagger", "com.chua.deeplearning.support.onnx.classification.ClTaggerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/enhancement/cl_tagger_1_02/cl_tagger_1_02_optimized.onnx");
        // 情感分析(DistilBERT)：对文本进行情感二分类（正面/负面），轻量级；适用评论分析、舆情监控、用户反馈
        reg("distil-bert-sentiment", "com.chua.deeplearning.support.onnx.classification.DistilBertSentimentTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/distilbert-sst2/model_quantized.onnx");
        // 图像分类(EfficientNet-Lite0)：1000 类 ImageNet 分类，轻量级速度快；适用通用物体识别、图片内容理解
        reg("efficient-net-lite0-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/efficientnet/efficientnet-lite0-11.onnx", "https://huggingface.co/onnx-community/efficientnet-lite0-11-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 图像分类(EfficientNet-Lite4)：1000 类 ImageNet 分类，lite4 精度更高但计算量更大；适用需要高精度的通用分类场景
        reg("efficient-net-lite4-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite4ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/efficientnet/efficientnet-lite4-11.onnx");
        // 零样本分类(SigLIP)：无需训练，任意文本类别列表即可分类（如"猫/狗/鸟"），基于 SigLIP 视觉语言模型；适用动态类别、开放词汇分类
        reg("siglip-zero-shot-classification", "com.chua.deeplearning.support.onnx.classification.SiglipZeroShotClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/zeroshot/siglip-base-patch16-224/onnx/model.onnx");
        // 中文CLIP图像特征(CN-CLIP ViT-B/16)：提取中文 CLIP 的图像特征向量（512维），与中文文本特征比对；适用中文图文检索、跨模态匹配
        reg("cn-clip-image", "com.chua.deeplearning.support.onnx.clip.CnClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/cn-clip-vit-b-16/vit-b-16.img.dyn.fp16.onnx", "https://hf-mirror.com/chtk/chua-dl-models/resolve/main/cn-clip/vit-b-16/vit-b-16.img.dyn.fp16.onnx", false, null);
        // 中文CLIP文本特征(CN-CLIP ViT-B/16)：提取中文 CLIP 的文本特征向量，与图像特征比对；适用中文图文检索、文本到图像匹配
        reg("cn-clip-text", "com.chua.deeplearning.support.onnx.clip.CnClipTextFeatureTranslator", String.class, float[].class, Object.class, "vision/zeroshot/cn-clip-vit-b-16/vit-b-16.txt.fp16.onnx", "https://hf-mirror.com/chtk/chua-dl-models/resolve/main/cn-clip/vit-b-16/vit-b-16.txt.fp16.onnx", false, null);
        // 中文CLIP图像特征(CN-CLIP ViT-L/14)：提取中文 CLIP 的图像特征向量（768维，高精度），与中文文本特征比对；适用中文图文检索、跨模态匹配
        reg("cn-clip-vit-l-14-image", "com.chua.deeplearning.support.onnx.clip.CnClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/cn-clip-vit-l-14/vit-l-14.img.dyn.fp16.onnx", "https://hf-mirror.com/chtk/chua-dl-models/resolve/main/cn-clip/vit-l-14/vit-l-14.img.dyn.fp16.onnx", false, null);
        // 中文CLIP文本特征(CN-CLIP ViT-L/14)：提取中文 CLIP 的文本特征向量（768维，高精度），与图像特征比对；适用中文图文检索、跨模态匹配
        reg("cn-clip-vit-l-14-text", "com.chua.deeplearning.support.onnx.clip.CnClipTextFeatureTranslator", String.class, float[].class, Object.class, "vision/zeroshot/cn-clip-vit-l-14/vit-l-14.txt.fp16.onnx", "https://hf-mirror.com/chtk/chua-dl-models/resolve/main/cn-clip/vit-l-14/vit-l-14.txt.fp16.onnx", false, null);
        // 中文CLIP-RN50图像特征(CN-CLIP)：提取中文 CLIP RN50 的图像特征向量（1024维），与文本特征比对；适用中文图文检索、跨模态匹配
        reg("cn-clip-rn50-image", "com.chua.deeplearning.support.onnx.clip.CnClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/cn-clip-rn50/rn50.img.b1.fp16.onnx");
        // 中文CLIP-RN50文本特征(CN-CLIP)：提取中文 CLIP RN50 的文本特征向量（1024维），与图像特征比对；适用中文图文检索、跨模态匹配
        reg("cn-clip-rn50-text", "com.chua.deeplearning.support.onnx.clip.CnClipTextFeatureTranslator", String.class, float[].class, Object.class, "vision/zeroshot/cn-clip-rn50/rn50.txt.fp16.onnx");
        // 中文CLIP-ViT-H/14图像特征(CN-CLIP)：提取中文 CLIP ViT-H/14 的图像特征向量（1024维，最准模型），与文本特征比对；适用高精度中文图文检索、跨模态匹配
        reg("cn-clip-vit-h-14-image", "com.chua.deeplearning.support.onnx.clip.CnClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/cn-clip-vit-h-14/vit-h-14.img.b1.fp32.onnx", null, false, null);
        // 语言检测(XLM-RoBERTa)：检测文本的语言种类（如中文、英文、日文等），多语言；适用文本预处理、多语言路由
        reg("xlm-roberta-language-detection", "com.chua.deeplearning.support.onnx.classification.XlmRobertaLanguageDetectionTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/xlm-roberta-language-detection/model_quantized.onnx");
        // CLIP文本特征(CLIP-ViT-B-32)：提取英文 CLIP 文本特征向量（512维），与 CLIP 图像特征比对；适用英文图文检索、跨模态搜索
        reg("clip-text-feature", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "vision/enhancement/CLIP-ViT-B-32-TEXT/model.onnx");
        // 深度估计(MiDaS)：从单张图片估计深度图（灰度图，越亮表示越近）；适用背景虚化、3D 重建、AR 效果
        reg("midas-depth", "com.chua.deeplearning.support.onnx.depth.MidasDepthTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/depth/midas/midas.onnx", "https://huggingface.co/onnx-community/MiDaS/resolve/main/onnx/model.onnx", false, null);
        // 视觉特征提取(DINOv2)：提取图像通用特征向量（384维），无需训练，适合图像相似度比对、检索；适用以图搜图、特征匹配
        reg("dino-v2", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov2-small/onnx/model.onnx", "https://huggingface.co/chtk/chua-dl-models/resolve/main/vision/feature/dinov2-small/onnx/model.onnx", java.util.List.of("https://hf-mirror.com/chtk/chua-dl-models/resolve/main/vision/feature/dinov2-small/onnx/model.onnx"), false, null);
        // 视觉特征提取(DINOv2-small emb)：DINOv2-small，384维特征；优先 jar 内嵌，缺失时自动下载
        reg("dino-v2-small-embedding", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov2-small/onnx/model.onnx", "https://huggingface.co/chtk/chua-dl-models/resolve/main/vision/feature/dinov2-small/onnx/model.onnx", java.util.List.of("https://hf-mirror.com/chtk/chua-dl-models/resolve/main/vision/feature/dinov2-small/onnx/model.onnx"), false, null);
        // 视觉特征提取(DINOv2-small fp16)：DINOv2 ViT-S/14，384维特征，fp16 半精度，内存减半、推理更快；适用内存受限的以图搜图、特征比对
        reg("dino-v2-small-embedding-fp16", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov2-small/onnx/model_fp16.onnx");
        // 视觉特征提取(DINOv2-base)：DINOv2 ViT-B/14，768维特征，精度更高；适用高精度以图搜图、特征比对
        reg("dino-v2-base-embedding", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov2-base/onnx/model.onnx", "https://huggingface.co/onnx-community/dinov2-base-ONNX/resolve/main/onnx/model.onnx", java.util.List.of("https://hf-mirror.com/onnx-community/dinov2-base-ONNX/resolve/main/onnx/model.onnx"), false, null);
        // 视觉特征提取(DINOv2-large)：DINOv2 ViT-L/14，1024维特征，最高精度；适用高精度检索/匹配
        reg("dino-v2-large-embedding", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov2-large/onnx/model.onnx", "https://huggingface.co/onnx-community/dinov2-large-ONNX/resolve/main/onnx/model.onnx", java.util.List.of("https://hf-mirror.com/onnx-community/dinov2-large-ONNX/resolve/main/onnx/model.onnx"), false, null);
        // 文档理解(Donut)：端到端文档理解模型，输入文档图片输出结构化文本；适用发票识别、表单解析、文档 OCR
        reg("donut", "com.chua.deeplearning.support.onnx.donut.DonutTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/donut/donut.onnx");
        // 情绪识别(FER+)：识别面部表情（开心、难过、生气等 7 种基础情绪）；适用情感分析、用户反馈、安防监控
        reg("emotion-ferplus", "com.chua.deeplearning.support.onnx.emotion.EmotionFerplusTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/expression/FER/FER.onnx");
        // 人脸识别(ArcFace)：提取人脸特征向量（512维），用于人脸比对/识别/搜索；适用人脸门禁、人脸搜索、身份验证
        reg("arc-face", "com.chua.deeplearning.support.onnx.face.ArcFaceTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/swap/common/buffalo_l/w600k_r50.onnx", "https://huggingface.co/onnx-community/arcface/resolve/main/onnx/model.onnx", false, null);
        // 人脸识别(AdaFace)：高质量人脸特征提取，对低质量/模糊人脸更鲁棒；适用复杂光照条件的人脸识别
        reg("ada-face", "com.chua.deeplearning.support.onnx.face.AdaFaceTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/adaface/adaface_ir101_webface12m.onnx", "https://huggingface.co/miccai/adaface-ir101-webface12m/resolve/main/model.onnx", false, null);
        // 人脸识别(InsightFace AdaFace)：buffalo_l AdaFace，112 输入 512 维；适用高精度人脸识别/比对
        reg("insightface-adaface", "com.chua.deeplearning.support.onnx.insightface.InsightFaceAdaFaceTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "models/onnx/face/recognition/adaface/adaface.onnx");
        // 人脸关键点(InsightFace 2D106)：buffalo_l 2d106det，192 输入 106 点；适用人脸对齐、美颜、换脸
        reg("insightface-landmark-2d106", "com.chua.deeplearning.support.onnx.insightface.InsightFaceLandmarkTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "models/onnx/face/landmark/2d106/2d106det.onnx");
        // 性别年龄(InsightFace)：buffalo_l genderage，96 输入，输出 [女性,男性,年龄]；适用人脸属性分析
        reg("insightface-genderage", "com.chua.deeplearning.support.onnx.insightface.InsightFaceGenderAgeTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "models/onnx/face/attribute/genderage/genderage.onnx");
        // 3D关键点(InsightFace 1K3D68)：buffalo_l 1k3d68，192 输入 1103 顶点；适用 3D 人脸重建、姿态估计
        reg("insightface-3d68", "com.chua.deeplearning.support.onnx.insightface.InsightFace3d68Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "models/onnx/face/landmark/3d68/1k3d68.onnx");
        
        // 人脸特征提取(R50FaceFeature)：ResNet50 架构的人脸特征提取，精度更高；适用高精度人脸识别
        reg("r50-face-feature", "com.chua.deeplearning.support.onnx.face.R50FaceFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/face_feature_sdk/face_feature_r50.onnx", "https://huggingface.co/onnx-community/arcface/resolve/main/onnx/model.onnx", false, null);
        // 人脸检测(SCRFD)：快速高精度人脸检测，输出人脸框+关键点（5点）；适用人脸检测、face crop 前置
        reg("scrfd-face-detector", "com.chua.deeplearning.support.onnx.face.ScrfdFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/scrfd/2.5g_bnkps.onnx", "https://huggingface.co/chtk/chua-dl-models/resolve/main/download/scrfd-face-detector/2.5g_bnkps.onnx", java.util.List.of("https://hf-mirror.com/chtk/chua-dl-models/resolve/main/download/scrfd-face-detector/2.5g_bnkps.onnx"), false, null);
        // 人脸检测(OpenCV UltraFace)：超轻量人脸检测（~1MB），适合嵌入式/移动端；适用快速人脸检测
        reg("opencv-face", "com.chua.deeplearning.support.onnx.face.UltraFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.face.FaceDetector.class, "face/detection/ultraface/ultraface.onnx");
        // 人脸检测(InsightFace SCRFD)：buffalo_l 标准 SCRFD-10g，640 输入，人脸框+5 点关键点；适用检测+对齐+识别前置
        reg("insightface-scrfd", "com.chua.deeplearning.support.onnx.face.ScrfdFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "models/onnx/face/detection/scrfd/scrfd.onnx");
        // 人脸检测(RetinaFace R34)：ResNet34 骨干，输出人脸框+5 点关键点；适用人脸检测/对齐
        reg("retinaface-r34", "com.chua.deeplearning.support.onnx.face.OnnxRetinaFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/retinaface/retinaface_r34.onnx");
        // 人脸检测(TinaFace R50)：ResNet50+GN+DCN 骨干，IoU-aware 评分，6 级 FPN+Inception 颈部；适用高精度人脸检测
        reg("tinaface", "com.chua.deeplearning.support.onnx.face.TinaFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/tinaface/tinaface_r50.onnx");
        // 人脸分割(ParseNet)：AIAS traced 导出，输出人脸软 mask；适用修复后贴回原图
        reg("onnx-parsenet", "com.chua.deeplearning.support.onnx.face.OnnxFaceSegTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, com.chua.deeplearning.support.image.ImageEnhancer.class, "face/segmentation/parsenet/parsenet.onnx");
        // CLIP图像特征(CLIP-ViT-B-32)：提取英文 CLIP 图像特征向量（512维），与 CLIP 文本特征比对；适用图文检索、图像零样本分类
        reg("clip-image-feature", "com.chua.deeplearning.support.onnx.feature.ClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/enhancement/CLIP-ViT-B-32-IMAGE/CLIP-ViT-B-32-IMAGE.onnx", "https://huggingface.co/onnx-community/clip-vit-base-patch32-ONNX/resolve/main/onnx/image_model.onnx", java.util.List.of("https://hf-mirror.com/onnx-community/clip-vit-base-patch32-ONNX/resolve/main/onnx/image_model.onnx"), false, null);
        // CLIP图像特征(MobileCLIP-S0)：轻量级 CLIP 图像特征提取（~50MB），适合移动端/边缘设备；适用移动端图文检索
        reg("mobile-clip-image-feature", "com.chua.deeplearning.support.onnx.feature.MobileClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/mobileclip_s0/onnx/vision_model.onnx", "https://huggingface.co/onnx-community/mobileclip_s0-ONNX/resolve/main/onnx/vision_model.onnx", java.util.List.of("https://hf-mirror.com/onnx-community/mobileclip_s0-ONNX/resolve/main/onnx/vision_model.onnx"), false, null);
        // CLIP图像特征(MobileCLIP-S0 embedded)：嵌入式版 MobileCLIP-S0 图像特征（~11MB quantized），jar 内嵌；适用离线图文检索
        reg("mobileclip-s0-vision", "com.chua.deeplearning.support.onnx.feature.MobileClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/mobileclip_s0/onnx/vision_model_quantized.onnx");
        // 性别识别(GoogleNet)：根据人脸图像识别性别（男/女）；适用人脸属性分析、安防系统
        // 性别识别(VGG)：VGG 架构的人脸性别识别，精度更高；适用高精度人脸属性分析
        reg("vgg-gender-recognition", "com.chua.deeplearning.support.onnx.gender.VggGenderRecognitionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/gender/vgg_ilsvrc_16_gender_imdb_wiki.onnx");
        // 图像生成(LCM-LoRA UNet)：潜在一致性模型 UNet，从噪声生成图像；适用文本到图像生成（需配合 VAE 和 text encoder）
        reg("lcm-lora-unet", "com.chua.deeplearning.support.onnx.generation.LcmLoraUnetTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/lcm/lora/unet.onnx");
        // 图像生成(LCM-LoRA VAE Decoder)：VAE 解码器，将潜变量解码为最终图像；适用图像生成流程的后处理
        reg("lcm-lora-vae-decoder", "com.chua.deeplearning.support.onnx.generation.LcmLoraVaeDecoderTranslator", ai.djl.ndarray.NDList.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/lcm/lora/vae_decoder.onnx");
        // 图像生成(LCM-LoRA VAE Encoder)：VAE 编码器，将图像编码为潜变量；适用图像编辑、图像变化
        reg("lcm-lora-vae-encoder", "com.chua.deeplearning.support.onnx.generation.LcmLoraVaeEncoderTranslator", ai.djl.modality.cv.Image.class, ai.djl.ndarray.NDList.class, Object.class, "vision/detection/lcm/lora/vae_encoder.onnx");
        // 文本编码器(Small-SD)：小型 Stable Diffusion 文本编码器，将文本提示编码为条件向量；适用轻量级文生图
        // 权重：subpixel/small-stable-diffusion-v0-onnx-ort-web（OFA-Sys/small-stable-diffusion-v0 的 ONNX 转换）
        reg("small-sd-text-encoder", "com.chua.deeplearning.support.onnx.generation.SmallSdTextEncoderTranslator", String.class, ai.djl.ndarray.NDList.class, Object.class, "vision/detection/small-sd/text_encoder/model.onnx",
                "https://huggingface.co/subpixel/small-stable-diffusion-v0-onnx-ort-web/resolve/main/text_encoder/model.onnx", false, null);
        // 图像生成(Small-SD UNet)：小型 Stable Diffusion 的 UNet 去噪网络；注意需将 unet/weights.pb（外部权重）手动放置到同目录
        reg("small-sd-unet", "com.chua.deeplearning.support.onnx.generation.SmallSdUnetTranslator", ai.djl.ndarray.NDList.class, ai.djl.ndarray.NDList.class, Object.class, "vision/detection/small-sd/unet/model.onnx",
                "https://huggingface.co/subpixel/small-stable-diffusion-v0-onnx-ort-web/resolve/main/unet/model.onnx", false, null);
        // 图像生成(Small-SD VAE Decoder)：小型 SD 的 VAE 解码器，将潜变量解码为图像；适用轻量级文生图后处理
        reg("small-sd-vae-decoder", "com.chua.deeplearning.support.onnx.generation.SmallSdVaeDecoderTranslator", ai.djl.ndarray.NDList.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/small-sd/vae_decoder/model.onnx",
                "https://huggingface.co/subpixel/small-stable-diffusion-v0-onnx-ort-web/resolve/main/vae_decoder/model.onnx", false, null);
        // 文本到图像生成(Small-SD Combined 全流程编排)：文本编码 → CFG 引导 DDIM 去噪 → VAE 解码；
        // 主模型为文本编码器，UNet/VAE/tokenizer.json 由编排器自动下载（fp32 约 3GB，含 UNet 外部权重 weights.pb）
        reg("small-stable-diffusion-combined", "com.chua.deeplearning.support.onnx.generation.SmallStableDiffusionCombinedTranslator", String.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/small-sd/text_encoder/model.onnx",
                "https://huggingface.co/subpixel/small-stable-diffusion-v0-onnx-ort-web/resolve/main/text_encoder/model.onnx", false, null);
        // 图像生成(TAESD Decoder)：微型 VAE 解码器，极轻量级，用于快速解码潜变量；适用快速图像预览
        reg("taesd-decoder", "com.chua.deeplearning.support.onnx.generation.TaesdDecoderTranslator", ai.djl.ndarray.NDList.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/taesd/decoder.onnx");
        // 素描转换(ImageToLineDrawing)：将照片转换为线条素描风格；适用艺术创作、素描特效
        reg("image-to-line-drawing", "com.chua.deeplearning.support.onnx.linedrawing.ImageToLineDrawingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/image-to-line-drawing-onnx/image-to-line-drawing-onnx.onnx");
                        // 活体检测(FLRGB)：ModelScope 官方 RGB 活体模型，112x112 输入，输出活体概率；模型内嵌 jar（utils-support-models-onnx-face-liveness）
        reg("face-liveness-flrgb", "com.chua.deeplearning.support.onnx.liveness.FlRgbLivenessTranslator", ai.djl.modality.cv.Image.class, Float.class, com.chua.deeplearning.support.liveness.LivenessDetector.class, "face/liveness/flrgb/model.onnx");
        // 活体检测(FLXC)：ModelScope 官方炫彩活体模型，12通道多帧序列，对3D面具/头模攻击鲁棒；模型内嵌 jar（utils-support-models-onnx-face-liveness-flxc）
        reg("face-liveness-flxc", "com.chua.deeplearning.support.onnx.liveness.FlXcLivenessTranslator", ai.djl.modality.cv.Image.class, Float.class, com.chua.deeplearning.support.liveness.LivenessDetector.class, "face/liveness/flxc/model.onnx");
        // 活体检测(人脸反欺诈)：通用人脸活体检测别名，指向 FLRGB 模型；适用 FaceIdentify 等场景
        reg("face-anti-spoof", "com.chua.deeplearning.support.onnx.liveness.FlRgbLivenessTranslator", ai.djl.modality.cv.Image.class, Float.class, com.chua.deeplearning.support.liveness.LivenessDetector.class, "face/liveness/flrgb/model.onnx");
        // 抠图(U2Net)：通用前景抠图，输出 alpha 通道（RGBA）；适用证件照处理、背景替换、电商抠图
        reg("matting", "com.chua.deeplearning.support.onnx.matting.translator.MattingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/seg_unet_sdk/u2net.onnx", "https://huggingface.co/onnx-community/u2net/resolve/main/onnx/model.onnx", false, null);
        // 抠图(MODNet)：人像抠图/前景分割（~25MB），嵌入式 jar 版，来自 ModelScope；适用人像抠图、视频会议背景替换
        reg("modnet", "com.chua.deeplearning.support.onnx.matting.translator.DjlMattingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/matting/modnet/onnx/model.onnx");
        // 抠图(RMBG-2.0)：BRIA 背景移除 v2.0，高质量抠图，需自动下载；适用电商图片、人像抠图
        reg("rmbg20", "com.chua.deeplearning.support.onnx.matting.translator.Rmbg20Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/RMBG-2.0/onnx/model.onnx", "https://huggingface.co/briaai/RMBG-2.0/resolve/main/onnx/model.onnx", false, null);
        // 抠图(RMBG-1.4)：BRIA 背景移除 v1.4，42MB 嵌入式，效果接近 2.0；适用离线抠图、嵌入式设备
        reg("rmbg14", "com.chua.deeplearning.support.onnx.matting.translator.Rmbg20Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/RMBG-1.4/onnx/model_quantized.onnx");
        // 抠图(U2Net 嵌入)：F:\models/u2net.onnx，通用前景抠图；320×320
        reg("matting-u2net", "com.chua.deeplearning.support.onnx.matting.translator.U2netSegTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "seg/u2net/u2net.onnx");
        // 抠图(U2Netp 轻量嵌入)：F:\models/u2netp.onnx，4.5MB，320×320
        reg("matting-u2netp", "com.chua.deeplearning.support.onnx.matting.translator.U2netSegTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "seg/u2netp/u2netp.onnx");
        // 抠图(ISNet 通用)：F:\models/isnet-general-use.onnx，178MB，1024×1024
        reg("matting-isnet", "com.chua.deeplearning.support.onnx.matting.translator.IsnetSegTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "seg/isnet/isnet.onnx");
        // 动漫人像分割：F:\models/anime.onnx，176MB，1024×1024
        reg("anime-seg", "com.chua.deeplearning.support.onnx.matting.translator.IsnetSegTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "seg/anime/anime.onnx");
        // 衣物分割(U2Net Cloth)：F:\models/cloth.onnx，176MB，768×768 4通道输出；适用服装换装、虚拟试衣
        reg("cloth-seg", "com.chua.deeplearning.support.onnx.matting.translator.ClothSegTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "seg/cloth/cloth.onnx");
        // 人像全身分割：F:\models/human.onnx，176MB，320×320
        reg("human-seg", "com.chua.deeplearning.support.onnx.matting.translator.U2netSegTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "seg/human/human.onnx");
        // SAM2-tiny：encoder/decoder 双文件交互式分割，需点提示；暂不注册（适合人机交互场景）
        // 超分辨率(Nomos2)：4x 图像超分辨率，增强动漫/二次元图片细节；适用动漫放大、老旧图片修复
        reg("nomos2", "com.chua.deeplearning.support.onnx.nomos2.Nomos2Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/esrgan/4xNomos2_otf_esrgan_fp32_opset17.onnx");
        // OCR方向检测(PP-OCR)：检测文本方向（0°/90°/180°/270°），PaddleOCR 预处理；适用 OCR 流水线前置
        // #DISABLED# pp-word-rotate 已禁用（模型未随 jar 分发，避免注册后加载失败）
        // reg("pp-word-rotate", "com.chua.deeplearning.support.onnx.ocr.direction.PpWordRotateTranslator", byte[].class, com.chua.deeplearning.support.onnx.ocr.direction.DirectionInfo.class, Object.class, "ocr/direction/ppocr_cls/model.onnx");
        // OCR文字识别(PP-OCR Server)：PP-OCRv5 服务器版文字识别，精度高但较慢；适用高精度 OCR
        reg("pp-word-extractor", "com.chua.deeplearning.support.onnx.ocr.extractor.PpWordExtractorTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/recognition/PP-OCRv5_server_rec_infer/PP-OCRv5_server_rec.onnx");
        // OCR文字识别(SVTR)：SVTR 架构轻量级文字识别，速度更快；适用快速 OCR
        reg("svtr-extractor", "com.chua.deeplearning.support.onnx.ocr.extractor.SvtrExtractorTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/recognition/PP-OCRv5_mobile_rec_infer/PP-OCRv5_mobile_rec_infer.onnx");
        // 版面分析(PP-DocLayoutV3)：PaddleOCR 官方文档版面分析（DETR，25 类区域），模型内嵌 jar（utils-support-models-onnx-ppdoclayoutv3）
        reg("pp-doc-layout", "com.chua.deeplearning.support.onnx.ocr.layout.PpDocLayoutTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/pp_doc_layoutv3/PP-DocLayoutV3.onnx");
        // 版面分析(PP-DocLayout-L)：RT-DETR-L，23 类区域，640×640 输入，mAP 90.4% 最高精度；模型内嵌 jar（utils-support-models-onnx-ppdoclayout-l）
        reg("pp-doc-layout-l", "com.chua.deeplearning.support.onnx.ocr.layout.PpDocLayoutLTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/pp_doc_layout_l/PP-DocLayout-L.onnx", "https://huggingface.co/chtk/chua-dl-models/resolve/main/vision/detection/pp_doc_layout_l/PP-DocLayout-L.onnx", java.util.List.of("https://hf-mirror.com/chtk/chua-dl-models/resolve/main/vision/detection/pp_doc_layout_l/PP-DocLayout-L.onnx"), false, null);
        // 版面分析(PP-DocLayout_plus-L)：RT-DETR-L，21 类区域，800×800 输入，mAP 83.2%；模型内嵌 jar（utils-support-models-onnx-ppdoclayout-plus-l）
        reg("pp-doc-layout-plus-l", "com.chua.deeplearning.support.onnx.ocr.layout.PpDocLayoutLTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/pp_doc_layout_plus_l/PP-DocLayout_plus-L.onnx", "https://huggingface.co/chtk/chua-dl-models/resolve/main/vision/detection/pp_doc_layout_plus_l/PP-DocLayout_plus-L.onnx", java.util.List.of("https://hf-mirror.com/chtk/chua-dl-models/resolve/main/vision/detection/pp_doc_layout_plus_l/PP-DocLayout_plus-L.onnx"), false, null);
        // 版面分析(DocLayout-YOLO)：YOLOv10 文档版面检测（DocStructBench 10 类，含 title），模型内嵌 jar；适用试卷/文档版面分析
        reg("doc-layout-yolo", "com.chua.deeplearning.support.onnx.yolo.v10.translator.DocLayoutYoloTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/doclayout-yolo/model.onnx");
        // OCR文字识别(PP-OCRv5 Server)：PaddleOCR 文字识别完整版；适用端到端 OCR
        reg("paddle-ocr-recognition", "com.chua.deeplearning.support.onnx.ocr.paddleocr.PaddleOcrRecognitionTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/ppocrv5-server-rec.onnx");
        // OCR文字识别(PP-OCRv4 Mobile)：PP-OCRv4 移动端版文字识别，轻量级；适用移动端 OCR
        reg("pp-ocr-rec", "com.chua.deeplearning.support.onnx.ocr.PpOcrRecTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/ch_PP-OCRv4_rec_infer.onnx");
        // 表格结构识别(TableStruct)：表格结构识别，与 PP-Structure 类似；适用表格文档解析
        reg("table-struct", "com.chua.deeplearning.support.onnx.ocr.table.TableStructTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "ocr/table_structure/pp_structure_v2/inference.onnx");
        // 开放词汇检测(OWLv2)：用任意文本描述检测图像中的物体（如"红色汽车"），无需训练；适用零样本检测、开放词汇目标检测
        reg("owlv2-zero-shot-detector", "com.chua.deeplearning.support.onnx.owlv2.Owlv2ZeroShotDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/owlv2_base_patch16/onnx/model.onnx", "https://huggingface.co/onnx-community/owlv2-base-patch16-ONNX/resolve/main/onnx/model.onnx", false, "owlv2-base-patch16.onnx");
        // 车牌识别(CRNN)：基于 CRNN 的车牌字符识别；适用停车场、交通监控
        reg("crnn-plate-rec", "com.chua.deeplearning.support.onnx.plate.translator.CrnnPlateRecTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/license/license-plate-finetune-v1x.onnx");
        // 超分辨率(RealWebPhoto)：4x 真实照片超分辨率，增强自然图像细节；适用老旧照片修复、图像放大
        reg("real-web-photo", "com.chua.deeplearning.support.onnx.realwebphoto.RealWebPhotoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/4xRealWebPhoto_v4/4xRealWebPhoto_v4_fp32_opset17.onnx");
        // 手势关键点(HandPose)：检测手部 21 个关键点坐标，用于手势识别；适用手势控制、手语识别
        reg("hand-pose", "com.chua.deeplearning.support.onnx.reid.HandPoseTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/handpose/handpose_estimation_mediapipe_2023feb.onnx");
        // 行人重识别(OSNet ReID)：提取行人特征向量，用于行人检索/跨摄像头追踪；适用人员追踪、安防监控
        reg("osnet-reid", "com.chua.deeplearning.support.onnx.reid.OsnetReidTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/reid/osnet_ain_multisource.onnx");
        // 人脸超分(GFPGAN)：人脸修复/增强，修复模糊、低分辨率人脸；适用老照片修复、人脸增强
        // 人脸修复(GFPGAN v1.3 clean)：重写 forward 规避 double 域，onnxruntime 可运行、无偏色；适用人脸修复/贴回
        reg("onnx-gfpgan", "com.chua.deeplearning.support.onnx.resolution.GfpganFaceSuperResolutionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, com.chua.deeplearning.support.image.ImageEnhancer.class, "face/restoration/gfpgan/GFPGANv1.3_clean.onnx");
        // 人脸超分(GFPGAN 别名)：指向 GFPGAN v1.3 clean，用于 ImagePipeline.superResolution() 等场景
        reg("gfpgan-face-super-resolution", "com.chua.deeplearning.support.onnx.resolution.GfpganFaceSuperResolutionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, com.chua.deeplearning.support.image.ImageEnhancer.class, "face/restoration/gfpgan/GFPGANv1.3_clean.onnx");
        // 文字超分(TextBSR)：针对文字图片的超分辨率，增强文字清晰度；适用 OCR 预处理、文檔增强
        reg("image-text-super-resolution", "com.chua.deeplearning.support.onnx.resolution.ImageTextSuperResolutionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "nlp/general/models/textbsr_traced_model.onnx");
        // 图像去模糊(NAFNet)：去除运动模糊/对焦模糊，恢复清晰图像；适用照片修复、监控图像增强
        reg("naf-net", "com.chua.deeplearning.support.onnx.resolution.NafNetTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/nafnet/nafnet_deblur_gopro.onnx");
        // 超分辨率(Real-ESRGAN)：通用 4x 超分辨率，增强图像细节；适用图片放大、老照片修复
        reg("real-esrgan", "com.chua.deeplearning.support.onnx.resolution.RealEsrganTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/esrgan/realesrgan_x4plus.onnx", "https://huggingface.co/onnx-community/Real-ESRGAN/resolve/main/onnx/model.onnx", false, null);
        // 文字超分(TextBSR)：文字图片专用超分辨率；适用文档扫描增强
        reg("real-text-image-super-resolution", "com.chua.deeplearning.support.onnx.resolution.RealTextImageSuperResolutionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "nlp/general/models/textbsr_traced_model.onnx");
        // 文字超分(TextBSR)：文字超分辨率别名；适用文档增强
        reg("text-bsr", "com.chua.deeplearning.support.onnx.resolution.TextBsrTranslator", byte[].class, java.awt.image.BufferedImage.class, Object.class, "vision/text_restore/textbsr/textbsr.onnx");
        // 动漫超分(Waifu2x)：针对动漫/二次元图片的 2x 超分辨率；适用动漫放大、老旧动漫修复
        reg("waifu2x", "com.chua.deeplearning.support.onnx.resolution.Waifu2xTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/waifu2x/waifu2x_noise.onnx", "https://huggingface.co/onnx-community/waifu2x/resolve/main/onnx/model.onnx", false, null);
        // 文档理解(SmolDocling Combined)：端到端文档理解，输入文档图片输出结构化文本（Markdown）；适用发票、报表、合同解析
        reg("smol-docling-combined", "com.chua.deeplearning.support.onnx.smoldocling.SmolDoclingCombinedTranslator", Object.class, String.class, Object.class, "vision/enhancement/smoldocling/vision_encoder.onnx");
        // 文档理解(SmolDocling Decoder)：解码器组件，用于文本序列生成；适用 SmolDocling 流程中的解码阶段
        reg("smol-docling-decoder", "com.chua.deeplearning.support.onnx.smoldocling.SmolDoclingDecoderTranslator", Object.class, Object.class, Object.class, "vision/enhancement/smoldocling/decoder_model_merged.onnx");
        // 文档理解(SmolDocling Embed)：文本嵌入层，将 token 映射为向量；适用 SmolDocling 的嵌入输入
        reg("smol-docling-embed", "com.chua.deeplearning.support.onnx.smoldocling.SmolDoclingEmbedTranslator", long[].class, Object.class, Object.class, "vision/enhancement/smoldocling/embed_tokens.onnx");
        // 文档理解(SmolDocling Vision)：视觉编码器，提取文档图像特征；适用 SmolDocling 的视觉输入
        reg("smol-docling-vision", "com.chua.deeplearning.support.onnx.smoldocling.SmolDoclingVisionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/smoldocling/vision_encoder.onnx");
        // 问答(BERT SQuAD)：基于 BERT 的阅读理解，输入问题+文本，输出答案；适用文档问答、智能客服
        reg("bert-squad", "com.chua.deeplearning.support.onnx.text.BertSquadTranslator", String.class, String.class, Object.class, "nlp/generation/bert-squad/model.onnx");
        // 文本生成(GPT2)：小型 GPT-2 文本生成模型，续写文本；适用文本生成、对话、内容创作
        reg("gpt2", "com.chua.deeplearning.support.onnx.text.gpt2.Gpt2Translator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/generation/gpt2/decoder_with_past_model.onnx");
        // 零样本分类(MobileBERT)：轻量级零样本文本分类，不需要训练数据；适用文本分类、意图识别
        reg("mobile-bert-zero-shot-classification", "com.chua.deeplearning.support.onnx.text.MobileBertZeroShotClassificationTranslator", String.class, String.class, Object.class, "nlp/classification/mobilebert-mnli/onnx/model_quantized.onnx");
        // 3D视觉(VGGT)：单张图片恢复 3D 场景几何结构；适用 3D 重建、空间感知
        reg("vggt-combined", "com.chua.deeplearning.support.onnx.vggt.VggtCombinedTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/vggt/vggt_combined.onnx");
        // 3D视觉(VGGT Output)：VGGT 输出处理；适用 3D 几何输出处理
        reg("vggt-output", "com.chua.deeplearning.support.onnx.vggt.VggtOutputTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/vggt/vggt_output.onnx");
        // 3D视觉(VGGT)：VGGT 主模型的简化版；适用基础 3D 感知
        reg("vggt", "com.chua.deeplearning.support.onnx.vggt.VggtTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/vggt/vggt.onnx");
        // 图像分类(YOLOv12n-cls)：YOLO 架构的图像分类，轻量级；适用快速通用分类
        reg("yolo-cls", "com.chua.deeplearning.support.onnx.yolo.cls.YoloClsTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/yolov12n-cls.onnx");
        // 旋转目标检测(YOLOv11-OBB)：YOLOv11 旋转框检测，检测任意方向物体；适用遥感图像、旋转物体检测
        reg("yolo11-odd", "com.chua.deeplearning.support.onnx.yolo.v11.translator.Yolo11OddTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/obb/yolo11n-obb.onnx", "https://huggingface.co/onnx-community/yolo11n-obb/resolve/main/onnx/model.onnx", false, null);
        // 旋转目标检测(YOLOv26-OBB)：YOLOv26 旋转框检测，最新版；适用高精度旋转物体检测
        reg("yolo26-obb", "com.chua.deeplearning.support.onnx.yolo.v26.translator.Yolo26ObbTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/v26/yolo26n.onnx", "https://huggingface.co/onnx-community/yolo26n-ONNX/resolve/main/onnx/model.onnx",
                java.util.List.of("https://hf-mirror.com/onnx-community/yolo26n-ONNX/resolve/main/onnx/model.onnx"), false, "model.onnx");
        // 目标检测(YOLOv8s)：YOLOv8s 标准 COCO 80 类检测，速度和精度平衡；适用通用物体检测
        reg("yolov8s", "com.chua.deeplearning.support.onnx.yolo.v8.translator.YoloV8sTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v8/yolov8s.onnx", "https://huggingface.co/lquint/yolov8s-onnx/resolve/main/onnx/model.onnx", false, "yolov8s.onnx");
        // 深度估计(DepthAnything v2)：从单张图片估计深度图，v2 版精度更高、细节更丰富；适用背景虚化、3D 场景理解
        reg("depth-anything", "com.chua.deeplearning.support.onnx.depth.DepthAnythingOrtTranslator", byte[].class, byte[].class, Object.class, "vision/depth/depth-anything-v2/model.onnx", "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx", false, null);
        // 姿态估计(ViTPose)：检测人体 17 个关键点（骨骼点），基于 Vision Transformer；适用人体姿态分析、动作识别
        reg("vit-pose", "com.chua.deeplearning.support.onnx.pose.VitPoseTranslator", ai.djl.modality.cv.Image.class, float[][].class, Object.class, "vision/pose/vitpose-base-simple/model.onnx", "https://huggingface.co/onnx-community/vitpose-base-simple/resolve/main/onnx/model.onnx", false, null);
        // 姿态估计(YOLOv8n-pose)：YOLOv8n 轻量级人体姿态估计，~3.6MB，检测 17 个关键点；适用边缘设备姿态分析、视频逐帧分析
        reg("yolov8n-pose", "com.chua.deeplearning.support.onnx.pose.YoloV8nPoseTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/pose/yolov8n/onnx/model_quantized.onnx");
        // 图像修复(SwinIR)：去噪/超分辨率，Swin Transformer 架构；适用图像去噪、修复
        reg("swinir", "com.chua.deeplearning.support.onnx.resolution.SwinIrTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/swinir/swinir_denoising_color_25.onnx", "https://huggingface.co/Heliosoph/swinir-onnx/resolve/main/swinir_denoising_color_25.onnx", false, null);
        // 人脸修复(CodeFormer)：人脸修复/增强，基于 CodeFormer 架构，修复模糊人脸；适用老照片修复、人脸增强
        reg("codeformer", "com.chua.deeplearning.support.onnx.face.CodeFormerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "face/restoration/codeformer/codeformer.onnx", "https://huggingface.co/bluefoxcreation/Codeformer-ONNX/resolve/main/codeformer.onnx", false, null);
        // SAM 图像编码器(SAM ViT-H)：SAM 的图像编码器，提取图像特征（256维），需配合 SAM 解码器使用；适用 SAM 分割流水线
        reg("sam-encoder", "com.chua.deeplearning.support.onnx.seg.SamImageEncoderTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/seg/sam_vit_h/encoder.onnx", "https://huggingface.co/vietanhdev/segment-anything-onnx-models/resolve/main/sam_vit_h_4b8939.zip", true, "encoder.onnx");
        // 开放词汇检测(GroundingDINO)：用任意文本描述检测图像中的物体，比 OWLv2 更准确；适用零样本检测、开放词汇目标检测
        reg("grounding-dino", "com.chua.deeplearning.support.onnx.dino.GroundingDinoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/grounding-dino-tiny/model.onnx", "https://huggingface.co/onnx-community/grounding-dino-tiny-ONNX/resolve/main/onnx/model.onnx",
                java.util.List.of("https://hf-mirror.com/onnx-community/grounding-dino-tiny-ONNX/resolve/main/onnx/model.onnx"), false, "model.onnx");
        // 文本摘要(T5-small)：英文摘要/生成/翻译，多任务 seq2seq；modelscope 下载
        // 注意：T5 为 encoder-decoder 自回归多文件模型（encoder/decoder/decoder_with_past + tokenizer），
        // 由 T5Seq2SeqOrtTranslator 按"嵌入/缓存/modelscope 下载"自行组装，注册不设 downloadUrl 避免单文件预下载。
        reg("t5-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.T5Seq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/t5-small/encoder_model_int8.onnx", null, null, false, null);
        // 文本摘要(T5-base)：英文摘要/生成，质量优于 t5-small（12 层 12 头）；modelscope 下载
        reg("t5-base-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.T5BaseSeq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/t5-base/encoder_model_int8.onnx", null, null, false, null);
        // 多语言摘要/生成(mT5-small)：中文/多语言文本摘要与生成；modelscope 下载（中文正式语料质量有限）
        reg("mt5-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.Mt5Seq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/mt5-small/encoder_model_fp16.onnx", null, null, false, null);
        // 多语言摘要/生成(mT5-base)：中文多句→一句总结，12 层 12 头；modelscope 下载
        reg("mt5-base-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.Mt5BaseSeq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/mt5-base/encoder_model_fp16.onnx", null, null, false, null);
        // 达摩院中文 mT5-base：中文对话改写/摘要，中文能力优于原版 mT5；int8 ONNX 需本地放置
        reg("mt5-zh-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.Mt5ZhSeq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/mt5-zh/encoder_model_int8.onnx", null, null, false, null);
        // 中文 BART-large：fnlp/bart-large-chinese，400M 中文书面语，int8 量化，downloadUrl 模式（Z:\temp\onnx-zh-bart-int8）
        reg("bart-zh-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.BartZhSeq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/bart-zh/encoder_model_int8.onnx", null, null, false, null);
        // 机器翻译(opus-mt-zh-en)：Helsinki-NLP 中译英 MarianMT，嵌入式模型 jar 提供，无需下载；适用中文翻译英文
        reg("opus-mt-zh-en", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtZhEnTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, "nlp/translation/opus_mt_zh_en/encoder_model_quantized.onnx");
        // 机器翻译(opus-mt-en-zh)：Helsinki-NLP 英译中 MarianMT，嵌入式模型 jar 提供，无需下载；适用英文翻译中文
        reg("opus-mt-en-zh", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtEnZhTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, "nlp/translation/opus_mt_en_zh/encoder_model_quantized.onnx");
        // 动漫人脸检测(YOLOv8n)：检测动漫/二次元图片中的人脸（YOLOv8 v1.4_n）；适用动漫人脸检测、二次元内容分析。模型内嵌 jar（utils-support-models-onnx-anime-face）
        reg("anime-face-detector", "com.chua.deeplearning.support.onnx.anime.detection.AnimeFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/anime-face/model.onnx");
        // 零样本分割(CLIPSeg)：用文本描述分割图像（如"分割出汽车"），无需训练；适用零样本语义分割、文本引导分割
        reg("clipseg-zero-shot", "com.chua.deeplearning.support.onnx.seg.CLIPSegZeroShotSegmentationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, com.chua.deeplearning.support.image.ImageSegmenter.class, "vision/seg/clipseg-rd64-refined/model.onnx", "https://modelscope.cn/models/Xenova/clipseg-rd64-refined/resolve/master/onnx/model.onnx", false, null);
        // 提示分割(EdgeSAM)：SAM 的边缘设备剪枝版（~36MB），用 bbox 或点提示分割物体；适用嵌入式分割、边缘设备
        reg("edgesam", "com.chua.deeplearning.support.onnx.seg.EdgeSamSegmentTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/seg/edge-sam/onnx/edge_sam_encoder.onnx");
        // 提示分割(EfficientSAM)：SAM 的蒸馏高效版（~40MB），用 bbox 或点提示分割物体；适用轻量级分割
        reg("efficientsam", "com.chua.deeplearning.support.onnx.seg.EfficientSamSegmentTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/seg/efficient-sam/onnx/efficientsam_ti_encoder.onnx");
        // 自动分割(FastSAM-s)：YOLOv8-seg 架构，自动检测并分割所有物体（~45MB，类无关）；适用全自动物体分割
        reg("fastsam", "com.chua.deeplearning.support.onnx.seg.FastSamSegmentTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/seg/fastsam/onnx/fastsam_s.onnx");
        // 图像修复(LaMa)：去除图像中不需要的物体/水印，智能填充背景；适用图片修复、水印去除、物体移除
        reg("lama-inpainting", "com.chua.deeplearning.support.onnx.inpainting.LamaInpaintingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/inpainting/lama/model.onnx", "https://huggingface.co/onnx-community/lama-inpainting-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 图像着色(DeOldify)：给黑白照片/视频自动上色，还原真实色彩；适用老照片修复、历史影像着色
        reg("image-colorize", "com.chua.deeplearning.support.onnx.colorize.ImageColorizeTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/colorization/deoldify/model.onnx", "https://huggingface.co/bluefoxcreation/DeOldify-ONNX/resolve/main/DeOldify.onnx", false, null);
        // 动漫人脸检测(YOLOv8)：YOLOv8 架构的动漫人脸检测，精度更高；适用动漫人脸检测、二次元内容分析
        reg("anime-face-yolov8", "com.chua.deeplearning.support.onnx.anime.detection.AnimeFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/anime-face/yolov8_animeface.onnx", "https://huggingface.co/Fuyucchi/yolov8_animeface/resolve/main/best.onnx", false, null);
                        // 人脸检测(YOLOv11n-face)：YOLOv11n 超轻量人脸检测，6MB；适用边缘设备人脸检测
        reg("yolo-face-detector", "com.chua.deeplearning.support.onnx.face.YoloFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/yolo11n-face.onnx", "https://huggingface.co/AdamCodd/YOLOv11n-face-detection/resolve/main/model.onnx", false, null);
        // 年龄+性别识别(ONNX)：基于 ViT 的人脸年龄+性别联合识别；适用人脸属性分析
        reg("age-gender-onnx", "com.chua.deeplearning.support.onnx.agegender.AgeRaceGenderTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/attribute/age_gender/age-gender-vit.onnx", "https://huggingface.co/onnx-community/age-gender-prediction-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 人脸年龄识别(YOLOv11n)：YOLOv11n 超轻量人脸年龄识别；适用边缘设备年龄估计
        // 文本嵌入(All-MiniLM-L6-v2)：英文句向量（384维），语义搜索/向量检索；适用英文语义搜索、文本相似度
        reg("all-MiniLM-L6-v2-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/all-MiniLM-L6-v2/model.onnx", "https://modelscope.cn/models/Xenova/all-MiniLM-L6-v2/resolve/master/onnx/model_uint8.onnx", java.util.List.of("https://huggingface.co/onnx-community/all-MiniLM-L6-v2-ONNX/resolve/main/onnx/model.onnx"), false, null);
        // 文本嵌入(BGE-small-en)：英文句向量（384维），BGE 系列英文版；适用英文语义搜索
        reg("bge-small-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-small-en-v1.5/model.onnx", "https://huggingface.co/chtk/chua-dl-models/resolve/main/nlp/embedding/bge-small-en-v1.5/model.onnx", java.util.List.of("https://hf-mirror.com/chtk/chua-dl-models/resolve/main/nlp/embedding/bge-small-en-v1.5/model.onnx"), false, null);
        // 文本嵌入(BGE-small-en embedded)：嵌入式版英文句向量（~63MB FP16），jar 内嵌，384维；适用离线英文语义搜索
        reg("bge-small-en-embedding", "com.chua.deeplearning.support.onnx.embedding.bge.BgeEmbeddingClient", String.class, float[].class, Object.class, "nlp/embedding/bge-small-en-v1.5/onnx/model_fp16.onnx");
        // 文本嵌入(BGE-small-zh)：中文句向量（512维），BGE 系列中文版，离线 jar 版；适用中文语义搜索、向量检索
        reg("bge-small-zh-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-small-zh-v1.5/model.onnx", "https://huggingface.co/chtk/chua-dl-models/resolve/main/nlp/embedding/bge-small-zh-v1.5/model.onnx", java.util.List.of("https://hf-mirror.com/chtk/chua-dl-models/resolve/main/nlp/embedding/bge-small-zh-v1.5/model.onnx"), false, null);
        // 文本嵌入(BGE-large-zh embedded)：中文句向量（1024维），BGE 系列中文 large 版，INT8 量化，jar 内嵌，326MB；适用高精度中文语义搜索、向量检索
        reg("bge-large-zh-embedding", "com.chua.deeplearning.support.onnx.embedding.bge.BgeEmbeddingClient", String.class, float[].class, Object.class, "nlp/embedding/bge-large-zh-v1.5/model.onnx");
        // 文本嵌入(BGE-base-zh)：中文句向量（768维），BGE 系列中文 base 版，自动下载；适用高精度中文语义搜索
        reg("bge-base-zh-embedding", "com.chua.deeplearning.support.onnx.embedding.bge.BgeTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-base-zh-v1.5/model.onnx", "https://huggingface.co/onnx-community/bge-base-zh-v1.5-ONNX/resolve/main/onnx/model.onnx", java.util.List.of("https://hf-mirror.com/onnx-community/bge-base-zh-v1.5-ONNX/resolve/main/onnx/model.onnx"), false, null);
        // 文本嵌入(BGE-base-en)：英文句向量（768维），BGE 系列英文 base 版，自动下载；适用高精度英文语义搜索
        reg("bge-base-en-embedding", "com.chua.deeplearning.support.onnx.embedding.bge.BgeTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-base-en-v1.5/model.onnx", "https://huggingface.co/onnx-community/bge-base-en-v1.5-ONNX/resolve/main/onnx/model.onnx", java.util.List.of("https://hf-mirror.com/onnx-community/bge-base-en-v1.5-ONNX/resolve/main/onnx/model.onnx"), false, null);
        // 文本嵌入(Granite-embedding-small)：IBM Granite 英文句向量；适用英文语义搜索
        reg("granite-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/granite-embedding-small/model.onnx", "https://huggingface.co/onnx-community/granite-embedding-small-english-r2-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 文本嵌入(BGE-M3)：多语言句向量（1024维），支持中英等多语言，自动下载；适用多语言语义搜索、跨语言检索
        reg("bge-m3-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-m3/model.onnx", "https://huggingface.co/onnx-community/bge-m3-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 零样本分类(CLIP-ViT-B-32)：CLIP 零样本图像分类，任意文本类别（如"猫/狗/车"），自动下载；适用动态分类、开放词汇分类
        reg("clip-vit-zero-shot", "com.chua.deeplearning.support.onnx.classification.SiglipZeroShotClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/zeroshot/clip-vit-base-patch32/model.onnx", "https://modelscope.cn/models/Xenova/clip-vit-base-patch32/resolve/master/onnx/model.onnx", false, null);
        // 零样本分类(MobileCLIP-S0)：轻量级 CLIP 零样本图像分类（~50MB），适合移动端；适用移动端开放词汇分类
        reg("mobileclip-zero-shot", "com.chua.deeplearning.support.onnx.classification.SiglipZeroShotClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/zeroshot/mobileclip_s0/model.onnx", "https://huggingface.co/onnx-community/mobileclip_s0-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 动物分类(10类)：识别 10 种常见动物（猫、狗、鸟等）；适用宠物识别、动物分类
        reg("animals-10-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/animals-10/model.onnx", "https://huggingface.co/onnx-community/10-animals-classification-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 食物分类(Food-101)：识别 101 种食物类别；适用食物识别、饮食记录
        reg("food-101-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/food101/vit-base.onnx", "https://huggingface.co/nateraw/vit-base-food101/resolve/main/model.onnx", false, null);
        // 植物识别(Plant classification)：识别室内植物种类；适用植物识别、园艺
        reg("plant-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/plant/model.onnx", "https://huggingface.co/onnx-community/house-plant-image-detection-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 图像分类(MobileNetV2)：MobileNetV2 1000 类 ImageNet 分类，轻量级；适用通用快速分类
        reg("mobilenetv2-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/mobilenetv2/mobilenet_v2_1.0_224.onnx", "https://huggingface.co/onnx-community/mobilenet_v2_1.0_224/resolve/main/onnx/model.onnx", false, null);
        // 图像分类(MobileNetV3)：MobileNetV3 1000 类 ImageNet 分类，更轻量更快；适用移动端通用分类
        reg("mobilenetv3-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/mobilenetv3/mobilenetv3_small.onnx", "https://huggingface.co/onnx-community/mobilenetv3_small_100.lamb_in1k/resolve/main/onnx/model.onnx", false, null);
        // 图像分类(CIFAR-10)：CIFAR-10 10 类分类（飞机、汽车、鸟等），ResNet18 架构；适用基础图像分类教学/演示
        reg("cifar10-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/cifar10/resnet18.onnx", "https://huggingface.co/aijadugar/cifar-10-resnet18/resolve/main/model.onnx", false, null);
        // 视觉特征提取(DINOv3-ViT-S)：DINOv3 自监督视觉特征（384维），比 DINOv2 更强；适用图像相似度、以图搜图
        reg("dinov3-feature", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov3-vits16/model.onnx", "https://huggingface.co/onnx-community/dinov3-vits16-pretrain-lvd1689m-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 视觉特征提取(ResNet50)：ResNet50 图像特征提取（2048维）；适用图像检索、特征比对
        reg("resnet50-feature", "com.chua.deeplearning.support.onnx.feature.ClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/resnet50/model.onnx", "https://modelscope.cn/models/Xenova/resnet-50/resolve/master/onnx/model.onnx", false, null);
        // 目标检测(YOLOv26n)：YOLOv26 通用检测，最新版；适用通用物体检测
        reg("yolo26n", "com.chua.deeplearning.support.onnx.yolo.v26.translator.Yolo26ObbTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/v26/yolo26n.onnx", "https://huggingface.co/onnx-community/yolo26n-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 目标检测(YOLOv10m)：YOLOv10m 通用 COCO 检测，精度较高；适用通用物体检测
        reg("yolov10m", "com.chua.deeplearning.support.onnx.yolo.v10.translator.YoloV10DetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v10/yolov10m.onnx", "https://huggingface.co/onnx-community/yolov10m/resolve/main/onnx/model.onnx",
                java.util.List.of("https://hf-mirror.com/onnx-community/yolov10m/resolve/main/onnx/model.onnx"), false, "model.onnx");
        // 目标检测(YOLOv10n)：YOLOv10n 超轻量通用 COCO 检测；适用边缘设备通用检测
        reg("yolov10n", "com.chua.deeplearning.support.onnx.yolo.v10.translator.YoloV10DetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v10/yolov10n.onnx", "https://huggingface.co/onnx-community/yolov10n/resolve/main/onnx/model.onnx",
                java.util.List.of("https://hf-mirror.com/onnx-community/yolov10n/resolve/main/onnx/model.onnx"), false, "model.onnx");
        // 标签分类(WD-SwinV2-Tagger)：为动漫图片打标签（如"长发"、"微笑"、"猫耳"等），基于 SwinV2；适用二次元图片标签、Danbooru 标签
        reg("wd-tagger-swinv2", "com.chua.deeplearning.support.onnx.classification.ClTaggerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/wd-swinv2-tagger-v3/model.onnx", "https://huggingface.co/SmilingWolf/wd-swinv2-tagger-v3/resolve/main/model.onnx", false, null);
        // 情绪识别(YOLOv11-face)：YOLOv11n 人脸情绪识别（开心、难过、生气等）；适用人脸情绪分析
        reg("yolo-face-emotion", "com.chua.deeplearning.support.onnx.emotion.EmotionFerplusTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/expression/yolo11-face-emotion.onnx", "https://huggingface.co/leeyunjai/yolo11-face-emotion-fer2013-cls/resolve/main/model.onnx", false, null);
        // 语音识别(Whisper-tiny)：OpenAI Whisper 多语言语音识别，音频转文本；适用语音转写、字幕生成、TTS 回读验证
        // 模型权重在 utils-support-models-onnx-whisper jar 中（audio/asr/whisper-tiny/，fp32 约 251MB）
        // 由 WhisperAudioClient 直接加载（encoder + 两阶段 decoder），无需注册 translator 类。
        reg("whisper-tiny", null, byte[].class, String.class, Object.class, "audio/asr/whisper-tiny/config.json");

        // preprocess+encode+uncached+cached 四模型 int8 约 118MB），词表 32768
        // 语音识别(SenseVoice-small)：阿里 FunAudioLLM 多语言 ASR（中/英/日/韩/粤），含 ITN 数字归一化
        // 模型权重在 utils-support-models-onnx-sensevoice jar 中（audio/asr/sensevoice-small/，int8 约 228MB）
        // 由 SenseVoiceAudioClient 直接加载（fbank+LFR+CMVN+CTC），无需注册 translator 类。
        reg("sensevoice", null, byte[].class, String.class, Object.class, "audio/asr/sensevoice-small/model.int8.onnx");
        // 情感分析(RoBERTa-go-emotions)：28 种细粒度情感分类（如"兴奋"、"悲伤"、"愤怒"等）；适用细粒度情感分析、用户评论分析
        reg("roberta-go-emotions", "com.chua.deeplearning.support.onnx.classification.DistilBertSentimentTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/roberta-go-emotions/model.onnx", "https://huggingface.co/SamLowe/roberta-base-go_emotions-onnx/resolve/main/model.onnx", false, null);
        // 文本生成(MiniMind)：小型因果语言模型，中文文本续写/生成，完全离线；适用离线文本生成、对话
        reg("minimind", "com.chua.deeplearning.support.onnx.text.minimind.MiniMindTranslator", String.class, String.class, Object.class, "models/minimind/model.onnx");
        // 中文通用语言模型(Gemma-3-270M it)：原版 262144 完整词表（含中文），fp16 + KV-cache ONNX，
        // 中文对话/文本生成；downloadUrl 自动下载（不再内嵌模型文件，tokenizer 仍从 models jar 抽取）。
        // 文件位于仓库 gemma-3-270m-it/ 子目录（model_fp16.onnx + model_fp16.onnx_data 权重）
        String gemmaItUrl = "https://huggingface.co/chtk/chua-dl-models/resolve/main/gemma-3-270m-it/model_fp16.onnx";
        reg("gemma-3-270m", "com.chua.deeplearning.support.onnx.text.gemma3.Gemma3Translator", String.class, String.class, Object.class,
                null,
                gemmaItUrl, java.util.List.of(gemmaItUrl, "https://hf-mirror.com/chtk/chua-dl-models/resolve/main/gemma-3-270m-it/model_fp16.onnx"),
                false, "model_fp16.onnx",
                HardwareConfig.builder().device("gpu").recommended(true)
                        .description("Gemma-3-270M it 版（fp16 KV-cache），downloadUrl 自动下载（含 onnx_data 权重）").build());
        // 本地大模型(Qwen2.5-1.5B-Instruct ONNX int8)：中文大模型对话（int8 单文件 ~1.5GB），downloadUrl 自动下载；适用本地对话（GPU 需 ≥2GB 显存）
        String qwenUrl = "https://huggingface.co/onnx-community/Qwen2.5-1.5B-Instruct/resolve/main/onnx/model_quantized.onnx";
        reg("qwen2-1.5b-onnx", "com.chua.deeplearning.support.onnx.text.qwen.OnnxQwenTranslator", String.class, String.class, Object.class,
                "models/qwen2.5-1.5b-instruct/model.onnx",
                qwenUrl, java.util.List.of(qwenUrl), false, "model.onnx",
                HardwareConfig.builder().device("gpu").minVramMb(2048).recommended(true).description("中文大模型对话 int8 1.5GB").build());
        // 图像分类(MobileNetV4)：MobileNetV4 1000 类 ImageNet 分类，最新版更快更准；适用移动端通用分类
        reg("mobilenetv4-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/mobilenetv4/mobilenetv4_conv_small.onnx", "https://huggingface.co/onnx-community/mobilenetv4_conv_small.e2400_r224_in1k/resolve/main/onnx/model.onnx", false, null);
        // 深度伪造检测(DeepFake Detector)：检测图片/视频是否为深度伪造；适用反欺诈、虚假内容检测
        reg("deepfake-detector", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/deepfake-detector/model.onnx", "https://huggingface.co/onnx-community/Deep-Fake-Detector-v2-Model-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 人脸检测(FacePlugin-Slim)：轻量级人脸检测插件，超小模型；适用轻量人脸检测
        reg("faceplugin-face-detect-slim", "com.chua.deeplearning.support.onnx.face.FacePluginDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "models/onnx/face/detection/faceplugin/face_detect_slim.onnx");
        // 小目标检测(VisDrone/DAMO-YOLO-TinyNAS)：无人机小目标检测（VisDrone 10 类：pedestrian/people/bicycle/car/van/truck/tricycle/awning-tricycle/bus/motor），YOLO 解码兼容；嵌入式目标 damoyolo_tinynasL25_S_640.pt 125.4MB 已下载，待 torch.onnx.export 转 ONNX 替换
        reg("visdrone-small-detector", "com.chua.deeplearning.support.onnx.yolo.VisDroneSmallDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/visdrone/damoyolo_visdrone.onnx");
        // 火灾烟雾检测(CCCCTV fire-smoke)：YOLOv8n 320 检测，cctv-ai-fire-smoke 嵌入式
        reg("fire-smoke", "com.chua.deeplearning.support.onnx.yolo.FireSmokeDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/fire-smoke/yolov8n/model.onnx");
        // 安全帽检测(helmet_head_person)：YOLOv8 640 检测，3 类 person/head/helmet
        reg("safety-helmet", "com.chua.deeplearning.support.onnx.yolo.SafetyHelmetDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/safety-helmet/yolov8/model.onnx");
        // 反光衣检测(ReflectiveClothes)：YOLOv8n 640 检测，2 类 safe/unsafe
        reg("reflective-clothes", "com.chua.deeplearning.support.onnx.yolo.ReflectiveClothesDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/reflective-clothes/yolov8n/model.onnx");
        // 口罩检测(FaceMask-YOLOv8)：YOLOv8 640 检测，2 类 cloth/surgical（能画口罩框）
        reg("face-mask-detector", "com.chua.deeplearning.support.onnx.yolo.FaceMaskDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/face-mask-detector/yolov8/model.onnx");
        // 人脸关键点(FacePlugin-Landmark)：人脸 68 关键点检测；适用人脸对齐、表情识别
        reg("faceplugin-face-landmark", "com.chua.deeplearning.support.onnx.face.FacePluginLandmarkTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "models/onnx/face/landmark/faceplugin/face_landmark.onnx");
        // 人脸特征(FacePlugin-Feature)：人脸特征向量提取，配合 FacePlugin 检测/关键点使用；适用人脸识别
        reg("faceplugin-face-feature", "com.chua.deeplearning.support.onnx.face.FacePluginFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "models/onnx/face/feature/faceplugin/face_feature.onnx");
        // 图像分类(EfficientNet-B1)：EfficientNet-B1 1000 类 ImageNet 分类，精度较高；适用通用分类
        reg("efficientnet-b1-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/efficientnet/efficientnet-lite4-11.onnx");
        // 情绪识别(FER+)：通用人脸情绪识别（7 种基础情绪）；适用情感分析
        reg("fer-plus", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/expression/FER/FER.onnx");
        // 文本嵌入(MiniLM-L6-v2)：离线版英文句向量（384维），jar 内嵌，用于语义搜索；适用英文语义搜索、向量检索
        reg("minilm-embedding", "com.chua.deeplearning.support.onnx.embedding.minilm.MiniLMEmbeddingTranslator", String.class, float[].class, Object.class, "nlp/embedding/minilm/model_quantized.onnx");
        // 文本嵌入(MiniLM-L6-v2 fp32)：离线版英文句向量（384维），fp32 高精度版，jar 内嵌；适用精度优先的英文语义搜索
        reg("minilm-fp32-embedding", "com.chua.deeplearning.support.onnx.embedding.minilm.MiniLMEmbeddingTranslator", String.class, float[].class, Object.class, "nlp/embedding/minilm-fp32/model.onnx");
        // 卡片矫正检测(Card Correction)：检测卡片类图片（身份证、名片等）四角点；适用文档扫描、证件识别
        reg("card-correction-detector", "com.chua.deeplearning.support.onnx.classification.CardCorrectionTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.image.ImageDetector.class, "cv/card_correction/card_detection.onnx");
        // 车牌检测(YOLOv11)：YOLOv11 架构的车牌检测（morsetechlab），内嵌模型，更高精度
        reg("yolo11-plate-detect", "com.chua.deeplearning.support.onnx.yolo.v11.translator.Yolo11PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/yolo11_plate/yolo11_plate_detect.onnx");
        // 车牌检测(YOLOv5)：YOLOv5 架构的车牌检测，适用于 PlateNumberPipeline 等场景
        reg("yolo5-plate-detect", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo5PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/yolov5_plate/yolov5_plate_detect.onnx");
        // 车牌识别(YOLOv5)：YOLOv5 车牌字符识别，配合检测使用；适用完整车牌识别流水线
        reg("yolov5-plate-recognize", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo5PlateRecTranslator", byte[].class, com.chua.deeplearning.support.plate.PlateResult.class, com.chua.deeplearning.support.plate.LicensePlateRecognizer.class, "vision/detection/yolov5_plate/yolov5_plate_rec_color.onnx");
        // OCR检测(PP-OCRv6-tiny)：PaddleOCR v6 超轻量文字检测；适用移动端 OCR
        reg("paddleocrv6-det", "com.chua.deeplearning.support.onnx.ocr.extractor.PpOcrDetTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.image.ImageDetector.class, "ocr/PP-OCRv6/tiny/det_infer/inference.onnx");
        // OCR识别(PP-OCRv6-tiny)：PaddleOCR v6 超轻量文字识别；适用移动端 OCR
        reg("paddleocrv6-rec", "com.chua.deeplearning.support.onnx.ocr.extractor.PpWordExtractorTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/PP-OCRv6/tiny/rec_infer/inference.onnx");
        // OCR检测(PP-OCRv6-medium)：PaddleOCR v6 中量文字检测，精度更高；适用高精度 OCR
        reg("paddleocrv6-medium-det", "com.chua.deeplearning.support.onnx.ocr.extractor.PpOcrDetMediumTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.image.ImageDetector.class, "ocr/PP-OCRv6/medium/det_infer/inference.onnx");
        // OCR识别(PP-OCRv6-medium)：PaddleOCR v6 中量文字识别，精度更高；适用高精度 OCR
        reg("paddleocrv6-medium-rec", "com.chua.deeplearning.support.onnx.ocr.extractor.PpWordExtractorMediumTranslator", byte[].class, String.class, Object.class, "ocr/PP-OCRv6/medium/rec_infer/inference.onnx");
        // 读光OCR(small)：读光中英文文字识别（DBNet检测+LightweightEdge识别，嵌入式），轻量快速；适用通用中英文OCR
        reg("duguang-ocr-small", "com.chua.deeplearning.support.onnx.ocr.duguang.DuguangOcrTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.ocr.OcrRecognizer.class, "ocr/duguang/small/det_512.onnx");
        // 读光OCR检测(small)：读光 DBNet 文本行检测，输出旋转框；供 OcrPipeline detector 使用
        reg("duguang-det-small", "com.chua.deeplearning.support.onnx.ocr.duguang.DuguangDetTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.image.ImageDetector.class, "ocr/duguang/small/det_512.onnx");
        // 读光OCR(large)：读光中英文文字识别（DBNet检测+ConvNeXT识别，嵌入式），精度更高；适用高精度中英文OCR
        reg("duguang-ocr-large", "com.chua.deeplearning.support.onnx.ocr.duguang.DuguangOcrTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.ocr.OcrRecognizer.class, "ocr/duguang/large/det_512.onnx");
        // 读光OCR检测(large)：读光 DBNet 文本行检测（大模型），输出旋转框；供 OcrPipeline detector 使用
        reg("duguang-det-large", "com.chua.deeplearning.support.onnx.ocr.duguang.DuguangDetTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.image.ImageDetector.class, "ocr/duguang/large/det_512.onnx");

        // ==================== 语音合成 TTS ====================
        // DFSMN 语音降噪（单麦 48k 实时近场，PSM）：输入带噪 48kHz 单声道 wav/pcm，输出降噪后音频。
        // 模型打包在 utils-support-models-onnx-dfsmn-ans jar 中（audio/denoise/dfsmn_ans/，源自 ModelScope speech_dfsmn_ans_psm_48k_causal）。
        reg("dfsmn-ans", "com.chua.deeplearning.support.onnx.audio.denoise.DfsmnAnsTranslator", byte[].class, byte[].class, com.chua.deeplearning.support.speech.SpeechEnhancer.class, "audio/denoise/dfsmn_ans/model.onnx");
        // 印章检测（SDT Seal inspection，YOLO 640）：检测 4 类印章（公章/个人章/审核章/其他）。
        // 模型打包在 utils-support-models-onnx-seal-inspection jar 中（vision/detection/seal/）。
// D-FINE-L 实时目标检测（Objects365 预训练 -> COCO 80 类对齐，57.3 AP，int8 量化嵌入式）
// RT-DETR v2 文档版面检测（DocLayNet 17 类，169MB fp32 嵌入式）
        reg("rtdetr-layout", "com.chua.deeplearning.support.onnx.layout.RTDetrLayoutTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/layout/rtdetr/model.onnx");
        reg("dfine-l-obj2coco", "com.chua.deeplearning.support.onnx.detr.DFineTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/dfine_l_obj2coco/model_quantized.onnx");
        reg("seal-inspection", "com.chua.deeplearning.support.onnx.yolo.SealInspectionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/seal/model.onnx");
        // MMS-TTS-English（VITS）：英文语音合成，输入文本输出 WAV 音频；适用英文朗读、语音播报。
        // 模型打包在 utils-support-models-onnx-mms-tts-eng jar 中（audio/tts/mms-tts-eng/），
        // 由 OnnxTextToAudioClient 直接加载，无需注册 translator 类。
        reg("mms-tts-eng", null, String.class, byte[].class, Object.class, "audio/tts/mms-tts-eng/model_quantized.onnx");

        // Pocket-TTS（Kyutai 100M 流匹配 TTS）：多语言语音合成（含零样本声音克隆），24kHz WAV；
        // 模型打包在 utils-support-models-onnx-pocket-tts jar 中（audio/tts/pocket-tts/，~225MB int8），
        // 由 OnnxTextToAudioClient 直接加载，无需注册 translator 类。
        reg("pocket-tts", null, String.class, byte[].class, Object.class, "audio/tts/pocket-tts/config.json");

        // VITS-icefall-zh 中文 TTS（AISHELL3 174 说话人）：中文语音合成，8kHz WAV；
        // 模型打包在 utils-support-models-onnx-vits-icefall-zh jar 中（audio/tts/vits-icefall-zh/），
        // 由 OnnxTextToAudioClient 直接加载，支持 .voice("SSB0005") 指定说话人。
        reg("vits-icefall-zh", null, String.class, byte[].class, Object.class, "audio/tts/vits-icefall-zh/model.onnx");

        // ==================== 音频指纹提取（Audio Fingerprint） ====================
        // wav2vec2-large-xlsr-53 中文（768维嵌入）：Facebook AI 自监督语音预训练模型，
        // 最后一层 hidden state 经全局平均池化为固定维度向量；适用于音频指纹匹配、
        // 相似音频检索、声纹初筛。输入 16kHz 单声道 PCM/WAV，输出 768 维 float[]。
        // 模型约 950MB，通过 downloadUrl 自动下载，hf-mirror 备用。
        reg("wav2vec2-zh-fingerprint",
                "com.chua.deeplearning.support.onnx.audio.Wav2Vec2FingerprintTranslator",
                byte[].class, float[].class,
                com.chua.deeplearning.support.audio.AudioFingerprinter.class,
                "audio/fingerprint/wav2vec2-zh/model.onnx",
                "https://huggingface.co/onnx-community/wav2vec2-large-xlsr-53-chinese-zh-cn-ONNX/resolve/main/model.onnx",
                java.util.List.of("https://hf-mirror.com/onnx-community/wav2vec2-large-xlsr-53-chinese-zh-cn-ONNX/resolve/main/model.onnx"),
                false, null);
        // wav2vec2-base backbone（Wav2Vec2ForPreTraining，768维 hidden state）：正宗的 wav2vec2 预训练
        // 主干模型，输出真正的 768 维语音表征（mean pooling over time）。适用于音频指纹匹配、相似
        // 音频检索、声纹初筛。输入 16kHz 单声道 PCM/WAV，输出 768 维 float[]，同源音频 cosine 相似度 > 0.9。
        // 模型约 90MB（INT4 量化版），JAR 内嵌。
        // 来源：https://huggingface.co/onnx-community/wav2vec2-base-ONNX/resolve/main/onnx/model_q4.onnx
        reg("wav2vec2-base-fingerprint",
                "com.chua.deeplearning.support.onnx.audio.Wav2Vec2FingerprintTranslator",
                byte[].class, float[].class,
                com.chua.deeplearning.support.audio.AudioFingerprinter.class,
                "audio/fingerprint/wav2vec2-base/model.onnx");

        // ==================== 说话人嵌入（Speaker Embedding） ====================
        // wespeaker-resnet34-LM（512维 x-vector）：专用于说话人验证的 ResNet34+LM 架构，
        // 输入 16kHz 单声道 PCM/WAV，输出 512 维 L2 归一化嵌入向量。
        // 嵌入式 jar：utils-support-models-onnx-wespeaker（INT8 量化，~6.7MB）。
        // 配合 DefaultSpeakerDiarizer（VAD 时间切分）完成端到端说话人分离。
        reg("wespeaker-resnet34",
                "com.chua.deeplearning.support.onnx.audio.WespeakerEmbeddingTranslator",
                byte[].class, float[].class,
                com.chua.deeplearning.support.audio.AudioFingerprinter.class,
                "audio/speaker/wespeaker-resnet34/model.onnx");

        // CAM++ 声纹嵌入（192维）：阿里 DAMO 说话人验证模型，中文优化，
        // 输入 16kHz 单声道 PCM/WAV，输出 192 维 L2 归一化嵌入向量。
        // 嵌入式 jar：utils-support-models-onnx-sensevoice（~28MB）。
        // 适用声纹识别、说话人验证、声纹入库检索。
        reg("campplus-voiceprint",
                "com.chua.deeplearning.support.onnx.audio.CampplusEmbeddingTranslator",
                byte[].class, float[].class,
                com.chua.deeplearning.support.audio.AudioFingerprinter.class,
                "audio/speaker/campplus/campplus_zh_cn_common_200k.onnx");

        // ==================== 零样本检测 YOLO-World（嵌入式友好） ====================
        // YOLO-World Small：开放词表检测，文本提示（中/英文）→ 检测框+类别；模型 ~40MB，适合嵌入式/边缘部署
        reg("yolov8s-world", "com.chua.deeplearning.support.onnx.yoloworld.YoloWorldDetectorTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                com.chua.deeplearning.support.image.ImageDetector.class,
                "vision/detection/yoloworld/yolov8s-worldv2.onnx",
                "https://huggingface.co/Instemic/yolo-world-onnx/resolve/main/yolov8s-worldv2.onnx",
                java.util.List.of("https://hf-mirror.com/Instemic/yolo-world-onnx/resolve/main/yolov8s-worldv2.onnx"),
                false, "model.onnx");
        // YOLO-World Medium：开放词表检测，精度与速度平衡；模型 ~70MB
        reg("yolov8m-world", "com.chua.deeplearning.support.onnx.yoloworld.YoloWorldDetectorTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                com.chua.deeplearning.support.image.ImageDetector.class,
                "vision/detection/yoloworld/yolov8m-world.onnx",
                "https://huggingface.co/onnx-community/YOLOWorld-m/resolve/main/onnx/model.onnx",
                java.util.List.of("https://hf-mirror.com/onnx-community/YOLOWorld-m/resolve/main/onnx/model.onnx"),
                false, "model.onnx");
        // YOLO-World Large：开放词表检测，高精度；模型 ~130MB
        reg("yolov8l-world", "com.chua.deeplearning.support.onnx.yoloworld.YoloWorldDetectorTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                com.chua.deeplearning.support.image.ImageDetector.class,
                "vision/detection/yoloworld/yolov8l-world.onnx",
                "https://huggingface.co/Instemic/yolo-world-onnx/resolve/main/yolov8l-worldv2.onnx",
                java.util.List.of("https://hf-mirror.com/Instemic/yolo-world-onnx/resolve/main/yolov8l-worldv2.onnx"),
                false, "model.onnx");
        // ==================== 图像描述 Image Captioning ====================
        // ViT-GPT2：图像内容文字描述。encoder 打包在 utils-support-models-onnx-vit-gpt2-captioning jar，
        // decoder 较大（~151MB）自动下载（modelscope Xenova/vit-gpt2-image-captioning）。
        reg("vit-gpt2-captioning", "com.chua.deeplearning.support.onnx.image.captioning.VitGpt2CaptioningTranslator",
                ai.djl.modality.cv.Image.class, String.class, Object.class,
                "vision/captioning/vit-gpt2/decoder_model_quantized.onnx",
                "https://modelscope.cn/models/Xenova/vit-gpt2-image-captioning/resolve/master/onnx/decoder_model_quantized.onnx",
                java.util.List.of("https://huggingface.co/Xenova/vit-gpt2-image-captioning/resolve/main/onnx/decoder_model_quantized.onnx"),
                false, null);
        // ==================== ViT-H-14 图像特征 ====================
        // ViT-H-14 (Chinese-CLIP)：提取图像特征向量（1024维），与文本特征比对；适用图文检索、图像匹配
        reg("vit-h-14-image", "com.chua.deeplearning.support.onnx.vision.clip.VitH14OnnxTranslator",
                ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class,
                "vision/clip/vit-h-14/vit-h-14.onnx");
        // ==================== ViT-H-14 文本特征 ====================
        // ViT-H-14 Text (Chinese-CLIP)：提取中文文本特征向量（1024维），与图像特征比对；适用中文图文检索
        reg("vit-h-14-text", "com.chua.deeplearning.support.onnx.vision.clip.VitH14TextTranslator",
                String.class, float[].class, Object.class,
                "vision/clip/vit-h-14/text_encoder.onnx");
    }

    /**
     * Reg
     * @param modelId modelId
     * @param translatorClassName translatorClassName
     * @param inputType inputType
     * @param outputType outputType
     * @param capability capability
     * @param relativePath relativePath
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, relativePath);
        }
    }

    /**
     * Reg
     * @param modelId modelId
     * @param translatorClassName translatorClassName
     * @param inputType inputType
     * @param outputType outputType
     * @param capability capability
     * @param relativePath relativePath
     * @param downloadUrl downloadUrl
     * @param compress compress
     * @param downloadFileName downloadFileName
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath,
                            String downloadUrl, boolean compress, String downloadFileName) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, relativePath, downloadUrl, compress, downloadFileName);
        }
    }

    /**
     * Reg
     * @param modelId modelId
     * @param translatorClassName translatorClassName
     * @param inputType inputType
     * @param outputType outputType
     * @param capability capability
     * @param relativePath relativePath
     * @param downloadUrl downloadUrl
     * @param downloadMirrors downloadMirrors
     * @param compress compress
     * @param downloadFileName downloadFileName
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath,
                            String downloadUrl, java.util.List<String> downloadMirrors,
                            boolean compress, String downloadFileName) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability,
                    relativePath, downloadUrl, downloadMirrors, compress, downloadFileName);
        }
    }

    /**
     * Reg
     * @param modelId modelId
     * @param translatorClassName translatorClassName
     * @param inputType inputType
     * @param outputType outputType
     * @param capability capability
     * @param relativePath relativePath
     * @param downloadUrl downloadUrl
     * @param downloadMirrors downloadMirrors
     * @param compress compress
     * @param downloadFileName downloadFileName
     * @param hardwareConfig hardwareConfig
     */
    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath,
                            String downloadUrl, java.util.List<String> downloadMirrors,
                            boolean compress, String downloadFileName,
                            com.chua.deeplearning.support.model.HardwareConfig hardwareConfig) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability,
                    relativePath, downloadUrl, downloadMirrors, compress, downloadFileName, hardwareConfig);
        }
    }
}

