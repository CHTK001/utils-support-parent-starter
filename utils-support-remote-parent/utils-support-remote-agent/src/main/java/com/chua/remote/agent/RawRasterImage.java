package com.chua.remote.agent;

import java.awt.Rectangle;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;

/**
 * 原始像素 RenderedImage 包装（不经 BufferedImage）。
 *
 * <p>包装原始 RGB 字节构造的 raster，供 ImageIO 编码器直接消费——编码链路全程
 * 不经过 {@link java.awt.image.BufferedImage}。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
final class RawRasterImage implements RenderedImage {

    private final Raster raster;
    private final int width;
    private final int height;
    private final ColorModel colorModel;

    RawRasterImage(Raster raster, int width, int height) {
        this.raster = raster;
        this.width = width;
        this.height = height;
        this.colorModel = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_sRGB), false, false,
                Transparency.OPAQUE, java.awt.image.DataBuffer.TYPE_BYTE);
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinX() {
        return 0;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public int getNumXTiles() {
        return 1;
    }

    @Override
    public int getNumYTiles() {
        return 1;
    }

    @Override
    public int getMinTileX() {
        return 0;
    }

    @Override
    public int getMinTileY() {
        return 0;
    }

    @Override
    public int getTileWidth() {
        return width;
    }

    @Override
    public int getTileHeight() {
        return height;
    }

    @Override
    public int getTileGridXOffset() {
        return 0;
    }

    @Override
    public int getTileGridYOffset() {
        return 0;
    }

    @Override
    public ColorModel getColorModel() {
        return colorModel;
    }

    @Override
    public SampleModel getSampleModel() {
        return raster.getSampleModel();
    }

    @Override
    public Raster getData() {
        return raster;
    }

    @Override
    public Raster getData(Rectangle rect) {
        return raster.createChild(rect.x, rect.y, rect.width, rect.height, 0, 0, null);
    }

    @Override
    public WritableRaster copyData(WritableRaster out) {
        if (out == null) {
            return raster.createCompatibleWritableRaster();
        }
        out.setRect(raster);
        return out;
    }

    @Override
    public Raster getTile(int tileX, int tileY) {
        return raster;
    }

    @Override
    public String[] getPropertyNames() {
        return null;
    }

    @Override
    public Object getProperty(String name) {
        return null;
    }
}
