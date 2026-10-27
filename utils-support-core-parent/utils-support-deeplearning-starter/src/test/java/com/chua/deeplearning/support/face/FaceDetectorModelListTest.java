package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.image.ImageDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人脸检测模型清单的补全规则验收。
 *
 * <p><b>为什么需要它</b>：注册表里「人脸检测」被两个能力位分开标注 —— 只有
 * {@code opencv-face} 声明 {@link FaceDetector}，而真正可用的 SCRFD / RetinaFace /
 * TinaFace / YOLO-Face / FacePlugin 声明的都是 {@link ImageDetector}。
 * 只按单一能力位枚举时，页面与报错提示里只剩一个 {@code opencv-face}，而它的权重
 * 在模型仓里取不到（实测 hf-mirror 上 404），选中即报「模型文件不存在」，
 * 使用者面对的是一份「不可用清单」。</p>
 *
 * <p>这里是补全规则本身的回归：按权重目录识别，而不是按模型名猜 ——
 * 名字会变，权重目录是注册时声明的数据。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@DisplayName("FaceDetector: 人脸检测模型清单补全")
class FaceDetectorModelListTest {

    /**
     * 注册表里真实存在的人脸检测权重路径（取自 OnnxModelRegistrar）。
     */
    private static final List<String> REAL_FACE_WEIGHT_PATHS = List.of(
            "face/detection/scrfd/2.5g_bnkps.onnx",
            "models/onnx/face/detection/scrfd/scrfd.onnx",
            "face/detection/retinaface/retinaface_r34.onnx",
            "face/detection/tinaface/tinaface_r50.onnx",
            "face/detection/yolo11n-face.onnx",
            "models/onnx/face/detection/faceplugin/face_detect_slim.onnx",
            "face/detection/ultraface/ultraface.onnx");

    /**
     * 不是人脸检测器的路径：口罩检测与动漫脸，都只是名字里带 face。
     */
    private static final List<String> NON_FACE_WEIGHT_PATHS = List.of(
            "vision/face-mask-detector/yolov8/model.onnx",
            "vision/detection/anime-face/model.onnx",
            "vision/detection/anime-face/yolov8_animeface.onnx",
            "models/onnx/animegan/AnimeGANv3_Hayao_36.onnx",
            "vision/detection/yolov8s/model.onnx",
            "face/attribute/genderage/genderage.onnx",
            "face/landmark/2d106/2d106det.onnx");

    @Test
    @DisplayName("真实人脸检测权重路径全部识别为人脸")
    void realFaceWeightPathsAreRecognized() {
        for (String path : REAL_FACE_WEIGHT_PATHS) {
            assertTrue(FaceDetector.isFaceWeightPath(path),
                    "应识别为人脸权重: " + path);
        }
    }

    @Test
    @DisplayName("只是名字带 face 的路径不得被误纳")
    void lookalikePathsAreRejected() {
        for (String path : NON_FACE_WEIGHT_PATHS) {
            assertFalse(FaceDetector.isFaceWeightPath(path),
                    "不得误纳为「人脸检测」: " + path
                            + "（face-mask / anime-face 不含 face/detection/ 段；"
                            + "attribute / landmark 是人脸权重但不是检测器）");
        }
    }

    @Test
    @DisplayName("空路径与反斜杠路径")
    void blankAndWindowsPaths() {
        assertFalse(FaceDetector.isFaceWeightPath(null), "null 应为 false");
        assertFalse(FaceDetector.isFaceWeightPath(""), "空串应为 false");
        assertFalse(FaceDetector.isFaceWeightPath("   "), "空白应为 false");
        assertTrue(FaceDetector.isFaceWeightPath("face\\detection\\scrfd\\2.5g_bnkps.onnx"),
                "Windows 反斜杠应等价识别");
        assertTrue(FaceDetector.isFaceWeightPath("FACE/DETECTION/SCRFD/2.5G_BNKPS.ONNX"),
                "大小写不应影响识别");
    }

    @Test
    @DisplayName("声明 ImageDetector 的人脸检测器要出现在清单里，口罩/动漫脸不出现")
    void imageDetectorFaceModelsAreListed() {
        // 用独立 id 注册，避免与真实注册表条目冲突（注册表是静态全局，无卸载入口）
        register("__test-face-scrfd", ImageDetector.class, "face/detection/__test__/scrfd.onnx");
        register("__test-face-mask", ImageDetector.class, "vision/face-mask-detector/__test__/model.onnx");
        register("__test-anime-face", ImageDetector.class, "vision/detection/anime-face/__test__/model.onnx");
        register("__test-opencv-face", FaceDetector.class, "face/detection/__test__/ultraface.onnx");

        List<String> models = FaceDetector.listModels();

        assertTrue(models.contains("__test-face-scrfd"),
                "声明 ImageDetector 但权重落在 face/ 下的检测器必须出现在人脸清单里（否则 UI 只剩 opencv-face）");
        assertTrue(models.contains("__test-opencv-face"),
                "声明 FaceDetector 的模型必须保留（原行为不能被破坏）");
        assertFalse(models.contains("__test-face-mask"),
                "口罩检测不是人脸检测，不应出现");
        assertFalse(models.contains("__test-anime-face"),
                "动漫脸检测不是人脸检测，不应出现");
    }

    /**
     * 按注册表公开入口登记一个模型。
     *
     * @param modelId     模型标识
     * @param capability  能力位
     * @param relativePath 权重相对路径
     */
    private static void register(String modelId, Class<?> capability, String relativePath) {
        if (ModelRegistry.get(modelId) != null) {
            return;
        }
        ModelRegistry.register(modelId,
                "com.chua.deeplearning.support.onnx.face.ScrfdFaceDetectorTranslator",
                Object.class, Object.class, capability, relativePath);
    }
}