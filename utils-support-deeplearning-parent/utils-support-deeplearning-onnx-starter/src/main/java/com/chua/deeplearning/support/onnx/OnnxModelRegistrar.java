package com.chua.deeplearning.support.onnx;

import com.chua.deeplearning.support.engine.ModelRegistrar;
import com.chua.deeplearning.support.engine.ModelRegistry;

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
    public void register(ModelRegistry registry) {
        registerAll();
    }

    private static void registerAll() {
        reg("common-action", "com.chua.deeplearning.support.onnx.action.CommonActionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/action/common/action.onnx");
        reg("google-net-age-recognition", "com.chua.deeplearning.support.onnx.age.GoogleNetAgeRecognitionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/age/age_googlenet.onnx");
        reg("vgg-age-recognition", "com.chua.deeplearning.support.onnx.age.VggAgeRecognitionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/age/vgg_ilsvrc_16_age_imdb_wiki.onnx");
        reg("age-race-gender", "com.chua.deeplearning.support.onnx.agegender.AgeRaceGenderTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/attribute/age_gender/AgeRaceGenderNet_v1.onnx");
        reg("anime-gan-v3", "com.chua.deeplearning.support.onnx.animegan.AnimeGanV3Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/AnimeGANv3/AnimeGANv3_Hayao_36.onnx");
        reg("nima", "com.chua.deeplearning.support.onnx.assessment.NimaTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/assessment/nima/nima.onnx");
        reg("anime-real-cls", "com.chua.deeplearning.support.onnx.classification.AnimeRealClsTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/anime/anime_real_cls/mobilenetv3_v1.4_dist/model.onnx");
        reg("cl-tagger", "com.chua.deeplearning.support.onnx.classification.ClTaggerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/enhancement/cl_tagger_1_02/cl_tagger_1_02_optimized.onnx");
        reg("distil-bert-sentiment", "com.chua.deeplearning.support.onnx.classification.DistilBertSentimentTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/distilbert-sst2/model_quantized.onnx");
        reg("efficient-net-lite0-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/efficientnet/efficientnet-lite0-11.onnx");
        reg("efficient-net-lite4-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite4ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/efficientnet/efficientnet-lite4-11.onnx");
        reg("siglip-zero-shot-classification", "com.chua.deeplearning.support.onnx.classification.SiglipZeroShotClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/zeroshot/siglip-base-patch16-224/onnx/model.onnx");
        reg("cn-clip-image", "com.chua.deeplearning.support.onnx.clip.CnClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/cn-clip-vit-b-16/vit-b-16.img.b1.fp32.onnx", "https://hf-mirror.com/gficcg/clip_cn_vit-onnx/resolve/main/clip_cn_vit-b-16/vit-b-16.img.b1.fp32.onnx", false, null);
        reg("cn-clip-text", "com.chua.deeplearning.support.onnx.clip.CnClipTextFeatureTranslator", String.class, float[].class, Object.class, "vision/zeroshot/cn-clip-vit-b-16/vit-b-16.txt.fp32.onnx", "https://hf-mirror.com/gficcg/clip_cn_vit-onnx/resolve/main/clip_cn_vit-b-16/vit-b-16.txt.fp32.onnx", false, null);
        reg("xlm-roberta-language-detection", "com.chua.deeplearning.support.onnx.classification.XlmRobertaLanguageDetectionTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/xlm-roberta-language-detection/model_quantized.onnx");
        reg("clip-text-feature", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "vision/enhancement/CLIP-ViT-B-32-TEXT/model.onnx");
        reg("midas-depth", "com.chua.deeplearning.support.onnx.depth.MidasDepthTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/depth/midas/midas.onnx");
        reg("dino-v2", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov2/model.onnx");
        reg("donut", "com.chua.deeplearning.support.onnx.donut.DonutTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/donut/donut.onnx");
        reg("emotion-ferplus", "com.chua.deeplearning.support.onnx.emotion.EmotionFerplusTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/expression/FrEmotion/fr_expression.onnx");
        reg("arc-face", "com.chua.deeplearning.support.onnx.face.ArcFaceTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/swap/common/buffalo_l/w600k_r50.onnx");
        reg("ada-face", "com.chua.deeplearning.support.onnx.face.AdaFaceTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/adaface/adaface_ir101_webface12m.onnx", "https://huggingface.co/miccai/adaface-ir101-webface12m/resolve/main/model.onnx", false, null);
        reg("common-face-rec", "com.chua.deeplearning.support.onnx.face.CommonFaceRecTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/face_feature_sdk/face_feature.onnx");
        reg("face-feature", "com.chua.deeplearning.support.onnx.face.FaceFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/face_feature_sdk/face_feature.onnx");
        reg("r50-face-feature", "com.chua.deeplearning.support.onnx.face.R50FaceFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "face/recognition/face_feature_sdk/face_feature_r50.onnx");
        reg("scrfd-face-detector", "com.chua.deeplearning.support.onnx.face.ScrfdFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/scrfd/2.5g_bnkps.onnx", "https://huggingface.co/RuteNL/SCRFD-face-detection-ONNX/resolve/main/2.5g_bnkps.onnx", false, null);
        reg("ultra-face", "com.chua.deeplearning.support.onnx.face.UltraFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/face_detection_sdk/RFB.onnx", "https://huggingface.co/onnxmodelzoo/version-RFB-320/resolve/main/version-RFB-320.onnx", false, null);
        reg("clip-image-feature", "com.chua.deeplearning.support.onnx.feature.ClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/enhancement/CLIP-ViT-B-32-IMAGE/CLIP-ViT-B-32-IMAGE.onnx");
        reg("mobile-clip-image-feature", "com.chua.deeplearning.support.onnx.feature.MobileClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/zeroshot/mobileclip_s0/onnx/vision_model.onnx");
        reg("google-net-gender-recognition", "com.chua.deeplearning.support.onnx.gender.GoogleNetGenderRecognitionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/gender/gender_googlenet.onnx");
        reg("vgg-gender-recognition", "com.chua.deeplearning.support.onnx.gender.VggGenderRecognitionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/gender/vgg_ilsvrc_16_gender_imdb_wiki.onnx");
        reg("lcm-lora-unet", "com.chua.deeplearning.support.onnx.generation.LcmLoraUnetTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/lcm/lora/unet.onnx");
        reg("lcm-lora-vae-decoder", "com.chua.deeplearning.support.onnx.generation.LcmLoraVaeDecoderTranslator", ai.djl.ndarray.NDList.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/lcm/lora/vae_decoder.onnx");
        reg("lcm-lora-vae-encoder", "com.chua.deeplearning.support.onnx.generation.LcmLoraVaeEncoderTranslator", ai.djl.modality.cv.Image.class, ai.djl.ndarray.NDList.class, Object.class, "vision/detection/lcm/lora/vae_encoder.onnx");
        reg("small-sd-text-encoder", "com.chua.deeplearning.support.onnx.generation.SmallSdTextEncoderTranslator", String.class, ai.djl.ndarray.NDList.class, Object.class, "vision/detection/small-sd/text_encoder.onnx");
        reg("small-sd-unet", "com.chua.deeplearning.support.onnx.generation.SmallSdUnetTranslator", ai.djl.ndarray.NDList.class, ai.djl.ndarray.NDList.class, Object.class, "vision/detection/small-sd/unet.onnx");
        reg("small-sd-vae-decoder", "com.chua.deeplearning.support.onnx.generation.SmallSdVaeDecoderTranslator", ai.djl.ndarray.NDList.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/small-sd/vae_decoder.onnx");
        reg("small-stable-diffusion-combined", "com.chua.deeplearning.support.onnx.generation.SmallStableDiffusionCombinedTranslator", String.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/small-sd/combined.onnx");
        reg("taesd-decoder", "com.chua.deeplearning.support.onnx.generation.TaesdDecoderTranslator", ai.djl.ndarray.NDList.class, ai.djl.modality.cv.Image.class, Object.class, "vision/detection/taesd/decoder.onnx");
        reg("layout-lmv3", "com.chua.deeplearning.support.onnx.layoutlmv3.LayoutLMv3Translator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/layout/layoutlmv3/model.onnx");
        reg("image-to-line-drawing", "com.chua.deeplearning.support.onnx.linedrawing.ImageToLineDrawingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/image-to-line-drawing-onnx/image-to-line-drawing-onnx.onnx");
        reg("face-anti-spoof", "com.chua.deeplearning.support.onnx.liveness.FaceAntiSpoofTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/antispoof/minifasnet_v2/model.onnx");
        reg("mini-vision-liveness", "com.chua.deeplearning.support.onnx.liveness.MiniVisionLivenessTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/liveness/MiniVision/4_0_0_80x80_MiniFASNetV1SE.onnx");
        reg("matting", "com.chua.deeplearning.support.onnx.matting.translator.MattingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/seg_unet_sdk/u2net.onnx");
        reg("rmbg20", "com.chua.deeplearning.support.onnx.matting.translator.Rmbg20Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/RMBG-2.0/onnx/model.onnx");
        reg("nomos2", "com.chua.deeplearning.support.onnx.nomos2.Nomos2Translator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/esrgan/4xNomos2_otf_esrgan_fp32_opset17.onnx");
        reg("pp-word-rotate", "com.chua.deeplearning.support.onnx.ocr.direction.PpWordRotateTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "ocr/ppocrv5-cls.onnx");
        reg("pp-word-extractor", "com.chua.deeplearning.support.onnx.ocr.extractor.PpWordExtractorTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/recognition/PP-OCRv5_server_rec_infer/PP-OCRv5_server_rec.onnx");
        reg("svtr-extractor", "com.chua.deeplearning.support.onnx.ocr.extractor.SvtrExtractorTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/recognition/PP-OCRv5_mobile_rec_infer/PP-OCRv5_mobile_rec_infer.onnx");
        reg("ocr-layout-detector", "com.chua.deeplearning.support.onnx.ocr.layout.OcrLayoutDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/pp_doc_layoutv3/PP-DocLayoutV3.onnx");
        reg("pp-doc-layout", "com.chua.deeplearning.support.onnx.ocr.layout.PpDocLayoutTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/pp_doc_layoutv2/pp_doc_layoutv2.onnx");
        reg("paddle-ocr-recognition", "com.chua.deeplearning.support.onnx.ocr.paddleocr.PaddleOcrRecognitionTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/ppocrv5-server-rec.onnx");
        reg("pp-structure-v2", "com.chua.deeplearning.support.onnx.ocr.paddlestructure.PpStructureV2Translator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "ocr/table_structure/pp_structure_v2/inference.onnx");
        reg("pp-ocr-rec", "com.chua.deeplearning.support.onnx.ocr.PpOcrRecTranslator", ai.djl.modality.cv.Image.class, String.class, Object.class, "ocr/ch_PP-OCRv4_rec_infer.onnx");
        reg("table-struct", "com.chua.deeplearning.support.onnx.ocr.table.TableStructTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "ocr/table_structure/pp_structure_v2/inference.onnx");
        reg("owlv2-zero-shot-detector", "com.chua.deeplearning.support.onnx.owlv2.Owlv2ZeroShotDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/owlv2_base_patch16/onnx/model.onnx", "https://huggingface.co/onnx-community/owlv2-base-patch16-ONNX/resolve/main/onnx/model.onnx", false, "owlv2-base-patch16.onnx");
        reg("crnn-plate-rec", "com.chua.deeplearning.support.onnx.plate.translator.CrnnPlateRecTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/license/license-plate-finetune-v1x.onnx");
        reg("real-web-photo", "com.chua.deeplearning.support.onnx.realwebphoto.RealWebPhotoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/4xRealWebPhoto_v4/4xRealWebPhoto_v4_fp32_opset17.onnx");
        reg("hand-pose", "com.chua.deeplearning.support.onnx.reid.HandPoseTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/handpose/handpose_estimation_mediapipe_2023feb.onnx");
        reg("osnet-reid", "com.chua.deeplearning.support.onnx.reid.OsnetReidTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/reid/osnet_ain_multisource.onnx");
        reg("gfpgan-face-super-resolution", "com.chua.deeplearning.support.onnx.resolution.GfpganFaceSuperResolutionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "face/restoration/gfpgan/GFPGANv1.4.onnx");
        reg("image-text-super-resolution", "com.chua.deeplearning.support.onnx.resolution.ImageTextSuperResolutionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "nlp/general/models/textbsr_traced_model.onnx");
        reg("naf-net", "com.chua.deeplearning.support.onnx.resolution.NafNetTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/nafnet/nafnet_deblur_gopro.onnx");
        reg("real-esrgan", "com.chua.deeplearning.support.onnx.resolution.RealEsrganTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/esrgan/realesrgan_x4plus.onnx");
        reg("real-text-image-super-resolution", "com.chua.deeplearning.support.onnx.resolution.RealTextImageSuperResolutionTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "nlp/general/models/textbsr_traced_model.onnx");
        reg("text-bsr", "com.chua.deeplearning.support.onnx.resolution.TextBsrTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "nlp/general/models/textbsr_traced_model.onnx");
        reg("waifu2x", "com.chua.deeplearning.support.onnx.resolution.Waifu2xTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/waifu2x/waifu2x_noise.onnx");
        reg("smol-docling-combined", "com.chua.deeplearning.support.onnx.smoldocling.SmolDoclingCombinedTranslator", Object.class, String.class, Object.class, "vision/enhancement/smoldocling/vision_encoder.onnx");
        reg("smol-docling-decoder", "com.chua.deeplearning.support.onnx.smoldocling.SmolDoclingDecoderTranslator", Object.class, Object.class, Object.class, "vision/enhancement/smoldocling/decoder_model_merged.onnx");
        reg("smol-docling-embed", "com.chua.deeplearning.support.onnx.smoldocling.SmolDoclingEmbedTranslator", long[].class, Object.class, Object.class, "vision/enhancement/smoldocling/embed_tokens.onnx");
        reg("smol-docling-vision", "com.chua.deeplearning.support.onnx.smoldocling.SmolDoclingVisionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/enhancement/smoldocling/vision_encoder.onnx");
        reg("bert-squad", "com.chua.deeplearning.support.onnx.text.BertSquadTranslator", String.class, String.class, Object.class, "nlp/generation/bert-squad/model.onnx");
        reg("gpt2", "com.chua.deeplearning.support.onnx.text.gpt2.Gpt2Translator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/generation/gpt2/decoder_with_past_model.onnx");
        reg("mobile-bert-zero-shot-classification", "com.chua.deeplearning.support.onnx.text.MobileBertZeroShotClassificationTranslator", String.class, String.class, Object.class, "nlp/classification/mobilebert-mnli/onnx/model_quantized.onnx");
        reg("vggt-combined", "com.chua.deeplearning.support.onnx.vggt.VggtCombinedTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/vggt/vggt_combined.onnx");
        reg("vggt-output", "com.chua.deeplearning.support.onnx.vggt.VggtOutputTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/vggt/vggt_output.onnx");
        reg("vggt", "com.chua.deeplearning.support.onnx.vggt.VggtTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/vggt/vggt.onnx");
        reg("yolo-cls", "com.chua.deeplearning.support.onnx.yolo.cls.YoloClsTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/yolov12n-cls.onnx");
        reg("yolo5-plate-detect", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo5PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/license/license-plate-finetune-v1x.onnx", "https://huggingface.co/morsetechlab/yolov11-license-plate-detection/resolve/main/license-plate-finetune-v1x.onnx", false, null);
        reg("yolo7-plate-detect", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo7PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/license/license-plate-finetune-v1x.onnx", "https://huggingface.co/morsetechlab/yolov11-license-plate-detection/resolve/main/license-plate-finetune-v1x.onnx", false, null);
        reg("yolo8-plate-detect", "com.chua.deeplearning.support.onnx.yolo.plate.translator.Yolo8PlateDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/license/license-plate-finetune-v1x.onnx", "https://huggingface.co/morsetechlab/yolov11-license-plate-detection/resolve/main/license-plate-finetune-v1x.onnx", false, null);
        reg("doc-layout-d4la", "com.chua.deeplearning.support.onnx.yolo.v10.translator.DocLayoutD4laTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/v10/doclayout_yolo_d4la_imgsz1600_docsynth_pretrain.onnx");
        reg("doc-layout-yolo", "com.chua.deeplearning.support.onnx.yolo.v10.translator.DocLayoutYoloTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/v10/doclayout_yolo_docstructbench_imgsz1024.onnx");
        reg("yolo11-odd", "com.chua.deeplearning.support.onnx.yolo.v11.translator.Yolo11OddTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/obb/yolo11n-obb.onnx");
        reg("yolov2-coco", "com.chua.deeplearning.support.onnx.yolo.v2.translator.Yolov2CocoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v2/yolov2-coco-9.onnx");
        reg("yolov2-coco-v2", "com.chua.deeplearning.support.onnx.yolo.v2.Yolov2CocoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, null);
        reg("yolo26-obb", "com.chua.deeplearning.support.onnx.yolo.v26.translator.Yolo26ObbTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/v26/yolo26n.onnx");
        reg("yolo8-cdla-layout", "com.chua.deeplearning.support.onnx.yolo.v8.translator.Yolo8CdlaLayoutTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/v8/layout_cdla_yolov8m.onnx");
        reg("yolo8-general-layout", "com.chua.deeplearning.support.onnx.yolo.v8.translator.Yolo8GeneralLayoutTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.layout.LayoutDetector.class, "vision/detection/v8/yolov8n_layout_general6.onnx");
        reg("yolov8s", "com.chua.deeplearning.support.onnx.yolo.v8.translator.YoloV8sTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v8/yolov8s.onnx", "https://huggingface.co/lquint/yolov8s-onnx/resolve/main/onnx/model.onnx", false, "yolov8s.onnx");

        reg("depth-anything", "com.chua.deeplearning.support.onnx.depth.DepthAnythingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/depth/depth-anything-v2/model.onnx", "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx", false, null);
        reg("vit-pose", "com.chua.deeplearning.support.onnx.pose.VitPoseTranslator", ai.djl.modality.cv.Image.class, float[][].class, Object.class, "vision/pose/vitpose-base-simple/model.onnx", "https://huggingface.co/onnx-community/vitpose-base-simple/resolve/main/onnx/model.onnx", false, null);
        reg("swinir", "com.chua.deeplearning.support.onnx.resolution.SwinIrTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/enhancement/swinir/swinir_denoising_color_25.onnx", "https://huggingface.co/Heliosoph/swinir-onnx/resolve/main/swinir_denoising_color_25.onnx", false, null);
        reg("codeformer", "com.chua.deeplearning.support.onnx.face.CodeFormerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "face/restoration/codeformer/codeformer.onnx", "https://huggingface.co/bluefoxcreation/Codeformer-ONNX/resolve/main/codeformer.onnx", false, null);
        reg("sam-encoder", "com.chua.deeplearning.support.onnx.seg.SamImageEncoderTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/seg/sam_vit_h/encoder.onnx", "https://huggingface.co/vietanhdev/segment-anything-onnx-models/resolve/main/sam_vit_h_4b8939.zip", true, "encoder.onnx");
        reg("grounding-dino", "com.chua.deeplearning.support.onnx.dino.GroundingDinoTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/grounding-dino-tiny/model.onnx", "https://huggingface.co/onnx-community/grounding-dino-tiny-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("bart-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.BartSeq2SeqTranslator", String.class, String.class, Object.class, "vision/generation/bart-large-cnn/model.onnx", "https://huggingface.co/Xenova/bart-large-cnn/resolve/main/onnx/model.onnx", false, null);
        reg("t5-seq2seq", "com.chua.deeplearning.support.onnx.seq2seq.T5Seq2SeqTranslator", String.class, String.class, Object.class, "vision/generation/t5-small/encoder_model.onnx", "https://huggingface.co/Xenova/t5-small/resolve/main/onnx/encoder_model.onnx", false, null);
        reg("chinese-t5-base", "com.chua.deeplearning.support.onnx.seq2seq.ChineseT5BaseTranslator", String.class, String.class, Object.class, "nlp/seq2seq/chinese-t5-base/encoder_model.onnx", "https://huggingface.co/hfl/chinese-t5-base/resolve/main/encoder_model.onnx", false, null);
        reg("chinese-bart-base", "com.chua.deeplearning.support.onnx.seq2seq.ChineseBartBaseTranslator", String.class, String.class, Object.class, "nlp/seq2seq/chinese-bart-base-cluecorpussmall/model.onnx", "https://huggingface.co/UER/bart-base-chinese-cluecorpussmall/resolve/main/model.onnx", false, null);
        reg("chinese-bart-large", "com.chua.deeplearning.support.onnx.seq2seq.ChineseBartLargeTranslator", String.class, String.class, Object.class, "nlp/seq2seq/chinese-bart-large/model.onnx", "https://huggingface.co/fnlp/bart-large-chinese/resolve/main/model.onnx", false, null);
        reg("randeng-t5", "com.chua.deeplearning.support.onnx.seq2seq.RandengT5Translator", String.class, String.class, Object.class, "nlp/seq2seq/randeng-t5-77m-chinese/encoder_model.onnx", "https://huggingface.co/IDEA-CCNL/Randeng-T5-77M-Chinese/resolve/main/encoder_model.onnx", false, null);
        reg("randeng-bart", "com.chua.deeplearning.support.onnx.seq2seq.RandengBartTranslator", String.class, String.class, Object.class, "nlp/seq2seq/randeng-bart-139m/model.onnx", "https://huggingface.co/IDEA-CCNL/Randeng-BART-139M/resolve/main/model.onnx", false, null);
        reg("anime-face-detector", "com.chua.deeplearning.support.onnx.anime.detection.AnimeFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/anime-face/best.onnx", "https://huggingface.co/g963302/AnimeFace_YOLOv8n/resolve/main/best.onnx", false, null);
        reg("clipseg-zero-shot", "com.chua.deeplearning.support.onnx.seg.CLIPSegZeroShotSegmentationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/seg/clipseg-rd64-refined/model.onnx", "https://huggingface.co/Xenova/clipseg-rd64-refined/resolve/main/model.onnx", false, null);
        reg("lama-inpainting", "com.chua.deeplearning.support.onnx.inpainting.LamaInpaintingTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/inpainting/lama/model.onnx", "https://huggingface.co/onnx-community/lama-inpainting-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("image-colorize", "com.chua.deeplearning.support.onnx.colorize.ImageColorizeTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.Image.class, Object.class, "vision/colorization/deoldify/model.onnx", "https://huggingface.co/bluefoxcreation/DeOldify-ONNX/resolve/main/DeOldify.onnx", false, null);

        // ==================== 动漫人物检测 ====================
        reg("anime-face-yolov8", "com.chua.deeplearning.support.onnx.anime.detection.AnimeFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/anime-face/yolov8_animeface.onnx", "https://huggingface.co/Fuyucchi/yolov8_animeface/resolve/main/best.onnx", false, null);

        // ==================== 活体检测 ====================
        reg("face-liveness-dinov2", "com.chua.deeplearning.support.onnx.liveness.FaceAntiSpoofTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/liveness/dinov2_liveness/model.onnx", "https://huggingface.co/nguyenkhoa/dinov2_Liveness_detection_v2.2.3/resolve/main/model.onnx", false, null);
        reg("face-liveness-mobilevit", "com.chua.deeplearning.support.onnx.liveness.FaceAntiSpoofTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "face/liveness/mobilevitv2_liveness/model.onnx", "https://huggingface.co/nguyenkhoa/mobilevitv2_Liveness_detection_v1.0/resolve/main/model.onnx", false, null);

        // ==================== 人脸检测 YOLO ====================
        reg("yolo-face-detector", "com.chua.deeplearning.support.onnx.face.UltraFaceTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/yolo11n-face.onnx", "https://huggingface.co/AdamCodd/YOLOv11n-face-detection/resolve/main/model.onnx", false, null);
        reg("yolo-face-person", "com.chua.deeplearning.support.onnx.face.ScrfdFaceDetectorTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "face/detection/yolo-face-person.onnx", "https://huggingface.co/iitolstykh/YOLO-Face-Person-Detector/resolve/main/model.onnx", false, null);

        // ==================== 年龄推算 (ONNX) ====================
        reg("age-gender-onnx", "com.chua.deeplearning.support.onnx.agegender.AgeRaceGenderTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/attribute/age_gender/age-gender-vit.onnx", "https://huggingface.co/onnx-community/age-gender-prediction-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("yolo-face-age", "com.chua.deeplearning.support.onnx.age.GoogleNetAgeRecognitionTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/attribute/age/yolo11n-face-age.onnx", "https://huggingface.co/AdamCodd/yolo11n-face-age/resolve/main/model.onnx", false, null);

        // ==================== 特征值提取 (文本嵌入) ====================
        reg("all-MiniLM-L6-v2-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/all-MiniLM-L6-v2/model.onnx", "https://huggingface.co/onnx-community/all-MiniLM-L6-v2-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("bge-small-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-small-en-v1.5/model.onnx", "https://huggingface.co/onnx-community/bge-small-en-v1.5-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("bge-small-zh-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/bge-small-zh-v1.5/model.onnx", "https://huggingface.co/onnx-community/bge-small-zh-v1.5-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("granite-embedding", "com.chua.deeplearning.support.onnx.clip.ClipTextFeatureTranslator", String.class, float[].class, Object.class, "nlp/embedding/granite-embedding-small/model.onnx", "https://huggingface.co/onnx-community/granite-embedding-small-english-r2-ONNX/resolve/main/onnx/model.onnx", false, null);

        // ==================== 零样本分类 ====================
        reg("clip-vit-zero-shot", "com.chua.deeplearning.support.onnx.classification.SiglipZeroShotClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/zeroshot/clip-vit-base-patch32/model.onnx", "https://huggingface.co/onnx-community/clip-vit-base-patch32-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("mobileclip-zero-shot", "com.chua.deeplearning.support.onnx.classification.SiglipZeroShotClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/zeroshot/mobileclip_s0/model.onnx", "https://huggingface.co/onnx-community/mobileclip_s0-ONNX/resolve/main/onnx/model.onnx", false, null);

        // ==================== 动物分类 ====================
        reg("animals-10-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/animals-10/model.onnx", "https://huggingface.co/onnx-community/10-animals-classification-ONNX/resolve/main/onnx/model.onnx", false, null);

        // ==================== 食物分类 ====================
        reg("food-101-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/food101/vit-base.onnx", "https://huggingface.co/nateraw/vit-base-food101/resolve/main/model.onnx", false, null);

        // ==================== 植物分类 ====================
        reg("plant-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/plant/model.onnx", "https://huggingface.co/onnx-community/house-plant-image-detection-ONNX/resolve/main/onnx/model.onnx", false, null);

        // ==================== MobileNet ====================
        reg("mobilenetv2-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/mobilenetv2/mobilenet_v2_1.0_224.onnx", "https://huggingface.co/onnx-community/mobilenet_v2_1.0_224/resolve/main/onnx/model.onnx", false, null);
        reg("mobilenetv3-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/mobilenetv3/mobilenetv3_small.onnx", "https://huggingface.co/onnx-community/mobilenetv3_small_100.lamb_in1k/resolve/main/onnx/model.onnx", false, null);

        // ==================== CIFAR-10 ====================
        reg("cifar10-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/cifar10/resnet18.onnx", "https://huggingface.co/aijadugar/cifar-10-resnet18/resolve/main/model.onnx", false, null);

        // ==================== 特征提取 (视觉) ====================
        reg("dinov3-feature", "com.chua.deeplearning.support.onnx.dinov2.DinoV2Translator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/dinov3-vits16/model.onnx", "https://huggingface.co/onnx-community/dinov3-vits16-pretrain-lvd1689m-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("resnet50-feature", "com.chua.deeplearning.support.onnx.feature.ClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/resnet50/model.onnx", "https://huggingface.co/Qdrant/resnet50-onnx/resolve/main/model.onnx", false, null);
        reg("wespeaker-feature", "com.chua.deeplearning.support.onnx.feature.ClipImageFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "vision/feature/wespeaker-resnet34/model.onnx", "https://huggingface.co/onnx-community/wespeaker-voxceleb-resnet34-LM/resolve/main/onnx/model.onnx", false, null);

        // ==================== YOLO 通用检测 ====================
        reg("yolo26n", "com.chua.deeplearning.support.onnx.yolo.v26.translator.Yolo26ObbTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "vision/detection/v26/yolo26n.onnx", "https://huggingface.co/onnx-community/yolo26n-ONNX/resolve/main/onnx/model.onnx", false, null);
        reg("yolov10m", "com.chua.deeplearning.support.onnx.yolo.v10.translator.DocLayoutYoloTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v10/yolov10m.onnx", "https://huggingface.co/onnx-community/yolov10m/resolve/main/onnx/model.onnx", false, null);
        reg("yolov10n", "com.chua.deeplearning.support.onnx.yolo.v10.translator.DocLayoutYoloTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "vision/detection/v10/yolov10n.onnx", "https://huggingface.co/onnx-community/yolov10n/resolve/main/onnx/model.onnx", false, null);

        // ==================== 特征提取 (文本) - 标签分类 ====================
        reg("wd-tagger-swinv2", "com.chua.deeplearning.support.onnx.classification.ClTaggerTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/wd-swinv2-tagger-v3/model.onnx", "https://huggingface.co/SmilingWolf/wd-swinv2-tagger-v3/resolve/main/model.onnx", false, null);

        // ==================== 情绪识别 ====================
        reg("yolo-face-emotion", "com.chua.deeplearning.support.onnx.emotion.EmotionFerplusTranslator", ai.djl.modality.cv.Image.class, Object.class, Object.class, "face/expression/yolo11-face-emotion.onnx", "https://huggingface.co/leeyunjai/yolo11-face-emotion-fer2013-cls/resolve/main/model.onnx", false, null);

        // ==================== Moonshine 语音识别 ====================
        reg("moonshine-base", "com.chua.deeplearning.support.onnx.text.BertSquadTranslator", String.class, String.class, Object.class, "nlp/audio/moonshine-base/model.onnx", "https://huggingface.co/onnx-community/moonshine-base-ONNX/resolve/main/onnx/model.onnx", false, null);

        // ==================== 情感分析 ====================
        reg("roberta-go-emotions", "com.chua.deeplearning.support.onnx.classification.DistilBertSentimentTranslator", String.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "nlp/classification/roberta-go-emotions/model.onnx", "https://huggingface.co/SamLowe/roberta-base-go_emotions-onnx/resolve/main/model.onnx", false, null);

        // ==================== MiniMind 因果语言模型（内置 ONNX） ====================
        // 模型资源打包在 utils-support-minimind-onnx JAR 的 models/minimind/ 目录下，
        // ModelRegistry 会自动从 classpath 解析并解压到临时目录。
        reg("minimind", "com.chua.deeplearning.support.onnx.text.minimind.MiniMindTranslator", String.class, String.class, Object.class, "models/minimind/model.onnx");

        // ==================== 图像分类 (EfficientNet / ViT) ====================
        reg("mobilenetv4-classification", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/mobilenetv4/mobilenetv4_conv_small.onnx", "https://huggingface.co/onnx-community/mobilenetv4_conv_small.e2400_r224_in1k/resolve/main/onnx/model.onnx", false, null);
        reg("deepfake-detector", "com.chua.deeplearning.support.onnx.classification.EfficientNetLite0ClassificationTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.Classifications.class, com.chua.deeplearning.support.image.ImageClassifier.class, "vision/classification/deepfake-detector/model.onnx", "https://huggingface.co/onnx-community/Deep-Fake-Detector-v2-Model-ONNX/resolve/main/onnx/model.onnx", false, null);

        // ==================== FacePlugin 人脸检测/关键点/特征 ====================
        // 模型资源随 utils-support-models-faceplugin-{detect,landmark,feature} 三个 jar 分发，
        // 通过 ModelRegistry.resolveModelPath() 在运行时从 classpath 解析并解压到 java.io.tmpdir/chua-dl-models/。
        reg("faceplugin-face-detect-slim", "com.chua.deeplearning.support.onnx.face.FacePluginDetectTranslator", ai.djl.modality.cv.Image.class, ai.djl.modality.cv.output.DetectedObjects.class, com.chua.deeplearning.support.image.ImageDetector.class, "models/onnx/face/detection/faceplugin/face_detect_slim.onnx");
        reg("faceplugin-face-landmark", "com.chua.deeplearning.support.onnx.face.FacePluginLandmarkTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "models/onnx/face/landmark/faceplugin/face_landmark.onnx");
        reg("faceplugin-face-feature", "com.chua.deeplearning.support.onnx.face.FacePluginFeatureTranslator", ai.djl.modality.cv.Image.class, float[].class, com.chua.deeplearning.support.feature.FeatureExtractor.class, "models/onnx/face/feature/faceplugin/face_feature.onnx");
    }

    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, relativePath);
        }
    }

    private static void reg(String modelId, String translatorClassName,
                            Class<?> inputType, Class<?> outputType,
                            Class<?> capability, String relativePath,
                            String downloadUrl, boolean compress, String downloadFileName) {
        if (ModelRegistry.get(modelId) == null) {
            ModelRegistry.register(modelId, translatorClassName, inputType, outputType, capability, relativePath, downloadUrl, compress, downloadFileName);
        }
    }
}
