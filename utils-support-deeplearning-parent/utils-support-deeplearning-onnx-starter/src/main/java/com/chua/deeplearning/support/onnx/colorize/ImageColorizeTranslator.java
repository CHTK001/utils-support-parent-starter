package com.chua.deeplearning.support.onnx.colorize;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * 图像上色 Translator
 * <p>
 * 基于深度学习的自动上色模型，为黑白照片或灰度图像生成自然的彩色图像。
 * 大多数 ONNX 上色模型（如 deoldify、Colorize）直接输出 RGB 格式，
 * 无需 Lab 色彩空间转换，流程简洁高效。
 * </p>
 * <p>
 * 模型来源：huggingface.co/bluefoxcreation/deoldify-ONNX
 * 架构：U-Net 风格编码器-解码器
 * 输入：镜像（灰度图像）
 * 输出：镜像（彩色 RGB 图像）
 * </p>
 * <p>
 * 输入流程：
 * <ol>
 *   <li>灰度图像归一化到 [0, 1]</li>
 *   <li>ONNX 模型直接预测 RGB 三通道输出</li>
 *   <li>反归一化到 [0, 255]，转为 uint8 图像</li>
 * </ol>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class ImageColorizeTranslator implements Translator<Image, Image> {

    /**
     * 处理输入
    */
    @Override
    public NDList processInput(@Nonnull TranslatorContext ctx, @Nonnull Image input) {
        NDManager manager = ctx.getNDManager();

        // 预处理：灰度 → 三通道 → [1,3,H,W] float32（NCHW，0~1）
        //
        // <p><b>为什么直接造 float[] 而不用 NDArray 算子拼形状：</b>
        // 这版 DJL 的 Rust NDArray 有两个反直觉行为，实测（最小复现，见提交说明）：
        //   ① {@code Image.toNDArray(GRAYSCALE)} 返回的是 **[H,W,1]**（3 维、带尾通道），
        //      不是文档暗示的 [H,W]；
        //   ② {@code NDArrays.concat(3 份 [1,H,W,1], 0)} 得到 **[3,H,W,1]** ——
        //      多出来的尾维 1 会一路带到模型入口。
        // 于是「expandDims(0) + concat + expandDims(0)」实际产出 [1,3,H,W,1]（rank 5），
        // ORT 报 `Invalid rank for input: input Got: 5 Expected: 4`；
        // 而 {@code repeat} 在 Rust 引擎里未实现，直接抛 UnsupportedOperationException。
        //
        // <p>已验证可行的路径：把灰度像素读成一维 float[]，按 NCHW 手工铺三份，
        // 再用 {@code manager.create(float[], new Shape(1,3,H,W))} 建张量。
        // 同一模型上实测输出 [1,3,256,256]，与 python onnxruntime 完全一致。</p>
        //
        // <p>batch 维（第 0 维）在这里自己加，且 {@link #getBatchifier()} 必须返回
        // {@code none} 与之配套 —— 否则框架会再 STACK 一层，变成 rank 5。
        // 这条组合已在最小复现里双向验证过（none 成功 / STACK 报 Got: 5）。</p>
        NDArray gray = input.toNDArray(manager, Image.Flag.GRAYSCALE);
        if (!DataType.FLOAT32.equals(gray.getDataType())) {
            gray = gray.toType(DataType.FLOAT32, false);
        }
        // 归一化到 0~1；此时 shape 可能是 [H,W,1]，一维化后长度恰为 H*W
        float[] hw = gray.div(255.0f).toFloatArray();
        int h = input.getHeight();
        int w = input.getWidth();
        int hwSize = h * w;
        if (hw.length < hwSize) {
            throw new IllegalStateException("灰度像素数不足: " + hw.length + " < " + hwSize);
        }
        // NCHW 铺三份（灰度当 R=G=B）
        float[] nchw = new float[3 * hwSize];
        for (int i = 0; i < hwSize; i++) {
            float v = hw[i];
            nchw[i] = v;
            nchw[hwSize + i] = v;
            nchw[2 * hwSize + i] = v;
        }
        NDArray batch = manager.create(nchw, new Shape(1, 3, h, w));

        log.debug("[ImageColorize] Input: {}", batch.getShape());
        return new NDList(batch);
    }

    /**
     * 处理输出
    */
    @Override
    public Image processOutput(@Nonnull TranslatorContext ctx, @Nonnull NDList list) {
        NDArray output = list.singletonOrThrow();
        long[] shape = output.getShape().getShape();
        log.debug("[ImageColorize] Output raw shape={} dtype={}", Arrays.toString(shape), output.getDataType());

        // 模型输出 [1,3,H,W]：去掉 batch 维得到 [3,H,W]
        if (shape.length == 4) {
            output = output.squeeze(0);
        }

        // 值域归一：deoldify Artistic 这一版权重的原始输出**不是** [0,1] 也不是 [-1,1]，
        // 实测（256×256 灰度输入）落在 1.83~10.52，三通道各自还有不同基线
        // （min 1.83/2.98/4.57，max 7.36/8.97/10.52）。
        //
        // 直接用会得到一片白；只按全局 max 除会整体偏亮偏蓝（RGB 均值 88/103/161）。
        // 这里用 **p1~p99 线性拉伸**：丢掉极少数离群点后把主色域铺满 0~255，
        // 实测效果最接近 deoldify 官方样张（紫蓝调）。
        // 判据顺序（从最常见的约定往下兜）：
        //   1) 出现负数      → 视作 [-1,1]，线性映射到 [0,1]；
        //   2) max <= 1.5    → 已是 [0,1]，直接用；
        //   3) 其余          → p1~p99 拉伸（本模型落在这里）。
        float[] values = output.toFloatArray();
        float minVal = Float.POSITIVE_INFINITY;
        float maxVal = Float.NEGATIVE_INFINITY;
        for (float v : values) {
            if (v < minVal) {
                minVal = v;
            }
            if (v > maxVal) {
                maxVal = v;
            }
        }
        if (minVal < 0.0f) {
            // [-1,1] → [0,1]
            output = output.add(1.0f).div(2.0f);
        } else if (maxVal > 1.5f) {
            float[] sorted = values.clone();
            Arrays.sort(sorted);
            int n = sorted.length;
            // p1 / p99 取排序后对应下标，n 很小时退化为 min/max
            float lo = n >= 100 ? sorted[(int) (n * 0.01)] : sorted[0];
            float hi = n >= 100 ? sorted[(int) (n * 0.99)] : sorted[n - 1];
            float span = hi - lo;
            if (span > 1e-6f) {
                output = output.sub(lo).div(span);
            } else if (maxVal > 1e-6f) {
                // 纯色图（拉伸区间退化）：退回按 max 归一，避免除零
                output = output.div(maxVal);
            }
        }

        output = output.transpose(1, 2, 0);
        output = output.mul(255.0f).toType(DataType.UINT8, true);

        return ImageFactory.getInstance().fromNDArray(output);
    }

    /**
     * 获取Batchifier
    */
    @Override
    public Batchifier getBatchifier() {
        // 必须是 "none"：processInput 已产出完整 [1,3,H,W]，
        // 再让框架 STACK 一次就变成 [1,1,3,H,W]（rank 5）。
        // 实测同一份 processInput 输出 [1,3,256,256]：
        //   getBatchifier()=STACK → ORT 报 Got: 5 Expected: 4；
        //   getBatchifier()=none  → 推理成功，输出 [1,3,256,256]。
        return Batchifier.fromString("none");
    }
}




