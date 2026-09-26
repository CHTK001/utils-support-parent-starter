package com.chua.common.support.image.gif;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 基于 Anthony Dekker 神经网络的颜色量化实现。
 *
 * <p>算法通过 Kohonen 神经网络将图像颜色压缩为最多 256 种颜色，
 * 适用于 GIF 调色板生成等场景。</p>
 *
 * @author Dekker
 * @author CH
 * @since 4.0.0.42
 */
public class NeuQuant {

    /**
     * 颜色网络大小。
     */
    protected static final int NETSIZE = 256;
    /**
     * 接近 500 且可整除常规图片总字节数的四个质数。
     */
    protected static final int PRIME1 = 499;
    /**
     * Prime2
    */
    protected static final int PRIME2 = 491;
    /**
     * Prime3
    */
    protected static final int PRIME3 = 487;
    /**
     * Prime4
    */
    protected static final int PRIME4 = 503;

    /**
     * 可供采样的最小图片字节数。
     */
    protected static final int MINPICTUREBYTES = (3 * PRIME4);

    /**
     * 网络定义。
     */
    protected static final int MAXNETPOS = (NETSIZE - 1);
    /**
     * 网络值偏置位数。
     */
    protected static final int NETBIASSHIFT = 4;
    /**
     * 网络学习循环次数。
     */
    protected static final int NCYCLES = 100;
    /**
     * 频率统计使用的偏置位数。
     */
    protected static final int INTBIASSHIFT = 16;
    /**
     * 频率统计偏置基数。
     */
    protected static final int INTBIAS = (1 << INTBIASSHIFT);
    /**
     * 伽马偏置位数。
     */
    protected static final int GAMMASHIFT = 10;
    /**
     * 伽马偏置基数。
     */
    protected static final int GAMMA = (1 << GAMMASHIFT);
    /**
     * 频率更新偏置位数。
     */
    protected static final int BETASHIFT = 10;
    /**
     * 频率更新基数。
     */
    protected static final int BETA = (INTBIAS >> BETASHIFT);
    /**
     * 频率更新偏置值。
     */
    protected static final int BETAGAMMA =
            (INTBIAS << (GAMMASHIFT - BETASHIFT));

    /**
     * 初始邻域半径。
     */
    protected static final int INITRAD = (NETSIZE >> 3);
    /**
     * 邻域半径偏置位数。
     */
    protected static final int RADIUSBIASSHIFT = 6;
    /**
     * 邻域半径偏置基数。
     */
    protected static final int RADIUSBIAS = (1 << RADIUSBIASSHIFT);
    /**
     * 初始邻域半径值。
     */
    protected static final int INITRADIUS = (INITRAD * RADIUSBIAS);
    /**
     * 邻域半径每轮缩减比例。
     */
    protected static final int RADIUSDEC = 30;

    /**
     * 学习率偏置位数。
     */
    protected static final int ALPHABIASSHIFT = 10;
    /**
     * 初始学习率。
     */
    protected static final int INITALPHA = (1 << ALPHABIASSHIFT);

    /**
     * 学习率衰减计数。
     */
    protected int alphadec;
    /**
     * 半径衰减偏置位数。
     */
    protected static final int RADBIASSHIFT = 8;
    /**
     * 半径衰减偏置基数。
     */
    protected static final int RADBIAS = (1 << RADBIASSHIFT);
    /**
     * 邻域衰减综合偏置位数。
     */
    protected static final int ALPHARADBSHIFT = (ALPHABIASSHIFT + RADBIASSHIFT);
    /**
     * 邻域衰减综合偏置值。
     */
    protected static final int ALPHARADBIAS = (1 << ALPHARADBSHIFT);


    /**
     * 待量化的图片数据。
     */
    protected byte[] thepicture;
    /**
     * 图片总字节数。
     */
    protected int lengthcount;

    /**
     * 图片采样步长。
     */
    protected int samplefac;

    /**
     * 颜色网络，每个节点包含蓝、绿、红和索引四个分量。
     */
    protected int[][] network;
    /**
     * 按绿色分量预计算的快速查找索引。
     */
    protected int[] netindex = new int[256];

    /**
     * 网络节点的偏置数组。
     */
    protected int[] bias = new int[NETSIZE];

    /**
     * 网络节点的选择频率数组。
     */
    protected int[] freq = new int[NETSIZE];
    /**
     * 邻域影响半径的预计算数组。
     */
    protected int[] radpower = new int[INITRAD];

    /**
     * 使用指定图片数据初始化颜色网络。
     *
     * @param thepic 待量化的图片数据
     * @param len 图片总字节数
     * @param sample 采样步长
     */
    public NeuQuant(byte[] thepic, int len, int sample) {

        int i;
        int[] p;

        thepicture = thepic;
        lengthcount = len;
        samplefac = sample;

        network = new int[NETSIZE][];
        for (i = 0; i < NETSIZE; i++) {
            network[i] = new int[4];
            p = network[i];
            p[0] = p[1] = p[2] = (i << (NETBIASSHIFT + 8)) / NETSIZE;
            freq[i] = INTBIAS / NETSIZE;
            // 每个节点的初始选择频率相同
            bias[i] = 0;
        }
    }

    /**
     * 生成颜色映射表。
     *
     * @return 每种颜色对应的蓝、绿、红三字节数据
     */
    public byte[] colorMap() {
        byte[] map = new byte[3 * NETSIZE];
        int[] index = new int[NETSIZE];
        for (int i = 0; i < NETSIZE; i++) {
            index[network[i][3]] = i;
        }
        int k = 0;
        for (int i = 0; i < NETSIZE; i++) {
            int j = index[i];
            map[k++] = (byte) (network[j][0]);
            map[k++] = (byte) (network[j][1]);
            map[k++] = (byte) (network[j][2]);
        }
        return map;
    }


    /**
     * 对颜色网络执行插入排序并构建快速查找索引。
     */
    public void inxbuild() {

        int i, j, smallpos, smallval;
        int[] p;
        int[] q;
        int previouscol, startpos;

        previouscol = 0;
        startpos = 0;
        for (i = 0; i < NETSIZE; i++) {
            p = network[i];
            smallpos = i;
            smallval = p[1];
/**
 * 以绿色分量定位相邻节点
*/

/**
 * 查找后续范围中绿色分量最小的节点
 */
            for (j = i + 1; j < NETSIZE; j++) {
                q = network[j];
                if (q[1] < smallval) {
/**
 * 以绿色分量定位相邻节点
*/
                    smallpos = j;
                    smallval = q[1];
/**
 * 以绿色分量定位相邻节点
*/
                }
            }
            q = network[smallpos];

/**
 * 交换两个网络节点的对应分量
 */
            if (i != smallpos) {
                j = q[0];
                q[0] = p[0];
                p[0] = j;
                j = q[1];
                q[1] = p[1];
                p[1] = j;
                j = q[2];
                q[2] = p[2];
                p[2] = j;
                j = q[3];
                q[3] = p[3];
                p[3] = j;
            }

/**
 * 记录最小绿色分量发生变化时的索引区间
 */
            if (smallval != previouscol) {
                netindex[previouscol] = (startpos + i) >> 1;
                for (j = previouscol + 1; j < smallval; j++) {
                    netindex[j] = i;
                }
                previouscol = smallval;
                startpos = i;
            }
        }
        int s256 = 256;
        netindex[previouscol] = (startpos + MAXNETPOS) >> 1;
        for (j = previouscol + 1; j < s256; j++) {
            netindex[j] = MAXNETPOS;
            // 补齐最后一段索引
        }
    }


    /**
     * 执行颜色网络主学习循环。
     */
    public void learn() {

        int i, j, b, g, r;
        int radius, rad, alpha, step, delta, samplepixels;
        byte[] p;
        int pix, lim;

        if (lengthcount < MINPICTUREBYTES) {
            samplefac = 1;
        }
        alphadec = 30 + ((samplefac - 1) / 3);
        p = thepicture;
        pix = 0;
        lim = lengthcount;
        samplepixels = lengthcount / (3 * samplefac);
        delta = samplepixels / NCYCLES;
        alpha = INITALPHA;
        radius = INITRADIUS;

        rad = radius >> RADIUSBIASSHIFT;
        for (i = 0; i < rad; i++) {
            radpower[i] =
                    alpha * (((rad * rad - i * i) * RADBIAS) / (rad * rad));
        }


        if (lengthcount < MINPICTUREBYTES) {
            step = 3;
        } else if ((lengthcount % PRIME1) != 0) {
            step = 3 * PRIME1;
        } else {
            if ((lengthcount % PRIME2) != 0) {
                step = 3 * PRIME2;
            } else {
                if ((lengthcount % PRIME3) != 0) {
                    step = 3 * PRIME3;
                } else {
                    step = 3 * PRIME4;
                }
            }
        }

        i = 0;
        while (i < samplepixels) {
            b = (p[pix] & 0xff) << NETBIASSHIFT;
            g = (p[pix + 1] & 0xff) << NETBIASSHIFT;
            r = (p[pix + 2] & 0xff) << NETBIASSHIFT;
            j = contest(b, g, r);

            altersingle(alpha, j, b, g, r);
            if (rad != 0) {
                alterneigh(rad, j, b, g, r);
                // 同时调整邻近节点
            }

            pix += step;
            if (pix >= lim) {
                pix -= lengthcount;
            }

            i++;
            if (delta == 0) {
                delta = 1;
            }
            if (i % delta == 0) {
                alpha -= alpha / alphadec;
                radius -= radius / RADIUSDEC;
                rad = radius >> RADIUSBIASSHIFT;
                if (rad <= 1) {
                    rad = 0;
                }
                for (j = 0; j < rad; j++) {
                    radpower[j] =
                            alpha * (((rad * rad - j * j) * RADBIAS) / (rad * rad));
                }
            }
        }

    }


    /**
     * 为给定蓝、绿、红分量查找最接近的颜色索引。
     *
     * @param b 蓝色分量，取值范围为 0 至 255
     * @param g 绿色分量，取值范围为 0 至 255
     * @param r 红色分量，取值范围为 0 至 255
     * @return 最接近的颜色索引
     */
    public int map(int b, int g, int r) {

        int i, j, dist, a, bestd;
        int[] p;
        int best;

        bestd = 1000;
        // 允许的最大颜色距离为 256 乘 3
        best = -1;
        i = netindex[g];
/**
 * 以绿色分量定位相邻节点
*/
        j = i - 1;
/**
 * 从绿色分量对应的索引开始向两侧查找
 */

        while ((i < NETSIZE) || (j >= 0)) {
            if (i < NETSIZE) {
                p = network[i];
                dist = p[1] - g;
/**
 * 查找正向距离
*/
                if (dist >= bestd) {
                    i = NETSIZE;
/**
 * 正向距离已超过当前最优值，停止当前方向查找
 */
                } else {
                    i++;
                    if (dist < 0) {
                        dist = -dist;
                    }
                    a = p[0] - b;
                    if (a < 0) {
                        a = -a;
                    }
                    dist += a;
                    if (dist < bestd) {
                        a = p[2] - r;
                        if (a < 0) {
                            a = -a;
                        }
                        dist += a;
                        if (dist < bestd) {
                            bestd = dist;
                            best = p[3];
                        }
                    }
                }
            }
            if (j >= 0) {
                p = network[j];
                dist = g - p[1];
/**
 * 查找反向距离
*/
                if (dist >= bestd) {
                    j = -1;
/**
 * 正向距离已超过当前最优值，停止当前方向查找
 */
                } else {
                    j--;
                    if (dist < 0) {
                        dist = -dist;
                    }
                    a = p[0] - b;
                    if (a < 0) {
                        a = -a;
                    }
                    dist += a;
                    if (dist < bestd) {
                        a = p[2] - r;
                        if (a < 0) {
                            a = -a;
                        }
                        dist += a;
                        if (dist < bestd) {
                            bestd = dist;
                            best = p[3];
                        }
                    }
                }
            }
        }
        return (best);
    }

    /**
     * 执行完整颜色量化流程。
     *
     * @return 量化后的颜色映射表
     */
    public byte[] process() {
        learn();
        unbiasnet();
        inxbuild();
        return colorMap();
    }


    /**
     * 移除网络偏置并记录节点对应的颜色索引。
     */
    public void unbiasnet() {
        for (int i = 0; i < NETSIZE; i++) {
            network[i][0] >>= NETBIASSHIFT;
            network[i][1] >>= NETBIASSHIFT;
            network[i][2] >>= NETBIASSHIFT;
            network[i][3] = i;
            // 记录当前颜色索引
        }
    }


    /**
     * 将相邻神经元按预计算的 alpha*(1-((i-j)^2/[R]^2)) 移入 radpower[|i-j|]
     * ---------------------------------------------------------------------------------
     * @param rad 邻域半径，不能为负
     * @param i 中心节点索引
     * @param b 蓝色分量
     * @param g 绿色分量
     * @param r 红色分量
     */
    protected void alterneigh(int rad, int i, int b, int g, int r) {

        int j, k, lo, hi, a, m;
        int[] p;

        lo = i - rad;
        if (lo < -1) {
            lo = -1;
        }
        hi = i + rad;
        if (hi > NETSIZE) {
            hi = NETSIZE;
        }

        j = i + 1;
        k = i - 1;
        m = 1;
        while ((j < hi) || (k > lo)) {
            a = radpower[m++];
            if (j < hi) {
                p = network[j++];
                try {
                    p[0] -= (a * (p[0] - b)) / ALPHARADBIAS;
                    p[1] -= (a * (p[1] - g)) / ALPHARADBIAS;
                    p[2] -= (a * (p[2] - r)) / ALPHARADBIAS;
                } catch (Exception ignored) {
                }
            }
            if (k > lo) {
                p = network[k--];
                try {
                    p[0] -= (a * (p[0] - b)) / ALPHARADBIAS;
                    p[1] -= (a * (p[1] - g)) / ALPHARADBIAS;
                    p[2] -= (a * (p[2] - r)) / ALPHARADBIAS;
                } catch (Exception ignored) {
                }
            }
        }
    }


    /**
     * 将神经元 i 以系数 alpha 向 (b,g,R) 移动
     * ----------------------------------------------------
     * @param alpha 学习系数
     * @param i 节点索引
     * @param b 蓝色分量
     * @param g 绿色分量
     * @param r 红色分量
     */
    protected void altersingle(int alpha, int i, int b, int g, int r) {


        // 调整当前命中的节点
        int[] n = network[i];
        n[0] -= (alpha * (n[0] - b)) / INITALPHA;
        n[1] -= (alpha * (n[1] - g)) / INITALPHA;
        n[2] -= (alpha * (n[2] - r)) / INITALPHA;
    }


    /**
     * 按距离和偏置查找最合适的颜色节点。
     *
     * @param b 蓝色分量
     * @param g 绿色分量
     * @param r 红色分量
     * @return 最合适的颜色节点索引
     */
    protected int contest(int b, int g, int r) {


/**
 * 查找颜色距离最小的节点并更新其选择频率
 */

/**
 * 查找扣除偏置后的最小距离节点并返回索引
 */

/**
 * 频繁选中的节点会提高频率并减小偏置
 */

/**
 * 偏置值为伽马乘以理论频率与实际频率之差
 */

        int i, dist, a, biasdist, betafreq;
        int bestpos, bestbiaspos, bestd, bestbiasd;
        int[] n;

        bestd = ~(1 << 31);
        bestbiasd = bestd;
        bestpos = -1;
        bestbiaspos = bestpos;

        for (i = 0; i < NETSIZE; i++) {
            n = network[i];
            dist = n[0] - b;
            if (dist < 0) {
                dist = -dist;
            }
            a = n[1] - g;
            if (a < 0) {
                a = -a;
            }
            dist += a;
            a = n[2] - r;
            if (a < 0) {
                a = -a;
            }
            dist += a;
            if (dist < bestd) {
                bestd = dist;
                bestpos = i;
            }
            biasdist = dist - ((bias[i]) >> (INTBIASSHIFT - NETBIASSHIFT));
            if (biasdist < bestbiasd) {
                bestbiasd = biasdist;
                bestbiaspos = i;
            }
            betafreq = (freq[i] >> BETASHIFT);
            freq[i] -= betafreq;
            bias[i] += (betafreq << GAMMASHIFT);
        }
        freq[bestpos] += BETA;
        bias[bestpos] -= BETAGAMMA;
        return (bestbiaspos);
    }
}
