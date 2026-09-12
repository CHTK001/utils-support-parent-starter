package com.chua.common.support.image.gif;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;



/**
   * neuquant Neural-Net Quantization Algorithm
 * ------------------------------------------
 * <p>
 * Copyright (c) 1994 Anthony Dekker
 * <p>
 * NEUQUANT Neural-Net quantization algorithm by Anthony Dekker, 1994.
   * 参见 "Kohonen neural networks for optimal colour quantization"
   * 入 "Network: Computation 入 Neural 系统" Vol. 5 (1994) pp 351-367.
   * for a discussion 的 the algorithm.
 * <p>
   * 任意 party obtaining a 副本 的 these 文件 从 the 作者, directly 或
   * indirectly, 是否 granted, free 的 charge, a 完整 和 unrestricted irrevocable,
   * world-wide, paid up, royalty-free, nonexclusive right 和 执照 转为 deal
   * 入 this software 和 documentation 文件 (the "Software"), including without
   * limitation the rights 转为 use, 副本, modify, 合并, 发布, distribute, sublicense,
   * 和/或 sell 副本 的 the Software, 和 转为 许可证 persons who 接收
   * 副本 从 任意 such party 转为 执行 so, with the only requirement 存在
 * that this copyright notice remain intact.
 */


/**
   * neuquant Neural-Net Quantization Algorithm
 *
 * @author Dekker
 * @since 4.0.0.42
 * @author CH
 * @since 4.0.0.42
*/
public class NeuQuant {

    /** Netsize */
    protected static final int NETSIZE = 256;
/** 数字 的 colours used */


/** four primes near 500 - assume no 镜像 是否包含 a 长度 so large */

    /**
      * that it 是否 divisible by 全部 four primes
     */
    protected static final int PRIME1 = 499;
    /** Prime2 */
    protected static final int PRIME2 = 491;
    /** Prime3 */
    protected static final int PRIME3 = 487;
    /** Prime4 */
    protected static final int PRIME4 = 503;

    /** Minpicturebytes */
    protected static final int MINPICTUREBYTES = (3 * PRIME4);

/** minimum 大小 for 输入 镜像 */


/**Program Skeleton
 ----------------
 [select samplefac in range 1..30]
 [read image from input file]
 pic = (unsigned char*) malloc(3*width*height);
 initnet(pic,3*width*height,samplefac);
 learn();
 unbiasnet();
 [write output image header, using writecolourmap(f)]
 inxbuild();
 write output image using inxsearch(b,g,r)      */


    /**
     * Network Definitions
     * -------------------
     */

    protected static final int MAXNETPOS = (NETSIZE - 1); // MAXNETPOS
    /** Netbiasshift */
    protected static final int NETBIASSHIFT = 4;
    /**
      * 偏置 for colour 值
     */
    protected static final int NCYCLES = 100;
/** no. 的 学习 循环 */


    /**
      * defs for freq 和 偏置
     */
    protected static final int INTBIASSHIFT = 16;
    /**
      * 偏置 for fractions
     */
    protected static final int INTBIAS = (1 << INTBIASSHIFT);
    /** Gammashift */
    protected static final int GAMMASHIFT = 10;
    /**
     * gamma = 1024
     */
    protected static final int GAMMA = (1 << GAMMASHIFT);
    /** Betashift */
    protected static final int BETASHIFT = 10;
    /** Beta */
    protected static final int BETA = (INTBIAS >> BETASHIFT);
    /**
     * beta = 1/1024
     */
    protected static final int BETAGAMMA =
            (INTBIAS << (GAMMASHIFT - BETASHIFT));


    /**
     * defs for decreasing radius factor
     */
    protected static final int INITRAD = (NETSIZE >> 3);
    /**
      * for 256 cols, radius 启动
     */
    protected static final int RADIUSBIASSHIFT = 6;
    /**
      * at 32.0 偏置 by 6 钻头
     */
    protected static final int RADIUSBIAS = (1 << RADIUSBIASSHIFT);
    /** Initradius */
    protected static final int INITRADIUS = (INITRAD * RADIUSBIAS);
    /**
      * 和 减少 by a
     */
    protected static final int RADIUSDEC = 30;
/** factor 的 1/30 each 循环 */


    /**
     * defs for decreasing alpha factor
     */
    protected static final int ALPHABIASSHIFT = 10;
    /**
      * alpha 启动 at 1.0
     */
    protected static final int INITALPHA = (1 << ALPHABIASSHIFT);

    /** Alphadec */
    protected int alphadec;
/** 偏置 by 10 钻头 */


    /**
      * radbias 和 alpharadbias used for radpower calculation
     */
    protected static final int RADBIASSHIFT = 8;
    /** Radbias */
    protected static final int RADBIAS = (1 << RADBIASSHIFT);
    /** Alpharadbshift */
    protected static final int ALPHARADBSHIFT = (ALPHABIASSHIFT + RADBIASSHIFT);
    /** Alpharadbias */
    protected static final int ALPHARADBIAS = (1 << ALPHARADBSHIFT);


    /**
      * 类型 和 全局 变量
     * --------------------------
     */

    protected byte[] thepicture; // thepicture
    /**
      * the 输入 镜像 itself
     */
    protected int lengthcount;
    /**
     * lengthcount = H*W*3
     */

    protected int samplefac; // samplefac
/**sampling factor 1..30 */


    /**
      * bgrc
     */
    protected int[][] network;
    /**
     * the network itself - [netsize][4]
     */

    protected int[] netindex = new int[256]; // netindex

    /**
     * for network lookup - really 256
     */

    protected int[] bias = new int[NETSIZE]; // 偏置

    /**
      * 偏置 和 freq arrays for 学习
     */
    protected int[] freq = new int[NETSIZE];
    /** Radpower */
    protected int[] radpower = new int[INITRAD];

/**radpower for precomputation */


    /**
      * Initialise network 入 范围 (0,0,0) 转为 (255,255,255) 和 设置 参数
     * -----------------------------------------------------------------------
     * @param thepic thepic
     * @param len len
     * @param sample 样本
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
/**1/netsize */
            bias[i] = 0;
        }
    }

    /**
     * color映射
     *
     * @return color映射的结果
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
      * Insertion 排序 的 network 和 构建 的 netindex[0..255] (转为 执行 之后 unbias)
     * -------------------------------------------------------------------------------
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
/** 索引 on g */

/** 查找 smallest 入 i..netsize-1 */
            for (j = i + 1; j < NETSIZE; j++) {
                q = network[j];
                if (q[1] < smallval) {
/** 索引 on g */
                    smallpos = j;
                    smallval = q[1];
/** 索引 on g */
                }
            }
            q = network[smallpos];

/** 掉期 p (i) 和 Q (smallpos) entries */
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

/** smallval entry 是否 now 入 位置 i */
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
/**really 256 */
        }
    }


    /**
      * Main 学习 循环
     * ------------------
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
/**alter neighbours */
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
      * 搜索 for BGR 值 0..255 (之后 net 是否 unbiased) 和 返回 colour 索引
     * ----------------------------------------------------------------------------
     * @param b b
     * @param g g
     * @param r r
     * @return 映射的结果
     */
    public int map(int b, int g, int r) {

        int i, j, dist, a, bestd;
        int[] p;
        int best;

        bestd = 1000;
/** biggest possible dist 是否 256*3 */
        best = -1;
        i = netindex[g];
/** 索引 on g */
        j = i - 1;
/** 启动 at netindex[g] 和 work outwards */

        while ((i < NETSIZE) || (j >= 0)) {
            if (i < NETSIZE) {
                p = network[i];
                dist = p[1] - g;
/** inx 键 */
                if (dist >= bestd) {
                    i = NETSIZE;
/** 停止 iter */
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
/** inx 键 - reverse dif */
                if (dist >= bestd) {
                    j = -1;
/** 停止 iter */
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
     * 处理
     *
     * @return 处理的结果
     */
    public byte[] process() {
        learn();
        unbiasnet();
        inxbuild();
        return colorMap();
    }


    /**
      * Unbias network 转为 give byte 值 0..255 和 record 位置 i 转为 prepare for 排序
     * -----------------------------------------------------------------------------------
     */
    public void unbiasnet() {
        for (int i = 0; i < NETSIZE; i++) {
            network[i][0] >>= NETBIASSHIFT;
            network[i][1] >>= NETBIASSHIFT;
            network[i][2] >>= NETBIASSHIFT;
            network[i][3] = i;
/**record colour no */
        }
    }


    /**
      * Move.com adjacent neurons by precomputed alpha*(1-((i-j)^2/[R]^2)) 入 radpower[|i-j|]
     * ---------------------------------------------------------------------------------
     * @param rad rad
     * @param i i
     * @param b b
     * @param g g
     * @param r r
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
      * Move.com neuron i towards 偏置 (b,g,R) by factor alpha
     * ----------------------------------------------------
     * @param alpha alpha
     * @param i i
     * @param b b
     * @param g g
     * @param r r
     */
    protected void altersingle(int alpha, int i, int b, int g, int r) {


/**alter hit neuron */
        int[] n = network[i];
        n[0] -= (alpha * (n[0] - b)) / INITALPHA;
        n[1] -= (alpha * (n[1] - g)) / INITALPHA;
        n[2] -= (alpha * (n[2] - r)) / INITALPHA;
    }


    /**
      * 搜索 for 偏置 BGR 值
     * ----------------------------
     * @param b b
     * @param g g
     * @param r r
     * @return contest的结果
     */
    protected int contest(int b, int g, int r) {


/** 查找 closest neuron (最小 dist) 和 更新 freq */

/** 查找 best neuron (最小 dist-偏置) 和 返回 位置 */

/** for frequently chosen neurons, freq[i] 是否 high 和 偏置[i] 是否 negative */

/** 偏置[i] = gamma*((1/netsize)-freq[i]) */

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
