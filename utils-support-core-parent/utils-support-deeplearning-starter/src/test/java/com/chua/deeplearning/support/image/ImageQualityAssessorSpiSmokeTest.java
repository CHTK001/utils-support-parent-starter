package com.chua.deeplearning.support.image;

import com.chua.common.support.spi.ServiceProvider;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 图像质量评估扩展点解析与端到端冒烟门（main 方法直跑，不依赖测试框架）。
 *
 * <p>{@link ImageQualityAssessor#create(String, String)} 走 SPI 按提供者取实例。
 * 本门固定两件事：与接口同包的 {@code @Spi} 实现可经同包扫描按别名解析（无需注册文件），
 * 以及取到的实例对真实像素（棋盘格与均色图）能给出正确的清晰度判定与阈值响应。</p>
 *
 * <p>运行方式：</p>
 * <pre>{@code
 * java -cp <类路径> com.chua.deeplearning.support.image.ImageQualityAssessorSpiSmokeTest
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class ImageQualityAssessorSpiSmokeTest {

    /**
     * 提供者别名：拉普拉斯方差评估器
     */
    private static final String PROVIDER = "laplacian";

    /**
     * 实现类全限定名
     */
    private static final String IMPL = "com.chua.deeplearning.support.image.LaplacianImageQualityAssessor";

    /**
     * 测试图像边长
     */
    private static final int SIZE = 64;

    /**
     * 棋盘格方块边长
     */
    private static final int BLOCK = 4;

    private static int pass;

    private static int fail;

    private ImageQualityAssessorSpiSmokeTest() {
    }

    /**
     * 执行门校验。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        checkProviderResolves();
        checkAssessViaProvider();
        checkUnknownProvider();
        System.out.println("结果: PASS=" + pass + ", FAIL=" + fail);
        System.out.println("RESULT: " + (fail == 0 ? "PASS" : "FAIL"));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 校验扩展点可按别名解析出实例。
     */
    private static void checkProviderResolves() {
        Map<String, Class<ImageQualityAssessor>> types = ServiceProvider.of(ImageQualityAssessor.class).listType();
        observe("已登记提供者 " + types.keySet());
        check("别名 " + PROVIDER + " 可见", types.keySet().stream().anyMatch(PROVIDER::equalsIgnoreCase));
        ImageQualityAssessor assessor = ImageQualityAssessor.create(PROVIDER, "");
        observe("create(" + PROVIDER + ", \"\")=" + (assessor == null ? "null" : assessor.getClass().getName()));
        check("可取得 " + PROVIDER + " 实例", IMPL.equals(assessor == null ? null : assessor.getClass().getName()));
    }

    /**
     * 经扩展点实例对真实像素做质量评估。
     */
    private static void checkAssessViaProvider() {
        ImageQualityAssessor assessor = ImageQualityAssessor.create(PROVIDER, "");
        if (assessor == null) {
            check("端到端评估跳过：实例为 空", false);
            check("对照评估跳过：实例为 空", false);
            return;
        }
        byte[] sharp = png(checkerboard());
        byte[] flat = png(uniform());
        var sharpInfo = assessor.assess(sharp);
        var flatInfo = assessor.assess(flat);
        observe("棋盘格 blurScore=" + fmt(sharpInfo.blurScore()) + " brightness=" + fmt(sharpInfo.brightness())
                + " sharpnessOk=" + sharpInfo.sharpnessOk() + " message=" + sharpInfo.message());
        observe("均色图 blurScore=" + fmt(flatInfo.blurScore()) + " brightness=" + fmt(flatInfo.brightness())
                + " sharpnessOk=" + flatInfo.sharpnessOk() + " message=" + flatInfo.message());
        check("清晰图判为合格", sharpInfo.sharpnessOk() && sharpInfo.brightnessOk());
        check("均色图判为模糊", !flatInfo.sharpnessOk());
        check("清晰图得分高于均色图", sharpInfo.blurScore() > flatInfo.blurScore());
        check("阈值可调生效", !assessor.blurThreshold(100000.0).assess(sharp).sharpnessOk());
    }

    /**
     * 校验未登记提供者的返回契约。
     */
    private static void checkUnknownProvider() {
        ImageQualityAssessor assessor = ImageQualityAssessor.create("no-such-provider", "");
        observe("create(未登记提供者)=" + (assessor == null ? "null" : assessor.getClass().getName()));
        check("未登记提供者不得返回拉普拉斯实例",
                assessor == null || !IMPL.equals(assessor.getClass().getName()));
    }

    /**
     * 构造高对比棋盘格图。
     *
     * @return 图像
     */
    private static BufferedImage checkerboard() {
        var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                boolean white = ((x / BLOCK) + (y / BLOCK)) % 2 == 0;
                image.setRGB(x, y, white ? 0xFFFFFF : 0x000000);
            }
        }
        return image;
    }

    /**
     * 构造均色图（无高频细节）。
     *
     * @return 图像
     */
    private static BufferedImage uniform() {
        var image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        var graphics = (Graphics2D) image.getGraphics();
        graphics.setColor(new Color(128, 128, 128));
        graphics.fillRect(0, 0, SIZE, SIZE);
        graphics.dispose();
        return image;
    }

    /**
     * 编码为 PNG 字节。
     *
     * @param image 图像
     * @return PNG 字节数组
     */
    private static byte[] png(BufferedImage image) {
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成测试图像失败: " + e, e);
        }
    }

    /**
     * 格式化数值。
     *
     * @param value 数值
     * @return 两位小数字符串
     */
    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    /**
     * 输出观察值。
     *
     * @param text 观察内容
     */
    private static void observe(String text) {
        System.out.println("OBSERVE " + text);
    }

    /**
     * 记录单项校验结果。
     *
     * @param name 校验名
     * @param ok 是否通过
     */
    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [通过] " + name);
        } else {
            fail++;
            System.out.println("  [失败] " + name);
        }
    }
}
