package com.chua.deeplearning.support.onnx.seg;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import com.chua.common.support.utils.NativeLoader;
import lombok.extern.slf4j.Slf4j;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
* edgesam 分割 Translator（提示框 → 前景掩码）。
*
* <p>EdgeSAM 是 Meta SAM 的边缘设备剪枝版（约 9M 参数），
* 模型来源：huggingface 镜像 {@code chongzhou/EdgeSAM} 的
* {@code edge_sam_encoder.onnx}（约 21MB）+ {@code edge_sam_decoder.onnx}（约 15MB）。</p>
*
* <p>推理流程（与官方 SAM 一致）：</p>
* <ol>
*   <li>图像按最长边 1024 等比缩放并居中 letterbox 到 1024×1024（RGB，0-255）</li>
*   <li>ImageNet 归一化：{@code (x - mean) / std}，mean=[123.675,116.28,103.53]，std=[58.395,57.12,57.375]</li>
*   <li>图像编码器：{@code [1,3,1024,1024]} → {@code image_embeddings [1,256,64,64]}</li>
*   <li>掩码解码器：bbox 转两个对角点 + 标签 [2,3] → {@code scores [1,4] + masks [1,4,256,256]}</li>
*   <li>取最高分掩码，sigmoid 阈值 0.5 → 二值掩码 → 等比缩放回原图</li>
* </ol>
*
* <p>资源位于 jar {@code utils-support-models-onnx-edgesam} 的
* {@code vision/seg/edge-sam/onnx/} 目录，由 {@link NativeLoader} 解压到临时目录后加载。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class EdgeSamSegmentTranslator {

    /**
    * edgesam 输入边长（1024）
    */
    private static final int INPUT_SIZE = 1024;

    /**
    * 图像编码器输出的特征图边长（64）
    */
    private static final int EMBED_SIZE = 64;

    /**
    * 掩码解码器输出的低分辨率掩码边长（256）
    */
    private static final int MASK_SIZE = 256;

    /**
    * 掩码候选数量（SAM 输出 4 个候选）
    */
    private static final int NUM_MASKS = 4;

    /**
    * 镜像net 均值（RGB，0-255 范围）
    */
    private static final float[] PIXEL_MEAN = {123.675f, 116.28f, 103.53f};

    /**
    * 镜像net 标准差（RGB，0-255 范围）
    */
    private static final float[] PIXEL_STD = {58.395f, 57.12f, 57.375f};

    /**
    * 资源目录前缀（jar 内）
    */
    private static final String RESOURCE_BASE = "vision/seg/edge-sam/onnx/";

    /**
    * 图像编码器模型文件名
    */
    private static final String ENCODER_FILE = "edge_sam_encoder.onnx";

    /**
    * 掩码解码器模型文件名
    */
    private static final String DECODER_FILE = "edge_sam_decoder.onnx";

    /** ONNX 运行时环境 */
    /** ORTENV */
    private OrtEnvironment ortEnv;
    /** 编码器会话 */
    private OrtSession encoderSession;
    /** 解码器会话 */
    private OrtSession decoderSession;

    /**
    * 当前依赖上下文（等比缩放参数），由 segment 串行使用
    */
    private int srcWidth;
    /** 源图像高度 */
    /** SRC高度 */
    private int srcHeight;
    /** 缩放系数 */
    /** 比例尺 */
    private float scale;
    /** X 轴填充值 */
    /** PADX坐标 */
    private int padX;
    /** Y 轴填充值 */
    /** PADY坐标 */
    private int padY;

    /** Prepare */
    private synchronized void prepare() throws Exception {
        if (encoderSession != null && decoderSession != null) {
            return;
        }
        Path tmpDir = Files.createTempDirectory("edgesam-onnx-");
        tmpDir.toFile().deleteOnExit();
        Path modelDir = tmpDir.resolve("edge-sam");
        Files.createDirectories(modelDir);

        NativeLoader.of("edge-sam")
                .from(EdgeSamSegmentTranslator.class.getClassLoader())
                .basePath(RESOURCE_BASE)
                .toTarget(modelDir)
                .glob("*.onnx")
                .withMd5(true)
                .extractOnly(true)
                .load();

        Path encoderPath = modelDir.resolve(ENCODER_FILE);
        Path decoderPath = modelDir.resolve(DECODER_FILE);
        if (!Files.isRegularFile(encoderPath) || !Files.isRegularFile(decoderPath)) {
            throw new IOException("EdgeSAM 资源缺失: encoder=" + encoderPath + " decoder=" + decoderPath);
        }

        try {
            this.ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.min(8, Runtime.getRuntime().availableProcessors()));
            this.encoderSession = ortEnv.createSession(encoderPath.toString(), opts);
            this.decoderSession = ortEnv.createSession(decoderPath.toString(), opts);
            log.info("[EdgeSAM] ONNX loaded: encoder={} decoder={}",
                    encoderPath.getFileName(), decoderPath.getFileName());
        } catch (Exception e) {
            throw new IOException("Failed to create ORT session for EdgeSAM: " + e.getMessage(), e);
        }
    }

    /**
    * 用提示框对图像做目标分割，返回与原图同尺寸的灰度掩码图。
    *
    * @param input      输入图像
    * @param box        提示框 [x1, y1, x2, y2]（原图像素坐标）
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
        float[] scores = new float[NUM_MASKS];
        float[][][][] masks = decode(embeddings, coords, labels, scores);

        int best = argmax(scores);
        BufferedImage mask = maskToImage(masks[0][best]);
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
        float maxSide = Math.max(srcWidth, srcHeight);
        scale = INPUT_SIZE / maxSide;
        int newWidth = Math.max(1, Math.round(srcWidth * scale));
        int newHeight = Math.max(1, Math.round(srcHeight * scale));
        padX = (INPUT_SIZE - newWidth) / 2;
        padY = (INPUT_SIZE - newHeight) / 2;
    }

    /**
    * 图像预处理：等比缩放 + 居中填充 + SAM 归一化 → [1,3,1024,1024]。
    * @param input 输入
    * @return preprocess的结果
    */
    private float[][] preprocess(Image input) {
        BufferedImage src = toBufferedImage(input);
        int newWidth = Math.max(1, Math.round(srcWidth * scale));
        int newHeight = Math.max(1, Math.round(srcHeight * scale));

        BufferedImage canvas = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(new java.awt.Color(0, 0, 0));
            g.fillRect(0, 0, INPUT_SIZE, INPUT_SIZE);
            g.drawImage(src, padX, padY, newWidth, newHeight, null);
        } finally {
            g.dispose();
        }

        float[][] out = new float[3][INPUT_SIZE * INPUT_SIZE];
        int h = INPUT_SIZE;
        int w = INPUT_SIZE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = canvas.getRGB(x, y);
                out[0][y * w + x] = (((rgb >>> 16) & 0xFF) - PIXEL_MEAN[0]) / PIXEL_STD[0];
                out[1][y * w + x] = (((rgb >>> 8) & 0xFF) - PIXEL_MEAN[1]) / PIXEL_STD[1];
                out[2][y * w + x] = ((rgb & 0xFF) - PIXEL_MEAN[2]) / PIXEL_STD[2];
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
            inputs.put("image", imageTensor);
            try (OrtSession.Result result = encoderSession.run(inputs)) {
                return (float[][][][]) result.get(0).getValue(); // [P3C 四十一 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
            }
        } catch (Exception e) {
            throw new RuntimeException("[EdgeSAM] encoder failed: " + e.getMessage(), e);
        }
    }

    /**
    * 解码
    * @param embeddings 嵌入
    * @param coords coords
    * @param labels 标签
    * @param scoresOut scores出
    */
    private float[][][][] decode(float[][][][] embeddings, float[][] coords,
                                 float[][] labels, float[] scoresOut) {
        long[] embShape = new long[]{1, 256, EMBED_SIZE, EMBED_SIZE};
        long[] coordShape = new long[]{1, 2, 2};
        long[] labelShape = new long[]{1, 2};
        try (OnnxTensor embTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flatten4(embeddings)), embShape);
             OnnxTensor coordTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flatten(coords)), coordShape);
             OnnxTensor labelTensor = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flatten(labels)), labelShape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("image_embeddings", embTensor);
            inputs.put("point_coords", coordTensor);
            inputs.put("point_labels", labelTensor);
            try (OrtSession.Result result = decoderSession.run(inputs)) {
                float[][] scores = (float[][]) result.get(0).getValue(); // [P3C 四十一 豁免] OrtSession.Result 模型输出索引（非 List/Collection）
                float[][][][] masks = (float[][][][]) result.get(1).getValue();
                for (int i = 0; i < Math.min(scores[0].length, scoresOut.length); i++) {
                    scoresOut[i] = scores[0][i];
                }
                return masks;
            }
        } catch (Exception e) {
            throw new RuntimeException("[EdgeSAM] decoder failed: " + e.getMessage(), e);
        }
    }

    /**
    * 将原图坐标 bbox 转换为解码器需要的缩放后坐标点 [top-left, bottom-right]。
    * @param box box
    * @return 转为pointcoords的结果
    */
    private float[][] toPointCoords(float[] box) {
        float x1 = Math.min(box[0], box[2]);
        float y1 = Math.min(box[1], box[3]);
        float x2 = Math.max(box[0], box[2]);
        float y2 = Math.max(box[1], box[3]);
        return new float[][]{
                {x1 * scale + padX, y1 * scale + padY},
                {x2 * scale + padX, y2 * scale + padY}
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
    * 掩码 logits [256,256] → sigmoid 阈值 → 灰度图 [0/255] → resize 回原图尺寸。
    * @param logits logits
    * @return mask转为镜像的结果
    */
    private BufferedImage maskToImage(float[][] logits) {
        BufferedImage lowRes = new BufferedImage(MASK_SIZE, MASK_SIZE, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = lowRes.getRaster();
        for (int y = 0; y < MASK_SIZE; y++) {
            for (int x = 0; x < MASK_SIZE; x++) {
                float sig = 1.0f / (1.0f + (float) Math.exp(-logits[y][x]));
                raster.setSample(x, y, 0, sig > 0.5f ? 255 : 0);
            }
        }

        BufferedImage result = new BufferedImage(srcWidth, srcHeight, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(lowRes, 0, 0, srcWidth, srcHeight, null);
        } finally {
            g.dispose();
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
            throw new IllegalArgumentException("EdgeSAM 输入图像为空");
        }
        Object wrapped = input.getWrappedImage();
        if (wrapped instanceof BufferedImage buffered) {
            return buffered;
        }
        Object converted = ImageFactory.getInstance().fromImage(input).getWrappedImage();
        if (converted instanceof BufferedImage buffered) {
            return buffered;
        }
        throw new IllegalStateException("EdgeSAM 无法将输入转换为 BufferedImage");
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
