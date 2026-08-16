package com.chua.common.support.image.png;

/**
 * 类RowFilter用于对PNG图像的行数据进行过滤处理，以优化压缩效率
 *
 * @author CH
 * @since 4.0.0.42
*/
final class RowFilter {

    /**
     * 计算整数的绝对值
     *
     * @param x 输入整数
     * @return 输入整数的绝对值
     */
    private static int abs(int x) {
        return (x < 0) ? -x : x;
    }

    /**
     * 使用sub过滤器处理行数据
     * sub过滤器将当前像素值与前一个像素值的差值存储到目标行
     *
     * @param currRow 当前行数据
     * @param subFilteredRow 存储过滤结果的目标行
     * @param bytesPerPixel 每个像素的字节数
     * @param bytesPerRow 每行的字节数
     * @return 返回绝对差值的总和
     */
    private static int subFilter(byte[] currRow,
                                 byte[] subFilteredRow,
                                 int bytesPerPixel,
                                 int bytesPerRow) {
        int badness = 0;
        for (int i = bytesPerPixel; i < bytesPerRow + bytesPerPixel; i++) {
            int curr = currRow[i] & 0xff;
            int left = currRow[i - bytesPerPixel] & 0xff;
            int difference = curr - left;
            subFilteredRow[i] = (byte)difference;

            badness += abs(difference);
        }

        return badness;
    }

    /**
     * 使用up过滤器处理行数据
     * up过滤器将当前像素值与上一行相同位置像素值的差值存储到目标行
     *
     * @param currRow 当前行数据
     * @param prevRow 上一行数据
     * @param upFilteredRow 存储过滤结果的目标行
     * @param bytesPerPixel 每个像素的字节数
     * @param bytesPerRow 每行的字节数
     * @return 返回绝对差值的总和
     */
    private static int upFilter(byte[] currRow,
                                byte[] prevRow,
                                byte[] upFilteredRow,
                                int bytesPerPixel,
                                int bytesPerRow) {
        int badness = 0;
        for (int i = bytesPerPixel; i < bytesPerRow + bytesPerPixel; i++) {
            int curr = currRow[i] & 0xff;
            int up = prevRow[i] & 0xff;
            int difference = curr - up;
            upFilteredRow[i] = (byte)difference;

            badness += abs(difference);
        }

        return badness;
    }

    /**
     * 使用Paeth预测器算法预测像素值
     *
     * @param a 左边像素值
     * @param b 上边像素值
     * @param c 左上角像素值
     * @return 预测的像素值
     */
    private int paethPredictor(int a, int b, int c) {
        int p = a + b - c;
        int pa = abs(p - a);
        int pb = abs(p - b);
        int pc = abs(p - c);

        if ((pa <= pb) && (pa <= pc)) {
            return a;
        } else if (pb <= pc) {
            return b;
        } else {
            return c;
        }
    }

    /**
     * 选择最优的行过滤方法
     * 根据每种过滤方法计算的“差值总和”来决定使用哪种过滤方法
     *
     * @param colorType 颜色类型
     * @param currRow 当前行数据
     * @param prevRow 上一行数据
     * @param scratchRows 用于存储临时过滤结果的数组
     * @param bytesPerRow 每行的字节数
     * @param bytesPerPixel 每个像素的字节数
     * @return 返回最优的过滤方法类型
     */
    public int filterRow(int colorType,
                         byte[] currRow,
                         byte[] prevRow,
                         byte[][] scratchRows,
                         int bytesPerRow,
                         int bytesPerPixel) {

        // Use type 0 for palette images
        if (colorType != PNG.PNG_COLOR_PALETTE) {
            System.arraycopy(currRow, bytesPerPixel,
                             scratchRows[0], bytesPerPixel,
                             bytesPerRow);
            return 0;
        }

        int[] filterBadness = new int[5];
        for (int i = 0; i < 5; i++) {
            filterBadness[i] = Integer.MAX_VALUE;
        }

        {
            int badness = 0;

            for (int i = bytesPerPixel; i < bytesPerRow + bytesPerPixel; i++) {
                int curr = currRow[i] & 0xff;
                badness += curr;
            }

            filterBadness[0] = badness;
        }

        {
            byte[] subFilteredRow = scratchRows[1];
            int badness = subFilter(currRow,
                                    subFilteredRow,
                                    bytesPerPixel,
                                    bytesPerRow);

            filterBadness[1] = badness;
        }

        {
            byte[] upFilteredRow = scratchRows[2];
            int badness = upFilter(currRow,
                                   prevRow,
                                   upFilteredRow,
                                   bytesPerPixel,
                                   bytesPerRow);

            filterBadness[2] = badness;
        }

        {
            byte[] averageFilteredRow = scratchRows[3];
            int badness = 0;

            for (int i = bytesPerPixel; i < bytesPerRow + bytesPerPixel; i++) {
                int curr = currRow[i] & 0xff;
                int left = currRow[i - bytesPerPixel] & 0xff;
                int up = prevRow[i] & 0xff;
                int difference = curr - (left + up)/2;
                averageFilteredRow[i] = (byte)difference;

                badness += abs(difference);
            }

            filterBadness[3] = badness;
        }

        {
            byte[] paethFilteredRow = scratchRows[4];
            int badness = 0;

            for (int i = bytesPerPixel; i < bytesPerRow + bytesPerPixel; i++) {
                int curr = currRow[i] & 0xff;
                int left = currRow[i - bytesPerPixel] & 0xff;
                int up = prevRow[i] & 0xff;
                int upleft = prevRow[i - bytesPerPixel] & 0xff;
                int predictor = paethPredictor(left, up, upleft);
                int difference = curr - predictor;
                paethFilteredRow[i] = (byte)difference;

                badness += abs(difference);
            }

            filterBadness[4] = badness;
        }

        int minBadness = filterBadness[0];
        int filterType = 0;

        for (int i = 1; i < 5; i++) {
            if (filterBadness[i] < minBadness) {
                minBadness = filterBadness[i];
                filterType = i;
            }
        }

        if (filterType == 0) {
            System.arraycopy(currRow, bytesPerPixel,
                             scratchRows[0], bytesPerPixel,
                             bytesPerRow);
        }

        return filterType;
    }
}
