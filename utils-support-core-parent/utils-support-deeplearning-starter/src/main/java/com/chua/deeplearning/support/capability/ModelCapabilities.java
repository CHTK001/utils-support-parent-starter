package com.chua.deeplearning.support.capability;

import com.chua.deeplearning.support.embedding.EmbeddingService;
import com.chua.deeplearning.support.face.EyeDetector;
import com.chua.deeplearning.support.face.FaceDetector;
import com.chua.deeplearning.support.face.FaceQualityAssessor;
import com.chua.deeplearning.support.face.FaceRecognizer;
import com.chua.deeplearning.support.face.SmileDetector;
import com.chua.deeplearning.support.feature.FeatureExtractor;
import com.chua.deeplearning.support.image.DepthEstimator;
import com.chua.deeplearning.support.image.ImageCaptioning;
import com.chua.deeplearning.support.image.ImageClassifier;
import com.chua.deeplearning.support.image.ImageDetector;
import com.chua.deeplearning.support.image.ImageEnhancer;
import com.chua.deeplearning.support.image.ImageGenerator;
import com.chua.deeplearning.support.image.ImageQualityAssessor;
import com.chua.deeplearning.support.image.ImageSegmenter;
import com.chua.deeplearning.support.image.PedestrianDetector;
import com.chua.deeplearning.support.layout.LayoutDetector;
import com.chua.deeplearning.support.liveness.LivenessDetector;
import com.chua.deeplearning.support.nlp.TextTranslator;
import com.chua.deeplearning.support.ocr.OcrRecognizer;
import com.chua.deeplearning.support.plate.LicensePlateRecognizer;
import com.chua.deeplearning.support.plate.PlateDetector;
import com.chua.deeplearning.support.pose.PoseEstimator;
import com.chua.deeplearning.support.audio.AudioFingerprinter;
import com.chua.deeplearning.support.audio.SpeakerDiarizer;
import com.chua.deeplearning.support.speech.SpeechRecognizer;
import com.chua.deeplearning.support.speech.SpeechSynthesizer;
import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.ai.image.ImageClient;
import com.chua.common.support.ai.video.VideoClient;
import com.chua.common.support.ai.embedding.EmbeddingClient;
import com.chua.common.support.ai.audio.VirtualClient;
import com.chua.common.support.ai.audio.TextToAudioClient;
import com.chua.common.support.ai.feature.FeatureClient;

import java.util.List;
import java.util.Map;

/**
 * 模型能力标签常量与能力接口映射工具。
 *
 * <p>统一模型注册时的能力声明：将能力接口（capabilityInterface）映射为
 * 标准能力标签字符串（如 {@code detect} / {@code feature} / {@code classify} / {@code enhance} 等），
 * 写入 {@link com.chua.common.support.ai.chat.ModelDefinition#getCapabilities()}，
 * 供统一能力清单、前端按能力筛选与分组使用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ModelCapabilities {

    // ==================== 本地深度学习能力标签 ====================

    /**
     * 人脸检测
    */
    public static final String DETECT = "detect";
    /**
     * 眼睛检测
    */
    public static final String EYE_DETECT = "eye-detect";
    /**
     * 微笑检测
    */
    public static final String SMILE_DETECT = "smile-detect";
    /**
     * 人脸质量评估
    */
    public static final String FACE_QUALITY = "face-quality";
    /**
     * 人脸识别（1:1/1:N）
    */
    public static final String FACE_RECOGNIZE = "face-recognize";
    /**
     * 特征提取（图像/文本）
    */
    public static final String FEATURE = "feature";
    /**
     * 图像分类
    */
    public static final String CLASSIFY = "classify";
    /**
     * 图像检测（目标/物体）
    */
    public static final String IMAGE_DETECT = "image-detect";
    /**
     * 图像增强（超分/上色/风格等 镜像→镜像）
    */
    public static final String ENHANCE = "enhance";
    /**
     * 图像修复（inpainting，去水印/去杂物/划痕，带掩码 镜像→镜像）
    */
    public static final String INPAINTING = "inpainting";
    /**
     * 图像分割
    */
    public static final String SEGMENT = "segment";
    /**
     * 图像质量评估
    */
    public static final String IMAGE_QUALITY = "image-quality";
    /**
     * 图像描述（镜像 Captioning）
    */
    public static final String CAPTIONING = "captioning";
    /**
     * 图像生成
    */
    public static final String IMAGE_GENERATE = "image-generate";
    /**
     * 深度估计
    */
    public static final String DEPTH = "depth";
    /**
     * 行人检测
    */
    public static final String PEDESTRIAN = "pedestrian";
    /**
     * OCR 文字识别
    */
    public static final String OCR = "ocr";
    /**
     * 活体检测
    */
    public static final String LIVENESS = "liveness";
    /**
     * 版面分析
    */
    public static final String LAYOUT = "layout";
    /**
     * 姿态估计
    */
    public static final String POSE = "pose";
    /**
     * 嵌入（文本/图像向量化）
    */
    public static final String EMBEDDING = "embedding";
    /**
     * 文本翻译
    */
    public static final String TRANSLATE = "translate";
    /**
     * 车牌检测
    */
    public static final String PLATE_DETECT = "plate-detect";
    /**
     * 车牌识别
    */
    public static final String PLATE_RECOGNIZE = "plate-recognize";
    /**
     * 语音识别 ASR
    */
    public static final String ASR = "asr";
    /**
     * 语音合成 TTS
    */
    public static final String TTS = "tts";
    /**
     * 音频指纹提取（语音特征向量）
    */
    public static final String AUDIO_FINGERPRINT = "audio-fingerprint";
    /**
     * 说话人分离（时间切分）
    */
    public static final String SPEAKER_DIARIZATION = "speaker-diarization";

    // ==================== 云端 AI 客户端能力标签 ====================

    /**
     * 对话/大模型
    */
    public static final String CHAT = "chat";
    /**
     * 视觉理解（多模态图像理解）
    */
    public static final String VISION = "vision";
    /**
     * 文生图
    */
    public static final String TEXT_TO_IMAGE = "text-to-image";
    /**
     * 文生视频
    */
    public static final String TEXT_TO_VIDEO = "text-to-video";
    /**
     * 文本嵌入
    */
    public static final String TEXT_EMBEDDING = "text-embedding";
    /**
     * RAG 检索增强
    */
    public static final String RAG = "rag";
    /**
     * Agent Agent
    */
    public static final String AGENT = "agent";

    /**
     * 能力接口 → 能力标签 映射。
     */
    private static final Map<Class<?>, String> CAPABILITY_LABELS = Map.ofEntries(
            Map.entry(FaceDetector.class, DETECT),
            Map.entry(EyeDetector.class, EYE_DETECT),
            Map.entry(SmileDetector.class, SMILE_DETECT),
            Map.entry(FaceQualityAssessor.class, FACE_QUALITY),
            Map.entry(FaceRecognizer.class, FACE_RECOGNIZE),
            Map.entry(FeatureExtractor.class, FEATURE),
            Map.entry(ImageClassifier.class, CLASSIFY),
            Map.entry(ImageDetector.class, IMAGE_DETECT),
            Map.entry(ImageEnhancer.class, ENHANCE),
            Map.entry(ImageSegmenter.class, SEGMENT),
            Map.entry(ImageQualityAssessor.class, IMAGE_QUALITY),
            Map.entry(ImageCaptioning.class, CAPTIONING),
            Map.entry(ImageGenerator.class, IMAGE_GENERATE),
            Map.entry(DepthEstimator.class, DEPTH),
            Map.entry(PedestrianDetector.class, PEDESTRIAN),
            Map.entry(OcrRecognizer.class, OCR),
            Map.entry(LivenessDetector.class, LIVENESS),
            Map.entry(LayoutDetector.class, LAYOUT),
            Map.entry(PoseEstimator.class, POSE),
            Map.entry(EmbeddingService.class, EMBEDDING),
            Map.entry(TextTranslator.class, TRANSLATE),
            Map.entry(PlateDetector.class, PLATE_DETECT),
            Map.entry(LicensePlateRecognizer.class, PLATE_RECOGNIZE),
            Map.entry(SpeechRecognizer.class, ASR),
            Map.entry(SpeechSynthesizer.class, TTS),
            Map.entry(ChatClient.class, CHAT),
            Map.entry(ImageClient.class, TEXT_TO_IMAGE),
            Map.entry(VideoClient.class, TEXT_TO_VIDEO),
            Map.entry(EmbeddingClient.class, TEXT_EMBEDDING),
            Map.entry(VirtualClient.class, ASR),
            Map.entry(TextToAudioClient.class, TTS),
            Map.entry(FeatureClient.class, FEATURE),
            Map.entry(AudioFingerprinter.class, AUDIO_FINGERPRINT),
            Map.entry(SpeakerDiarizer.class, SPEAKER_DIARIZATION),
            Map.entry(com.chua.deeplearning.support.image.ImageInpainter.class, INPAINTING)
    );

    /**
     * 创建 模型capabilities 实例
    */
    private ModelCapabilities() {
    }

    /**
     * 将能力接口映射为能力标签。
     *
     * @param capabilityInterface 能力接口，可能为 空
     * @return 能力标签；无法识别返回 空
     */
    public static String labelOf(Class<?> capabilityInterface) {
        if (capabilityInterface == null) {
            return null;
        }
        String label = CAPABILITY_LABELS.get(capabilityInterface);
        if (label != null) {
            return label;
        }
 // 支持子类型：遍历映射找 是否assignable从
        for (Map.Entry<Class<?>, String> entry : CAPABILITY_LABELS.entrySet()) {
            if (entry.getKey().isAssignableFrom(capabilityInterface)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 获取全部能力标签。
     *
     * @return 能力标签列表
     */
    public static List<String> allLabels() {
        return List.copyOf(new java.util.LinkedHashSet<>(CAPABILITY_LABELS.values()));
    }
}

