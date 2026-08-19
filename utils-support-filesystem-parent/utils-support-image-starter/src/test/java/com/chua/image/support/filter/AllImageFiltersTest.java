package com.chua.image.support.filter;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;


/**
 * ImageFilter 全量实现测试
 *
 * <p>遍历 {@code D:\images} 目录下的所有图像，对每个 {@link AbstractImageFilter} 实现
 * 执行滤镜处理，并将结果写入 {@code D:\images\output\filter\{filterName}\} 目录。
 *
 * <p>使用 {@code main} 方法直接执行，无需 JUnit 等测试框架。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class AllImageFiltersTest {

    /**
     * 输入目录
     */
    private static final Path INPUT_DIR = Paths.get("D:", "images");

    /**
     * 输出根目录
     */
    private static final Path OUTPUT_DIR = Paths.get("D:", "images", "output", "filter");

    /**
     * 支持的图像后缀
     */
    private static final List<String> SUPPORTED_EXTS = Arrays.asList(".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp");

    public static void main(String[] args) throws Exception {
        if (!Files.exists(INPUT_DIR)) {
            System.err.println("输入目录不存在: " + INPUT_DIR);
            System.exit(1);
        }
        Files.createDirectories(OUTPUT_DIR);

        List<File> images = listImages(INPUT_DIR);
        if (images.isEmpty()) {
            System.err.println("输入目录下未找到支持的图像: " + INPUT_DIR);
            System.exit(1);
        }
        // 只取一张图测试所有滤镜
        File image = images.get(0);

        Map<String, FilterEntry> filters = buildFilters();

        System.out.println("=" .repeat(70));
        System.out.println("ImageFilter 全量实现测试");
        System.out.println("=" .repeat(70));
        System.out.println("输入目录: " + INPUT_DIR);
        System.out.println("测试图像: " + image.getAbsolutePath());
        System.out.println("输出目录: " + OUTPUT_DIR);
        System.out.println("滤镜数量: " + filters.size());
        System.out.println();

        int totalRun = 0;
        int totalSuccess = 0;
        int totalFailed = 0;
        int totalSkipped = 0;

        String imageName = image.getName();
        String baseName = stripExt(imageName);
        String ext = getExt(imageName);

        for (Map.Entry<String, FilterEntry> entry : filters.entrySet()) {
            String filterName = entry.getKey();
            FilterEntry fe = entry.getValue();
            totalRun++;
            System.out.print("▶ " + filterName + " (" + fe.note + ") ... ");

            Path outFile = OUTPUT_DIR.resolve(filterName + "_" + baseName + ext);

            try {
                BufferedImage src = ImageIO.read(image);
                if (src == null) {
                    System.out.println("[SKIP] (ImageIO.read returned null)");
                    totalSkipped++;
                    continue;
                }
                BufferedImage result = fe.processor.apply(src);
                if (result == null) {
                    System.out.println("[SKIP] (filter returned null)");
                    totalSkipped++;
                    continue;
                }
                String lower = ext.toLowerCase();
                String format;
                if (lower.equals(".jpg") || lower.equals(".jpeg")) {
                    format = "jpg";
                } else {
                    format = lower.replace(".", "");
                }
                if (format.equals("webp")) {
                    format = "png";
                }
                boolean noAlpha = "jpg".equalsIgnoreCase(format) || "bmp".equalsIgnoreCase(format);
                BufferedImage toWrite = noAlpha ? toRgbIfNeeded(result) : result;
                boolean written = ImageIO.write(toWrite, format, outFile.toFile());
                if (!written) {
                    System.out.println("[FAIL] (ImageIO.write returned false)");
                    totalFailed++;
                } else {
                    System.out.println("[ OK ] -> " + outFile.getFileName());
                    totalSuccess++;
                }
            } catch (Throwable t) {
                System.out.println("[FAIL] " + t.getClass().getSimpleName() + " - " + t.getMessage());
                totalFailed++;
            }
        }

        System.out.println();
        System.out.println("=" .repeat(70));
        System.out.println("测试完成");
        System.out.println("  总计: " + totalRun);
        System.out.println("  成功: " + totalSuccess);
        System.out.println("  失败: " + totalFailed);
        System.out.println("  跳过: " + totalSkipped);
        System.out.println("  输出目录: " + OUTPUT_DIR);
        System.out.println("=" .repeat(70));

        if (totalFailed > 0) {
            System.exit(1);
        }
    }


    /**
     * 当输出格式不包含 alpha 通道(jpg/bmp)时, 将带 alpha 通道的图像转为 RGB
     */
    private static BufferedImage toRgbIfNeeded(BufferedImage src) {
        int type = src.getType();
        if (type == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }


    /**
     * 构建所有需要测试的滤镜实例
     */
    private static Map<String, FilterEntry> buildFilters() throws Exception {
        Map<String, FilterEntry> map = new LinkedHashMap<>();
        map.put("gray", new FilterEntry("灰度化", ImageGrayImageFilter::new));
        map.put("grayscale", new FilterEntry("NTSC标准灰度", ImageGrayscaleFilter::new));
        map.put("bright", new FilterEntry("明亮度增强", ImageBrightImageFilter::new));
        map.put("bin", new FilterEntry("二值化", ImageBinImageFilter::new));
        map.put("negative", new FilterEntry("负片反转", ImageNegativeImageFilter::new));
        map.put("oldFashion", new FilterEntry("复古怀旧", ImageOldFashionImageFilter::new));
        map.put("antiAliasing", new FilterEntry("抗锯齿", ImageAntiAliasingImageFilter::new));
        map.put("transparent", new FilterEntry("透明度背景移除", ImageTransparentFilter::new));
        map.put("findEdge", new FilterEntry("综合边缘检测", ImageFindEdgeFilter::new));
        map.put("sobel", new FilterEntry("Sobel边缘检测", ImageSobelFilter::new));
        map.put("gaussianBlur", new FilterEntry("高斯模糊", ImageGaussianBlurFilter::new));
        map.put("usm", new FilterEntry("USM锐化", ImageUsmFilterImage::new));
        map.put("mosaic", new FilterEntry("马赛克", () -> new ImageMosaicFilter(10)));
        map.put("pixel", new FilterEntry("像素化", () -> new ImagePixelImageFilter(10)));
        map.put("sudoku", new FilterEntry("九宫格", SudokuImageFilter::new));
        map.put("bsc", new FilterEntry("BSC调整", BscAdjustImageFilter::new));
        map.put("nightVision", new FilterEntry("夜视效果", NightVisionImageFilter::new));
        map.put("sepiaTone", new FilterEntry("复古棕褐色调", SepiaToneImageFilter::new));
        map.put("laplace", new FilterEntry("拉普拉斯锐化", LaplaceImageFilter::new));
        map.put("underwater", new FilterEntry("水下增强", UnderwaterEnhancementFilter::new));
        map.put("minecraft", new FilterEntry("我的世界风格", MinecraftStyleImageFilter::new));
        map.put("ghibli", new FilterEntry("宫崎骏风格", StudioGhibliStyleImageFilter::new));
        map.put("ghibliStudio", new FilterEntry("吉卜力工作室风格", GhibliStudioImageFilter::new));
        map.put("clay", new FilterEntry("黏土风格", ClayStyleImageFilter::new));
        map.put("textWater", new FilterEntry("文本水印", () -> new TextWaterImageFilter("CH")));
        map.put("textImgWater", new FilterEntry("文本+图片水印",
                () -> {
                    byte[] logo;
                    try {
                        logo = readSmallLogo();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return new TextImgWaterImageFilter("CH", logo, com.chua.common.support.constant.Position.RIGHT_BOTTOM);
                }));
        map.put("sized", new FilterEntry("按比例缩放", () -> new ImageSizedFilter(0.5d)));
        map.put("width", new FilterEntry("按宽高缩放", () -> new ImageWidthFilter(200, 200)));
        map.put("imageWater", new FilterEntry("图片水印",
                () -> {
                    byte[] logo;
                    try {
                        logo = readSmallLogo();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return new ImageWaterImageFilter(logo, com.chua.common.support.constant.Position.RIGHT_BOTTOM);
                }));
        return map;
    }


    /**
     * 读取一张小尺寸图像作为水印图片
     */
    private static byte[] readSmallLogo() throws IOException {
        File dir = INPUT_DIR.toFile();
        File[] candidates = dir.listFiles((d, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".webp");
        });
        if (candidates != null && candidates.length > 0) {
            Arrays.sort(candidates, (a, b) -> Long.compare(a.length(), b.length()));
            return Files.readAllBytes(candidates[0].toPath());
        }
        return new byte[0];
    }


    /**
     * 列出目录下所有支持的图像 (仅当前目录, 不递归)
     */
    private static List<File> listImages(Path dir) throws IOException {
        List<File> result = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString().toLowerCase();
                for (String ext : SUPPORTED_EXTS) {
                    if (name.endsWith(ext)) {
                        result.add(p.toFile());
                        return;
                    }
                }
            });
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }


    /**
     * 去除文件后缀
     */
    private static String stripExt(String name) {
        int idx = name.lastIndexOf('.');
        return idx < 0 ? name : name.substring(0, idx);
    }


    /**
     * 获取文件后缀
     */
    private static String getExt(String name) {
        int idx = name.lastIndexOf('.');
        return idx < 0 ? ".png" : name.substring(idx);
    }


    /**
     * 滤镜测试条目
     */
    private static final class FilterEntry {

        /**
         * 滤镜描述
         */
        final String note;

        /**
         * 滤镜处理器: 接收输入 BufferedImage, 返回处理后的 BufferedImage
         */
        final FilterProcessor processor;

        FilterEntry(String note, Supplier<? extends AbstractImageFilter> factory) {
            this.note = note;
            this.processor = (src) -> {
                AbstractImageFilter filter = factory.get();
                return filter.converter(src);
            };
        }
    }


    /**
     * 滤镜处理函数式接口
     */
    @FunctionalInterface
    private interface FilterProcessor {
        BufferedImage apply(BufferedImage src) throws Exception;
    }
}
