package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;

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
        // 年龄识别(GoogleNet)：根据人脸图像估算年龄，输出年龄区间；适用人脸属性分析、年龄统计
        // 年龄识别(VGG)：VGG 架构的人脸年龄估算，精度更高但计算量更大；适用人脸属性分析
        reg("vgg-age-recognition", "com.chua.deeplearning.support.onnx.age.VggAgeRecognitionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/age/vgg_ilsvrc_16_age_imdb_wiki.onnx");
        // 年龄+种族+性别多任务识别：单模型同时输出年龄区间、种族、性别；适用人脸属性综合分析、安防
        reg("age-race-gender", "com.chua.deeplearning.support.onnx.agegender.AgeRaceGenderTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/attribute/age_gender/AgeRaceGenderNet_v1.onnx");
        // 动漫风格迁移(AnimeGANv3)：将真实照片转换为宫崎骏/新海诚等动漫风格，v3 版质量更高；适用图片特效、二次元创作
        reg("anime-gan-v3", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV3Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/animegan/AnimeGANv3_Hayao_36.onnx");
        reg("anime-gan-v3-shinkai", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV3Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/animegan/AnimeGANv3_Shinkai_37.onnx");
        reg("anime-gan-v3-ghibli", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV3Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/animegan/AnimeGANv3_large_Ghibli_c1_e299.onnx");
        // 风格迁移(Candy)：将照片转换为糖果风格（色彩鲜艳、弯曲扭曲的效果）；适用艺术特效、创意图片处理
        reg("style-candy", "com.chua.deeplearning.support.onnx.style.FastNeuralStyleTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/style/candy-9.onnx");
        // 风格迁移(Mosaic)：将照片转换为马赛克拼贴风格；适用艺术特效、装饰性图片处理
        reg("style-mosaic", "com.chua.deeplearning.support.onnx.style.FastNeuralStyleTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/style/mosaic-9.onnx");
        // 风格迁移(Rain Princess)：将照片转换为水彩手绘风格（柔和、梦幻）；适用艺术特效、插画风格转换
        reg("style-rain-princess", "com.chua.deeplearning.support.onnx.style.FastNeuralStyleTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/style/rain-princess-9.onnx");
        // 风格迁移(Udnie)：将照片转换为抽象表现主义风格（色彩块面、半抽象效果）；适用艺术创作、特效处理
        reg("style-udnie", "com.chua.deeplearning.support.onnx.style.FastNeuralStyleTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/style/udnie-9.onnx");
        // 风格迁移(Pointilism)：将照片转换为点彩画风格（用小点构成图像）；适用艺术特效、复古风格处理
                // 水下图像增强(LU2Net)：基于轻量级U-Net的水下图像增强，改善水下颜色失真和对比度，适用于水下摄影、水下监控
                reg("lu2net", "com.chua.deeplearning.support.onnx.lu2net.Lu2NetTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/lu2net/lu2net.onnx");
        reg("style-pointilism", "com.chua.deeplearning.support.onnx.style.FastNeuralStyleTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "models/onnx/style/pointilism-9.onnx");
        // 动漫风格迁移(AnimeGANv2-Hayao)：将照片转换为宫崎骏风格动画（细腻、明亮）；适用动漫化、图片特效
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
        reg("cl-tagger", "com.chua.deeplearning.support.onnx.classification.ClTaggerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/enhancement/cl_tagger_1_02/cl_tagger_1_02_optimized.onnx");
        // 情感分析(DistilBERT)：对文本进行情感二分类（正面/负面），轻量级；适用评论分析、舆情监控、用户反馈
        reg("distil-bert-sentiment", "com.chua.deeplearning.support.onnx.classification.DistilBertSentimentTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/distilbert-sst2/model_quantized.onnx");
        // 图像分类(EfficientNet-Lite0)：1000 类 ImageNet 分类，轻量级速度快；适用通用物体识别、图片内容理解
        reg("efficient-net-lite0-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/efficientnet/efficientnet-lite0-11.onnx", "https://huggingface.co/onnx-community/efficientnet-lite0-11-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 图像分类(EfficientNet-Lite4)：1000 类 ImageNet 分类，lite4 精度更高但计算量更大；适用需要高精度的通用分类场景
        reg("efficient-net-lite4-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite4ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/efficientnet/efficientnet-lite4-11.onnx");
        // 零样本分类(SigLIP)：无需训练，任意文本类别列表即可分类（如"猫/狗/鸟"），基于 SigLIP 视觉语言模型；适用动态类别、开放词汇分类
        reg("siglip-zero-shot-classification", "com.chua.deeplearning.support.onnx.classification.SiglipZeroShotClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/zeroshot/siglip-base-patch16-224/onnx/model.onnx");
        // 中文CLIP图像特征(CN-CLIP)：提取中文 CLIP 的图像特征向量（512维），与中文文本特征比对；适用中文图文检索、跨模态匹配
        reg("cn-clip-image", "com.chua.deeplearning.support.onnx.clip.CnClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/cn-clip-vit-b-16/vit-b-16.img.b1.fp32.onnx", "https://hf-mirror.com/gficcg/clip_cn_vit-onnx/resolve/main/clip_cn_vit-b-16/vit-b-16.img.b1.fp32.onnx", false, null);
        // 中文CLIP文本特征(CN-CLIP)：提取中文 CLIP 的文本特征向量，与图像特征比对；适用中文图文检索、文本到图像匹配
        reg("cn-clip-text", "com.chua.deeplearning.support.onnx.clip.CnClipTextFeatureTranslator", String.class, float[].class, Object.class, "vision/zeroshot/cn-clip-vit-b-16/vit-b-16.txt.fp32.onnx", "https://hf-mirror.com/gficcg/clip_cn_vit-onnx/resolve/main/clip_cn_vit-b-16/vit-b-16.txt.fp32.onnx", false, null);
        // 语言检测(XLM-RoBERTa)：检测文本的语言种类（如中文、英文、日文等），多语言；适用文本预处理、多语言路由
        reg("xlm-roberta-language-detection", "com.chua.deeplearning.support.onnx.classification.XlmRobertaLanguageDetectionTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/xlm-roberta-language-detection/model_quantized.onnx");
        // CLIP文本特征(CLIP-ViT-B-32)：提取英文 CLIP 文本特征向量（512维），与 CLIP 图像特征比对；适用英文图文检索、跨模态搜索
        reg("clip-text-feature", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "vision/enhancement/CLIP-ViT-B-32-TEXT/model.onnx");
        // 深度估计(MiDaS)：从单张图片估计深度图（灰度图，越亮表示越近）；适用背景虚化、3D 重建、AR 效果
        reg("midas-depth", "com.chua.deeplearning.support.onnx.depth.MidasDepthTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/depth/midas/midas.onnx", "https://huggingface.co/onnx-community/MiDaS/resolve/main/onnx/model.onnx", false, null);
        // 视觉特征提取(DINOv2)：提取图像通用特征向量（384维），无需训练，适合图像相似度比对、检索；适用以图搜图、特征匹配
        reg("dino-v2", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov2/model.onnx");
        // 视觉特征提取(DINOv2-small emb)：嵌入式版 DINOv2-small（~84MB），FP32，384维特征，jar 内嵌；适用离线以图搜图、特征比对
        reg("dino-v2-small-embedding", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov2-small/onnx/model.onnx");
        // 文档理解(Donut)：端到端文档理解模型，输入文档图片输出结构化文本；适用发票识别、表单解析、文档 OCR
        reg("donut", "com.chua.deeplearning.support.onnx.donut.DonutTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/donut/donut.onnx");
        // 情绪识别(FER+)：识别面部表情（开心、难过、生气等 7 种基础情绪）；适用情感分析、用户反馈、安防监控
        reg("emotion-ferplus", "com.chua.deeplearning.support.onnx.emotion.EmotionFerplusTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/expression/FrEmotion/fr_expression.onnx");
        // 人脸识别(ArcFace)：提取人脸特征向量（512维），用于人脸比对/识别/搜索；适用人脸门禁、人脸搜索、身份验证
        reg("arc-face", "com.chua.deeplearning.support.onnx.face.ArcFaceTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/swap/common/buffalo_l/w600k_r50.onnx", "https://huggingface.co/onnx-community/arcface/resolve/main/onnx/model.onnx", false, null);
        // 人脸识别(AdaFace)：高质量人脸特征提取，对低质量/模糊人脸更鲁棒；适用复杂光照条件的人脸识别
        reg("ada-face", "com.chua.deeplearning.support.onnx.face.AdaFaceTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/adaface/adaface_ir101_webface12m.onnx", "https://huggingface.co/miccai/adaface-ir101-webface12m/resolve/main/model.onnx", false, null);
        // 通用人脸识别(CommonFace)：基于 SDK 的人脸特征提取，速度快；适用快速人脸比对
        reg("common-face-rec", "com.chua.deeplearning.support.onnx.face.CommonFaceRecTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/face_feature_sdk/face_feature.onnx", "https://huggingface.co/onnx-community/arcface/resolve/main/onnx/model.onnx", false, null);
        // 人脸特征提取(FaceFeature)：通用人脸特征向量提取；适用人脸识别、人脸聚类
        reg("face-feature", "com.chua.deeplearning.support.onnx.face.FaceFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/face_feature_sdk/face_feature.onnx");
        // 人脸特征提取(R50FaceFeature)：ResNet50 架构的人脸特征提取，精度更高；适用高精度人脸识别
        reg("r50-face-feature", "com.chua.deeplearning.support.onnx.face.R50FaceFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/face_feature_sdk/face_feature_r50.onnx", "https://huggingface.co/onnx-community/arcface/resolve/main/onnx/model.onnx", false, null);
        // 人脸检测(SCRFD)：快速高精度人脸检测，输出人脸框+关键点（5点）；适用人脸检测、face crop 前置
        reg("scrfd-face-detector", "com.chua.deeplearning.support.onnx.face.ScrfdFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/scrfd/2.5g_bnkps.onnx", "https://huggingface.co/RuteNL/SCRFD-face-detection-ONNX/resolve/main/2.5g_bnkps.onnx", false, null);
        // 人脸检测(UltraFace)：超轻量人脸检测，320x240 输入，适合移动端/边缘设备；适用嵌入式人脸检测
        reg("ultra-face", "com.chua.deeplearning.support.onnx.face.UltraFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/face_detection_sdk/RFB.onnx", "https://huggingface.co/onnxmodelzoo/version-RFB-320/resolve/main/version-RFB-320.onnx", false, null);
        // 人脸检测(RetinaFace)：AIAS traced 导出，任意输入尺寸，输出 5 点关键点；适用人脸修复/对齐前置
        reg("onnx-retinaface", "com.chua.deeplearning.support.onnx.face.OnnxRetinaFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/retinaface/retinaface_v1.onnx");
        // 人脸分割(ParseNet)：AIAS traced 导出，输出人脸软 mask；适用修复后贴回原图
        reg("onnx-parsenet", "com.chua.deeplearning.support.onnx.face.OnnxFaceSegTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, com.chua.deeplearning.support.image.ImageEnhancer.class, "face/segmentation/parsenet/parsenet.onnx");
        // CLIP图像特征(CLIP-ViT-B-32)：提取英文 CLIP 图像特征向量（512维），与 CLIP 文本特征比对；适用图文检索、图像零样本分类
        reg("clip-image-feature", "com.chua.deeplearning.support.onnx.feature.ClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/enhancement/CLIP-ViT-B-32-IMAGE/CLIP-ViT-B-32-IMAGE.onnx");
        // CLIP图像特征(MobileCLIP-S0)：轻量级 CLIP 图像特征提取（~50MB），适合移动端/边缘设备；适用移动端图文检索
        reg("mobile-clip-image-feature", "com.chua.deeplearning.support.onnx.feature.MobileClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/mobileclip_s0/onnx/vision_model.onnx");
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
        reg("small-sd-text-encoder", "com.chua.deeplearning.support.onnx.generation.SmallSdTextEncoderTranslator", String.class, ai.djl.ndarray.NDList.class, Object.class, "vision/detection/small-sd/text_encoder.onnx");
        // 图像生成(Small-SD UNet)：小型 Stable Diffusion 的 UNet 去噪网络；适用轻量级文生图
        reg("small-sd-unet", "com.chua.deeplearning.support.onnx.generation.SmallSdUnetTranslator", ai.djl.ndarray.NDList.class, ai.djl.ndarray.NDList.class, Object.class, "vision/detection/small-sd/unet.onnx");
        // 图像生成(Small-SD VAE Decoder)：小型 SD 的 VAE 解码器，将潜变量解码为图像；适用轻量级文生图后处理
        reg("small-sd-vae-decoder", "com.chua.deeplearning.support.onnx.generation.SmallSdVaeDecoderTranslator", ai.djl.ndarray.NDList.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/small-sd/vae_decoder.onnx");
        // 文本到图像生成(Small-SD Combined)：小型 Stable Diffusion 全流程合并版，输入文本直接输出图像；适用轻量级离线文生图
        reg("small-stable-diffusion-combined", "com.chua.deeplearning.support.onnx.generation.SmallStableDiffusionCombinedTranslator", String.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/small-sd/combined.onnx");
        // 图像生成(TAESD Decoder)：微型 VAE 解码器，极轻量级，用于快速解码潜变量；适用快速图像预览
        reg("taesd-decoder", "com.chua.deeplearning.support.onnx.generation.TaesdDecoderTranslator", ai.djl.ndarray.NDList.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/taesd/decoder.onnx");
        // 素描转换(ImageToLineDrawing)：将照片转换为线条素描风格；适用艺术创作、素描特效
        reg("image-to-line-drawing", "com.chua.deeplearning.support.onnx.linedrawing.ImageToLineDrawingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/image-to-line-drawing-onnx/image-to-line-drawing-onnx.onnx");
        // 活体检测(FaceAntiSpoof)：检测人脸是否为真实人脸（防照片/视频攻击）；适用人脸识别防假、活体验证
        reg("face-anti-spoof", "com.chua.deeplearning.support.onnx.liveness.FaceAntiSpoofTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/antispoof/minifasnet_v2/model.onnx");
        // 活体检测(MiniVision)：轻量级活体检测模型，80x80 输入，适合移动端；适用移动端人脸活体验证
        reg("mini-vision-liveness", "com.chua.deeplearning.support.onnx.liveness.MiniVisionLivenessTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/liveness/MiniVision/4_0_0_80x80_MiniFASNetV1SE.onnx");
        // 活体检测(FLRGB)：ModelScope 官方 RGB 活体模型，112x112 输入，输出活体概率；模型内嵌 jar（utils-support-models-onnx-face-liveness）
        reg("face-liveness-flrgb", "com.chua.deeplearning.support.onnx.liveness.FlRgbLivenessTranslator", ai.djl.modality.cv.Image.class, Float.class, com.chua.deeplearning.support.liveness.LivenessDetector.class, "face/liveness/flrgb/model.onnx");
        // 活体检测(FLXC)：ModelScope 官方炫彩活体模型，12通道多帧序列，对3D面具/头模攻击鲁棒；模型内嵌 jar（utils-support-models-onnx-face-liveness-flxc）
        reg("face-liveness-flxc", "com.chua.deeplearning.support.onnx.liveness.FlXcLivenessTranslator", ai.djl.modality.cv.Image.class, Float.class, com.chua.deeplearning.support.liveness.LivenessDetector.class, "face/liveness/flxc/model.onnx");
        // 抠图(U2Net)：通用前景抠图，输出 alpha 通道（RGBA）；适用证件照处理、背景替换、电商抠图
        reg("matting", "com.chua.deeplearning.support.onnx.matting.translator.MattingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/seg_unet_sdk/u2net.onnx", "https://huggingface.co/onnx-community/u2net/resolve/main/onnx/model.onnx", false, null);
        // 抠图(MODNet)：人像抠图/前景分割（~25MB），嵌入式 jar 版，来自 ModelScope；适用人像抠图、视频会议背景替换
        reg("modnet", "com.chua.deeplearning.support.onnx.matting.translator.DjlMattingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/matting/modnet/onnx/model.onnx");
        // 抠图(RMBG-2.0)：BRIA 背景移除 v2.0，高质量抠图，需自动下载；适用电商图片、人像抠图
        reg("rmbg20", "com.chua.deeplearning.support.onnx.matting.translator.Rmbg20Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/RMBG-2.0/onnx/model.onnx", "https://huggingface.co/briaai/RMBG-2.0/resolve/main/onnx/model.onnx", false, null);
        // 抠图(RMBG-1.4)：BRIA 背景移除 v1.4，42MB 嵌入式，效果接近 2.0；适用离线抠图、嵌入式设备
        reg("rmbg14", "com.chua.deeplearning.support.onnx.matting.translator.Rmbg20Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/RMBG-1.4/onnx/model_quantized.onnx");
        // 超分辨率(Nomos2)：4x 图像超分辨率，增强动漫/二次元图片细节；适用动漫放大、老旧图片修复
        reg("nomos2", "com.chua.deeplearning.support.onnx.nomos2.Nomos2Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/esrgan/4xNomos2_otf_esrgan_fp32_opset17.onnx");
        // OCR方向检测(PP-OCR)：检测文本方向（0°/90°/180°/270°），PaddleOCR 预处理；适用 OCR 流水线前置
        reg("pp-word-rotate", "com.chua.deeplearning.support.onnx.ocr.direction.PpWordRotateTranslator", byte[].class, com.chua.deeplearning.support.onnx.ocr.direction.DirectionInfo.class, Object.class, "ocr/direction/ppocr_cls/model.onnx");
        // 文档方向分类(PP-LCNet_x1_0_doc_ori)：4 类（0°/90°/180°/270°），整图方向检测；适用文档矫正、旋转自动修正
        reg("doc-orientation", "com.chua.deeplearning.support.onnx.ocr.direction.DocOrientationTranslator", byte[].class, com.chua.deeplearning.support.onnx.ocr.direction.DirectionInfo.class, Object.class, "ocr/direction/doc_ori/model.onnx",
                "https://huggingface.co/PaddlePaddle/PP-LCNet_x1_0_doc_ori_onnx/resolve/main/model.onnx",
                List.of(
                        "https://hf-mirror.com/PaddlePaddle/PP-LCNet_x1_0_doc_ori_onnx/resolve/main/model.onnx",
                        "https://huggingface.co/ningpp/PP-LCNet_x1_0_doc_ori-ONNX/resolve/main/model.onnx"
                ),
                false, "model.onnx");
        // OCR文字识别(PP-OCR Server)：PP-OCRv5 服务器版文字识别，精度高但较慢；适用高精度 OCR
        reg("pp-word-extractor", "com.chua.deeplearning.support.onnx.ocr.extractor.PpWordExtractorTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/recognition/PP-OCRv5_server_rec_infer/PP-OCRv5_server_rec.onnx");
        // OCR文字识别(SVTR)：SVTR 架构轻量级文字识别，速度更快；适用快速 OCR
        reg("svtr-extractor", "com.chua.deeplearning.support.onnx.ocr.extractor.SvtrExtractorTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/recognition/PP-OCRv5_mobile_rec_infer/PP-OCRv5_mobile_rec_infer.onnx");
        // 版面分析(PP-DocLayoutV3)：PaddleOCR 官方文档版面分析（DETR，25 类区域），模型内嵌 jar（utils-support-models-onnx-ppdoclayoutv3）
        reg("pp-doc-layout", "com.chua.deeplearning.support.onnx.ocr.layout.PpDocLayoutTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/pp_doc_layoutv3/PP-DocLayoutV3.onnx");
        // OCR文字识别(PP-OCRv5 Server)：PaddleOCR 文字识别完整版；适用端到端 OCR
        reg("paddle-ocr-recognition", "com.chua.deeplearning.support.onnx.ocr.paddleocr.PaddleOcrRecognitionTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/ppocrv5-server-rec.onnx");
        // 表格结构识别(PP-Structure v2)：识别表格结构，输出表格行列信息；适用表格识别、Excel 还原
        reg("pp-structure-v2", "com.chua.deeplearning.support.onnx.ocr.paddlestructure.PpStructureV2Translator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "ocr/table_structure/pp_structure_v2/inference.onnx");
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
        // 车牌检测(YOLOv5)：YOLOv5 架构的车牌检测；适用停车场、出入口车牌识别
        reg("yolo5-plate-detect", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo5PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/license/license-plate-finetune-v1x.onnx", "https://huggingface.co/morsetechlab/yolov11-license-plate-detection/resolve/main/license-plate-finetune-v1x.onnx", false, null);
        // 车牌检测(YOLOv7)：YOLOv7 架构的车牌检测，精度更高；适用高精度车牌识别
        reg("yolo7-plate-detect", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo7PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/license/license-plate-finetune-v1x.onnx", "https://huggingface.co/morsetechlab/yolov11-license-plate-detection/resolve/main/license-plate-finetune-v1x.onnx", false, null);
        // 车牌检测(YOLOv8)：YOLOv8 架构的车牌检测，最新版；适用最新车牌检测场景
        reg("yolo8-plate-detect", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo8PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/license/license-plate-finetune-v1x.onnx", "https://huggingface.co/morsetechlab/yolov11-license-plate-detection/resolve/main/license-plate-finetune-v1x.onnx", false, null);
        // 旋转目标检测(YOLOv11-OBB)：YOLOv11 旋转框检测，检测任意方向物体；适用遥感图像、旋转物体检测
        reg("yolo11-odd", "com.chua.deeplearning.support.onnx.yolo.v11.translator.Yolo11OddTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/obb/yolo11n-obb.onnx", "https://huggingface.co/onnx-community/yolo11n-obb/resolve/main/onnx/model.onnx", false, null);
        // 目标检测(YOLOv2-COCO)：YOLOv2 经典 COCO 80 类检测，轻量级；适用通用物体检测
        reg("yolov2-coco", "com.chua.deeplearning.support.onnx.yolo.v2.translator.Yolov2CocoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v2/yolov2-coco-9.onnx");
        // 目标检测(YOLOv2-COCO v2)：YOLOv2 变体版；适用通用物体检测
        reg("yolov2-coco-v2", "com.chua.deeplearning.support.onnx.yolo.v2.Yolov2CocoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, null);
        // 旋转目标检测(YOLOv26-OBB)：YOLOv26 旋转框检测，最新版；适用高精度旋转物体检测
        reg("yolo26-obb", "com.chua.deeplearning.support.onnx.yolo.v26.translator.Yolo26ObbTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/v26/yolo26n.onnx", "https://huggingface.co/onnx-community/yolo26n-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 目标检测(YOLOv8s)：YOLOv8s 标准 COCO 80 类检测，速度和精度平衡；适用通用物体检测
        reg("yolov8s", "com.chua.deeplearning.support.onnx.yolo.v8.translator.YoloV8sTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v8/yolov8s.onnx", "https://huggingface.co/lquint/yolov8s-onnx/resolve/main/onnx/model.onnx", false, "yolov8s.onnx");
        // 深度估计(DepthAnything v2)：从单张图片估计深度图，v2 版精度更高、细节更丰富；适用背景虚化、3D 场景理解
        reg("depth-anything", "com.chua.deeplearning.support.onnx.depth.DepthAnythingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/depth/depth-anything-v2/model.onnx", "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx", false, null);
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
        reg("grounding-dino", "com.chua.deeplearning.support.onnx.dino.GroundingDinoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/grounding-dino-tiny/model.onnx", "https://huggingface.co/onnx-community/grounding-dino-tiny-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 文本摘要(BART)：BART-large-cnn 文本摘要，输入长文本输出摘要；适用文章摘要、新闻概括
        reg("bart-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.BartSeq2SeqTranslator", String.class, String.class, Object.class, "vision/generation/bart-large-cnn/model.onnx", "https://huggingface.co/Xenova/bart-large-cnn/resolve/main/onnx/model.onnx", false, null);
        // 文本摘要(T5)：T5-small 文本摘要/翻译，多任务 seq2seq；适用文本生成、翻译、摘要
        // 注意：T5 为 encoder-decoder 自回归多文件模型（encoder/decoder/decoder_with_past + tokenizer），
        // 由 T5Seq2SeqOrtTranslator 按"嵌入/缓存/modelscope 下载"自行组装，注册不设 downloadUrl 避免单文件预下载。
        reg("t5-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.T5Seq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/t5-small/encoder_model_int8.onnx", null, null, false, null);
        // 多语言摘要/生成(mT5)：mt5-small 中文/多语言文本摘要与生成，encoder-decoder 自回归；适用中文多句→一句总结
        reg("mt5-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.Mt5Seq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/mt5-small/encoder_model_fp16.onnx", null, null, false, null);
        // 多语言摘要/生成(mT5-base)：中文多句→一句总结，12 层 12 头，效果优于 mt5-small；适用正式/长文本
        reg("mt5-base-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.Mt5BaseSeq2SeqOrtTranslator", String.class, String.class, Object.class, "nlp/seq2seq/mt5-base/encoder_model_fp16.onnx", null, null, false, null);
        // 文本摘要(Chinese-T5-base)：中文 T5-base 文本摘要，中文优化；适用中文文本摘要、生成
        reg("chinese-t5-base", "com.chua.deeplearning.support.onnx.seq2seq.ChineseT5BaseTranslator", String.class, String.class, Object.class, "nlp/seq2seq/chinese-t5-base/encoder_model.onnx", "https://huggingface.co/hfl/chinese-t5-base/resolve/main/encoder_model.onnx", false, null);
        // 文本摘要(Chinese-BART-base)：中文 BART-base 文本摘要；适用中文文本摘要、文章概括
        reg("chinese-bart-base", "com.chua.deeplearning.support.onnx.seq2seq.ChineseBartBaseTranslator", String.class, String.class, Object.class, "nlp/seq2seq/chinese-bart-base-cluecorpussmall/model.onnx", "https://huggingface.co/UER/bart-base-chinese-cluecorpussmall/resolve/main/model.onnx", false, null);
        // 文本摘要(Chinese-BART-large)：中文 BART-large 文本摘要，容量更大效果更好；适用高质量中文文本摘要
        reg("chinese-bart-large", "com.chua.deeplearning.support.onnx.seq2seq.ChineseBartLargeTranslator", String.class, String.class, Object.class, "nlp/seq2seq/chinese-bart-large/model.onnx", "https://huggingface.co/fnlp/bart-large-chinese/resolve/main/model.onnx", false, null);
        // 文本摘要(Randeng-T5)：中文 Randeng-T5 文本生成，基于 T5 架构；适用中文文本生成、摘要
        reg("randeng-t5", "com.chua.deeplearning.support.onnx.seq2seq.RandengT5Translator", String.class, String.class, Object.class, "nlp/seq2seq/randeng-t5-77m-chinese/encoder_model.onnx", "https://huggingface.co/IDEA-CCNL/Randeng-T5-77M-Chinese/resolve/main/encoder_model.onnx", false, null);
        // 文本摘要(Randeng-BART)：中文 Randeng-BART 文本摘要，139M 参数；适用高质量中文文本摘要
        reg("randeng-bart", "com.chua.deeplearning.support.onnx.seq2seq.RandengBartTranslator", String.class, String.class, Object.class, "nlp/seq2seq/randeng-bart-139m/model.onnx", "https://huggingface.co/IDEA-CCNL/Randeng-BART-139M/resolve/main/model.onnx", false, null);
        // 机器翻译(opus-mt-zh-en)：Helsinki-NLP 中译英 MarianMT，嵌入式模型 jar 提供，无需下载；适用中文翻译英文
        reg("opus-mt-zh-en", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtZhEnTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, "nlp/translation/opus_mt_zh_en/encoder_model_quantized.onnx");
        // 机器翻译(opus-mt-en-zh)：Helsinki-NLP 英译中 MarianMT，ONNX 自动下载（~30MB 量化）；适用英文翻译中文
        reg("opus-mt-en-zh", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtEnZhTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, null);
        // 机器翻译(opus-mt-zh-ja)：Helsinki-NLP 中译日 MarianMT，ONNX 自动下载（~30MB 量化）；适用中文翻译日文
        reg("opus-mt-zh-ja", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtZhJaTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, null);
        // 机器翻译(opus-mt-en-fr)：Helsinki-NLP 英译法 MarianMT，ONNX 自动下载（~30MB 量化）；适用英文翻译法文
        reg("opus-mt-en-fr", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtEnFrTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, null);
        // 机器翻译(opus-mt-en-de)：Helsinki-NLP 英译德 MarianMT，ONNX 自动下载（~30MB 量化）；适用英文翻译德文
        reg("opus-mt-en-de", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtEnDeTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, null);
        // 机器翻译(opus-mt-en-es)：Helsinki-NLP 英译西 MarianMT，ONNX 自动下载（~30MB 量化）；适用英文翻译西班牙文
        reg("opus-mt-en-es", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtEnEsTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, null);
        // 机器翻译(opus-mt-en-ru)：Helsinki-NLP 英译俄 MarianMT，ONNX 自动下载（~30MB 量化）；适用英文翻译俄文
        reg("opus-mt-en-ru", "com.chua.deeplearning.support.onnx.nlp.translation.OpusMtEnRuTranslationTranslator", String.class, String.class, com.chua.deeplearning.support.nlp.TextTranslator.class, null);
        // 动漫人脸检测(YOLOv8n)：检测动漫/二次元图片中的人脸（YOLOv8 v1.4_n）；适用动漫人脸检测、二次元内容分析。模型内嵌 jar（utils-support-models-onnx-anime-face）
        reg("anime-face-detector", "com.chua.deeplearning.support.onnx.anime.detection.AnimeFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/anime-face/model.onnx");
        // 零样本分割(CLIPSeg)：用文本描述分割图像（如"分割出汽车"），无需训练；适用零样本语义分割、文本引导分割
        reg("clipseg-zero-shot", "com.chua.deeplearning.support.onnx.seg.CLIPSegZeroShotSegmentationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/seg/clipseg-rd64-refined/model.onnx", "https://modelscope.cn/models/Xenova/clipseg-rd64-refined/resolve/master/onnx/model.onnx", false, null);
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
        // 活体检测(DINOv2)：基于 DINOv2 的活体检测；适用高精度人脸活体检测
        reg("face-liveness-dinov2", "com.chua.deeplearning.support.onnx.liveness.FaceAntiSpoofTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/liveness/dinov2_liveness/model.onnx", "https://huggingface.co/nguyenkhoa/dinov2_Liveness_detection_v2.2.3/resolve/main/model.onnx", false, null);
        // 活体检测(MobileViT)：基于 MobileViT 的轻量级活体检测；适用移动端活体检测
        reg("face-liveness-mobilevit", "com.chua.deeplearning.support.onnx.liveness.FaceAntiSpoofTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/liveness/mobilevitv2_liveness/model.onnx", "https://huggingface.co/nguyenkhoa/mobilevitv2_Liveness_detection_v1.0/resolve/main/model.onnx", false, null);
        // 人脸检测(YOLOv11n-face)：YOLOv11n 超轻量人脸检测，6MB；适用边缘设备人脸检测
        reg("yolo-face-detector", "com.chua.deeplearning.support.onnx.face.UltraFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/yolo11n-face.onnx", "https://huggingface.co/AdamCodd/YOLOv11n-face-detection/resolve/main/model.onnx", false, null);
        // 人脸+人体检测(YOLO-Face-Person)：同时检测人脸和人体；适用安防监控、人群分析
        reg("yolo-face-person", "com.chua.deeplearning.support.onnx.face.ScrfdFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/yolo-face-person.onnx", "https://huggingface.co/iitolstykh/YOLO-Face-Person-Detector/resolve/main/model.onnx", false, null);
        // 年龄+性别识别(ONNX)：基于 ViT 的人脸年龄+性别联合识别；适用人脸属性分析
        reg("age-gender-onnx", "com.chua.deeplearning.support.onnx.agegender.AgeRaceGenderTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/attribute/age_gender/age-gender-vit.onnx", "https://huggingface.co/onnx-community/age-gender-prediction-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 人脸年龄识别(YOLOv11n)：YOLOv11n 超轻量人脸年龄识别；适用边缘设备年龄估计
        // 文本嵌入(All-MiniLM-L6-v2)：英文句向量（384维），语义搜索/向量检索；适用英文语义搜索、文本相似度
        reg("all-MiniLM-L6-v2-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/all-MiniLM-L6-v2/model.onnx", "https://modelscope.cn/models/Xenova/all-MiniLM-L6-v2/resolve/master/onnx/model_uint8.onnx", java.util.List.of("https://huggingface.co/onnx-community/all-MiniLM-L6-v2-ONNX/resolve/main/onnx/model.onnx"), false, null);
        // 文本嵌入(BGE-small-en)：英文句向量（384维），BGE 系列英文版；适用英文语义搜索
        reg("bge-small-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-small-en-v1.5/model.onnx", "https://huggingface.co/onnx-community/bge-small-en-v1.5-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 文本嵌入(BGE-small-en embedded)：嵌入式版英文句向量（~63MB FP16），jar 内嵌，384维；适用离线英文语义搜索
        reg("bge-small-en-embedding", "com.chua.deeplearning.support.onnx.embedding.bge.BgeEmbeddingClient", String.class, float[].class, Object.class, "nlp/embedding/bge-small-en-v1.5/onnx/model_fp16.onnx");
        // 文本嵌入(BGE-small-zh)：中文句向量（512维），BGE 系列中文版，离线 jar 版；适用中文语义搜索、向量检索
        reg("bge-small-zh-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-small-zh-v1.5/model.onnx", "https://huggingface.co/onnx-community/bge-small-zh-v1.5-ONNX/resolve/main/onnx/model.onnx", false, null);
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
        // 声纹特征提取(Wespeaker)：说话人识别/声纹特征提取，ResNet34 架构；适用说话人识别、声纹比对
        reg("wespeaker-feature", "com.chua.deeplearning.support.onnx.feature.ClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/wespeaker-resnet34/model.onnx", "https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM/resolve/main/onnx/model.onnx", false, null);
        // 目标检测(YOLOv26n)：YOLOv26 通用检测，最新版；适用通用物体检测
        reg("yolo26n", "com.chua.deeplearning.support.onnx.yolo.v26.translator.Yolo26ObbTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/v26/yolo26n.onnx", "https://huggingface.co/onnx-community/yolo26n-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 目标检测(YOLOv10m)：YOLOv10m 通用 COCO 检测，精度较高；适用通用物体检测
        reg("yolov10m", "com.chua.deeplearning.support.onnx.yolo.v10.translator.YoloV10DetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v10/yolov10m.onnx", "https://huggingface.co/onnx-community/yolov10m/resolve/main/onnx/model.onnx", false, null);
        // 目标检测(YOLOv10n)：YOLOv10n 超轻量通用 COCO 检测；适用边缘设备通用检测
        reg("yolov10n", "com.chua.deeplearning.support.onnx.yolo.v10.translator.YoloV10DetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v10/yolov10n.onnx", "https://huggingface.co/onnx-community/yolov10n/resolve/main/onnx/model.onnx", false, null);
        // 标签分类(WD-SwinV2-Tagger)：为动漫图片打标签（如"长发"、"微笑"、"猫耳"等），基于 SwinV2；适用二次元图片标签、Danbooru 标签
        reg("wd-tagger-swinv2", "com.chua.deeplearning.support.onnx.classification.ClTaggerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/wd-swinv2-tagger-v3/model.onnx", "https://huggingface.co/SmilingWolf/wd-swinv2-tagger-v3/resolve/main/model.onnx", false, null);
        // 情绪识别(YOLOv11-face)：YOLOv11n 人脸情绪识别（开心、难过、生气等）；适用人脸情绪分析
        reg("yolo-face-emotion", "com.chua.deeplearning.support.onnx.emotion.EmotionFerplusTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/expression/yolo11-face-emotion.onnx", "https://huggingface.co/leeyunjai/yolo11-face-emotion-fer2013-cls/resolve/main/model.onnx", false, null);
        // 语音识别(Moonshine-base)：Moonshine 语音识别，输入音频输出文本；适用语音转文字、语音指令
        reg("moonshine-base", "com.chua.deeplearning.support.onnx.text.BertSquadTranslator", String.class, String.class, Object.class, "nlp/audio/moonshine-base/model.onnx", "https://huggingface.co/onnx-community/moonshine-base-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 情感分析(RoBERTa-go-emotions)：28 种细粒度情感分类（如"兴奋"、"悲伤"、"愤怒"等）；适用细粒度情感分析、用户评论分析
        reg("roberta-go-emotions", "com.chua.deeplearning.support.onnx.classification.DistilBertSentimentTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/roberta-go-emotions/model.onnx", "https://huggingface.co/SamLowe/roberta-base-go_emotions-onnx/resolve/main/model.onnx", false, null);
        // 文本生成(MiniMind)：小型因果语言模型，中文文本续写/生成，完全离线；适用离线文本生成、对话
        reg("minimind", "com.chua.deeplearning.support.onnx.text.minimind.MiniMindTranslator", String.class, String.class, Object.class, "models/minimind/model.onnx");
        // 图像分类(MobileNetV4)：MobileNetV4 1000 类 ImageNet 分类，最新版更快更准；适用移动端通用分类
        reg("mobilenetv4-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/mobilenetv4/mobilenetv4_conv_small.onnx", "https://huggingface.co/onnx-community/mobilenetv4_conv_small.e2400_r224_in1k/resolve/main/onnx/model.onnx", false, null);
        // 深度伪造检测(DeepFake Detector)：检测图片/视频是否为深度伪造；适用反欺诈、虚假内容检测
        reg("deepfake-detector", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/deepfake-detector/model.onnx", "https://huggingface.co/onnx-community/Deep-Fake-Detector-v2-Model-ONNX/resolve/main/onnx/model.onnx", false, null);
        // 人脸检测(FacePlugin-Slim)：轻量级人脸检测插件，超小模型；适用轻量人脸检测
        reg("faceplugin-face-detect-slim", "com.chua.deeplearning.support.onnx.face.FacePluginDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "models/onnx/face/detection/faceplugin/face_detect_slim.onnx");
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
        // 卡片矫正检测(Card Correction)：检测卡片类图片（身份证、名片等）四角点；适用文档扫描、证件识别
        reg("card-correction-detector", "com.chua.deeplearning.support.onnx.classification.CardCorrectionTranslator", byte[].class, java.util.List.class, com.chua.deeplearning.support.image.ImageDetector.class, "cv/card_correction/card_detection.onnx");
        // 车牌检测(YOLOv5)：YOLOv5 架构的车牌检测；适用停车场、出入口车牌识别
        reg("yolov5-plate-detect", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo5PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/yolov5_plate/yolov5_plate_detect.onnx");
        // 车牌识别(YOLOv5)：YOLOv5 车牌字符识别，配合检测使用；适用完整车牌识别流水线
        reg("yolov5-plate-recognize", "com.chua.deeplearning.support.onnx.ocr.extractor.PpWordExtractorTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "vision/detection/yolov5_plate/yolov5_plate_rec_color.onnx");
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
        // MMS-TTS-English（VITS）：英文语音合成，输入文本输出 WAV 音频；适用英文朗读、语音播报。
        // 模型打包在 utils-support-models-onnx-mms-tts-eng jar 中（audio/tts/mms-tts-eng/），
        // 由 OnnxTextToAudioClient 直接加载，无需注册 translator 类。
        reg("mms-tts-eng", null, String.class, byte[].class, Object.class, "audio/tts/mms-tts-eng/model_quantized.onnx");

        // Pocket-TTS（Kyutai 100M 流匹配 TTS）：多语言语音合成（含零样本声音克隆），24kHz WAV；
        // 模型打包在 utils-support-models-onnx-pocket-tts jar 中（audio/tts/pocket-tts/，~225MB int8），
        // 由 OnnxTextToAudioClient 直接加载，无需注册 translator 类。
        reg("pocket-tts", null, String.class, byte[].class, Object.class, "audio/tts/pocket-tts/config.json");

        // ==================== 零样本检测 YOLO-World（嵌入式友好） ====================
        // YOLO-World Small：开放词表检测，文本提示（中/英文）→ 检测框+类别；模型 ~40MB，适合嵌入式/边缘部署
        reg("yolov8s-world", "com.chua.deeplearning.support.onnx.yoloworld.YoloWorldDetectorTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                com.chua.deeplearning.support.image.ImageDetector.class,
                "vision/detection/yoloworld/yolov8s-world.onnx",
                "https://huggingface.co/onnx-community/YOLOWorld-s/resolve/main/onnx/model.onnx",
                java.util.List.of("https://hf-mirror.com/onnx-community/YOLOWorld-s/resolve/main/onnx/model.onnx"),
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
                "https://huggingface.co/onnx-community/YOLOWorld-l/resolve/main/onnx/model.onnx",
                java.util.List.of("https://hf-mirror.com/onnx-community/YOLOWorld-l/resolve/main/onnx/model.onnx"),
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
}



