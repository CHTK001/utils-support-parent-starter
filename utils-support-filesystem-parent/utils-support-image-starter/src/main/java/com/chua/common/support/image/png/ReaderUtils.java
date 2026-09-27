package com.chua.common.support.image.png;

import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 本类收录若干对图像读取插件有实用价值的工具方法。理想情况下这些方法应放在
 * 图像读取的基础类中，以便所有子类都能直接复用；但那会构成对既有 API 的
 * 扩展，且这些方法是否普遍适用尚不明确，因此暂时先放在这里。
 *
 * @author CH
 * @since 4.0.0.42
 */
final class ReaderUtils {

    // 辅助 computeUpdatedPixels 方法
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
        // We need to satisfy the congruences:
        // dst = destinationOffset + (src - sourceOffset)/sourceSubsampling
        //
        // src - passStart == 0 (mod passPeriod)
        // src - sourceOffset == 0 (mod sourceSubsampling)
        //
        // Subject to the inequalities:
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

        // If we never saw a valid pixel, set width to 0
        if (!gotPixel) {
            vals[offset + 2] = 0;
        } else {
            vals[offset + 2] = lastDst - firstDst + 1;
        }

        // The period is given by the difference of any two adjacent pixels
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
     * 读取 PNG 多字节整数：每字节贡献 7 位，高字节在前，
     * 除最后一个字节外高位均为 1 作为续位标志。
     *
     * @param iis 流，从当前读取位置按 PNG 多字节整数编码取值
     * @return 解出的整数值
     * @throws IOException 流读取失败
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
     * 分段分配并初始化字节数组：按固定上限分批读取，
     * 而不是依据图像头推导出的长度一次性分配大数组。
     *
     * @param iis 待解码并写入字节数组的 {@code ImageInputStream}
     * @param length 待解码的数据长度
     *
     * @return 解码成功时返回的数组长度
     *
     * @throws IOException 解码过程中流读取失败
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
