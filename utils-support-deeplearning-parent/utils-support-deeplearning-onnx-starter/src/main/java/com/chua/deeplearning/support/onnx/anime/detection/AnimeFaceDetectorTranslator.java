package com.chua.deeplearning.support.onnx.anime.detection;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
* Anime Face yolov8 ONNX Translator（嵌入式，纯 Java 预处理，兼容 onnxruntime engine）。
*
* <p>动漫人脸检测：YOLOv8 v1.4_n（deepghs/anime_face_detection）。
* 输入 640×640 RGB 归一化 [0,1]，输出 [1,5,8400]（cx,cy,w,h,face_conf）。</p>
*
* <p>onnxruntime engine 不支持 NDArray resize/set 等运算，故 letterbox 与 NMS 均用纯 Java 实现。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class AnimeFaceDetectorTranslator implements Translator<Image, DetectedObjects> {

    /**
    * 标签名。
    */
    private static final String FACE_LABEL = "anime_face";

    /**
    * 输入尺寸。
    */
    private static final int INPUT_SIZE = 640;

    /**
    * 置信度阈值。
    */
    /** 置信度阈值（默认 0.5），可经 detection配置 覆盖。 */
        /**
    * 创建 Translator（支持运行参数覆盖阈值，未提供的键使用内置默认值）。
    *
    * @param configuration 检测配置（可空）
    */
    public AnimeFaceDetectorTranslator(com.chua.deeplearning.support.ai.DetectionConfiguration configuration) {
        if (configuration != null) {
            this.confThreshold = configuration.optFloat(com.chua.deeplearning.support.ai.DetectionConfiguration.KEY_THRESHOLD, this.confThreshold);
        }
    }

    /**
    * 无参构造：使用默认阈值（SPI/反射实例化要求）。
    */
    public AnimeFaceDetectorTranslator() {
        this((com.chua.deeplearning.support.ai.DetectionConfiguration) null);
    }

private float confThreshold = 0.45f; // conf阈值

    /**
    * NMS IOU 阈值。
    */
    private static final float IOU_THRESHOLD = 0.45f;

    /**
    * Top-K。
    */
    private static final int TOP_K = 100;

    /**
    * letterbox 缩放比例与填充。
    */
    private float scaleR = 1f;

    /**
    * 左侧填充。
    */
    private int padLeft;

    /**
    * 顶部填充。
    */
    private int padTop;

    /**
    * 原图宽。
    */
    private int imageWidth;

    /**
    * 原图高。
    */
    private int imageHeight;

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        imageWidth = input.getWidth();
        imageHeight = input.getHeight();
        BufferedImage src = (BufferedImage) input.getWrappedImage();

        // letterbox：等比缩放 + 灰边填充到 640x640
        float r = Math.min(INPUT_SIZE / (float) imageWidth, INPUT_SIZE / (float) imageHeight);
        int newW = Math.round(imageWidth * r);
        int newH = Math.round(imageHeight * r);
        scaleR = r;
        padLeft = (INPUT_SIZE - newW) / 2;
        padTop = (INPUT_SIZE - newH) / 2;

        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        scaled.getGraphics().drawImage(src, 0, 0, newW, newH, null);

        int[] pixels = scaled.getRGB(0, 0, newW, newH, null, 0, newW);
        float[] data = new float[3 * INPUT_SIZE * INPUT_SIZE];
        // 灰边填充 114（归一化后 ~0.447）
        java.util.Arrays.fill(data, 114f / 255f);
        for (int y = 0; y < newH; y++) {
            for (int x = 0; x < newW; x++) {
                int p = pixels[y * newW + x];
                int idx = (y + padTop) * INPUT_SIZE + (x + padLeft);
                data[idx] = ((p >> 16) & 0xff) / 255f;
                data[idx + INPUT_SIZE * INPUT_SIZE] = ((p >> 8) & 0xff) / 255f;
                data[idx + 2 * INPUT_SIZE * INPUT_SIZE] = (p & 0xff) / 255f;
            }
        }
        NDArray array = ctx.getNDManager().create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));
        return new NDList(array);
    }

    @Override
    /** 处理输出 */
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray output = list.getFirst();
        long[] shape = output.getShape().getShape();
        // 直接读 flat float 数组，避免 ORT 引擎不支持的 squeeze/transpose（会递归 StackOverflow）
        float[] data = output.toFloatArray();

 // 兼容 [1,5,8400] / [5,8400] / [1,8400,5] 布局，统一为 [numdets, num通道]
        int dim = shape.length;
        if (dim == 3) {
            // [B,C,N]：C 小（通道维 5/6）。内存为通道优先（C-contiguous），必须按 ch*N+i 步长读，
            // 不能按 i*C+ch 读（会串通道导致坐标/conf 全错）
            return processNch(dim, shape, data);
        }
        int numDets;
        int numChannels;
        if (dim == 3) {
            // [1,C,N] 或 [B,C,N]，B 可>=1
            numChannels = (int) shape[1];
            numDets = (int) shape[2];
        } else if (dim == 2) {
            // [N,C] 或 [C,N]
            if (shape[1] == 5 || shape[1] == 6 || shape[1] < shape[0]) {
                // [N,C]
                numDets = (int) shape[0];
                numChannels = (int) shape[1];
            } else {
                // [C,N] → 步长遍历
                return processNch(dim, shape, data);
            }
        } else {
            throw new IllegalStateException("AnimeFace 输出维度异常: " + java.util.Arrays.toString(shape));
        }
        return processNcm(data, numDets, numChannels);
    }

    /**
    * 处理 [C,N] / [B,N,C] 布局（NCH 步长直读，避免转置）。
    *
    * @param dim       维度数
    * @param shape     形状
    * @param data      flat 数据
    * @return 检测结果
    */
    private DetectedObjects processNch(int dim, long[] shape, float[] data) {
        int c = (int) shape[dim - 2];
        int n = (int) shape[dim - 1];
 // [C,N]：按列读；若为 [B,N,C] 取 批量0 的 [N,C] 视作 [C,N]？不合理，
        // 这里按 [C,N] 步长 C 大在前处理
        float[] boxes = new float[n * c];
        for (int i = 0; i < n; i++) {
            for (int ch = 0; ch < c; ch++) {
                boxes[i * c + ch] = data[ch * n + i];
            }
        }
        return assemble(boxes, n, c);
    }

    /**
    * 处理 [N,C] / [B,C,N] 布局（NCM 步长直读）。
    *
    * @param data      flat 数据
    * @param numDets   检测数
    * @param numChannels 通道数
    * @return 检测结果
    */
    private DetectedObjects processNcm(float[] data, int numDets, int numChannels) {
        return assemble(data, numDets, numChannels);
    }

    /**
    * 从 [numdets, num通道] 扁平数组组装检测框。
    *
    * @param data     扁平数据
    * @param numDets  检测数
    * @param numChannels 通道数（须 >=5：cx,cy,w,h,conf...）
    * @return 检测结果
    */
    private DetectedObjects assemble(float[] data, int numDets, int numChannels) {
        if (numChannels < 5) {
            throw new IllegalStateException("AnimeFace 输出通道异常: " + numChannels);
        }
        // 收集候选框（letterbox 坐标系）
        List<float[]> boxes = new ArrayList<>();
        List<Float> scores = new ArrayList<>();
        for (int i = 0; i < numDets; i++) {
            int offset = i * numChannels;
            if (offset + 4 >= data.length) {
                break;
            }
            float cx = data[offset];
            float cy = data[offset + 1];
            float w = data[offset + 2];
            float h = data[offset + 3];
            float conf = 0f;
            for (int cc = 4; cc < numChannels && offset + cc < data.length; cc++) {
                conf = Math.max(conf, data[offset + cc]);
            }
            // 模型 ONNX 已含 sigmoid，输出为概率，无需再变换
            if (conf < confThreshold) {
                continue;
            }
            boxes.add(new float[]{cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2});
            scores.add(conf);
        }

        if (boxes.isEmpty()) {
            return new DetectedObjects(List.of(), List.of(), List.of());
        }

        // NMS（纯 Java）
        int[] keep = nms(boxes, scores);

        List<String> names = new ArrayList<>();
        List<Double> probs = new ArrayList<>();
        List<BoundingBox> rects = new ArrayList<>();
        int topK = Math.min(keep.length, TOP_K);
        for (int i = 0; i < topK; i++) {
            float[] b = boxes.get(keep[i]);
            // letterbox 坐标还原到原图
            float x1 = Math.max(0, (b[0] - padLeft) / scaleR);
            float y1 = Math.max(0, (b[1] - padTop) / scaleR);
            float x2 = Math.min(imageWidth, (b[2] - padLeft) / scaleR);
            float y2 = Math.min(imageHeight, (b[3] - padTop) / scaleR);
            if (x2 <= x1 || y2 <= y1) {
                continue;
            }
            names.add(FACE_LABEL);
            probs.add((double) scores.get(keep[i]));
            rects.add(new Rectangle(x1 / imageWidth, y1 / imageHeight,
                    (x2 - x1) / imageWidth, (y2 - y1) / imageHeight));
        }
        return new DetectedObjects(names, probs, rects);
    }

    /**
    * 纯 Java NMS（按分数降序，抑制 IOU 重叠框）。
    *
    * @param boxes  候选框 [x1,y1,x2,y2] 列表
    * @param scores 对应分数
    * @return 保留框索引（分数降序）
    */
    private static int[] nms(List<float[]> boxes, List<Float> scores) {
        int n = boxes.size();
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(scores.get(b), scores.get(a)));
        List<Integer> keep = new ArrayList<>();
        boolean[] removed = new boolean[n];
        for (int ii = 0; ii < n; ii++) {
            int i = idx[ii];
            if (removed[i]) {
                continue;
            }
            keep.add(i);
            float[] bi = boxes.get(i);
            float ix1 = bi[0], iy1 = bi[1], ix2 = bi[2], iy2 = bi[3];
            float iArea = Math.max(0, ix2 - ix1) * Math.max(0, iy2 - iy1);
            for (int jj = ii + 1; jj < n; jj++) {
                int j = idx[jj];
                if (removed[j]) {
                    continue;
                }
                float[] bj = boxes.get(j);
                float xx1 = Math.max(ix1, bj[0]);
                float yy1 = Math.max(iy1, bj[1]);
                float xx2 = Math.min(ix2, bj[2]);
                float yy2 = Math.min(iy2, bj[3]);
                if (xx2 <= xx1 || yy2 <= yy1) {
                    continue;
                }
                float inter = (xx2 - xx1) * (yy2 - yy1);
                float jArea = Math.max(0, bj[2] - bj[0]) * Math.max(0, bj[3] - bj[1]);
                float iou = inter / (iArea + jArea - inter);
                if (iou > IOU_THRESHOLD) {
                    removed[j] = true;
                }
            }
        }
        int[] result = new int[keep.size()];
        for (int i = 0; i < keep.size(); i++) {
            result[i] = keep.get(i);
        }
        return result;
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
 // ONNX Runtime 的 ndarray 不支持 Stack，单图推理不批处理
        return null;
    }
}
