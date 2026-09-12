package com.chua.common.support.image.png;

import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
* This 类 contains 工具 方法 that may be useful 转为 镜像读取
* plugins.  Ideally these 方法 would be 入 the 镜像读取 基础 类
* so that 全部 subclasses could 福利 从 them, but that would be an
* addition 转为 the existing API, 和 it 是否 not yet clear whether these 方法
* are universally useful, so for now we will 请假 them here.
*
* @author CH
* @since 4.0.0.42
*/
final class ReaderUtils {

 // 助手 for compute更新pixels 方法
    private static void computeUpdatedPixels(int sourceOffset,
                                             int sourceExtent,
                                             int destinationOffset,
                                             int dstMin,
                                             int dstMax,
                                             int sourceSubsampling,
                                             int passStart,
                                             int passExtent,
                                             int passPeriod,
                                             int[] vals,
                                             int offset)
    {
 // We need 转为 satisfy the congruences:
        // dst = destinationOffset + (src - sourceOffset)/sourceSubsampling
        //
        // src - passStart == 0 (mod passPeriod)
        // src - sourceOffset == 0 (mod sourceSubsampling)
        //
 // 主题 转为 the inequalities:
        //
        // src >= passStart
        // src < passStart + passExtent
        // src >= sourceOffset
        // src < sourceOffset + sourceExtent
        // dst >= dstMin
        // dst <= dstmax
        //
        // where
        //
        // dst = destinationOffset + (src - sourceOffset)/sourceSubsampling
        //
        // For now we use a brute-force approach although we could
 // 尝试 转为 分析 the congruences.  If 通过周期 和
 // 源subsamling are relatively prime, the 周期 will be
 // their product.  If they 共享 a 通用 factor, either the
 // 周期 will be equal 转为 the larger 值, 或 the sequences
        // will be completely disjoint, depending on the relationship
 // between 通过启动 和 源偏移量.  自 we only have 转为 执行 this
        // twice per image (once each for X and Y), it seems cheap enough
 // 转为 执行 it the straightforward way.

        boolean gotPixel = false;
        int firstDst = -1;
        int secondDst = -1;
        int lastDst = -1;

        for (int i = 0; i < passExtent; i++) {
            int src = passStart + i*passPeriod;
            if (src < sourceOffset) {
                continue;
            }
            if ((src - sourceOffset) % sourceSubsampling != 0) {
                continue;
            }
            if (src >= sourceOffset + sourceExtent) {
                break;
            }

            int dst = destinationOffset +
                (src - sourceOffset)/sourceSubsampling;
            if (dst < dstMin) {
                continue;
            }
            if (dst > dstMax) {
                break;
            }

            if (!gotPixel) {
                // Record smallest valid pixel
                // rstDst = dst;
                
                gotPixel = true;
            } else if (secondDst == -1) {
                // Record second smallest valid pixel
                secondDst = dst;
            }
            // Record largest valid pixel
            lastDst = dst;
        }

        vals[offset] = firstDst;

 // If we 从不 锯 a valid pixel, 设置 width 转为 0
        if (!gotPixel) {
            vals[offset + 2] = 0;
        } else {
            vals[offset + 2] = lastDst - firstDst + 1;
        }

 // The 周期 是否 given by the difference 的 任意 two adjacent pixels
        vals[offset + 4] = Math.max(secondDst - firstDst, 1);
    }

    /**
    * A 工具 方法 that computes the exact 设置 的 目标
    * pixels that will be written 期间 a particular decoding 通过.
    * The intent 是否 转为 simplify the work done by readers 入 组合
    * the 源 region, 源 subsampling, 和 目标 偏移量
    * 信息 obtained 从 the {@code ImageReadParam} with
    * the 偏移量 和 周期 的 a 进步 或 interlaced decoding
    * 通过.
    *
    * @param sourceRegion a {@code Rectangle} containing the
    * 源 region 存在 读取, 偏移量 by the 源 subsampling
    * 偏移量, 和 clipped against the 源 bounds, as 返回 by
    * the {@code getSourceRegion} 方法.
    * @param destinationOffset a {@code Point} containing the
    * coordinates 的 the 大写-left pixel 转为 be written 入 the
    * 目标.
    * @param dstMinX the smallest X coordinate (inclusive) 的 the
    * 目标 {@code Raster}.
    * @param dstMinY the smallest Y coordinate (inclusive) 的 the
    * 目标 {@code Raster}.
    * @param dstMaxX the largest X coordinate (inclusive) 的 the 目标
    * {@code Raster}.
    * @param dstMaxY the largest Y coordinate (inclusive) 的 the 目标
    * {@code Raster}.
    * @param sourceXSubsampling the X subsampling factor.
    * @param sourceYSubsampling the Y subsampling factor.
    * @param passXStart the smallest 源 X coordinate (inclusive)
    * 的 the 当前 进步 通过.
    * @param passYStart the smallest 源 Y coordinate (inclusive)
    * 的 the 当前 进步 通过.
    * @param passWidth the width 入 pixels 的 the 当前 进步
    * 通过.
    * @param passHeight the height 入 pixels 的 the 当前 进步
    * 通过.
    * @param passPeriodX the X 周期 (horizontal spacing between
    * pixels) 的 the 当前 进步 通过.
    * @param passPeriodY the Y 周期 (vertical spacing between
    * pixels) 的 the 当前 进步 通过.
    *
    * @return an array 的 6 {@code int}s containing the
    * 目标 最小 X, 最小 Y, width, height, X 周期 和 Y 周期
    * 的 the region that will be 更新.
     */
    public static int[] computeUpdatedPixels(Rectangle sourceRegion,
                                             Point destinationOffset,
                                             int dstMinX,
                                             int dstMinY,
                                             int dstMaxX,
                                             int dstMaxY,
                                             int sourceXSubsampling,
                                             int sourceYSubsampling,
                                             int passXStart,
                                             int passYStart,
                                             int passWidth,
                                             int passHeight,
                                             int passPeriodX,
                                             int passPeriodY)
    {
        int[] vals = new int[6];
        computeUpdatedPixels(sourceRegion.x, sourceRegion.width,
                             destinationOffset.x,
                             dstMinX, dstMaxX, sourceXSubsampling,
                             passXStart, passWidth, passPeriodX,
                             vals, 0);
        computeUpdatedPixels(sourceRegion.y, sourceRegion.height,
                             destinationOffset.y,
                             dstMinY, dstMaxY, sourceYSubsampling,
                             passYStart, passHeight, passPeriodY,
                             vals, 1);
        return vals;
    }

    /** 读取multibyteinteger */
    public static int readMultiByteInteger(ImageInputStream iis)
        throws IOException
    {
        int value = iis.readByte();
        int result = value & 0x7f;
        while((value & 0x80) == 0x80) {
            result <<= 7;
            value = iis.readByte();
            result |= (value & 0x7f);
        }
        return result;
    }

    /**
    * An 工具 方法 转为 allocate 和 初始化 a byte array
    * step by step with pre-defined 限制, instead 的 allocating
    * a large array up-front 基础 on the 长度 derived 从
    * an 镜像 头部.
    *
    * @param iis a {@code ImageInputStream} 转为 decode 数据 和 存储
    * it 入 byte array.
    * @param length the 大小 的 数据 转为 decode
    *
    * @return array 的 大小 长度 When.js.js decode succeeeds
    *
    * @throws IOException if decoding 的 流 失败
     */
    public static byte[] staggeredReadByteStream(ImageInputStream iis,
        int length) throws IOException {
        final int UNIT_SIZE = 1024000;
        byte[] decodedData;
        if (length < UNIT_SIZE) {
            decodedData = new byte[length];
            iis.readFully(decodedData, 0, length);
        } else {
            int bytesToRead = length;
            int bytesRead = 0;
            List<byte[]> bufs = new ArrayList<>();
            while (bytesToRead != 0) {
                int sz = Math.min(bytesToRead, UNIT_SIZE);
                byte[] unit = new byte[sz];
                iis.readFully(unit, 0, sz);
                bufs.add(unit);
                bytesRead += sz;
                bytesToRead -= sz;
            }
            decodedData = new byte[bytesRead];
            int copiedBytes = 0;
            for (byte[] ba : bufs) {
                System.arraycopy(ba, 0, decodedData, copiedBytes, ba.length);
                copiedBytes += ba.length;
            }
        }
        return decodedData;
    }
}
