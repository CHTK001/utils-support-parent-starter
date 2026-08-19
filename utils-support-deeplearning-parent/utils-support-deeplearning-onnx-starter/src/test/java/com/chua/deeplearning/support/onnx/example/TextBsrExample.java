package com.chua.deeplearning.support.onnx.example;

import com.chua.deeplearning.support.engine.AbstractIdentificationEngine;
import com.chua.deeplearning.support.translator.ITranslator;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * text-bsr 文字超分测试。
 *
 * <pre>{@code
 *   TextBsrExample G:\images\很不清楚的文字图片用于测试文字高清修复模型.png [2|4]
 * }</pre>
 *
 * @since 4.0.0.42
 */
public final class TextBsrExample {

    private TextBsrExample() {
    }

    public static void main(String[] args) throws Exception {
        String imagePath = args.length > 0 ? args[0] : "G:\\images\\很不清楚的文字图片用于测试文字高清修复模型.png";
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        byte[] img = Files.readAllBytes(Path.of(imagePath));

        @SuppressWarnings("unchecked")
        ITranslator<Object, Object> t =
                (ITranslator<Object, Object>) AbstractIdentificationEngine.getInstance()
                        .get("text-bsr", ITranslator.class);
        if (t == null) {
            System.out.println("[text-bsr] 模型未注册");
            return;
        }
        // 穿透 LazyDjlTranslator / ITranslatorDelegate 包装，找到原生 TextBsrTranslator 设置 scale
        try {
            Object target = unwrap(t);
            java.lang.reflect.Method setScale = target.getClass().getMethod("setScale", int.class);
            setScale.invoke(target, scale);
            System.out.println("[text-bsr] scale=" + scale + "x");
        } catch (Exception e) {
            System.out.println("[text-bsr] 设置 scale 失败: " + e.getMessage() + "，使用默认 2x");
        }        long t0 = System.currentTimeMillis();
        Object out = t.translate(img);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("[text-bsr] 图片: " + imagePath);
        System.out.println("       输出类型: " + (out == null ? "null" : out.getClass().getName()));
        if (out instanceof java.awt.image.BufferedImage bi) {
            System.out.println("       尺寸: " + bi.getWidth() + "x" + bi.getHeight());
            System.out.println("       耗时=" + elapsed + "ms");
            Path outPath = Path.of("G:\\images\\output\\textbsr_out_" + scale + "x.png");
            java.io.File f = outPath.toFile();
            if (!f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            javax.imageio.ImageIO.write(bi, "png", f);
            System.out.println("       已保存: " + outPath + " (" + f.length() + " bytes)");
        }
    }
    /**
     * 穿透 {@code LazyDjlTranslator.unwrap()} 包装链，返回最内层原生 Translator 实例。
     *
     * @param obj 顶层包装对象
     * @return 最内层原生实例
     */
    private static Object unwrap(Object obj) throws Exception {
        Object current = obj;
        for (int i = 0; i < 8 && current != null; i++) {
            java.lang.reflect.Method unwrap;
            try {
                unwrap = current.getClass().getMethod("unwrap");
            } catch (NoSuchMethodException e) {
                break;
            }
            unwrap.setAccessible(true);
            Object next = unwrap.invoke(current);
            if (next == null || next == current) {
                break;
            }
            current = next;
        }
        return current;
    }

}
