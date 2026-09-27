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
     * 默认压缩级别 = 4 即中等压缩
     */
    private static final int DEFAULT_COMPRESSION_LEVEL = 4;

    ImageOutputStream stream = null; // 输出流

    PNGMetadata metadata = null; // 元数据

    /**
     * 是否已开始写入图像序列。
     */
    private boolean isWritingSequence = false;

    /**
     * 是否已写入序列头。
     */
    private boolean wroteSequenceHeader = false;

    /**
     * 当前正在写入的图像索引。
     */
    private int imageIndex = 0;

    /**
     * 当前序列号。
     */
    private int nextSequenceNumber = 0;

    // 来自写入参数的图像采样因子
    int sourceXOffset = 0;
    int sourceYOffset = 0; // 源 Y 偏移量
    int sourceWidth = 0; // 源宽度
    int sourceHeight = 0; // 源高度
    int[] sourceBands = null; // 源波段
    int periodX = 1; // X 方向周期
    int periodY = 1; // Y 方向周期

    int numBands; // 波段数
    int bpp; // 每像素字节数

    RowFilter rowFilter = new RowFilter(); // 行过滤器
    byte[] prevRow = null; // 上一行数据
    byte[] currRow = null; // 当前行数据
    byte[][] filteredRows = null; // 过滤后各行数据

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
    // 等价于 scale[0]
    // ;
    byte[] scale0 = null;

 // 16 位输出的缩放表
    // 高字节输出
    // ;
    byte[][] scaleh = null;
    // 低字节输出
    // ;
    byte[][] scalel = null;

    // 由 write_IDAT 和 write_fdat 写入的像素总数
    // ;
    int totalPixels = 0;
    // 由 write_IDAT 和 write_fdat 已写入的像素数
    // ;
    int pixelsDone = 0;

    /**
     * 创建 PNG 图像写出器实例。
     *
     * @param originatingProvider 触发本次创建的 SPI 提供者
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
    /**
     * 获取默认写入参数
    */
    public ImageWriteParam getDefaultWriteParam() {
        
        return new PNGImageWriteParam(getLocale());
    
    }

    @Override
    /**
     * 获取默认流式输出元数据
     *
     * @param param 写入参数
     */
    public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) {
        
        return null;
    
    }

    @Override
    /**
     * 获取默认图像元数据
     *
     * @param imageType 图像类型
     * @param param     参数
     * @return 按图像类型初始化好的 PNG 元数据
     */
    public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier imageType,
                                               ImageWriteParam param) {
        PNGMetadata m = new PNGMetadata();
        m.initialize(imageType, imageType.getSampleModel().getNumBands());
        return m;
    }

    @Override
    /**
     * 转换流式输出元数据
     *
     * @param inData 输入元数据
     * @param param  写入参数
     */
    public IIOMetadata convertStreamMetadata(IIOMetadata inData,
                                             ImageWriteParam param) {
        
        return null;
    
    }

    @Override
    /**
     * 转换图像元数据
     *
     * @param inData    输入元数据
     * @param imageType 图像类型
     * @param param     参数
     * @return 转换后的元数据
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

    /**
     * 写入魔法
    */
    private void write_magic() throws IOException {
        // write signature
        byte[] magic = { (byte)137, 80, 78, 71, 13, 10, 26, 10 };
        stream.write(magic);
    }

    /**
     * 写入IHDR
    */
    private void write_IHDR() throws IOException {
        // write IHDR chunk
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

    /**
     * 写入chrm
    */
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

    /**
     * 写入cicp
    */
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

    /**
     * 写入gama
    */
    private void write_gAMA() throws IOException {
        if (metadata.gAMA_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.gAMA_TYPE, stream);
            cs.writeInt(metadata.gAMA_gamma);
            cs.finish();
        }
    }

    /**
     * 写入iccp
    */
    private void write_iCCP() throws IOException {
        if (metadata.iCCP_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.iCCP_TYPE, stream);
            if (metadata.iCCP_profileName.length() > 79) {
                throw new IIOException("iCCP profile name is longer than 79");
            }
            cs.writeBytes(metadata.iCCP_profileName);
        // null Terminator
            cs.writeByte(0);

            cs.writeByte(metadata.iCCP_compressionMethod);
            cs.write(metadata.iCCP_compressedProfile);
            cs.finish();
        }
    }

    /**
     * 写入 sBIT
    */
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

    /**
     * 写入srgb
    */
    private void write_sRGB() throws IOException {
        if (metadata.sRGB_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.sRGB_TYPE, stream);
            cs.writeByte(metadata.sRGB_renderingIntent);
            cs.finish();
        }
    }

    /**
     * 写入PLTE
    */
    private void write_PLTE() throws IOException {
        if (metadata.PLTE_present) {
            if (metadata.IHDR_colorType == PNG.PNG_COLOR_GRAY ||
              metadata.IHDR_colorType == PNG.PNG_COLOR_GRAY_ALPHA) {
                // PLTE cannot occur in a gray image

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

    /**
     * 写入hist
    */
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

    /**
     * 写入trns
    */
    private void write_tRNS() throws IOException {
        if (metadata.tRNS_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.tRNS_TYPE, stream);
            int colorType = metadata.IHDR_colorType;
            int chunkType = metadata.tRNS_colorType;

            // Special case: image is RGB but chunk is Gray
            // Promote chunk contents to RGB
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

    /**
     * 写入bkgd
    */
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

    /**
     * 写入phys
    */
    private void write_pHYs() throws IOException {
        if (metadata.pHYs_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.pHYs_TYPE, stream);
            cs.writeInt(metadata.pHYs_pixelsPerUnitXAxis);
            cs.writeInt(metadata.pHYs_pixelsPerUnitYAxis);
            cs.writeByte(metadata.pHYs_unitSpecifier);
            cs.finish();
        }
    }

    /**
     * 写入splt
    */
    private void write_sPLT() throws IOException {
        if (metadata.sPLT_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.sPLT_TYPE, stream);

            if (metadata.sPLT_paletteName.length() > 79) {
                throw new IIOException("sPLT palette name is longer than 79");
            }
            cs.writeBytes(metadata.sPLT_paletteName);
        // null Terminator
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

    /**
     * 写入时间
    */
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

    /**
     * 写入文本
    */
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
     * 对字节数组做 deflate 压缩。
     *
     * @param b 待压缩数据
     * @return 压缩后的字节数组
     */
    private byte[] deflate(byte[] b) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DeflaterOutputStream dos = new DeflaterOutputStream(baos);
        dos.write(b);
        dos.close();
        return baos.toByteArray();
    }

    /**
     * 写入itxt
    */
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

    /**
     * 写入ztxt
    */
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

    /**
     * 写入exif
    */
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
     * @return 打包后的 4 字节块类型值
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
     * 编码一个扫描通道。
     *
     * @param os      输出流
     * @param image   源图像
     * @param xOffset X 偏移量
     * @param yOffset Y 偏移量
     * @param xSkip   X 方向步长
     * @param ySkip   Y 方向步长
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

        // Early exit if no data for this pass
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
            // reserve space for alpha samples
            bytesPerRow *= 2;
            // will be used to calculate alpha value for the pixel
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

            // Reorder palette data if necessary
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
                // image can only have a single band

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

                // Left Shift the last byte
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
     * 写入 IDAT 块
     *
     * @param image         源图像
     * @param deflaterLevel deflater 压缩级别
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

    /**
     * 写入IEND
    */
    private void write_IEND() throws IOException {
        ChunkStream cs = new ChunkStream(PNGImageReader.IEND_TYPE, stream);
        cs.finish();
    }

    // Check two int arrays for value equality, always returns false
    // if either array is null
    /**
     * 判断两个 {@code int} 数组是否相等。
     *
     * @param s0 第一个数组
     * @param s1 第二个数组
     * @return 两者非 {@code null} 且逐元素相同返回 {@code true}
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
     * 初始化缩放表
     *
     * @param sampleSize 各波段样本大小（位）
     */
    private void initializeScaleTables(int[] sampleSize) {
        int bitDepth = metadata.IHDR_bitDepth;

        // If the existing tables are still valid, just return
        if (bitDepth == scalingBitDepth &&
            equals(sampleSize, this.sampleSize)) {
            return;
        }

        // Compute new tables
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
            // Divide scaling table into high and low bytes
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
     * 写出单张图像。
     *
     * @param streamMetadata 流元数据
     * @param image           待写图像
     * @param param           写入参数
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

    /**
     * 写入actl
    */
    private void write_acTL() throws IOException {
        if (metadata.acTL_present) {
            ChunkStream cs = new ChunkStream(PNGImageReader.acTL_TYPE, stream);
            cs.writeInt(metadata.acTL_num_frames);
            cs.writeInt(metadata.acTL_num_plays);
            cs.finish();
        }
    }

    /**
     * 写入 fdAT 块
     *
     * @param image           源图像
     * @param deflaterLevel   deflater 压缩级别
     * @param currentSequence 当前序列号
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
    /**
     * 是否支持写入图像序列。
    */
    public boolean canWriteSequence() {
        
        return true;
    
    }

    @Override
    /**
     * 准备写入图像序列
     *
     * @param streamMetadata 流元数据
     */
    public void prepareWriteSequence(IIOMetadata streamMetadata) throws IOException {

        if (stream == null) {
            throw new IllegalStateException("Output is not set.");
        }

        resetStreamSettings();

        this.isWritingSequence = true;
    }

    /**
     * 写入图像序列中的一帧。
     *
     * @param image         待写图像
     * @param param         写入参数
     * @param acTL_present  是否写入 acTL 块
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

               // 初始化源区域与子采样为默认值
        this.sourceXOffset = im.getMinX();
        this.sourceYOffset = im.getMinY();
        this.sourceWidth = im.getWidth();
        this.sourceHeight = im.getHeight();
        this.sourceBands = null;
        this.periodX = 1;
        this.periodY = 1;

        if (param != null) {

            // 读取源区域与子采样因子
            Rectangle sourceRegion = param.getSourceRegion();
            if (sourceRegion != null) {
                Rectangle imageBounds = new Rectangle(im.getMinX(),
                        im.getMinY(),
                        im.getWidth(),
                        im.getHeight());
                // Clip to actual image bounds
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

            // 读取子采样因子
            periodX = param.getSourceXSubsampling();
            periodY = param.getSourceYSubsampling();

            int[] sBands = param.getSourceBands();
            if (sBands != null) {
                sourceBands = sBands;
                numBands = sourceBands.length;
            }
        }

        // Compute output dimensions
        int destWidth = (sourceWidth + periodX - 1)/periodX;
        int destHeight = (sourceHeight + periodY - 1)/periodY;
        if (destWidth <= 0 || destHeight <= 0) {
            throw new IllegalArgumentException("Empty source region!");
        }

        // Compute total number of pixels for progress notification
        this.totalPixels = destWidth*destHeight;
        this.pixelsDone = 0;

        // Create metadata
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


        // reset compression level to default:
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

            // Use Adam7 interlacing if set in write param
            switch (param.getProgressiveMode()) {
                case ImageWriteParam.MODE_DEFAULT:
                    metadata.IHDR_interlaceMethod = 1;
                    break;
                case ImageWriteParam.MODE_DISABLED:
                    metadata.IHDR_interlaceMethod = 0;
                    break;
                // MODE_COPY_FROM_METADATA should already be taken care of
                // MODE_EXPLICIT is not allowed
                default:
            }
        }

 // 初始化位深度和颜色类型
        metadata.initialize(new ImageTypeSpecifier(im), numBands);

        // Overwrite IHDR width and height values with values from image
        metadata.IHDR_width = destWidth;
        metadata.IHDR_height = destHeight;

        this.bpp = numBands*((metadata.IHDR_bitDepth == 16) ? 2 : 1);

        // Initialize scaling tables for this image
        initializeScaleTables(sampleModel.getSampleSize());

        clearAbortRequest();

        processImageStarted(imageIndex);
        if (abortRequested()) {
            processWriteAborted();
        } else {
            if (wroteSequenceHeader) {
                PNGMetadata metadata;
        // Create metadata
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
        // Create metadata
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
     * 向序列追加一帧。
     *
     * @param image 待写图像
     * @param param 写入参数
     */
    public void writeToSequence(IIOImage image, ImageWriteParam param) throws IOException {
        writeToSequence0(image, param, true);
    }

    @Override
    /**
     * 结束写入序列。
    */
    public void endWriteSequence() throws IOException {
        if (stream == null) {
            throw new IllegalStateException("output == null!");
        }
        if (!isWritingSequence) {
            throw new IllegalStateException("prepareWriteSequence() was not invoked!");
        }
        // 收尾并通知监听器写入完成
        write_IEND();
        processImageComplete();
        resetStreamSettings();
    }

    @Override
    /**
     * Reset
    */
    public void reset() {
        super.reset();
        resetStreamSettings();
    }

    /**
     * 重置流式输出设置
    */
    private void resetStreamSettings() {
        this.isWritingSequence = false;
        this.wroteSequenceHeader = false;
        this.metadata = null;
        this.imageIndex = 0;
        this.nextSequenceNumber = 0;
    }
}
