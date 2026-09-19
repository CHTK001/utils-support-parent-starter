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
 // 尝试分析同余关系：若 passPeriod 与 sourceSubsampling
 // 互质，则周期为二者之积；若存在公因子，
 // 则周期等于较大值，或两个序列完全不相交，
        // 具体取决于它们之间的关系，
 // 即 passStart 与 sourceOffset 之间的关系。由于
        // 每幅图像只需处理两次（X 和 Y 各一次），
 // 因此用最直接的方式处理也已足够廉价。

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
     * 一个工具方法，用于计算在特定解码过程中将被写入的
     * 目标像素集合。其目的是简化读取器合并
     * 源区域、源子采样和目标偏移量信息的操作，
     * 这些信息从 {@code ImageReadParam} 中获得，并与
     * 渐进式或隔行解码过程中每一步的偏移量和周期
     * 相结合。
     *
     * @param sourceRegion 包含待读取源区域的 {@code Rectangle}，
     * 按源子采样偏移，并针对源边界裁剪，由
     * {@code getSourceRegion} 方法返回。
     * @param destinationOffset 包含目标区域左上角像素坐标的
     * {@code Point}。
     * @param dstMinX 目标 {@code Raster} 的最小 X 坐标（含）。
     * @param dstMinY 目标 {@code Raster} 的最小 Y 坐标（含）。
     * @param dstMaxX 目标 {@code Raster} 的最大 X 坐标（含）。
     * @param dstMaxY 目标 {@code Raster} 的最大 Y 坐标（含）。
     * @param sourceXSubsampling X 方向子采样因子。
     * @param sourceYSubsampling Y 方向子采样因子。
     * @param passXStart 当前解码步骤中最小的源 X 坐标（含）。
     * @param passYStart 当前解码步骤中最小的源 Y 坐标（含）。
     * @param passWidth 当前解码步骤以像素为单位的宽度。
     * @param passHeight 当前解码步骤以像素为单位的高度。
     * @param passPeriodX 当前解码步骤的 X 周期（像素间水平间距）。
     * @param passPeriodY 当前解码步骤的 Y 周期（像素间垂直间距）。
     *
     * @return 一个包含 6 个 {@code int} 的数组，表示将被更新区域的
     * 目标最小 X、最小 Y、宽度、高度、X 周期和 Y 周期。
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

    /**
     * 读取multibyteinteger
    */
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
