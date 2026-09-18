package com.chua.common.support.image.png;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.ImageOutputStreamImpl;
import java.awt.*;
import java.awt.image.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
* PNG 图像写入器（支持静态 PNG 与 APNG 多帧序列），使用 JDK javax.imageio 镜像writer SPI 注册。
* <p>
* 默认压缩级别 {@value #DEFAULT_COMPRESSION_LEVEL}（中等）。
* 内部通过 {@link RowFilter} 实时行过滤，通过 {@link ChunkStream} 同步累积 CRC。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
public final class PNGImageWriter extends ImageWriter {
    /**
    * 默认hotp生成器 compression 级别 = 4 ie medium compression
    */
    private static final int DEFAULT_COMPRESSION_LEVEL = 4;

    ImageOutputStream stream = null; // 流

    PNGMetadata metadata = null; // metadata

    /**
    * Whether a sequence 是否 存在 written.
    */
    private boolean isWritingSequence = false;

    /**
    * Whether the 头部 是否包含 been written.
    */
    private boolean wroteSequenceHeader = false;

    /**
    * The 索引 的 the 镜像 存在 written.
    */
    private int imageIndex = 0;

    /**
    * The 索引 的 当前 sequence 数字.
    */
    private int nextSequenceNumber = 0;

 // Factors 从 the 镜像写入参数
    int sourceXOffset = 0;
    int sourceYOffset = 0; // 源y偏移量
    int sourceWidth = 0; // 源width
    int sourceHeight = 0; // 源height
    int[] sourceBands = null; // 源bands
    int periodX = 1; // 周期x
    int periodY = 1; // 周期y

    int numBands; // numbands
    int bpp; // bpp

    RowFilter rowFilter = new RowFilter(); // row过滤器
    byte[] prevRow = null; // prevrow
    byte[] currRow = null; // currrow
    byte[][] filteredRows = null; // 过滤器rows

    // Per-band scaling tables
    //
 // 首次调用 initializeScaleTables 后，scale 和 scale0
 // 或 scaleh 和 scalel 将有效，但不会同时有效。
    //
 // 这些表将用于配合由 sampleSize 给定的输入位深，
 // 以及由 scalingBitDepth 给定的输出位深。
    //
 // 每个波段的样本大小（以位为单位）
    // ;
    int[] sampleSize = null;
 // 缩放表的输出位深
    // ;
    int scalingBitDepth = 0;

 // 1、2、4 或 8 位输出的缩放表
 // 8 位表
    // ;
    byte[][] scale = null;
 // equivalent 转为 scale[0]
    // ;
    byte[] scale0 = null;

 // 16 位输出的缩放表
 // High bytes 的 输出
    // ;
    byte[][] scaleh = null;
 // Low bytes 的 输出
    // ;
    byte[][] scalel = null;

 // Total 数字 的 pixels 转为 be written by 写入_IDAT 和 写入_fdat
    // ;
    int totalPixels = 0;
 // Running 数量 的 pixels written by 写入_IDAT 和 写入_fdat
    // ;
    int pixelsDone = 0;

    /**
    * 创建 png镜像writer 实例
    * @param originatingProvider originating提供者
    */
    public PNGImageWriter(ImageWriterSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    /**
    * 设置输出
    * @param output 输出
    */
    public void setOutput(Object output) {
        super.setOutput(output);
        if (output != null) {
            if (!(output instanceof ImageOutputStream)) {
                throw new IllegalArgumentException("output not an ImageOutputStream!");
            }
            this.stream = (ImageOutputStream)output;
        } else {
            this.stream = null;
        }
    }

    @Override
    /** 获取默认写入参数 */
    public ImageWriteParam getDefaultWriteParam() {
        
        return new PNGImageWriteParam(getLocale());
    
    }

    @Override
    /**
    * 获取默认流式输出Metadata
    * @param param 参数
    */
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        
        return null;
    
    }

    @Override
    /**
    * 获取默认镜像metadata
    * @param imageType 镜像类型
    * @param param 参数
    */
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType,
                                               ImageWriteParam param) {
        PNGMetadata m = new PNGMetadata();
        m.initialize(imageType, imageType.getSampleModel().getNumBands());
        return m;
    }

    @Override
    /**
    * 转换流式输出Metadata
    * @param inData 入数据
    * @param param 参数
    */
    public IIOMetadata convertStreamMetadata(IIOMetadata inData,
                                             ImageWriteParam param) {
        
        return null;
    
    }

    @Override
    /**
    * 转换镜像metadata
    * @param inData 入数据
    * @param imageType 镜像类型
    * @param param 参数
    */
    public IIOMetadata convertImageMetadata(IIOMetadata inData,
                                            ImageTypeSpecifier imageType,
                                            ImageWriteParam param) {
        if (inData instanceof PNGMetadata) {
            return (PNGMetadata)((PNGMetadata)inData).clone();
        } else {
            return new PNGMetadata(inData);
        }
    }

    /** 写入魔法 */
    private void write_magic() throws IOException {
 // 写入 签名
        byte[] magic = { (byte)137, 80, 78, 71, 13, 10, 26, 10 };
        stream.write(magic);
    }

    /** 写入IHDR */
    private void write_IHDR() throws IOException {
 // 写入 IHDR chunk
        ChunkStream cs = new ChunkStream(PNGImageReader.IHDR_TYPE, stream);
        cs.writeInt(metadata.IHDR_width);
        cs.writeInt(metadata.IHDR_height);
        cs.writeByte(metadata.IHDR_bitDepth);
        cs.writeByte(metadata.IHDR_colorType);
        if (metadata.IHDR_compressionMethod != 0) {
            throw new IIOException(
"Only compression method 0 is defined in PNG 1.1");
        }
        cs.writeByte(metadata.IHDR_compressionMethod);
        if (metadata.IHDR_filterMethod != 0) {
            throw new IIOException(
"Only filter method 0 is defined in PNG 1.1");
        }
        cs.writeByte(metadata.IHDR_filterMethod);
        if (metadata.IHDR_interlaceMethod < 0 ||
            metadata.IHDR_interlaceMethod > 1) {
            throw new IIOException(
"Only interlace methods 0 (node) and 1 (adam7) are defined in PNG 1.1");
        }
        cs.writeByte(metadata.IHDR_interlaceMethod);
        cs.finish();
    }

    /** 写入chrm */
    private void write_cHRM() throws IOException {
        if (metadata.cHRM_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.cHRM_TYPE, stream);
            cs.writeInt(metadata.cHRM_whitePointX);
            cs.writeInt(metadata.cHRM_whitePointY);
            cs.writeInt(metadata.cHRM_redX);
            cs.writeInt(metadata.cHRM_redY);
            cs.writeInt(metadata.cHRM_greenX);
            cs.writeInt(metadata.cHRM_greenY);
            cs.writeInt(metadata.cHRM_blueX);
            cs.writeInt(metadata.cHRM_blueY);
            cs.finish();
        }
    }

    /** 写入cicp */
    private void write_cICP() throws IOException {
        if (metadata.cICP_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.cICP_TYPE, stream);
            cs.writeByte(metadata.cICP_colourPrimaries);
            cs.writeByte(metadata.cICP_transferFunction);
            cs.writeByte(metadata.cICP_matrixCoefficients);
            cs.writeBoolean(metadata.cICP_videoFullRangeFlag);
            cs.finish();
        }
    }

    /** 写入gama */
    private void write_gAMA() throws IOException {
        if (metadata.gAMA_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.gAMA_TYPE, stream);
            cs.writeInt(metadata.gAMA_gamma);
            cs.finish();
        }
    }

    /** 写入iccp */
    private void write_iCCP() throws IOException {
        if (metadata.iCCP_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.iCCP_TYPE, stream);
            if (metadata.iCCP_profileName.length() > 79) {
                throw new IIOException("iCCP profile name is longer than 79");
            }
            cs.writeBytes(metadata.iCCP_profileName);
 // 空 Terminator
            cs.writeByte(0);

            cs.writeByte(metadata.iCCP_compressionMethod);
            cs.write(metadata.iCCP_compressedProfile);
            cs.finish();
        }
    }

    /** 写入 sBIT */
    private void write_sBIT() throws IOException {
        if (metadata.sBIT_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.sBIT_TYPE, stream);
            int colorType = metadata.IHDR_colorType;
            if (metadata.sBIT_colorType != colorType) {
                processWarningOccurred(0,
"sBIT metadata has wrong color type.\n" +
"The chunk will not be written.");
                return;
            }

            if (colorType == PNG.PNG_COLOR_GRAY ||
                colorType == PNG.PNG_COLOR_GRAY_ALPHA) {
                cs.writeByte(metadata.sBIT_grayBits);
            } else if (colorType == PNG.PNG_COLOR_RGB ||
                       colorType == PNG.PNG_COLOR_PALETTE ||
                       colorType == PNG.PNG_COLOR_RGB_ALPHA) {
                cs.writeByte(metadata.sBIT_redBits);
                cs.writeByte(metadata.sBIT_greenBits);
                cs.writeByte(metadata.sBIT_blueBits);
            }

            if (colorType == PNG.PNG_COLOR_GRAY_ALPHA ||
                colorType == PNG.PNG_COLOR_RGB_ALPHA) {
                cs.writeByte(metadata.sBIT_alphaBits);
            }
            cs.finish();
        }
    }

    /** 写入srgb */
    private void write_sRGB() throws IOException {
        if (metadata.sRGB_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.sRGB_TYPE, stream);
            cs.writeByte(metadata.sRGB_renderingIntent);
            cs.finish();
        }
    }

    /** 写入PLTE */
    private void write_PLTE() throws IOException {
        if (metadata.PLTE_present) {
            if (metadata.IHDR_colorType == PNG.PNG_COLOR_GRAY ||
              metadata.IHDR_colorType == PNG.PNG_COLOR_GRAY_ALPHA) {
 // PLTE cannot occur 入 a gray 镜像

                processWarningOccurred(0,
"A PLTE chunk may not appear in a gray or gray alpha image.\n" +
"The chunk will not be written");
                return;
            }

            ChunkStream cs = new ChunkStream(PNGImageReader.PLTE_TYPE, stream);

            int numEntries = metadata.PLTE_red.length;
            byte[] palette = new byte[numEntries*3];
            int index = 0;
            for (int i = 0; i < numEntries; i++) {
                palette[index++] = metadata.PLTE_red[i];
                palette[index++] = metadata.PLTE_green[i];
                palette[index++] = metadata.PLTE_blue[i];
            }

            cs.write(palette);
            cs.finish();
        }
    }

    /** 写入hist */
    private void write_hIST() throws IOException {
        if (metadata.hIST_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.hIST_TYPE, stream);

            if (!metadata.PLTE_present) {
                throw new IIOException("hIST chunk without PLTE chunk!");
            }

            cs.writeChars(metadata.hIST_histogram,
                          0, metadata.hIST_histogram.length);
            cs.finish();
        }
    }

    /** 写入trns */
    private void write_tRNS() throws IOException {
        if (metadata.tRNS_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.tRNS_TYPE, stream);
            int colorType = metadata.IHDR_colorType;
            int chunkType = metadata.tRNS_colorType;

 // Special 大小写: 镜像 是否 RGB 和 chunk 是否 Gray
 // Promote chunk 内容 转为 RGB
            int chunkRed = metadata.tRNS_red;
            int chunkGreen = metadata.tRNS_green;
            int chunkBlue = metadata.tRNS_blue;
            if (colorType == PNG.PNG_COLOR_RGB &&
                chunkType == PNG.PNG_COLOR_GRAY) {
                chunkType = colorType;
                chunkRed = chunkGreen = chunkBlue =
                    metadata.tRNS_gray;
            }

            if (chunkType != colorType) {
                processWarningOccurred(0,
"tRNS metadata has incompatible color type.\n" +
"The chunk will not be written.");
                return;
            }

            if (colorType == PNG.PNG_COLOR_PALETTE) {
                if (!metadata.PLTE_present) {
                    throw new IIOException("tRNS chunk without PLTE chunk!");
                }
                cs.write(metadata.tRNS_alpha);
            } else if (colorType == PNG.PNG_COLOR_GRAY) {
                cs.writeShort(metadata.tRNS_gray);
            } else if (colorType == PNG.PNG_COLOR_RGB) {
                cs.writeShort(chunkRed);
                cs.writeShort(chunkGreen);
                cs.writeShort(chunkBlue);
            } else {
                throw new IIOException("tRNS chunk for color type 4 or 6!");
            }
            cs.finish();
        }
    }

    /** 写入bkgd */
    private void write_bKGD() throws IOException {
        if (metadata.bKGD_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.bKGD_TYPE, stream);
            int colorType = metadata.IHDR_colorType & 0x3;
            int chunkType = metadata.bKGD_colorType;

            int chunkRed = metadata.bKGD_red;
            int chunkGreen = metadata.bKGD_green;
            int chunkBlue = metadata.bKGD_blue;
            // 特殊情况：图像为 RGB(A) 而 chunk 为 Gray
 // 把 chunk 内容提升为 RGB
            if (colorType == PNG.PNG_COLOR_RGB &&
                chunkType == PNG.PNG_COLOR_GRAY) {
 // 让灰度的 bkgd chunk 表现得像 RGB
                chunkType = colorType;
                chunkRed = chunkGreen = chunkBlue =
                    metadata.bKGD_gray;
            }

 // 忽略与 color 类型不一致的 alpha
            if (chunkType != colorType) {
                processWarningOccurred(0,
"bKGD metadata has incompatible color type.\n" +
"The chunk will not be written.");
                return;
            }

            if (colorType == PNG.PNG_COLOR_PALETTE) {
                cs.writeByte(metadata.bKGD_index);
            } else if (colorType == PNG.PNG_COLOR_GRAY ||
                       colorType == PNG.PNG_COLOR_GRAY_ALPHA) {
                cs.writeShort(metadata.bKGD_gray);
            // } else { // colorType == PNG.PNG_COLOR_RGB ||
         
                     // colorType == PNG.PNG_COLOR_RGB_ALPHA
                cs.writeShort(chunkRed);
                cs.writeShort(chunkGreen);
                cs.writeShort(chunkBlue);
            }
            cs.finish();
        }
    }

    /** 写入phys */
    private void write_pHYs() throws IOException {
        if (metadata.pHYs_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.pHYs_TYPE, stream);
            cs.writeInt(metadata.pHYs_pixelsPerUnitXAxis);
            cs.writeInt(metadata.pHYs_pixelsPerUnitYAxis);
            cs.writeByte(metadata.pHYs_unitSpecifier);
            cs.finish();
        }
    }

    /** 写入splt */
    private void write_sPLT() throws IOException {
        if (metadata.sPLT_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.sPLT_TYPE, stream);

            if (metadata.sPLT_paletteName.length() > 79) {
                throw new IIOException("sPLT palette name is longer than 79");
            }
            cs.writeBytes(metadata.sPLT_paletteName);
 // 空 Terminator
            cs.writeByte(0);

            cs.writeByte(metadata.sPLT_sampleDepth);
            int numEntries = metadata.sPLT_red.length;

            if (metadata.sPLT_sampleDepth == 8) {
                for (int i = 0; i < numEntries; i++) {
                    cs.writeByte(metadata.sPLT_red[i]);
                    cs.writeByte(metadata.sPLT_green[i]);
                    cs.writeByte(metadata.sPLT_blue[i]);
                    cs.writeByte(metadata.sPLT_alpha[i]);
                    cs.writeShort(metadata.sPLT_frequency[i]);
                }
            // } else { // sampleDepth == 16
         
                for (int i = 0; i < numEntries; i++) {
                    cs.writeShort(metadata.sPLT_red[i]);
                    cs.writeShort(metadata.sPLT_green[i]);
                    cs.writeShort(metadata.sPLT_blue[i]);
                    cs.writeShort(metadata.sPLT_alpha[i]);
                    cs.writeShort(metadata.sPLT_frequency[i]);
                }
            }
            cs.finish();
        }
    }

    /** 写入时间 */
    private void write_tIME() throws IOException {
        if (metadata.tIME_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.tIME_TYPE, stream);
            cs.writeShort(metadata.tIME_year);
            cs.writeByte(metadata.tIME_month);
            cs.writeByte(metadata.tIME_day);
            cs.writeByte(metadata.tIME_hour);
            cs.writeByte(metadata.tIME_minute);
            cs.writeByte(metadata.tIME_second);
            cs.finish();
        }
    }

    /** 写入文本 */
    private void write_tEXt() throws IOException {
        Iterator<String> keywordIter = metadata.tEXt_keyword.iterator();
        Iterator<String> textIter = metadata.tEXt_text.iterator();

        while (keywordIter.hasNext()) {
            ChunkStream cs = new ChunkStream(PNGImageReader.tEXt_TYPE, stream);
            String keyword = keywordIter.next();
            if (keyword.length() > 79) {
                throw new IIOException("tEXt keyword is longer than 79");
            }
            cs.writeBytes(keyword);
            cs.writeByte(0);

            String text = textIter.next();
            cs.writeBytes(text);
            cs.finish();
        }
    }

    /**
    * Deflate
    * @param b b
    * @return deflate的结果
    */
    private byte[] deflate(byte[] b) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(baos);
        dos.write(b);
        dos.close();
        return baos.toByteArray();
    }

    /** 写入itxt */
    private void write_iTXt() throws IOException {
        Iterator<String> keywordIter = metadata.iTXt_keyword.iterator();
        Iterator<Boolean> flagIter = metadata.iTXt_compressionFlag.iterator();
        Iterator<Integer> methodIter = metadata.iTXt_compressionMethod.iterator();
        Iterator<String> languageIter = metadata.iTXt_languageTag.iterator();
        Iterator<String> translatedKeywordIter =
            metadata.iTXt_translatedKeyword.iterator();
        Iterator<String> textIter = metadata.iTXt_text.iterator();

        while (keywordIter.hasNext()) {
            ChunkStream cs = new ChunkStream(PNGImageReader.iTXt_TYPE, stream);

            String keyword = keywordIter.next();
            if (keyword.length() > 79) {
                throw new IIOException("iTXt keyword is longer than 79");
            }
            cs.writeBytes(keyword);
            cs.writeByte(0);

            Boolean compressed = flagIter.next();
            cs.writeByte(compressed ? 1 : 0);

            cs.writeByte(methodIter.next().intValue());

            cs.writeBytes(languageIter.next());
            cs.writeByte(0);


            cs.write(translatedKeywordIter.next().getBytes(StandardCharsets.UTF_8));
            cs.writeByte(0);

            String text = textIter.next();
            if (compressed) {
                cs.write(deflate(text.getBytes(StandardCharsets.UTF_8)));
            } else {
                cs.write(text.getBytes(StandardCharsets.UTF_8));
            }
            cs.finish();
        }
    }

    /** 写入ztxt */
    private void write_zTXt() throws IOException {
        Iterator<String> keywordIter = metadata.zTXt_keyword.iterator();
        Iterator<Integer> methodIter = metadata.zTXt_compressionMethod.iterator();
        Iterator<String> textIter = metadata.zTXt_text.iterator();

        while (keywordIter.hasNext()) {
            ChunkStream cs = new ChunkStream(PNGImageReader.zTXt_TYPE, stream);
            String keyword = keywordIter.next();
            if (keyword.length() > 79) {
                throw new IIOException("zTXt keyword is longer than 79");
            }
            cs.writeBytes(keyword);
            cs.writeByte(0);

            int compressionMethod = (methodIter.next()).intValue();
            cs.writeByte(compressionMethod);

            String text = textIter.next();
            cs.write(deflate(text.getBytes(StandardCharsets.ISO_8859_1)));
            cs.finish();
        }
    }

    /** 写入exif */
    private void write_eXIf() throws IOException {
        if (metadata.eXIf_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.eXIf_TYPE, stream);
            cs.write(metadata.eXIf_data);
            cs.finish();
        }
    }

     /**
    * 写入unknownchunks。
    */
    private void writeUnknownChunks() throws IOException {
        Iterator<String> typeIter = metadata.unknownChunkType.iterator();
        Iterator<byte[]> dataIter = metadata.unknownChunkData.iterator();

        while (typeIter.hasNext() && dataIter.hasNext()) {
            String type = typeIter.next();
            ChunkStream cs = new ChunkStream(chunkType(type), stream);
            byte[] data = dataIter.next();
            cs.write(data);
            cs.finish();
        }
    }

    /**
     * 分块类型。
     *
     * @param typeString 类型字符串，不允许为 null
     * @return 结果数值
     */
    private static int chunkType(String typeString) {
        char c0 = typeString.charAt(0);
        char c1 = typeString.charAt(1);
        char c2 = typeString.charAt(2);
        char c3 = typeString.charAt(3);

        int type = (c0 << 24) | (c1 << 16) | (c2 << 8) | c3;
        return type;
    }

    /**
    * 编码通过
    * @param os os
    * @param image 镜像
    * @param xOffset x偏移量
    * @param yOffset y偏移量
    * @param xSkip x跳过
    * @param ySkip y跳过
    */
    private void encodePass(ImageOutputStream os,
                            RenderedImage image,
                            int xOffset, int yOffset,
                            int xSkip, int ySkip) throws IOException {
        int minX = sourceXOffset;
        int minY = sourceYOffset;
        int width = sourceWidth;
        int height = sourceHeight;

 // 根据源子采样因子调整偏移量和跳过
        xOffset *= periodX;
        xSkip *= periodX;
        yOffset *= periodY;
        ySkip *= periodY;

 // Early exit if no 数据 for this 通过
        int hpixels = (width - xOffset + xSkip - 1)/xSkip;
        int vpixels = (height - yOffset + ySkip - 1)/ySkip;
        if (hpixels == 0 || vpixels == 0) {
            return;
        }

 // 将 X 偏移量和跳过从像素转换为样本
        xOffset *= numBands;
        xSkip *= numBands;

 // 创建 row 缓冲
        int samplesPerByte = 8/metadata.IHDR_bitDepth;
        int numSamples = width*numBands;
        int[] samples = new int[numSamples];

        int bytesPerRow = hpixels*numBands;
        if (metadata.IHDR_bitDepth < 8) {
            bytesPerRow = (bytesPerRow + samplesPerByte - 1)/samplesPerByte;
        } else if (metadata.IHDR_bitDepth == 16) {
            bytesPerRow *= 2;
        }

        IndexColorModel icm_gray_alpha = null;
        if (metadata.IHDR_colorType == PNG.PNG_COLOR_GRAY_ALPHA &&
            image.getColorModel() instanceof IndexColorModel) {
            {
            // reserve space for alpha 样本
            bytesPerRow *= 2;
            // will be used 转为 calculate alpha 值 for the pixel
            icm_gray_alpha = (IndexColorModel)image.getColorModel();
            }
            currRow = new byte[bytesPerRow + bpp];
        }
        prevRow = new byte[bytesPerRow + bpp];
        filteredRows = new byte[5][bytesPerRow + bpp];

        int bitDepth = metadata.IHDR_bitDepth;
        for (int row = minY + yOffset; row < minY + height; row += ySkip) {
            Rectangle rect = new Rectangle(minX, row, width, 1);
            Raster ras = image.getData(rect);
            if (sourceBands != null) {
                ras = ras.createChild(minX, row, width, 1, minX, row,
                                      sourceBands);
            }

            ras.getPixels(minX, row, width, 1, samples);

            if (image.getColorModel().isAlphaPremultiplied()) {
                WritableRaster wr = ras.createCompatibleWritableRaster();
                wr.setPixels(wr.getMinX(), wr.getMinY(),
                             wr.getWidth(), wr.getHeight(),
                             samples);

                image.getColorModel().coerceData(wr, false);
                wr.getPixels(wr.getMinX(), wr.getMinY(),
                             wr.getWidth(), wr.getHeight(),
                             samples);
            }

 // Reorder palette 数据 if necessary
            int[] paletteOrder = metadata.PLTE_order;
            if (paletteOrder != null) {
                for (int i = 0; i < numSamples; i++) {
                    samples[i] = paletteOrder[samples[i]];
                }
            }

            // leave first 'bpp' bytes zero
            // count = bpp;
            int count = bpp;
            int pos = 0;
            int tmp = 0;

            switch (bitDepth) {
            case 1: case 2: case 4:
 // 镜像 能否 only have a 单个 band

                int mask = samplesPerByte - 1;
                for (int s = xOffset; s < numSamples; s += xSkip) {
                    byte val = scale0[samples[s]];
                    tmp = (tmp << bitDepth) | val;

                    if ((pos++ & mask) == mask) {
                        currRow[count++] = (byte)tmp;
                        tmp = 0;
                        pos = 0;
                    }
                }

 // Left Shift the 最后一个 byte
                if ((pos & mask) != 0) {
                    tmp <<= ((8/bitDepth) - pos)*bitDepth;
                    currRow[count++] = (byte)tmp;
                }
                break;

            case 8:
                if (numBands == 1) {
                    for (int s = xOffset; s < numSamples; s += xSkip) {
                        currRow[count++] = scale0[samples[s]];
                        if (icm_gray_alpha != null) {
                            currRow[count++] =
                                scale0[icm_gray_alpha.getAlpha(0xff & samples[s])];
                        }
                    }
                } else {
                    for (int s = xOffset; s < numSamples; s += xSkip) {
                        for (int b = 0; b < numBands; b++) {
                            currRow[count++] = scale[b][samples[s + b]];
                        }
                    }
                }
                break;

            case 16:
                for (int s = xOffset; s < numSamples; s += xSkip) {
                    for (int b = 0; b < numBands; b++) {
                        currRow[count++] = scaleh[b][samples[s + b]];
                        currRow[count++] = scalel[b][samples[s + b]];
                    }
                }
                break;
            }

 // 执行 过滤器
            int filterType = rowFilter.filterRow(metadata.IHDR_colorType,
                                                 currRow, prevRow,
                                                 filteredRows,
                                                 bytesPerRow, bpp);

            os.write(filterType);
            os.write(filteredRows[filterType], bpp, bytesPerRow);

 // 掉期 当前 和 上一个 rows
            byte[] swap = currRow;
            currRow = prevRow;
            prevRow = swap;

            pixelsDone += hpixels;
            processImageProgress(100.0F*pixelsDone/totalPixels);

            if (abortRequested()) {
                processWriteAborted();
                return;
            }
        }
    }

 // 使用源 x 偏移量等
    /**
    * 写入IDAT
    * @param image 镜像
    * @param deflaterLevel deflater级别
    */
    private void write_IDAT(RenderedImage image, int deflaterLevel)
        throws IOException
    {
        PNGIDATOutputStream ios = new PNGIDATOutputStream(stream, 32768,
                                                    deflaterLevel);
        try {
            if (metadata.IHDR_interlaceMethod == 1) {
                for (int i = 0; i < 7; i++) {
                    encodePass(ios, image,
                               PNGImageReader.adam7XOffset[i],
                               PNGImageReader.adam7YOffset[i],
                               PNGImageReader.adam7XSubsampling[i],
                               PNGImageReader.adam7YSubsampling[i]);
                    if (abortRequested()) {
                        break;
                    }
                }
            } else {
                encodePass(ios, image, 0, 0, 1, 1);
            }
        } finally {
            ios.finish();
        }
    }

    /** 写入IEND */
    private void write_IEND() throws IOException {
        ChunkStream cs = new ChunkStream(PNGImageReader.IEND_TYPE, stream);
        cs.finish();
    }

 // 检查 two int arrays for 值 equality, always 返回 false
 // if either array 是否 空
    /**
    * 判断相等
    * @param s0 s0
    * @param s1 s1
    * @return equals的结果
    */
    private boolean equals(int[] s0, int[] s1) {
        if (s0 == null || s1 == null) {
            return false;
        }
        if (s0.length != s1.length) {
            return false;
        }
        for (int i = 0; i < s0.length; i++) {
            if (s0[i] != s1[i]) {
                return false;
            }
        }
        return true;
    }

 // 初始化 scale/scale0 或 scaleh/scalel 数组，
 // 用于将输入值缩放到所需的输出位深
    /**
    * 初始化scaletables
    * @param sampleSize 样本大小
    */
    private void initializeScaleTables(int[] sampleSize) {
        int bitDepth = metadata.IHDR_bitDepth;

 // If the existing tables are still valid, just 返回
        if (bitDepth == scalingBitDepth &&
            equals(sampleSize, this.sampleSize)) {
            return;
        }

 // Compute 新 tables
        this.sampleSize = sampleSize;
        this.scalingBitDepth = bitDepth;
        int maxOutSample = (1 << bitDepth) - 1;
        if (bitDepth <= 8) {
            scale = new byte[numBands][];
            for (int b = 0; b < numBands; b++) {
                int maxInSample = (1 << sampleSize[b]) - 1;
                int halfMaxInSample = maxInSample/2;
                scale[b] = new byte[maxInSample + 1];
                for (int s = 0; s <= maxInSample; s++) {
                    scale[b][s] =
                        (byte)((s*maxOutSample + halfMaxInSample)/maxInSample);
                }
            }
            scale0 = scale[0];
            scaleh = scalel = null;
        // lse { // bitDepth == 16
        }
 // Divide scaling table into high 和 low bytes
            scaleh = new byte[numBands][];
            scalel = new byte[numBands][];

            for (int b = 0; b < numBands; b++) {
                int maxInSample = (1 << sampleSize[b]) - 1;
                int halfMaxInSample = maxInSample/2;
                scaleh[b] = new byte[maxInSample + 1];
                scalel[b] = new byte[maxInSample + 1];
                for (int s = 0; s <= maxInSample; s++) {
                    int val = (s*maxOutSample + halfMaxInSample)/maxInSample;
                    scaleh[b][s] = (byte)(val >> 8);
                    scalel[b][s] = (byte)(val & 0xff);
                }
            }
            scale = null;
            scale0 = null;
    }

    @Override
    /**
    * 写入
    * @param streamMetadata 流metadata
    * @param image 镜像
    * @param param 参数
    */
    public void write(IIOMetadata streamMetadata,
                      IIOImage image,
                      ImageWriteParam param) throws IIOException {

        if (stream == null) {
            throw new IllegalStateException("output == null!");
        }
        if (image == null) {
            throw new IllegalArgumentException("image == null!");
        }
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("image has a Raster!");
        }

        try {
            prepareWriteSequence(streamMetadata);
            writeToSequence0(image, param, false);
            endWriteSequence();
        } catch (IOException e) {
            throw new IIOException("I/O error writing PNG file!", e);
        }
    }

    /** 写入actl */
    private void write_acTL() throws IOException {
        if (metadata.acTL_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.acTL_TYPE, stream);
            cs.writeInt(metadata.acTL_num_frames);
            cs.writeInt(metadata.acTL_num_plays);
            cs.finish();
        }
    }

    /**
    * 写入fdat
    * @param image 镜像
    * @param deflaterLevel deflater级别
    * @param currentSequence 当前sequence
    */
    private void write_fdAT(RenderedImage image, int deflaterLevel, int currentSequence)
            throws IOException
    {
        PNGfdATOutputStream fos = new PNGfdATOutputStream(stream, 32768,
                deflaterLevel, currentSequence);
        try {
            if (metadata.IHDR_interlaceMethod == 1) {
                for (int i = 0; i < 7; i++) {
                    encodePass(fos, image,
                            PNGImageReader.adam7XOffset[i],
                            PNGImageReader.adam7YOffset[i],
                            PNGImageReader.adam7XSubsampling[i],
                            PNGImageReader.adam7YSubsampling[i]);
                    if (abortRequested()) {
                        break;
                    }
                }
            } else {
                encodePass(fos, image, 0, 0, 1, 1);
            }
        } finally {
            fos.finish();
            nextSequenceNumber = fos.sequenceNumber;
        }
    }

    /**
    * 写入 fcTL
    * @param metadata metadata
    */
    private void write_fcTL(PNGMetadata metadata) throws IOException {
        if (metadata.fcTL_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.fcTL_TYPE, stream);
            cs.writeInt(metadata.fcTL_sequence_number);
            cs.writeInt(metadata.fcTL_width);
            cs.writeInt(metadata.fcTL_height);
            cs.writeInt(metadata.fcTL_x_offset);
            cs.writeInt(metadata.fcTL_y_offset);
            cs.writeShort(metadata.fcTL_delay_num);
            cs.writeShort(metadata.fcTL_delay_den);
            cs.writeByte(metadata.fcTL_dispose_op);
            cs.writeByte(metadata.fcTL_blend_op);
            cs.finish();
        }
    }

    @Override
    /** 是否可以写入Sequence */
    public boolean canWriteSequence() {
        
        return true;
    
    }

    @Override
    /**
    * Prepare写入Sequence
    * @param streamMetadata 流metadata
    */
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IOException {

        if (stream == null) {
            throw new IllegalStateException("Output is not set.");
        }

        resetStreamSettings();

        this.isWritingSequence = true;
    }

    /**
    * 写入转为sequence
    * @param image 镜像
    * @param param 参数
    * @param acTL_present actl_present
    */
    private void writeToSequence0(IIOImage image, ImageWriteParam param, boolean acTL_present) throws IOException {

        if (stream == null) {
            throw new IllegalStateException("output == null!");
        }
        if (image == null) {
            throw new IllegalArgumentException("image == null!");
        }
        if (image.hasRaster()) {
            throw new UnsupportedOperationException("image has a Raster!");
        }
        if (!isWritingSequence) {
            throw new IllegalStateException("prepareWriteSequence() was not invoked!");
        }

        RenderedImage im = image.getRenderedImage();
        SampleModel sampleModel = im.getSampleModel();
        this.numBands = sampleModel.getNumBands();

 // 设置 源 region 和 subsampling 转为 默认 值
        this.sourceXOffset = im.getMinX();
        this.sourceYOffset = im.getMinY();
        this.sourceWidth = im.getWidth();
        this.sourceHeight = im.getHeight();
        this.sourceBands = null;
        this.periodX = 1;
        this.periodY = 1;

        if (param != null) {

 // 获取 源 region 和 subsampling factors
            Rectangle sourceRegion = param.getSourceRegion();
            if (sourceRegion != null) {
                Rectangle imageBounds = new Rectangle(im.getMinX(),
                        im.getMinY(),
                        im.getWidth(),
                        im.getHeight());
 // Clip 转为 actual 镜像 bounds
                sourceRegion = sourceRegion.intersection(imageBounds);
                sourceXOffset = sourceRegion.x;
                sourceYOffset = sourceRegion.y;
                sourceWidth = sourceRegion.width;
                sourceHeight = sourceRegion.height;
            }

 // 针对子采样偏移量进行调整
            int gridX = param.getSubsamplingXOffset();
            int gridY = param.getSubsamplingYOffset();
            sourceXOffset += gridX;
            sourceYOffset += gridY;
            sourceWidth -= gridX;
            sourceHeight -= gridY;

 // 获取 subsampling factors
            periodX = param.getSourceXSubsampling();
            periodY = param.getSourceYSubsampling();

            int[] sBands = param.getSourceBands();
            if (sBands != null) {
                sourceBands = sBands;
                numBands = sourceBands.length;
            }
        }

 // Compute 输出 维度
        int destWidth = (sourceWidth + periodX - 1)/periodX;
        int destHeight = (sourceHeight + periodY - 1)/periodY;
        if (destWidth <= 0 || destHeight <= 0) {
            throw new IllegalArgumentException("Empty source region!");
        }

 // Compute total 数字 的 pixels for 进步 notification
        this.totalPixels = destWidth*destHeight;
        this.pixelsDone = 0;

 // 创建 metadata
        if (metadata == null) {
            IIOMetadata imd = image.getMetadata();
            if (imd != null) {
                metadata = (PNGMetadata) convertImageMetadata(imd,
                        ImageTypeSpecifier.createFromRenderedImage(im),
                        null);
            } else {
                metadata = new PNGMetadata();
            }
            metadata.acTL_present = acTL_present;
        }


 // reset compression 级别 转为 默认:
        int deflaterLevel = DEFAULT_COMPRESSION_LEVEL;

        if (param != null) {
            switch(param.getCompressionMode()) {
                case ImageWriteParam.MODE_DISABLED:
                    deflaterLevel = Deflater.NO_COMPRESSION;
                    break;
                case ImageWriteParam.MODE_EXPLICIT:
                    float quality = param.getCompressionQuality();
                    if (quality >= 0f && quality <= 1f) {
                        deflaterLevel = 9 - Math.round(9f * quality);
                    }
                    break;
                default:
            }

 // Use Adam7 interlacing if 设置 入 写入 参数
            switch (param.getProgressiveMode()) {
                case ImageWriteParam.MODE_DEFAULT:
                    metadata.IHDR_interlaceMethod = 1;
                    break;
                case ImageWriteParam.MODE_DISABLED:
                    metadata.IHDR_interlaceMethod = 0;
                    break;
 // MODE_副本_从_METADATA should already be taken care 的
 // MODE_EXPLICIT 是否 not allowed
                default:
            }
        }

 // 初始化位深度和颜色类型
        metadata.initialize(new ImageTypeSpecifier(im), numBands);

 // Overwrite IHDR width 和 height 值 with 值 从 镜像
        metadata.IHDR_width = destWidth;
        metadata.IHDR_height = destHeight;

        this.bpp = numBands*((metadata.IHDR_bitDepth == 16) ? 2 : 1);

 // 初始化 scaling tables for this 镜像
        initializeScaleTables(sampleModel.getSampleSize());

        clearAbortRequest();

        processImageStarted(imageIndex);
        if (abortRequested()) {
            processWriteAborted();
        } else {
            if (wroteSequenceHeader) {
                PNGMetadata metadata;
 // 创建 metadata
                IIOMetadata imd = image.getMetadata();
                if (imd != null) {
                    metadata = (PNGMetadata) convertImageMetadata(imd,
                            ImageTypeSpecifier.createFromRenderedImage(im),
                            null);
                } else {
                    metadata = new PNGMetadata();
                }
                metadata.fcTL_present = true;
                metadata.fcTL_sequence_number = nextSequenceNumber;
                metadata.fcTL_width = im.getWidth();
                metadata.fcTL_height = im.getHeight();
                nextSequenceNumber ++;
                metadata.fdAT_present = true;
                metadata.fdAT_sequence_number = nextSequenceNumber;
                write_fcTL(metadata);
                write_fdAT(im, deflaterLevel, metadata.fdAT_sequence_number);

                imageIndex ++;
            } else {
                write_magic();
                write_IHDR();
                write_acTL();

                write_cHRM();
                write_cICP();
                write_gAMA();
                write_iCCP();
                write_sBIT();
                write_sRGB();

                write_PLTE();

                write_hIST();
                write_tRNS();
                write_bKGD();

                write_pHYs();
                write_sPLT();
                write_tIME();
                write_tEXt();
                write_iTXt();
                write_zTXt();
                write_eXIf();

                writeUnknownChunks();

                if (param != null && ((PNGImageWriteParam) param).isAnimContainsIDAT()) {
                    PNGMetadata metadata;
 // 创建 metadata
                    IIOMetadata imd = image.getMetadata();
                    if (imd != null) {
                        metadata = (PNGMetadata) convertImageMetadata(imd,
                                ImageTypeSpecifier.createFromRenderedImage(im),
                                null);
                    } else {
                        metadata = new PNGMetadata();
                    }
                    metadata.fcTL_present = true;
                    metadata.fcTL_sequence_number = nextSequenceNumber;
                    metadata.fcTL_width = im.getWidth();
                    metadata.fcTL_height = im.getHeight();
                    nextSequenceNumber ++;
                    write_fcTL(metadata);
                    write_IDAT(im, deflaterLevel);

                    imageIndex ++;
                } else {
                    write_IDAT(im, deflaterLevel);
                }

                wroteSequenceHeader = true;
            }
        }
    }

    @Override
    /**
    * 写入转为sequence
    * @param image 镜像
    * @param param 参数
    */
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IOException {
        writeToSequence0(image, param, true);
    }

    @Override
    /** 结束写入Sequence */
    public void endWriteSequence() throws IOException {
        if (stream == null) {
            throw new IllegalStateException("output == null!");
        }
        if (!isWritingSequence) {
            throw new IllegalStateException("prepareWriteSequence() was not invoked!");
        }
 // 饰面 up 和 inform the 监听器 we are done
        write_IEND();
        processImageComplete();
        resetStreamSettings();
    }

    @Override
    /** Reset */
    public void reset() {
        super.reset();
        resetStreamSettings();
    }

    /** Reset流式输出Settings */
    private void resetStreamSettings() {
        this.isWritingSequence = false;
        this.wroteSequenceHeader = false;
        this.metadata = null;
        this.imageIndex = 0;
        this.nextSequenceNumber = 0;
    }
}


