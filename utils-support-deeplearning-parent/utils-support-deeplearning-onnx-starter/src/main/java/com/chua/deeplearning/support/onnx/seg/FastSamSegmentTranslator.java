package com.chua.deeplearning.support.onnx.seg;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * FastSAM-s 分割 Translator（YOLOv8-seg 架构，单模型自动分割）。
 *
 * <p>FastSAM 是 YOLOv8 与 SAM 的融合，单模型自动检测并分割任意物体。
 * 输入 {@code [1,3,1024,1024]}，输出检测框 + 原型掩码。
 * 本 Translator 自动检测所有物体并合并为前景掩码图。</p>
 *
 * <p>模型来源：HuggingFace 镜像 {@code anakhiu/fastsam-onnx} 的
 * {@code fastsam_s.onnx}（约 45MB，opset 17，FP32）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class FastSamSegmentTranslator {

    /** 输入尺寸 */
    /** Input_size */
    private static final int INPUT_SIZE = 1024;
    /** 类别数量 */
    /** Num_classes */
    private static final int NUM_CLASSES = 1;
    /** 原型掩码数量 */
    /** Num_protos */
    private static final int NUM_PROTOS = 32;
    /** 步长 */
    /** Stride */
    private static final int STRIDE = 4;
    /** 置信度阈值 */
    /** Conf_threshold */
    private static final float CONF_THRESHOLD = 0.3f;
    /** NMS 阈值 */
    /** Nms_threshold */
    private static final float NMS_THRESHOLD = 0.5f;
    /** 掩码尺寸 */
    /** Mask_size */
    private static final int MASK_SIZE = 256;

    /** 资源基础路径 */
    /** Resource_base */
    private static final String RESOURCE_BASE = "vision/seg/fastsam/onnx/";
    /** 模型文件路径 */
    /** Model_file */
    private static final String MODEL_FILE = "fastsam_s.onnx";

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 会话 */
    private OrtSession session;

    /** 源图像宽度 */
    /** SRC宽度 */
    private int srcWidth;
    /** 源图像高度 */
    /** SRC高度 */
    private int srcHeight;

    private synchronized void prepare() throws Exception {
        if (session != null) return;
        Path tmpDir = Files.createTempDirectory("fastsam-onnx-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("fastsam");
        Files.createDirectories(modelDir);

        NativeLoader.of("fastsam")
                .from(FastSamSegmentTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();

        Path modelPath = modelDir.resolve(MODEL_FILE);
        if (!Files.isRegularFile(modelPath)) {
            throw new IOException("FastSAM 模型缺失: " + modelPath);
        }
        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.session = ortEnv.createSession(modelPath.toString(), opts);
            log.info("[FastSAM] ONNX loaded: {}", modelPath.getFileName());
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session for FastSAM: " + e.getMessage(), e);
        }
    }

    public Image segment(Image input) throws Exception {
        prepare();
        srcWidth = input.getWidth();
        srcHeight = input.getHeight();

        // Preprocess: resize to 1024x1024 stretch, /255
        BufferedImage src = toBufferedImage(input);
        BufferedImage canvas = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_LINEAR);

        float[] pixels = new float[3 * INPUT_SIZE * INPUT_SIZE];
        int idx = 0;
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int rgb = canvas.getRGB(x, y);
                pixels[idx] = ((rgb >>> 16) & 0xFF) / 255.0f;
                pixels[idx + INPUT_SIZE * INPUT_SIZE] = ((rgb >>> 8) & 0xFF) / 255.0f;
                pixels[idx + 2 * INPUT_SIZE * INPUT_SIZE] = (rgb & 0xFF) / 255.0f;
                idx++;
            }
        }

        long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
        try (OnnxTensor tensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pixels), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("images", tensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][][] detections = (float[][][]) result.get(0).getValue();
                float[][][][] protos = (float[][][][]) result.get(1).getValue();
                float[] flat = detections[0][0];
                int rows = detections[0].length;
                int cols = detections[0][0].length;
                float[] flatDetections = new float[rows * cols];
                for (int i = 0; i < rows; i++) {
                    System.arraycopy(detections[0][i], 0, flatDetections, i * cols, cols);
                }
                return postprocess(flatDetections, protos[0]);
            }
        }
    }

    private Image postprocess(float[] detections, float[][][] protos) throws Exception {
        int numPreds = detections.length / 37;
        int numDetections = 0;
        for (int i = 0; i < numPreds; i++) {
            float conf = detections[i * 37 + 4];
            if (conf > CONF_THRESHOLD) numDetections++;
        }

        float[][] boxes = new float[numDetections][];
        float[] scores = new float[numDetections];
        float[][] maskCoeffs = new float[numDetections][];
        int detIdx = 0;
        for (int i = 0; i < numPreds; i++) {
            float conf = detections[i * 37 + 4];
            if (conf <= CONF_THRESHOLD) continue;
            float cx = detections[i * 37] / INPUT_SIZE * srcWidth;
            float cy = detections[i * 37 + 1] / INPUT_SIZE * srcHeight;
            float w = detections[i * 37 + 2] / INPUT_SIZE * srcWidth;
            float h = detections[i * 37 + 3] / INPUT_SIZE * srcHeight;
            boxes[detIdx] = new float[]{cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2};
            scores[detIdx] = conf;
            float[] coeffs = new float[NUM_PROTOS];
            for (int j = 0; j < NUM_PROTOS; j++) {
                coeffs[j] = detections[i * 37 + 5 + j];
            }
            maskCoeffs[detIdx] = coeffs;
            detIdx++;
        }

        // NMS
        int[] keep = nms(boxes, scores, NMS_THRESHOLD);

        // Merge masks
        BufferedImage result = new BufferedImage(srcWidth, srcHeight, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = result.getRaster();

        for (int k = 0; k < keep.length; k++) {
            int ki = keep[k];
            float[] coeffs = maskCoeffs[ki];

            // Compute mask from prototypes
            float[][] mask = new float[MASK_SIZE][MASK_SIZE];
            for (int y = 0; y < MASK_SIZE; y++) {
                for (int x = 0; x < MASK_SIZE; x++) {
                    float sum = 0;
                    for (int p = 0; p < NUM_PROTOS; p++) {
                        sum += coeffs[p] * protos[p][y][x];
                    }
                    float sig = 1.0f / (1.0f + (float) Math.exp(-sum));
                    if (sig > 0.5f) {
                        mask[y][x] = 1.0f;
                    }
                }
            }

            // Resize mask to image size and blend
            BufferedImage maskImg = new BufferedImage(MASK_SIZE, MASK_SIZE, BufferedImage.TYPE_BYTE_GRAY);
            WritableRaster maskRaster = maskImg.getRaster();
            for (int y = 0; y < MASK_SIZE; y++) {
                for (int x = 0; x < MASK_SIZE; x++) {
                    maskRaster.setSample(x, y, 0, mask[y][x] > 0.5f ? 255 : 0);
                }
            }

            BufferedImage scaled = new BufferedImage(srcWidth, srcHeight, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(maskImg, 0, 0, srcWidth, srcHeight, null);
            g2.dispose();

            for (int y = 0; y < srcHeight; y++) {
                for (int x = 0; x < srcWidth; x++) {
                    if (scaled.getRaster().getSample(x, y, 0) > 127) {
                        raster.setSample(x, y, 0, 255);
                    }
                }
            }
        }

        return ImageFactory.getInstance().fromImage(result);
    }

    private int[] nms(float[][] boxes, float[] scores, float threshold) {
        int n = boxes.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Float.compare(scores[b], scores[a]));

        boolean[] suppressed = new boolean[n];
        List<Integer> kept = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int ii = idx[i];
            if (suppressed[ii]) continue;
            kept.add(ii);
            for (int j = i + 1; j < n; j++) {
                int jj = idx[j];
                if (suppressed[jj]) continue;
                if (iou(boxes[ii], boxes[jj]) > threshold) suppressed[jj] = true;
            }
        }
        int[] result = new int[kept.size()];
        for (int i = 0; i < kept.size(); i++) result[i] = kept.get(i);
        return result;
    }

    private float iou(float[] a, float[] b) {
        float x1 = Math.max(a[0], b[0]);
        float y1 = Math.max(a[1], b[1]);
        float x2 = Math.min(a[2], b[2]);
        float y2 = Math.min(a[3], b[3]);
        float inter = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        float areaA = (a[2] - a[0]) * (a[3] - a[1]);
        float areaB = (b[2] - b[0]) * (b[3] - b[1]);
        return inter / (areaA + areaB - inter + 1e-9f);
    }

    private BufferedImage toBufferedImage(Image input) {
        Object wrapped = input.getWrappedImage();
        if (wrapped instanceof BufferedImage b) return b;
        return (BufferedImage) ImageFactory.getInstance().fromImage(input).getWrappedImage();
    }

    public synchronized void close() {
        try { if (session != null) session.close(); } catch (Exception ignore) {}
        session = null;
        ortEnv = null;
    }
}