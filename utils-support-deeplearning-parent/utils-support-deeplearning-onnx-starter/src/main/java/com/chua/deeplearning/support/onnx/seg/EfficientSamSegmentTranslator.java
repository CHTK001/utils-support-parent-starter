package com.chua.deeplearning.support.onnx.seg;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.chua.common.support.utils.NativeLoader;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
   * efficientsam-Ti 分割 Translator（提示框 → 前景掩码）。
 *
 * <p>EfficientSAM 是 Meta SAM 的高效版（蒸馏自 SAM，推理速度更快）。
   * 模型来源：huggingface 镜像 {@code camenduru/EfficientSAM} 的
 * {@code efficientsam_ti_encoder.onnx}（约 24MB）+ {@code efficientsam_ti_decoder.onnx}（约 16MB），
 * opset 17，FP32。</p>
 *
 * <p>推理流程（与官方 EfficientSAM 一致）：</p>
 * <ol>
 *   <li>图像等比缩放到最长边 1024，居中 letterbox 到 1024×1024（RGB，0-255）</li>
 *   <li>图像编码器：{@code batched_images [1,3,1024,1024]} → {@code image_embeddings [1,256,64,64]}</li>
 *   <li>掩码解码器：bbox 转两个对角点 + 标签 [2,3] → {@code output_masks [1,1,3,H,W]}（已在原图尺寸）
 *       + {@code iou_predictions [1,1,3]}</li>
 *   <li>取 IoU 最高的掩码，阈值 0 得到二值掩码（已是原图尺寸，无需再 resize）</li>
 * </ol>
 *
 * <p>资源位于 jar {@code utils-support-models-onnx-efficientsam} 的
 * {@code vision/seg/efficient-sam/onnx/} 目录，由 {@link NativeLoader} 解压到临时目录后加载。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class EfficientSamSegmentTranslator {

    /**
     * 图像编码器输入边长（1024）
     */
    private static final int INPUT_SIZE = 1024;

    /**
     * 图像编码器输出的特征图边长（64）
     */
    private static final int EMBED_SIZE = 64;

    /**
      * 掩码候选数量（efficientsam 输出 3 个候选）
     */
    private static final int NUM_MASKS = 3;

    /**
     * 资源目录前缀（jar 内）
     */
    private static final String RESOURCE_BASE = "vision/seg/efficient-sam/onnx/";

    /**
     * 图像编码器模型文件名
     */
    private static final String ENCODER_FILE = "efficientsam_ti_encoder.onnx";

    /**
     * 掩码解码器模型文件名
     */
    private static final String DECODER_FILE = "efficientsam_ti_decoder.onnx";

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 编码器会话 */
    private OrtSession encoderSession;
    /** 解码器会话 */
    private OrtSession decoderSession;

    /**
     * 当前依赖上下文（原图尺寸），由 segment 串行使用
     */
    private int srcWidth;
    /** 源图像高度 */
    /** SRC高度 */
    private int srcHeight;

    /** Prepare */
    private synchronized void prepare() throws Exception {
        if (encoderSession != null && decoderSession != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("efficientsam-onnx-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("efficient-sam");
        Files.createDirectories(modelDir);

        NativeLoader.of("efficient-sam")
                .from(EfficientSamSegmentTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();

        Path encoderPath = modelDir.resolve(ENCODER_FILE);
        Path decoderPath = modelDir.resolve(DECODER_FILE);
        if (!Files.isRegularFile(encoderPath) || !Files.isRegularFile(decoderPath)) {
            throw new IOException("EfficientSAM 资源缺失: encoder=" + encoderPath + " decoder=" + decoderPath);
        }

        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.encoderSession = ortEnv.createSession(encoderPath.toString(), opts);
            this.decoderSession = ortEnv.createSession(decoderPath.toString(), opts);
            log.info("[EfficientSAM] ONNX loaded: encoder={} decoder={}",
                    encoderPath.getFileName(), decoderPath.getFileName());
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session for EfficientSAM: " + e.getMessage(), e);
        }
    }

    /**
     * 用提示框对图像做目标分割，返回与原图同尺寸的灰度掩码图。
     *
     * @param input 输入图像
     * @param box   提示框 [x1, y1, x2, y2]（原图像素坐标）
     * @return 灰度掩码图（0=背景，255=前景）
     */
    public Image segment(Image input, float[] box) throws Exception {
        if (box == null || box.length != 4) {
            throw new IllegalArgumentException("box 必须为 [x1, y1, x2, y2]");
        }
        extendScale(input);
        prepare();

        float[][] imageInput = preprocess(input);
        float[][][][] embeddings = encode(imageInput);
        float[][] coords = toPointCoords(box);
        float[][] labels = {new float[]{2.0f, 3.0f}};
        long[] origSize = {srcHeight, srcWidth};
        float[] iou = new float[NUM_MASKS];
        float[][][][][] masks = decode(embeddings, coords, labels, origSize, iou);

        int best = argmax(iou);
        BufferedImage mask = maskToImage(masks[0][0][best]);
        return ImageFactory.getInstance().fromImage(mask);
    }

    /**
     * extendscale
     *
     * @param input 输入
     */
    private void extendScale(Image input) {
        srcWidth = input.getWidth();
        srcHeight = input.getHeight();
    }

    /**
     * 图像预处理：直接拉伸到 1024×1024（非 letterbox），RGB /255 → [1,3,1024,1024]。
      * efficientsam 编码器接收 [0,1] 归一化 RGB。
     * @param input 输入
     * @return preprocess的结果
     */
    private float[][] preprocess(Image input) {
        BufferedImage src = toBufferedImage(input);

        BufferedImage canvas = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_LINEAR);

        float[][] out = new float[3][INPUT_SIZE * INPUT_SIZE];
        int h = INPUT_SIZE;
        int w = INPUT_SIZE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = canvas.getRGB(x, y);
                out[0][y * w + x] = ((rgb >>> 16) & 0xFF) / 255.0f;
                out[1][y * w + x] = ((rgb >>> 8) & 0xFF) / 255.0f;
                out[2][y * w + x] = (rgb & 0xFF) / 255.0f;
            }
        }
        return out;
    }

    /**
     * 编码
     *
     * @param normalized normalized
     * @return encode的结果
     */
    private float[][][][] encode(float[][] normalized) {
        long[] shape = new long[]{1, 3, INPUT_SIZE, INPUT_SIZE};
        try (OnnxTensor imageTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flatten(normalized)), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("batched_images", imageTensor);
            try (OrtSession.Result result = encoderSession.run(inputs)) {
                return (float[][][][]) result.get(0).getValue();
            }
        } catch (Exception e) {
            throw new RuntimeException("[EfficientSAM] encoder failed: " + e.getMessage(), e);
        }
    }

    /**
     * 解码
     * @param embeddings 嵌入
     * @param coords coords
     * @param labels 标签
     * @param origSize orig大小
     * @param iouOut iou出
     */
    private float[][][][][] decode(float[][][][] embeddings, float[][] coords,
                                   float[][] labels, long[] origSize, float[] iouOut) {
        long[] embShape = new long[]{1, 256, EMBED_SIZE, EMBED_SIZE};
        long[] coordShape = new long[]{1, 1, 2, 2};
        long[] labelShape = new long[]{1, 1, 2};
        long[] origShape = new long[]{2};
        try (OnnxTensor embTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flatten4(embeddings)), embShape);
             OnnxTensor coordTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flatten(coords)), coordShape);
             OnnxTensor labelTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flatten(labels)), labelShape);
             OnnxTensor origTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(origSize), origShape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("image_embeddings", embTensor);
            inputs.put("batched_point_coords", coordTensor);
            inputs.put("batched_point_labels", labelTensor);
            inputs.put("orig_im_size", origTensor);
            try (OrtSession.Result result = decoderSession.run(inputs)) {
                float[][][][][] masks = (float[][][][][]) result.get(0).getValue();
                float[][][] iou = (float[][][]) result.get(1).getValue();
                for (int i = 0; i < Math.min(iou[0][0].length, iouOut.length); i++) {
                    iouOut[i] = iou[0][0][i];
                }
                return masks;
            }
        } catch (Exception e) {
            throw new RuntimeException("[EfficientSAM] decoder failed: " + e.getMessage(), e);
        }
    }

    /**
     * 将原图坐标 bbox 转换为解码器需要的坐标点 [top-left, bottom-right]。
      * efficientsam 解码器期望原图像素坐标（不缩放）。
     * @param box box
     * @return 转为pointcoords的结果
     */
    private float[][] toPointCoords(float[] box) {
        float x1 = Math.min(box[0], box[2]);
        float y1 = Math.min(box[1], box[3]);
        float x2 = Math.max(box[0], box[2]);
        float y2 = Math.max(box[1], box[3]);
        return new float[][]{
                {x1, y1},
                {x2, y2}
        };
    }

    /**
     * Argmax
     *
     * @param scores scores
     * @return argmax的结果
     */
    private int argmax(float[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * 掩码 [H,W]（logits，已在原图尺寸）→ sigmoid 阈值 0.5 → 灰度图 [0/255]。
     * @param mask mask
     * @return mask转为镜像的结果
     */
    private BufferedImage maskToImage(float[][] mask) {
        int w = mask[0].length;
        int h = mask.length;
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = result.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sig = 1.0f / (1.0f + (float) Math.exp(-mask[y][x]));
                raster.setSample(x, y, 0, sig > 0.5f ? 255 : 0);
            }
        }
        return result;
    }

    /**
     * 转为缓冲镜像
     *
     * @param input 输入
     * @return 转为缓冲镜像的结果
     */
    private BufferedImage toBufferedImage(Image input) {
        if (input == null) {
            throw new IllegalArgumentException("EfficientSAM 输入图像为空");
        }
        Object wrapped = input.getWrappedImage();
        if (wrapped instanceof BufferedImage buffered) {
            return buffered;
        }
        Object converted = ImageFactory.getInstance().fromImage(input).getWrappedImage();
        if (converted instanceof BufferedImage buffered) {
            return buffered;
        }
        throw new IllegalStateException("EfficientSAM 无法将输入转换为 BufferedImage");
    }

    /**
     * 扁平化
     *
     * @param arr arr
     * @return flatten的结果
     */
    private static float[] flatten(float[][] arr) {
        int n = 0;
        for (float[] row : arr) {
            n += row.length;
        }
        float[] out = new float[n];
        int idx = 0;
        for (float[] row : arr) {
            for (float v : row) {
                out[idx++] = v;
            }
        }
        return out;
    }

    /**
     * 扁平化
     *
     * @param arr arr
     * @return flatten4的结果
     */
    private static float[] flatten4(float[][][][] arr) {
        int n = 0;
        for (float[][][] a : arr) {
            for (float[][] b : a) {
                for (float[] c : b) {
                    n += c.length;
                }
            }
        }
        float[] out = new float[n];
        int idx = 0;
        for (float[][][] a : arr) {
            for (float[][] b : a) {
                for (float[] c : b) {
                    for (float v : c) {
                        out[idx++] = v;
                    }
                }
            }
        }
        return out;
    }

    /**
      * 关闭底层 ONNX 会话。
     */
    public synchronized void close() {
        try {
            if (encoderSession != null) {
                encoderSession.close();
            }
        } catch (Exception ignore) {
        }
        try {
            if (decoderSession != null) {
                decoderSession.close();
            }
        } catch (Exception ignore) {
        }
        encoderSession = null;
        decoderSession = null;
        ortEnv = null;
    }
}