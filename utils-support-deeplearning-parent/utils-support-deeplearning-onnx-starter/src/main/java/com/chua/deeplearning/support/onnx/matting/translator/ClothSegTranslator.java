package com.chua.deeplearning.support.onnx.matting.translator;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;

/**
 * U2Net Cloth 分割 Translator：768×768 输入，4 通道输出（背景/衣物/人体/配件），
 * 取 argmax 生成前景 mask，RGBA 输出。
 *
 * @author CH
 */
public final class ClothSegTranslator implements Translator<Image, Image> {

    private static final int SIZE = 768;
    private static final int CH = 4;

    private int width, height;
    private BufferedImage originalImage;

    public ClothSegTranslator() {
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        width = input.getWidth();
        height = input.getHeight();
        originalImage = toBufferedImage(input);
        BufferedImage resized = resizeTo(input, SIZE, SIZE);

        float[] data = new float[3 * SIZE * SIZE];
        int idx = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int rgb = resized.getRGB(x, y);
                data[idx] = ((rgb >>> 16) & 0xFF) / 255.0f;
                data[idx + SIZE * SIZE] = ((rgb >>> 8) & 0xFF) / 255.0f;
                data[idx + 2 * SIZE * SIZE] = (rgb & 0xFF) / 255.0f;
                idx++;
            }
        }
        return new NDList(ctx.getNDManager().create(data, new Shape(1, 3, SIZE, SIZE)));
    }

    @Override
    public Image processOutput(TranslatorContext ctx, NDList list) {
        NDArray raw = list.get(0); // [1, 4, 768, 768]
        Shape s = raw.getShape();
        int h = (int) s.get(2);
        int w = (int) s.get(3);
        float[] vals = raw.toFloatArray();

        // argmax across 4 channels -> segmentation mask
        byte[] mask = new byte[h * w];
        int pixelIdx = 0;
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int best = 0;
                float bestVal = vals[pixelIdx];
                for (int c = 1; c < CH; c++) {
                    float v = vals[pixelIdx + c * h * w];
                    if (v > bestVal) {
                        bestVal = v;
                        best = c;
                    }
                }
                mask[py * w + px] = (byte) (best == 0 ? 0 : 255);
                pixelIdx++;
            }
        }

        BufferedImage maskImg = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = maskImg.getRaster();
        raster.setDataElements(0, 0, w, h, mask);

        if (w != width || h != height) {
            BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(maskImg, 0, 0, width, height, null);
            g.dispose();
            maskImg = scaled;
        }

        return createRgbaImage(maskImg);
    }

    private BufferedImage toBufferedImage(Image input) {
        Object wrapped = input.getWrappedImage();
        if (wrapped instanceof BufferedImage bi) return bi;
        throw new IllegalStateException("无法获取 BufferedImage");
    }

    private BufferedImage resizeTo(Image input, int tw, int th) {
        BufferedImage src = toBufferedImage(input);
        if (src.getWidth() == tw && src.getHeight() == th) return src;
        BufferedImage resized = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return resized;
    }

    private Image createRgbaImage(BufferedImage mask) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int a = mask.getRaster().getSample(x, y, 0);
                int rgb = originalImage.getRGB(x, y) & 0x00FFFFFF;
                result.setRGB(x, y, (a << 24) | rgb);
            }
        }
        return ImageFactory.getInstance().fromImage(result);
    }

    @Override
    public Batchifier getBatchifier() {
        return null;
    }
}
