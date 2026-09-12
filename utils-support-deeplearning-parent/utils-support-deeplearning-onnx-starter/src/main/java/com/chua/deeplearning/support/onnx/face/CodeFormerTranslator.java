package com.chua.deeplearning.support.onnx.face;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;

/**
   * 编码former ONNX
 * <p>
   * 编码former
 *       face restoration / enhancement                                 
 *       blurry / low-quality / damaged face -> restored face               
 * </p>
 * <p>
   * : 512x512 RGB 镜像
 *      : bluefoxcreation/Codeformer-ONNX
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class CodeFormerTranslator implements Translator<Image, Image> {

    /** 输入尺寸 */
    /** 输入_大小 */
    private static final int INPUT_SIZE = 512;
    /** 均值数组 */
    /** Mean */
    private static final float[] MEAN = {0.5f, 0.5f, 0.5f};
    /** 标准差数组 */
    /** STD */
    private static final float[] STD = {0.5f, 0.5f, 0.5f};

    @Override
    /** 处理输入 */
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDManager manager = ctx.getNDManager();

        // 纯 Java 预处理：AWT BICUBIC resize + CHW 标准化，
 // 避免 nd镜像工具.resize / ndarray 张量运算在部分 engine（Rust/ONNX）不受支持
        BufferedImage src = (BufferedImage) input.getWrappedImage();
        BufferedImage resized = ImageUtils.resize(src, INPUT_SIZE, INPUT_SIZE, org.opencv.imgproc.Imgproc.INTER_CUBIC);

        int total = INPUT_SIZE * INPUT_SIZE;
        float[] data = new float[3 * total];
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                int idx = y * INPUT_SIZE + x;
                data[idx] = ((((rgb >> 16) & 0xff) / 255.0f) - MEAN[0]) / STD[0];
                data[total + idx] = ((((rgb >> 8) & 0xff) / 255.0f) - MEAN[1]) / STD[1];
                data[2 * total + idx] = (((rgb & 0xff) / 255.0f) - MEAN[2]) / STD[2];
            }
        }

        NDArray array = manager.create(data, new Shape(1, 3, INPUT_SIZE, INPUT_SIZE));

        // 身份条件权重 w：模型导出为「标量条件」形式（fuse 块直接与特征广播相乘，
 // 特征 通道 为 256/128，仅标量可广播），故传 [1,1] 标量。
        // 注：模型输入声明 w 为 tensor(double)，需用 double 创建。
        NDArray w = manager.create(new double[]{1.0d}, new Shape(1, 1));

        return new NDList(array, w);
    }

    @Override
    /** 处理输出 */
    public Image processOutput(TranslatorContext ctx, NDList list) {
        // 模型输出 3 个：y(修复图) / logits / style_feat，取第一个 y
        NDArray outputImg = list.get(0);

        // 输出为 [1, 3, H, W] float，纯 Java 还原为 [0,255] RGB 图像
        Object arr = outputImg.toArray();
        int h = (int) outputImg.getShape().get(2);
        int w = (int) outputImg.getShape().get(3);

        float[][][] out;
        if (arr instanceof float[][][][] d4) {
            out = d4[0];
        } else if (arr instanceof float[][] d3) {
            float[][][] tmp = new float[3][h][w];
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < h; y++) {
                    tmp[c][y] = d3[c * h + y];
                }
            }
            out = tmp;
        } else if (arr instanceof Number[] flat) {
            // ONNX engine 下 toArray() 返回扁平 Number[]，按 CHW 顺序解析
            float[][][] tmp = new float[3][h][w];
            for (int c = 0; c < 3; c++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        tmp[c][y][x] = flat[c * h * w + y * w + x].floatValue();
                    }
                }
            }
            out = tmp;
        } else {
            throw new IllegalStateException("CodeFormer 输出格式不支持: " + arr.getClass().getName());
        }

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r = clamp255(out[0][y][x]);
                float g = clamp255(out[1][y][x]);
                float b = clamp255(out[2][y][x]);
                image.setRGB(x, y, ((int) r << 16) | ((int) g << 8) | (int) b);
            }
        }
        return ImageFactory.getInstance().fromImage(image);
    }

    /**
     * 将模型输出像素裁剪到 [0,255] 并取整。
     *
     * @param v 模型输出值（通常约 [0,1]，需反归一化）
     * @return [0,255] 整数
     */
    private static int clamp255(float v) {
        float val = (v * STD[0] + MEAN[0]) * 255.0f;
        if (val < 0) {
            val = 0;
        }
        if (val > 255) {
            val = 255;
        }
        return Math.round(val);
    }

    @Override
    /** 获取Batchifier */
    public Batchifier getBatchifier() {
 // 返回 空：单输入无需 批量 包装，避免 Batchifier.STACK 将 x 变为 5 维导致 rank 不匹配
        return null;
    }
}
