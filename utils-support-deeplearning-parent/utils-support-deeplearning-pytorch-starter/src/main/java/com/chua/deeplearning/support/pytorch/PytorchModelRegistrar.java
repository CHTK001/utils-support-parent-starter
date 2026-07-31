package com.chua.deeplearning.support.pytorch;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.image.DepthEstimator;
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.image.ImageEnhancer;
import com.chua.deeplearning.support.image.ImageGenerator;
import com.chua.deeplearning.support.nlp.TextTranslator;

/**
 * PyTorch 模块模型集中注册器。
 * <p>
 * 通过 SPI 被主框架加载；类名字符串注册 + 懒加载 Translator。
 * relativePath 相对 models 根目录，支持 pytorch/ 前缀。
 * 模型文件为 TorchScript（.pt / .pth），由 DJL PyTorch 引擎加载。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PytorchModelRegistrar implements ModelRegistrar {

    static {
        registerAll();
    }

    @Override
    public void register(ModelRegistry registry) {
        registerAll();
    }

    private static void registerAll() {
        // 图像分类
        reg("pytorch-resnet18",
                "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "classification/resnet18.pt");
        reg("pytorch-mobilenet-v2",
                "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "classification/mobilenet_v2.pt");

        // YOLO 检测
        reg("pytorch-yolov5s",
                "com.chua.deeplearning.support.pytorch.detection.PytorchYoloTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "detection/yolov5s.pt",
                "https://huggingface.co/Ultralytics/YOLOv5/resolve/main/yolov5s.pt", false, null);
        reg("pytorch-yolov8n",
                "com.chua.deeplearning.support.pytorch.detection.PytorchYoloTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "detection/yolov8n.pt",
                "https://huggingface.co/Ultralytics/YOLOv8/resolve/main/yolov8n.pt", false, null);

        // 图像特征
        reg("pytorch-image-feature",
                "com.chua.deeplearning.support.pytorch.feature.PytorchImageFeatureTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "feature/resnet18_feature.pt");

        // 人脸特征 / 识别
        reg("pytorch-face-feature",
                "com.chua.deeplearning.support.pytorch.face.feature.FaceFeatureTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/face_feature.pt");
        reg("pytorch-insightface",
                "com.chua.deeplearning.support.pytorch.face.recognition.InsightFaceTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/insightface.pt");
        reg("pytorch-sphereface",
                "com.chua.deeplearning.support.pytorch.face.recognition.SphereFaceTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/sphereface.pt");
        reg("pytorch-vggface",
                "com.chua.deeplearning.support.pytorch.face.recognition.VggFaceTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/vggface.pt");
        reg("pytorch-elasticface",
                "com.chua.deeplearning.support.pytorch.face.recognition.ElasticFaceTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/elasticface.pt");
        reg("pytorch-evolveface",
                "com.chua.deeplearning.support.pytorch.face.recognition.EvolveFaceTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/evolveface.pt");
        reg("pytorch-amazon-face",
                "com.chua.deeplearning.support.pytorch.face.recognition.AmazonFaceFeatureTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "face/amazon_face.pt");

        // 表情
        reg("pytorch-expression",
                "com.chua.deeplearning.support.pytorch.face.expression.DenseNetExpressionTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class,
                ImageClassifier.class, "face/expression_densenet.pt");

        // 图像增强：风格 / 超分 / 上色
        reg("pytorch-style-transfer",
                "com.chua.deeplearning.support.pytorch.style.StyleTransferTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "style/style_transfer.pt");
        reg("pytorch-realesrgan",
                "com.chua.deeplearning.support.pytorch.resolution.RealEsrganTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "resolution/realesrgan_x4.pt");
        reg("pytorch-colorization",
                "com.chua.deeplearning.support.pytorch.colorization.ColorizationTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "colorization/colorization.pt");
        reg("pytorch-hed-scribble",
                "com.chua.deeplearning.support.pytorch.controlnet.HedScribbleTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "controlnet/hed_scribble.pt");

        // 深度估计
        reg("pytorch-midas-depth",
                "com.chua.deeplearning.support.pytorch.depth.MidasDepthTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                DepthEstimator.class, "depth/midas.pt");
        reg("pytorch-dpt-depth",
                "com.chua.deeplearning.support.pytorch.depth.DptDepthTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                DepthEstimator.class, "depth/dpt.pt");

        // 图像生成
        reg("pytorch-biggan-128",
                "com.chua.deeplearning.support.pytorch.biggan.BigGAN128Translator",
                Long.class, ai.djl.modality.cv.Image.class,
                ImageGenerator.class, "generation/biggan_128.pt");
        reg("pytorch-biggan-256",
                "com.chua.deeplearning.support.pytorch.biggan.BigGAN256Translator",
                Long.class, ai.djl.modality.cv.Image.class,
                ImageGenerator.class, "generation/biggan_256.pt");
        reg("pytorch-biggan-512",
                "com.chua.deeplearning.support.pytorch.biggan.BigGAN512Translator",
                Long.class, ai.djl.modality.cv.Image.class,
                ImageGenerator.class, "generation/biggan_512.pt");

        // Diffusion 条件图 / ControlNet 预处理
        reg("pytorch-lineart",
                "com.chua.deeplearning.support.pytorch.diffusion.lineart.LineArtTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "diffusion/lineart.pt");
        reg("pytorch-lineart-anime",
                "com.chua.deeplearning.support.pytorch.diffusion.lineart.LineArtAnimeTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "diffusion/lineart_anime.pt");
        reg("pytorch-diffusion-depth",
                "com.chua.deeplearning.support.pytorch.diffusion.depth.DiffusionDepthTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                DepthEstimator.class, "diffusion/depth.pt");
        reg("pytorch-net-normal",
                "com.chua.deeplearning.support.pytorch.diffusion.normal.NetNormalTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "diffusion/net_normal.pt");
        reg("pytorch-pidi-scribble",
                "com.chua.deeplearning.support.pytorch.diffusion.scribble.PidiScribbleTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "diffusion/pidi_scribble.pt");
        reg("pytorch-clip-text-encoder",
                "com.chua.deeplearning.support.pytorch.diffusion.clip.ClipTextEncoderTranslator",
                String.class, ai.djl.ndarray.NDList.class,
                FeatureExtractor.class, "diffusion/clip_text_encoder.pt");

        // 句向量 / 文本特征
        reg("pytorch-sentence",
                "com.chua.deeplearning.support.pytorch.sentence.SentenceTransTranslator",
                String.class, float[].class,
                FeatureExtractor.class, "sentence/sentence.pt");
        reg("pytorch-text-feature",
                "com.chua.deeplearning.support.pytorch.feature.PytorchTextFeatureTranslator",
                String.class, float[].class,
                FeatureExtractor.class, "feature/text_feature.pt");

        // NLLB / 翻译子模块（encoder/decoder 分片，供流水线组合）
        reg("pytorch-nllb-encoder",
                "com.chua.deeplearning.support.pytorch.translation.NllbEncoderTranslator",
                long[].class, ai.djl.ndarray.NDArray.class,
                TextTranslator.class, "translation/nllb_encoder.pt");
        reg("pytorch-nllb-decoder",
                "com.chua.deeplearning.support.pytorch.translation.NllbDecoderTranslator",
                ai.djl.ndarray.NDList.class,
                com.chua.deeplearning.support.pytorch.translation.CausalLMOutput.class,
                TextTranslator.class, "translation/nllb_decoder.pt");
        reg("pytorch-nllb-decoder2",
                "com.chua.deeplearning.support.pytorch.translation.NllbDecoder2Translator",
                ai.djl.ndarray.NDList.class,
                com.chua.deeplearning.support.pytorch.translation.CausalLMOutput.class,
                TextTranslator.class, "translation/nllb_decoder2.pt");
        reg("pytorch-opus-encoder",
                "com.chua.deeplearning.support.pytorch.translation.opus.OpusEncoderTranslator",
                int[].class, ai.djl.ndarray.NDArray.class,
                TextTranslator.class, "translation/opus_encoder.pt");
        reg("pytorch-opus-decoder",
                "com.chua.deeplearning.support.pytorch.translation.opus.OpusDecoderTranslator",
                ai.djl.ndarray.NDList.class,
                com.chua.deeplearning.support.pytorch.translation.CausalLMOutput.class,
                TextTranslator.class, "translation/opus_decoder.pt");
        reg("pytorch-opus-decoder2",
                "com.chua.deeplearning.support.pytorch.translation.opus.OpusDecoder2Translator",
                ai.djl.ndarray.NDList.class,
                com.chua.deeplearning.support.pytorch.translation.CausalLMOutput.class,
                TextTranslator.class, "translation/opus_decoder2.pt");

        // 人脸检测 / 人脸修复
        reg("pytorch-ultraface",
                "com.chua.deeplearning.support.pytorch.face.detector.UltraFaceTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class,
                ImageDetector.class, "face/ultraface.pt");
        reg("pytorch-gfpgan",
                "com.chua.deeplearning.support.pytorch.face.resolution.GfpganTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "face/gfpgan.pt");

        // 图文超分 / CLIP 图像特征
        reg("pytorch-image-text-sr",
                "com.chua.deeplearning.support.pytorch.resolution.ImageTextSuperResolutionTranslator",
                ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class,
                ImageEnhancer.class, "resolution/image_text_sr.pt");
        reg("pytorch-clip-image",
                "com.chua.deeplearning.support.pytorch.feature.ClipImageFeatureTranslator",
                ai.djl.modality.cv.Image.class, float[].class,
                FeatureExtractor.class, "feature/clip_image.pt");

        // ==================== 人脸检测 ====================
        reg("pytorch-yolo-face", "com.chua.deeplearning.support.pytorch.face.detector.UltraFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, ImageDetector.class, "face/yolo11n-face.pt", "https://huggingface.co/AdamCodd/YOLOv11n-face-detection/resolve/main/model.pt", false, null);
        reg("pytorch-retinaface", "com.chua.deeplearning.support.pytorch.face.detector.UltraFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, ImageDetector.class, "face/retinaface.pt", "https://huggingface.co/nakamura196/retinaface-r50-onnx/resolve/main/model.pt", false, null);

        // ==================== 年龄推算 ====================
        reg("pytorch-age-gender", "com.chua.deeplearning.support.pytorch.face.expression.DenseNetExpressionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "face/age_gender.pt", "https://huggingface.co/onnx-community/age-gender-prediction-ONNX/resolve/main/onnx/model.pt", false, null);

        // ==================== 特征值提取 ====================
        reg("pytorch-bge-embedding", "com.chua.deeplearning.support.pytorch.feature.PytorchTextFeatureTranslator", String.class, float[].class, FeatureExtractor.class, "feature/bge-small.pt", "https://huggingface.co/onnx-community/bge-small-en-v1.5-ONNX/resolve/main/onnx/model.pt", false, null);
        reg("pytorch-all-MiniLM", "com.chua.deeplearning.support.pytorch.feature.PytorchTextFeatureTranslator", String.class, float[].class, FeatureExtractor.class, "feature/all-MiniLM-L6-v2.pt", "https://huggingface.co/onnx-community/all-MiniLM-L6-v2-ONNX/resolve/main/onnx/model.pt", false, null);

        // ==================== 零样本分类 ====================
        reg("pytorch-clip-zero-shot", "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "classification/clip-vit.pt", "https://huggingface.co/onnx-community/clip-vit-base-patch32-ONNX/resolve/main/onnx/model.pt", false, null);

        // ==================== 动物分类 ====================
        reg("pytorch-animals-10", "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "classification/animals-10.pt", "https://huggingface.co/onnx-community/10-animals-classification-ONNX/resolve/main/onnx/model.pt", false, null);

        // ==================== 食物分类 ====================
        reg("pytorch-food101", "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "classification/food101.pt", "https://huggingface.co/nateraw/vit-base-food101/resolve/main/model.pt", false, null);

        // ==================== 植物分类 ====================
        reg("pytorch-plant", "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "classification/plant.pt", "https://huggingface.co/onnx-community/house-plant-image-detection-ONNX/resolve/main/onnx/model.pt", false, null);

        // ==================== MobileNet / EfficientNet ====================
        reg("pytorch-mobilenetv3", "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "classification/mobilenetv3.pt", "https://huggingface.co/onnx-community/mobilenetv3_small_100.lamb_in1k/resolve/main/onnx/model.pt", false, null);
        reg("pytorch-mobilenetv4", "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "classification/mobilenetv4.pt", "https://huggingface.co/onnx-community/mobilenetv4_conv_small.e2400_r224_in1k/resolve/main/onnx/model.pt", false, null);

        // ==================== 特征提取 (视觉) ====================
        reg("pytorch-dinov3", "com.chua.deeplearning.support.pytorch.feature.PytorchImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, FeatureExtractor.class, "feature/dinov3.pt", "https://huggingface.co/onnx-community/dinov3-vits16-pretrain-lvd1689m-ONNX/resolve/main/onnx/model.pt", false, null);
        reg("pytorch-granite-embedding", "com.chua.deeplearning.support.pytorch.feature.PytorchTextFeatureTranslator", String.class, float[].class, FeatureExtractor.class, "feature/granite-embedding.pt", "https://huggingface.co/onnx-community/granite-embedding-small-english-r2-ONNX/resolve/main/onnx/model.pt", false, null);

        // ==================== 情绪识别 ====================
        reg("pytorch-face-emotion", "com.chua.deeplearning.support.pytorch.face.expression.DenseNetExpressionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "face/yolo11-face-emotion.pt", "https://huggingface.co/leeyunjai/yolo11-face-emotion-fer2013-cls/resolve/main/model.pt", false, null);

        // ==================== 动漫标签 ====================
        reg("pytorch-wd-tagger", "com.chua.deeplearning.support.pytorch.classification.PytorchImageNetClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, ImageClassifier.class, "classification/wd-swinv2-tagger.pt", "https://huggingface.co/SmilingWolf/wd-swinv2-tagger-v3/resolve/main/model.pt", false, null);
    }

    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath) {
        if (ModelRegistry.get(modelId) == null) {
            String path = relativePath == null ? null : "pytorch/" + relativePath;
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, path);
        }
    }

    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath,
                            String downloadUrl, boolean compress, String downloadFileName) {
        if (ModelRegistry.get(modelId) == null) {
            String path = relativePath == null ? null : "pytorch/" + relativePath;
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, path, downloadUrl, compress, downloadFileName);
        }
    }
}
