package com.chua.common.support.image;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * ImagePipeline 落地验证：从 D:\images 读取图片，各步骤处理结果输出到 output/utils。
 *
 * <p>输出：original / grayscale / binarize / denoise / erode / dilate / full 共 7 张 PNG。</p>
 *
 * @since 4.0.0.42
 */
public final class ImagePipelineSave {

    private ImagePipelineSave() {
    }

    /**
     * 入口。
     *
     * @param args 可选：输入图片路径，默认 D:\images\document-html.png
     */
    public static void main(String[] args) throws IOException {
        String inputPath = args.length > 0 ? args[0] : "D:\\images\\document-html.png";
        Path outDir = Paths.get("D:\\images\\output\\utils");
        Files.createDirectories(outDir);

        byte[] in = Files.readAllBytes(Paths.get(inputPath));
        System.out.println("[input] " + inputPath + " (" + in.length + " B)");

        write(outDir.resolve("original.png"), in);
        write(outDir.resolve("grayscale.png"),
                ImagePipeline.builder().grayscale(true).build().process(in));
        write(outDir.resolve("binarize.png"),
                ImagePipeline.builder().binarize(true, 128).build().process(in));
        write(outDir.resolve("denoise.png"),
                ImagePipeline.builder().denoise(true, 1).build().process(in));
        write(outDir.resolve("erode.png"),
                ImagePipeline.builder().erode(true, 3).build().process(in));
        write(outDir.resolve("dilate.png"),
                ImagePipeline.builder().dilate(true, 3).build().process(in));
        write(outDir.resolve("full.png"),
                ImagePipeline.builder()
                        .grayscale(true).binarize(true, 128)
                        .denoise(true, 1).erode(true, 3).dilate(true, 3)
                        .build().process(in));

        System.out.println("[output] " + outDir);
        System.out.println("完成 ✅");
    }

    /**
     * 写文件。
     *
     * @param path 输出路径
     * @param data 字节
     */
    private static void write(Path path, byte[] data) throws IOException {
        Files.write(path, data);
        System.out.println("  " + path.getFileName() + " (" + data.length + " B)");
    }
}