package com.chua.common.support.image.gif;

import java.io.IOException;
import java.io.OutputStream;


/**
 * lzw
 *
 * @author K Weiner 12/00
 * @author CH
*/
class LzwEncoder {

    private static final int EOF = -1;

    private final int imgW;
    private final int imgH;
    private final byte[] pixAry;
    private final int initCodeSize;

    private int remaining;
    private int curPixel;


    static final int BITS = 12;
    static final int HSIZE = 5003;
    int nBits;
    int maxbits = BITS;
    int maxcode;
    int maxmaxcode = 1 << BITS;

    int[] htab = new int[HSIZE];
    int[] codetab = new int[HSIZE];

    int hsize = HSIZE;

    int freeEnt = 0;

    boolean clearFlg = false;

    int gInitBits;

    int clearCode;
    int eofCode;
    int curAccum = 0;
    int curBits = 0;

    final int[] masks =
            {
                    0x0000,
                    0x0001,
                    0x0003,
                    0x0007,
                    0x000F,
                    0x001F,
                    0x003F,
                    0x007F,
                    0x00FF,
                    0x01FF,
                    0x03FF,
                    0x07FF,
                    0x0FFF,
                    0x1FFF,
                    0x3FFF,
                    0x7FFF,
                    0xFFFF};


    int aCount;
    byte[] accum = new byte[256];


    LzwEncoder(int width, int height, byte[] pixels, int colorDepth) {
        imgW = width;
        imgH = height;
        pixAry = pixels;
        initCodeSize = Math.max(2, colorDepth);
    }


    void charOut(byte c, OutputStream outs) throws IOException {
        accum[aCount++] = c;
        int s254 = 254;
        if (aCount >= s254) {
            flushChar(outs);
        }
    }


    void clBlock(OutputStream outs) throws IOException {
        clHash(hsize);
        freeEnt = clearCode + 2;
        clearFlg = true;

        output(clearCode, outs);
    }


    void clHash(int hsize) {
        for (int i = 0; i < hsize; ++i) {
            htab[i] = -1;
        }
    }

    void compress(int initBits, OutputStream outs) throws IOException {
        int fcode;
        int i /* = 0 */;
        int c;
        int ent;
        int disp;
        int hsizeReg;
        int hshift;
        gInitBits = initBits;
        clearFlg = false;
        nBits = gInitBits;
        maxcode = maxcode(nBits);

        clearCode = 1 << (initBits - 1);
        eofCode = clearCode + 1;
        freeEnt = clearCode + 2;

        aCount = 0;

        ent = nextPixel();

        hshift = 0;
        int s65536 = 65536, s2 = 2;
        for (fcode = hsize; fcode < s65536; fcode *= s2) {
            ++hshift;
        }
        hshift = 8 - hshift;


        hsizeReg = hsize;
        clHash(hsizeReg);

        output(clearCode, outs);

        outer_loop:
        while ((c = nextPixel()) != EOF) {
            fcode = (c << maxbits) + ent;
            i = (c << hshift) ^ ent;

            if (htab[i] == fcode) {
                ent = codetab[i];
                continue;
            } else if (htab[i] >= 0) {
                disp = hsizeReg - i;
                if (i == 0) {
                    disp = 1;
                }
                do {
                    if ((i -= disp) < 0) {
                        i += hsizeReg;
                    }

                    if (htab[i] == fcode) {
                        ent = codetab[i];
                        continue outer_loop;
                    }
                } while (htab[i] >= 0);
            }
            output(ent, outs);
            ent = c;
            if (freeEnt < maxmaxcode) {
                codetab[i] = freeEnt++;
                htab[i] = fcode;
            } else {
                clBlock(outs);
            }
        }

        output(ent, outs);
        output(eofCode, outs);
    }


    void encode(OutputStream os) throws IOException {
        os.write(initCodeSize);

        remaining = imgW * imgH;
        curPixel = 0;

        compress(initCodeSize + 1, os);

        os.write(0);
    }


    void flushChar(OutputStream outs) throws IOException {
        if (aCount > 0) {
            outs.write(aCount);
            outs.write(accum, 0, aCount);
            aCount = 0;
        }
    }

    final int maxcode(int nBits) {
        return (1 << nBits) - 1;
    }


    private int nextPixel() {
        if (remaining == 0) {
            return EOF;
        }

        --remaining;

        byte pix = pixAry[curPixel++];

        return pix & 0xff;
    }

    void output(int code, OutputStream outs) throws IOException {
        curAccum &= masks[curBits];

        if (curBits > 0) {
            curAccum |= (code << curBits);
        } else {
            curAccum = code;
        }

        curBits += nBits;

        int s8 = 8;
        while (curBits >= s8) {
            charOut((byte) (curAccum & 0xff), outs);
            curAccum >>= 8;
            curBits -= 8;
        }

        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                maxcode = maxcode(nBits = gInitBits);
                clearFlg = false;
            } else {
                ++nBits;
                if (nBits == maxbits) {
                    maxcode = maxmaxcode;
                } else {
                    maxcode = maxcode(nBits);
                }
            }
        }

        if (code == eofCode) {

            while (curBits > 0) {
                charOut((byte) (curAccum & 0xff), outs);
                curAccum >>= 8;
                curBits -= 8;
            }

            flushChar(outs);
        }
    }
}
