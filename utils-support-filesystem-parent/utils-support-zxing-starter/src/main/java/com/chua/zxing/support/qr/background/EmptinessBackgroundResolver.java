package com.chua.zxing.support.qr.background;

import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.qr.BackgroundSetting;
import com.chua.common.support.lang.qr.CodeEyeStyle;
import com.chua.common.support.lang.qr.CodePointStyle;
import com.chua.common.support.lang.qr.QrSetting;
import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.zxing.support.qr.background.emptiness.CodeEyeResolver;
import com.github.hui.quick.plugin.qrcode.wrapper.BitMatrixEx;
import com.google.zxing.qrcode.encoder.QRCode;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 底图穿透
* @author CH
* @since 4.0.0.42
 */
public class EmptinessBackgroundResolver implements BackgroundResolver {

    static final int UNIT_WIDTH = 54; // UNIT_WIDTH

    @Override
    /** 解析 */
    public BufferedImage resolve(QrSetting setting, BackgroundSetting backgroundSetting, BufferedImage image, QRCode qrCode, BitMatrixEx bitMatrix) {
        int width = setting.getWidth();
        int height = setting.getHeight();

        BufferedImage bg = BufferedImageUtils.toBufferedImage(backgroundSetting.getBackgroundImage());
        bg = BufferedImageUtils.scaleImage(bg, width, height);
        byte[][] rect = qrCode.getMatrix().getArray();

        int newWidth = rect.length * UNIT_WIDTH + 2 * UNIT_WIDTH;
        int newHeight = newWidth;
        Color eyeDf = Converter.convertIfNecessary(setting.getCodeEyeSetting().getCodeEyeColor(), Color.class);
        Color pointDf = Converter.convertIfNecessary(setting.getCodePointSetting().getCodePointColor(), Color.class);
        Color lb = new Color(255, 255, 255, (int) (255 * 0.25));
        Color lf = new Color(255, 255, 255, (int) (255 * 0.75));
        BufferedImage bufferedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);

        int version = qrCode.getVersion().getVersionNumber();
        Graphics2D g = bufferedImage.createGraphics();

        g.setBackground(Color.WHITE);
        g.clearRect(0, 0, newWidth, newHeight);

        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, 1.0f));
        g.drawImage(bg, 0, 0, newWidth, newHeight, null);
        CodePointStyle codePointStyle = setting.getCodePointSetting().getCodePoint();
        // 设置不要齿（画出的图形会圆滑）
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        fillPositionDetectionShape(g, eyeDf, lf, lb, width, UNIT_WIDTH, UNIT_WIDTH, version, setting.getCodeEyeSetting().getCodeEye());
        for (int i = 0; i < rect.length; i++) {
            for (int j = 0; j < rect.length; j++) {
                Color f = rect[i][j] == 1 ? pointDf : lf;
                if (isFixed(version, i, j)) {
                    continue;
                }
                render(g, f, lb, bufferedImage, rect, i, j, codePointStyle);
            }
        }
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER));

        g.dispose();
 // resize 镜像编码
        return BufferedImageUtils.scaleImage(bufferedImage, width, height);
    }

    /**
    * Render
    *
    * @param g g
    * @param whiteColor whitecolor
    * @param blackColor blackcolor
    * @param bufferedImage 缓冲镜像
    * @param rect rect
    * @param i i
    * @param j j
    * @param codePointStyle 编码pointstyle
     */
    private void render(Graphics2D g, Color whiteColor, Color blackColor, BufferedImage bufferedImage, byte[][] rect, int i, int j, CodePointStyle codePointStyle) {
        if (rect[i][j] == 1) {
            // 画黑色的点
            g.setColor(whiteColor);
            BufferedImageUtils.fillShape(g, codePointStyle, UNIT_WIDTH, UNIT_WIDTH, UNIT_WIDTH, i, j);
            return;
        }
        int[] arrColor = new int[324];
        arrColor = bufferedImage.getRGB(UNIT_WIDTH + i * UNIT_WIDTH + 3, UNIT_WIDTH + j * UNIT_WIDTH + 3, UNIT_WIDTH - 6, UNIT_WIDTH - 6, arrColor, 0, 4);
        for (int value : arrColor) {
            if (value != -1) {
                g.setColor(blackColor);
                BufferedImageUtils.fillShape(g, codePointStyle, UNIT_WIDTH, UNIT_WIDTH, UNIT_WIDTH, i, j);
            }
        }
    }
    /**
    * fill位置detectionshape
    * @param g g
    * @param eyeDf eyedf
    * @param lf lf
    * @param lb lb
    * @param qrCodeWidth qr编码width
    * @param startX 启动x
    * @param startY 启动y
    * @param version 版本
    * @param fillPositionDetectionShapeModel fill位置detectionshape模型
     */
    private static void fillPositionDetectionShape(Graphics2D g, Color eyeDf,Color lf, Color lb, int qrCodeWidth, int startX, int startY, int version,
                                                   CodeEyeStyle fillPositionDetectionShapeModel) {
        ServiceProvider.of(CodeEyeResolver.class).getNewExtension(fillPositionDetectionShapeModel)
                    .resolve(g, eyeDf, lf, lb, qrCodeWidth, startX, startY, version);
    }
    /**
    * 计算指定版本的二维码的大小。
    *
    * @param version 二维码的版本号，范围从1到20
    * @return 返回二维码的大小，如果版本号超出范围，则返回0
     */
    public static int size(int version) {
        // 根据二维码版本计算其大小
        if (version >= 1 && version <= 20) {
            return (version - 1) * 4 + 21;
        } else {
            return 0;
        }
    }

    /**
    * 判断指定位置的模块是否为固定模块。
    *
    * @param size 二维码的大小
    * @param x 指定位置的x坐标
    * @param y 指定位置的y坐标
    * @return 如果指定位置为固定模块，则返回true，否则返回false
     */
    private static boolean isFixed(int size, int x, int y) {
        // 判断位置是否为固定模块
        if (x < 8 && y < 8) {
            return true;
        } else if (x > (size(size) - 1) - 8 && y < 8) {
            return true;
        } else if (x < 8 && y > (size(size) - 1) - 8) {
            return true;
        } else return size != 1 && (x > (size(size) - 1) - 9 && x < (size(size) - 1) - 3 && y > (size(size) - 1) - 9 && y < (size(size) - 1) - 3);
    }

}

