package com.chua.common.support.image.png;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import static com.chua.common.support.image.png.PNG.*;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
/**
 * class PNGImageDataEnumeration implements Enumeration<InputStream> {
 *
 * @author CH
 * @since 4.0.0.42
 */



class PNGImageDataEnumeration implements Enumeration<InputStream> {

    boolean firstTime = true;
    ImageInputStream stream;
    int length;
    final boolean fdAT;

    /**
     * 创建 PNGImageDataEnumeration 实例
     * @param stream stream
     * @param fdAT fdAT
     */
    public PNGImageDataEnumeration(ImageInputStream stream, boolean fdAT)
        throws IOException {
        this.stream = stream;
        this.length = stream.readInt();
        // skip chunk type
        stream.skipBytes(4);
        int type = stream.readInt();
        this.fdAT = fdAT;
        if (fdAT) {
            stream.readInt();
        }
    }

    @Override
    /** NextElement */
    public InputStream nextElement() {
        try {
            firstTime = false;
            ImageInputStream iis = new SubImageInputStream(stream, length - (fdAT ? 4 : 0));
            return new InputStreamAdapter(iis);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    /** 是否拥有MoreElements */
    public boolean hasMoreElements() {
        if (firstTime) {
            return true;
        }
        try {
            int crc = stream.readInt();
            this.length = stream.readInt();
            int type = stream.readInt();
            if (fdAT) {
                stream.readInt();
                return type == PNGImageReader.fdAT_TYPE;
            }
            else {
                return type == PNGImageReader.IDAT_TYPE;
            }
        } catch (IOException e) {
            return false;
        }
    }
}

/**
 * PNG 图像读取器。
 *
 * <p>基于 JDK Image I/O 框架实现的 PNG 格式图像读取器，
 * 支持标准 PNG 规范的各项特性，包括透明度、伽马校正、ICC 色彩配置等。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PNGImageReader extends ImageReader {

    /*
     * 注意：以下 chunk 类型常量是自动生成的，每个值均由其 4 字符名称的
     * ASCII 码推导而来。例如 IHDR_TYPE 的计算方式为：
     *            ('I' << 24) | ('H' << 16) | ('D' << 8) | 'R'
     */

    // Critical chunks
    static final int IHDR_TYPE = 0x49484452;
    static final int PLTE_TYPE = 0x504c5445;
    static final int IDAT_TYPE = 0x49444154;
    static final int IEND_TYPE = 0x49454e44;

    // Ancillary chunks
    static final int tRNS_TYPE = 0x74524e53;

    static final int cHRM_TYPE = 0x6348524d;
    static final int gAMA_TYPE = 0x67414d41;
    static final int iCCP_TYPE = 0x69434350;
    static final int sBIT_TYPE = 0x73424954;
    static final int sRGB_TYPE = 0x73524742;
    static final int cICP_TYPE = 0x63494350;

    static final int iTXt_TYPE = 0x69545874;
    static final int tEXt_TYPE = 0x74455874;
    static final int zTXt_TYPE = 0x7a545874;

    static final int bKGD_TYPE = 0x624b4744;
    static final int hIST_TYPE = 0x68495354;
    static final int pHYs_TYPE = 0x70485973;
    static final int sPLT_TYPE = 0x73504c54;
    static final int eXIf_TYPE = 0x65584966;

    static final int tIME_TYPE = 0x74494d45;
    static final int acTL_TYPE = 0x6163544C;
    static final int fcTL_TYPE = 0x6663544C;
    static final int fdAT_TYPE = 0x66644154;

    // PNG 颜色类型对应的 band 数量
    static final int[] inputBandsForColorType = {
         // 1, // gray
   
        // -1, // unused
    
         // 3, // rgb
   
         // 1, // palette
   
         // 2, // gray + alpha
   
        // -1, // unused
    
         // 4  // rgb + alpha
   
    };

    static final int[] adam7XOffset = { 0, 4, 0, 2, 0, 1, 0 };
    static final int[] adam7YOffset = { 0, 0, 4, 0, 2, 0, 1 };
    static final int[] adam7XSubsampling = { 8, 8, 4, 4, 2, 2, 1, 1 };
    static final int[] adam7YSubsampling = { 8, 8, 8, 4, 4, 2, 2, 1 };

    //private static final boolean debug = true;

    boolean isAnimated;

    ImageInputStream stream = null;

    boolean gotHeader = false;
    boolean gotMetadata = false;

    //ImageReadParam lastParam = null;

    long imageStartPosition = -1L;
    Map<Integer, PNGMetadata> frameMetadata = new HashMap<>();
    Map<Integer, Long> frameImageStartPositions = new HashMap<>();
    int nextImageIndex = 0;

    Rectangle sourceRegion = null;
    int sourceXSubsampling = -1;
    int sourceYSubsampling = -1;
    int sourceMinProgressivePass = 0;
    int sourceMaxProgressivePass = 6;
    int[] sourceBands = null;
    int[] destinationBands = null;
    Point destinationOffset = new Point(0, 0);

    PNGMetadata metadata = new PNGMetadata();

    DataInputStream pixelStream = null;

    BufferedImage theImage = null;

    // 已处理的源像素计数
    int pixelsDone = 0;

    // 源图像像素总数
    int totalPixels;

    boolean animContainsIDAT = false;

    /**
     * 创建 PNGImageReader 实例
     * @param originatingProvider originatingProvider
     */
    public PNGImageReader(ImageReaderSpi originatingProvider) {
        super(originatingProvider);
    }

    @Override
    /**
     * 设置Input
     * @param input input
     * @param seekForwardOnly seekForwardOnly
     * @param ignoreMetadata ignoreMetadata
     */
    public void setInput(Object input,
                         boolean seekForwardOnly,
                         boolean ignoreMetadata) {
        super.setInput(input, seekForwardOnly, ignoreMetadata);
        this.stream = (ImageInputStream) input;

        // 清除先前流内容对应的全部值
        resetStreamSettings();
    }

    /**
     * 读取NullTerminatedString
     * @param charset charset
     * @param maxLen maxLen
     */
    private String readNullTerminatedString(String charset, int maxLen) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b = 0;
        int count = 0;
        while ((maxLen > count++) && ((b = stream.read()) != 0)) {
            if (b == -1) {
                throw new EOFException();
            }
            baos.write(b);
        }
        if (b != 0) {
            throw new IIOException("Found non null terminated string");
        }
        return baos.toString(charset);
    }

    /** 读取Header */
    private void readHeader() throws IIOException {
        if (gotHeader) {
            return;
        }
        if (stream == null) {
            throw new IllegalStateException("Input source not set!");
        }

        try {
            byte[] signature = new byte[8];
            stream.readFully(signature);

            if (signature[0] != (byte)137 ||
                signature[1] != (byte)80 ||
                signature[2] != (byte)78 ||
                signature[3] != (byte)71 ||
                signature[4] != (byte)13 ||
                signature[5] != (byte)10 ||
                signature[6] != (byte)26 ||
                signature[7] != (byte)10) {
                throw new IIOException("Bad PNG signature!");
            }

            int IHDR_length = stream.readInt();
            if (IHDR_length != 13) {
                throw new IIOException("Bad length for IHDR chunk!");
            }
            int IHDR_type = stream.readInt();
            if (IHDR_type != IHDR_TYPE) {
                throw new IIOException("Bad type for IHDR chunk!");
            }

            this.metadata = new PNGMetadata();

            int width = stream.readInt();
            int height = stream.readInt();

            // 复用 signature 数组批量读取这些无符号字节值
            stream.readFully(signature, 0, 5);
            int bitDepth          = signature[0] & 0xff;
            int colorType         = signature[1] & 0xff;
            int compressionMethod = signature[2] & 0xff;
            int filterMethod      = signature[3] & 0xff;
            int interlaceMethod   = signature[4] & 0xff;

            // Skip IHDR CRC
            stream.skipBytes(4);

            stream.flushBefore(stream.getStreamPosition());

            if (width <= 0) {
                throw new IIOException("Image width <= 0!");
            }
            if (height <= 0) {
                throw new IIOException("Image height <= 0!");
            }
            if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 &&
                bitDepth != 8 && bitDepth != 16) {
                throw new IIOException("Bit depth must be 1, 2, 4, 8, or 16!");
            }
            if (colorType != 0 && colorType != 2 && colorType != 3 &&
                colorType != 4 && colorType != 6) {
                throw new IIOException("Color type must be 0, 2, 3, 4, or 6!");
            }
            if (colorType == PNG_COLOR_PALETTE && bitDepth == 16) {
                throw new IIOException("Bad color type/bit depth combination!");
            }
            if ((colorType == PNG_COLOR_RGB ||
                 colorType == PNG_COLOR_RGB_ALPHA ||
                 colorType == PNG_COLOR_GRAY_ALPHA) &&
                (bitDepth != 8 && bitDepth != 16)) {
                throw new IIOException("Bad color type/bit depth combination!");
            }
            if (compressionMethod != 0) {
                throw new IIOException("Unknown compression method (not 0)!");
            }
            if (filterMethod != 0) {
                throw new IIOException("Unknown filter method (not 0)!");
            }
            if (interlaceMethod != 0 && interlaceMethod != 1) {
                throw new IIOException("Unknown interlace method (not 0 or 1)!");
            }

            metadata.IHDR_present = true;
            metadata.IHDR_width = width;
            metadata.IHDR_height = height;
            metadata.IHDR_bitDepth = bitDepth;
            metadata.IHDR_colorType = colorType;
            metadata.IHDR_compressionMethod = compressionMethod;
            metadata.IHDR_filterMethod = filterMethod;
            metadata.IHDR_interlaceMethod = interlaceMethod;

            gotHeader = true;
        } catch (IOException e) {
            throw new IIOException("I/O error reading PNG header!", e);
        }
    }

    /** 解析acTchunk */
    private void parse_acTL_chunk() throws IOException {
        if (metadata.acTL_present) {
            processWarningOccurred(
"An APNG image may not contain more than one acTL chunk.\n" +
"The chunk wil be ignored.");
            stream.skipBytes(8);
            return;
        }
        metadata.acTL_num_frames = stream.readInt();
        metadata.acTL_num_plays = stream.readInt();

        isAnimated = true;
        metadata.acTL_present = true;
    }

    /** 解析fcTchunk */
    private void parse_fcTL_chunk() throws IOException {
        if (!frameMetadata.containsKey(nextImageIndex)) {
            PNGMetadata metadata = new PNGMetadata();
            frameMetadata.put(nextImageIndex, metadata);
        }
        PNGMetadata metadata = frameMetadata.get(nextImageIndex);
        if (metadata.fcTL_present) {
            processWarningOccurred(
"An APNG image frame may not contain more than one fcTL chunk.\n" +
"The chunk wil be ignored.");
            stream.skipBytes(26);
            return;
        }
        metadata.fcTL_sequence_number = stream.readInt();
        metadata.fcTL_width = stream.readInt();
        metadata.fcTL_height = stream.readInt();
        metadata.fcTL_x_offset = stream.readInt();
        metadata.fcTL_y_offset = stream.readInt();
        metadata.fcTL_delay_num = stream.readUnsignedShort();
        metadata.fcTL_delay_den = stream.readUnsignedShort();
        metadata.fcTL_dispose_op = stream.readUnsignedByte();
        metadata.fcTL_blend_op = stream.readUnsignedByte();

        metadata.fcTL_present = true;
        nextImageIndex ++;
    }

    /**
     * 解析PLTchunk
     * @param chunkLength chunkLength
     */
    private void parse_PLTE_chunk(int chunkLength) throws IOException {
        if (metadata.PLTE_present) {
            processWarningOccurred(
"A PNG image may not contain more than one PLTE chunk.\n" +
"The chunk wil be ignored.");
            return;
        } else if (metadata.IHDR_colorType == PNG_COLOR_GRAY ||
                   metadata.IHDR_colorType == PNG_COLOR_GRAY_ALPHA) {
            processWarningOccurred(
"A PNG gray or gray alpha image cannot have a PLTE chunk.\n" +
"The chunk wil be ignored.");
            return;
        }

        byte[] palette = new byte[chunkLength];
        stream.readFully(palette);

        int numEntries = chunkLength/3;
        if (metadata.IHDR_colorType == PNG_COLOR_PALETTE) {
            int maxEntries = 1 << metadata.IHDR_bitDepth;
            if (numEntries > maxEntries) {
                processWarningOccurred(
"PLTE chunk contains too many entries for bit depth, ignoring extras.");
                numEntries = maxEntries;
            }
            numEntries = Math.min(numEntries, maxEntries);
        }

        // Round array sizes up to 2^2^n
        int paletteEntries;
        if (numEntries > 16) {
            paletteEntries = 256;
        } else if (numEntries > 4) {
            paletteEntries = 16;
        } else if (numEntries > 2) {
            paletteEntries = 4;
        } else {
            paletteEntries = 2;
        }

        metadata.PLTE_present = true;
        metadata.PLTE_red = new byte[paletteEntries];
        metadata.PLTE_green = new byte[paletteEntries];
        metadata.PLTE_blue = new byte[paletteEntries];

        int index = 0;
        for (int i = 0; i < numEntries; i++) {
            metadata.PLTE_red[i] = palette[index++];
            metadata.PLTE_green[i] = palette[index++];
            metadata.PLTE_blue[i] = palette[index++];
        }
    }

    /** 解析bKGchunk */
    private void parse_bKGD_chunk() throws IOException {
        if (metadata.IHDR_colorType == PNG_COLOR_PALETTE) {
            metadata.bKGD_colorType = PNG_COLOR_PALETTE;
            metadata.bKGD_index = stream.readUnsignedByte();
        } else if (metadata.IHDR_colorType == PNG_COLOR_GRAY ||
                   metadata.IHDR_colorType == PNG_COLOR_GRAY_ALPHA) {
            metadata.bKGD_colorType = PNG_COLOR_GRAY;
            metadata.bKGD_gray = stream.readUnsignedShort();
        } else {
            // RGB 或 RGB_ALPHA
            metadata.bKGD_colorType = PNG_COLOR_RGB;
            metadata.bKGD_red = stream.readUnsignedShort();
            metadata.bKGD_green = stream.readUnsignedShort();
            metadata.bKGD_blue = stream.readUnsignedShort();
        }

        metadata.bKGD_present = true;
    }

    /** 解析cHRchunk */
    private void parse_cHRM_chunk() throws IOException {
        metadata.cHRM_whitePointX = stream.readInt();
        metadata.cHRM_whitePointY = stream.readInt();
        metadata.cHRM_redX = stream.readInt();
        metadata.cHRM_redY = stream.readInt();
        metadata.cHRM_greenX = stream.readInt();
        metadata.cHRM_greenY = stream.readInt();
        metadata.cHRM_blueX = stream.readInt();
        metadata.cHRM_blueY = stream.readInt();

        metadata.cHRM_present = true;
    }

    /** 解析gAMchunk */
    private void parse_gAMA_chunk() throws IOException {
        int gamma = stream.readInt();
        metadata.gAMA_gamma = gamma;

        metadata.gAMA_present = true;
    }

    /**
     * 解析h是否chunk
     * @param chunkLength chunkLength
     */
    private void parse_hIST_chunk(int chunkLength) throws IOException {
        if (!metadata.PLTE_present) {
            throw new IIOException("hIST chunk without prior PLTE chunk!");
        }

        /* 按 PNG 规范，hIST chunk 的长度以字节计，
         * 且 hIST chunk 由 2 字节元素组成
         * （因此长度应为偶数）。
         */
        metadata.hIST_histogram = new char[chunkLength/2];
        stream.readFully(metadata.hIST_histogram,
                         0, metadata.hIST_histogram.length);

        metadata.hIST_present = true;
    }

    /**
     * 解析iCCchunk
     * @param chunkLength chunkLength
     */
    private void parse_iCCP_chunk(int chunkLength) throws IOException {
        String keyword = readNullTerminatedString("ISO-8859-1", 80);
        int compressedProfileLength = chunkLength - keyword.length() - 2;
        if (compressedProfileLength <= 0) {
            throw new IIOException("iCCP chunk length is not proper");
        }
        metadata.iCCP_profileName = keyword;

        metadata.iCCP_compressionMethod = stream.readUnsignedByte();

        byte[] compressedProfile =
          new byte[compressedProfileLength];
        stream.readFully(compressedProfile);
        metadata.iCCP_compressedProfile = compressedProfile;

        metadata.iCCP_present = true;
    }

    /**
     * 解析iTXtchunk
     * @param chunkLength chunkLength
     */
    private void parse_iTXt_chunk(int chunkLength) throws IOException {
        long chunkStart = stream.getStreamPosition();

        String keyword = readNullTerminatedString("ISO-8859-1", 80);
        metadata.iTXt_keyword.add(keyword);

        int compressionFlag = stream.readUnsignedByte();
        metadata.iTXt_compressionFlag.add(Boolean.valueOf(compressionFlag == 1));

        int compressionMethod = stream.readUnsignedByte();
        metadata.iTXt_compressionMethod.add(Integer.valueOf(compressionMethod));

        long pos = stream.getStreamPosition();
        int remainingLen = (int)(chunkStart + chunkLength - pos);
        String languageTag = readNullTerminatedString("UTF8", remainingLen);
        metadata.iTXt_languageTag.add(languageTag);

        pos = stream.getStreamPosition();
        remainingLen = (int)(chunkStart + chunkLength - pos);
        if (remainingLen < 0) {
            throw new IIOException("iTXt chunk length is not proper");
        }
        String translatedKeyword =
            readNullTerminatedString("UTF8", remainingLen);
        metadata.iTXt_translatedKeyword.add(translatedKeyword);

        String text;
        pos = stream.getStreamPosition();
        int textLength = (int)(chunkStart + chunkLength - pos);
        if (textLength < 0) {
            throw new IIOException("iTXt chunk length is not proper");
        }
        byte[] b = new byte[textLength];
        stream.readFully(b);

        if (compressionFlag == 1) {
            // 解压文本
            text = new String(inflate(b), StandardCharsets.UTF_8);
        } else {
            text = new String(b, StandardCharsets.UTF_8);
        }
        metadata.iTXt_text.add(text);

        // 检查文本 chunk 是否包含图像创建时间
        if (keyword.equals(PNGMetadata.tEXt_creationTimeKey)) {
            // 从文本 chunk 更新 Standard/Document/ImageCreationTime
            int index = metadata.iTXt_text.size() - 1;
            metadata.decodeImageCreationTimeFromTextChunk(
                    metadata.iTXt_text.listIterator(index));
        }
    }

    /** 解析pHYschunk */
    private void parse_pHYs_chunk() throws IOException {
        metadata.pHYs_pixelsPerUnitXAxis = stream.readInt();
        metadata.pHYs_pixelsPerUnitYAxis = stream.readInt();
        metadata.pHYs_unitSpecifier = stream.readUnsignedByte();

        metadata.pHYs_present = true;
    }

    /** 解析sBIchunk */
    private void parse_sBIT_chunk() throws IOException {
        int colorType = metadata.IHDR_colorType;
        if (colorType == PNG_COLOR_GRAY ||
            colorType == PNG_COLOR_GRAY_ALPHA) {
            metadata.sBIT_grayBits = stream.readUnsignedByte();
        } else if (colorType == PNG_COLOR_RGB ||
                   colorType == PNG_COLOR_PALETTE ||
                   colorType == PNG_COLOR_RGB_ALPHA) {
            metadata.sBIT_redBits = stream.readUnsignedByte();
            metadata.sBIT_greenBits = stream.readUnsignedByte();
            metadata.sBIT_blueBits = stream.readUnsignedByte();
        }

        if (colorType == PNG_COLOR_GRAY_ALPHA ||
            colorType == PNG_COLOR_RGB_ALPHA) {
            metadata.sBIT_alphaBits = stream.readUnsignedByte();
        }

        metadata.sBIT_colorType = colorType;
        metadata.sBIT_present = true;
    }

    /**
     * 解析sPLchunk
     * @param chunkLength chunkLength
     */
    private void parse_sPLT_chunk(int chunkLength)
        throws IOException {
        metadata.sPLT_paletteName = readNullTerminatedString("ISO-8859-1", 80);
        int remainingChunkLength = chunkLength -
                (metadata.sPLT_paletteName.length() + 1);
        if (remainingChunkLength <= 0) {
            throw new IIOException("sPLT chunk length is not proper");
        }

        int sampleDepth = stream.readUnsignedByte();
        metadata.sPLT_sampleDepth = sampleDepth;

        int numEntries = remainingChunkLength/(4*(sampleDepth/8) + 2);
        metadata.sPLT_red = new int[numEntries];
        metadata.sPLT_green = new int[numEntries];
        metadata.sPLT_blue = new int[numEntries];
        metadata.sPLT_alpha = new int[numEntries];
        metadata.sPLT_frequency = new int[numEntries];

        if (sampleDepth == 8) {
            for (int i = 0; i < numEntries; i++) {
                metadata.sPLT_red[i] = stream.readUnsignedByte();
                metadata.sPLT_green[i] = stream.readUnsignedByte();
                metadata.sPLT_blue[i] = stream.readUnsignedByte();
                metadata.sPLT_alpha[i] = stream.readUnsignedByte();
                metadata.sPLT_frequency[i] = stream.readUnsignedShort();
            }
        } else if (sampleDepth == 16) {
            for (int i = 0; i < numEntries; i++) {
                metadata.sPLT_red[i] = stream.readUnsignedShort();
                metadata.sPLT_green[i] = stream.readUnsignedShort();
                metadata.sPLT_blue[i] = stream.readUnsignedShort();
                metadata.sPLT_alpha[i] = stream.readUnsignedShort();
                metadata.sPLT_frequency[i] = stream.readUnsignedShort();
            }
        } else {
            throw new IIOException("sPLT sample depth not 8 or 16!");
        }

        metadata.sPLT_present = true;
    }

    /** 解析sRGchunk */
    private void parse_sRGB_chunk() throws IOException {
        metadata.sRGB_renderingIntent = stream.readUnsignedByte();

        metadata.sRGB_present = true;
    }

    /** 解析cICchunk */
    private void parse_cICP_chunk() throws IOException {
        metadata.cICP_colourPrimaries = stream.readUnsignedByte();
        metadata.cICP_transferFunction = stream.readUnsignedByte();
        metadata.cICP_matrixCoefficients = stream.readUnsignedByte();
        metadata.cICP_videoFullRangeFlag = stream.readUnsignedByte() == 1;

        metadata.cICP_present = true;
    }

    /**
     * 解析tEXtchunk
     * @param chunkLength chunkLength
     */
    private void parse_tEXt_chunk(int chunkLength) throws IOException {
        String keyword = readNullTerminatedString("ISO-8859-1", 80);
        int textLength = chunkLength - keyword.length() - 1;
        if (textLength < 0) {
            throw new IIOException("tEXt chunk length is not proper");
        }
        metadata.tEXt_keyword.add(keyword);

        byte[] b = new byte[textLength];
        stream.readFully(b);
        metadata.tEXt_text.add(new String(b, StandardCharsets.ISO_8859_1));

        // 检查文本 chunk 是否包含图像创建时间
        if (keyword.equals(PNGMetadata.tEXt_creationTimeKey)) {
            // 从文本 chunk 更新 Standard/Document/ImageCreationTime
            int index = metadata.tEXt_text.size() - 1;
            metadata.decodeImageCreationTimeFromTextChunk(
                    metadata.tEXt_text.listIterator(index));
        }
    }

    /** 解析tIMchunk */
    private void parse_tIME_chunk() throws IOException {
        metadata.tIME_year = stream.readUnsignedShort();
        metadata.tIME_month = stream.readUnsignedByte();
        metadata.tIME_day = stream.readUnsignedByte();
        metadata.tIME_hour = stream.readUnsignedByte();
        metadata.tIME_minute = stream.readUnsignedByte();
        metadata.tIME_second = stream.readUnsignedByte();

        metadata.tIME_present = true;
    }

    /**
     * 解析tRNchunk
     * @param chunkLength chunkLength
     */
    private void parse_tRNS_chunk(int chunkLength) throws IOException {
        int colorType = metadata.IHDR_colorType;
        if (colorType == PNG_COLOR_PALETTE) {
            if (!metadata.PLTE_present) {
                processWarningOccurred(
"tRNS chunk without prior PLTE chunk, ignoring it.");
                return;
            }

            // Alpha 表的条目数可能少于 RGB 调色板条目数
            int maxEntries = metadata.PLTE_red.length;
            int numEntries = chunkLength;
            if (numEntries > maxEntries && maxEntries > 0) {
                processWarningOccurred(
"tRNS chunk has more entries than prior PLTE chunk, ignoring extras.");
                numEntries = maxEntries;
            }
            metadata.tRNS_alpha = new byte[numEntries];
            metadata.tRNS_colorType = PNG_COLOR_PALETTE;
            stream.read(metadata.tRNS_alpha, 0, numEntries);
            stream.skipBytes(chunkLength - numEntries);
        } else if (colorType == PNG_COLOR_GRAY) {
            if (chunkLength != 2) {
                processWarningOccurred(
"tRNS chunk for gray image must have length 2, ignoring chunk.");
                stream.skipBytes(chunkLength);
                return;
            }
            metadata.tRNS_gray = stream.readUnsignedShort();
            metadata.tRNS_colorType = PNG_COLOR_GRAY;
        } else if (colorType == PNG_COLOR_RGB) {
            if (chunkLength != 6) {
                processWarningOccurred(
"tRNS chunk for RGB image must have length 6, ignoring chunk.");
                stream.skipBytes(chunkLength);
                return;
            }
            metadata.tRNS_red = stream.readUnsignedShort();
            metadata.tRNS_green = stream.readUnsignedShort();
            metadata.tRNS_blue = stream.readUnsignedShort();
            metadata.tRNS_colorType = PNG_COLOR_RGB;
        } else {
            processWarningOccurred(
"Gray+Alpha and RGBS images may not have a tRNS chunk, ignoring it.");
            return;
        }

        metadata.tRNS_present = true;
    }

    private static byte[] inflate(byte[] b) throws IOException {
        InputStream bais = new ByteArrayInputStream(b);
        InputStream iis = new InflaterInputStream(bais);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        int c;
        try {
            while ((c = iis.read()) != -1) {
                baos.write(c);
            }
        } finally {
            iis.close();
        }
        return baos.toByteArray();
    }

    /**
     * 解析eXIfchunk
     * @param chunkLength chunkLength
     */
    private void parse_eXIf_chunk(int chunkLength) throws IOException {
        byte[] b = new byte[chunkLength];
        stream.readFully(b);
        metadata.eXIf_data = b;

        metadata.eXIf_present = true;
    }

    /**
     * 解析zTXtchunk
     * @param chunkLength chunkLength
     */
    private void parse_zTXt_chunk(int chunkLength) throws IOException {
        String keyword = readNullTerminatedString("ISO-8859-1", 80);
        int textLength = chunkLength - keyword.length() - 2;
        if (textLength < 0) {
            throw new IIOException("zTXt chunk length is not proper");
        }
        metadata.zTXt_keyword.add(keyword);

        int method = stream.readUnsignedByte();
        metadata.zTXt_compressionMethod.add(method);

        byte[] b = new byte[textLength];
        stream.readFully(b);
        metadata.zTXt_text.add(new String(inflate(b), StandardCharsets.ISO_8859_1));

        // 检查文本 chunk 是否包含图像创建时间
        if (keyword.equals(PNGMetadata.tEXt_creationTimeKey)) {
            // 从文本 chunk 更新 Standard/Document/ImageCreationTime
            int index = metadata.zTXt_text.size() - 1;
            metadata.decodeImageCreationTimeFromTextChunk(
                    metadata.zTXt_text.listIterator(index));
        }
    }

    /** 读取Metadata */
    private void readMetadata() throws IIOException {
        if (gotMetadata) {
            return;
        }
        readHeader();

        /*
    * 优化点：当 ignoreMetadata 置位且 colorType 不是 PNG_COLOR_PALETTE 时，
    * 可以跳过读取元数据。但仍需解析 tRNS chunk 以从元数据中取出透明色，
    * 这样 PNGImageReader 才能在解码图像中正确识别并设置
    * colorType 为 PNG_COLOR_RGB 与 PNG_COLOR_GRAY 的透明像素。
    */
        int colorType = metadata.IHDR_colorType;
        if (ignoreMetadata && colorType != PNG_COLOR_PALETTE) {
            try {
                while (true) {
                    int chunkLength = stream.readInt();

                    // 先校验 chunk 长度
                    if (chunkLength < 0 || chunkLength + 4 < 0) {
                        throw new IIOException("Invalid chunk length " + chunkLength);
                    }

                    int chunkType = stream.readInt();

                    if (chunkType == acTL_TYPE) {
                        parse_acTL_chunk();
                    } else if (isAnimated && chunkType == fcTL_TYPE) {
                        if (imageStartPosition == -1L) {
                            animContainsIDAT = true;
                        }
                        parse_fcTL_chunk();
                    } else if (isAnimated && chunkType == fdAT_TYPE) {
                        int sequenceNumber = stream.readInt();
                        stream.skipBytes(-12);
                        int index = nextImageIndex - 1;
                        if (!frameImageStartPositions.containsKey(index)) {
                            if (!frameMetadata.containsKey(index)) {
                                throw new IIOException("Required fcTL chunk missing");
                            }
                            PNGMetadata metadata = frameMetadata.get(index);
                            metadata.fdAT_present = true;
                            metadata.fdAT_sequence_number = sequenceNumber;
                            frameImageStartPositions.put(index, stream.getStreamPosition());
                        }
                    } else if (chunkType == IDAT_TYPE) {
                        // 已到达第一个 IDAT chunk 的位置
                        stream.skipBytes(-8);
                        imageStartPosition = stream.getStreamPosition();
                        /*
                         * 按 PNG 规范，tRNS chunk 必须位于第一个 IDAT chunk
                         * 之前，因此此处可以停止读取元数据。
                         */
                        break;
                    } else if (chunkType == tRNS_TYPE) {
                        parse_tRNS_chunk(chunkLength);
                        // 解析完 tRNS chunk 后跳过 4 字节 CRC
                        stream.skipBytes(4);
                    } else {
                        // 跳过整个 chunk 及其后 4 字节 CRC
                        stream.skipBytes(chunkLength + 4);
                    }
                }
            } catch (IOException e) {
                throw new IIOException("Error skipping PNG metadata", e);
            }

            gotMetadata = true;
            return;
        }

        try {
            loop: while (true) {
                int chunkLength = stream.readInt();
                int chunkType = stream.readInt();
                // 初始化 chunkCRC，赋予的值本身无意义
                int chunkCRC = -1;

                // 校验 chunk 长度
                if (chunkLength < 0) {
                    throw new IIOException("Invalid chunk length " + chunkLength);
                }

                try {
                    /*
                     * 按 PNG 规范，所有 chunk 都应带 4 字节 CRC，但部分图像的
                     * IEND chunk 缺失或损坏了 CRC，而其他解码器也支持这类图像。
                     * 因此一旦读到 IEND chunk 类型，就停止读取元数据。
                     */
                    if (chunkType != IEND_TYPE) {
                        stream.mark();
                        stream.seek(stream.getStreamPosition() + chunkLength);
                        chunkCRC = stream.readInt();
                        stream.reset();
                    }
                } catch (IOException e) {
                    throw new IIOException("Invalid chunk length " + chunkLength);
                }

                switch (chunkType) {
                case acTL_TYPE:
                    parse_acTL_chunk();
                    break;
                case fcTL_TYPE:
                    if (imageStartPosition == -1L) {
                        animContainsIDAT = true;
                    }
                    parse_fcTL_chunk();
                    break;
                case fdAT_TYPE:
                    int sequenceNumber = stream.readInt();

                    /*
                     * PNG 规范规定：当 colorType 为 PNG_COLOR_PALETTE 时，
                     * PLTE chunk 必须位于第一个 IDAT chunk 之前。
                     */
                    if (colorType == PNG_COLOR_PALETTE &&
                            !(metadata.PLTE_present))
                    {
                        throw new IIOException("Required PLTE chunk"
                                + " missing");
                    }

                    int index = nextImageIndex - 1;
                    if (!frameImageStartPositions.containsKey(index)) {
                        if (!frameMetadata.containsKey(index)) {
                            throw new IIOException("Required fcTL chunk missing");
                        }
                        PNGMetadata metadata = frameMetadata.get(index);
                        metadata.fdAT_present = true;
                        metadata.fdAT_sequence_number = sequenceNumber;
                        frameImageStartPositions.put(index, stream.getStreamPosition() - 12);
                    }
                    // 移动到 CRC 字节位置
                    stream.skipBytes(chunkLength - 4);
                    break;
                case IDAT_TYPE:
                    // chunk 类型为 IDAT：已进入图像数据区
                    if (imageStartPosition == -1L) {
                        /*
                         * PNG 规范规定：当 colorType 为 PNG_COLOR_PALETTE 时，
                         * PLTE chunk 必须位于第一个 IDAT chunk 之前。
                         */
                        if (colorType == PNG_COLOR_PALETTE &&
                            !(metadata.PLTE_present))
                        {
                            throw new IIOException("Required PLTE chunk"
                                    + " missing");
                        }
                        /*
                         * PNG 可能包含多个 IDAT chunk，各承载一部分图像数据。
                         * 这里记录第一个 IDAT chunk 的位置，并继续遍历
                         * 图像数据之后的其余 chunk。
                         */
                        imageStartPosition = stream.getStreamPosition() - 8;
                    }
                    // 移动到 CRC 字节位置
                    stream.skipBytes(chunkLength);
                    break;
                case IEND_TYPE:
                    /*
                     * chunk 类型为 IEND：图像已结束。
                     * 定位回第一个 IDAT chunk 供后续解码。
                     */
                    stream.seek(imageStartPosition);

                    /*
                     * flushBefore 会丢弃指定位置之前的流内容，
                     * 因此必须在遍历完所有 chunk（包括 IDAT 之后的）
                     * 之后再调用。
                     */
                    stream.flushBefore(stream.getStreamPosition());
                    break loop;
                case PLTE_TYPE:
                    parse_PLTE_chunk(chunkLength);
                    break;
                case bKGD_TYPE:
                    parse_bKGD_chunk();
                    break;
                case cHRM_TYPE:
                    parse_cHRM_chunk();
                    break;
                case gAMA_TYPE:
                    parse_gAMA_chunk();
                    break;
                case hIST_TYPE:
                    parse_hIST_chunk(chunkLength);
                    break;
                case iCCP_TYPE:
                    parse_iCCP_chunk(chunkLength);
                    break;
                case iTXt_TYPE:
                    if (ignoreMetadata) {
                        stream.skipBytes(chunkLength);
                    } else {
                        parse_iTXt_chunk(chunkLength);
                    }
                    break;
                case pHYs_TYPE:
                    parse_pHYs_chunk();
                    break;
                case sBIT_TYPE:
                    parse_sBIT_chunk();
                    break;
                case sPLT_TYPE:
                    parse_sPLT_chunk(chunkLength);
                    break;
                case sRGB_TYPE:
                    parse_sRGB_chunk();
                    break;
                case cICP_TYPE:
                    parse_cICP_chunk();
                    break;
                case tEXt_TYPE:
                    parse_tEXt_chunk(chunkLength);
                    break;
                case tIME_TYPE:
                    parse_tIME_chunk();
                    break;
                case tRNS_TYPE:
                    parse_tRNS_chunk(chunkLength);
                    break;
                case eXIf_TYPE:
                    if (ignoreMetadata) {
                        stream.skipBytes(chunkLength);
                    } else {
                        parse_eXIf_chunk(chunkLength);
                    }
                    break;
                case zTXt_TYPE:
                    if (ignoreMetadata) {
                        stream.skipBytes(chunkLength);
                    } else {
                        parse_zTXt_chunk(chunkLength);
                    }
                    break;
                default:
                    // Read an unknown chunk
                    byte[] b = new byte[chunkLength];
                    stream.readFully(b);

                    String chunkName = String.valueOf((char) (chunkType >>> 24)) +
                            (char) ((chunkType >> 16) & 0xff) +
                            (char) ((chunkType >> 8) & 0xff) +
                            (char) (chunkType & 0xff);

                    int ancillaryBit = chunkType >>> 28;
                    if (ancillaryBit == 0) {
                        processWarningOccurred(
"Encountered unknown chunk with critical bit set!");
                    }

                    metadata.unknownChunkType.add(chunkName);
                    metadata.unknownChunkData.add(b);
                    break;
                }

                // 再次确认所有 chunk 数据均已消费
                if (chunkCRC != stream.readInt()) {
                    throw new IIOException("Failed to read a chunk of type " +
                            chunkType);
                }
            }
        } catch (IOException e) {
            throw new IIOException("Error reading PNG metadata", e);
        }

        gotMetadata = true;
    }

    // Data filtering methods

    private static void decodeSubFilter(byte[] curr, int coff, int count,
                                        int bpp) {
        for (int i = bpp; i < count; i++) {
            int val;

            val = curr[i + coff] & 0xff;
            val += curr[i + coff - bpp] & 0xff;

            curr[i + coff] = (byte)val;
        }
    }

    private static void decodeUpFilter(byte[] curr, int coff,
                                       byte[] prev, int poff,
                                       int count) {
        for (int i = 0; i < count; i++) {
            int raw = curr[i + coff] & 0xff;
            int prior = prev[i + poff] & 0xff;

            curr[i + coff] = (byte)(raw + prior);
        }
    }

    private static void decodeAverageFilter(byte[] curr, int coff,
                                            byte[] prev, int poff,
                                            int count, int bpp) {
        int raw, priorPixel, priorRow;

        for (int i = 0; i < bpp; i++) {
            raw = curr[i + coff] & 0xff;
            priorRow = prev[i + poff] & 0xff;

            curr[i + coff] = (byte)(raw + priorRow/2);
        }

        for (int i = bpp; i < count; i++) {
            raw = curr[i + coff] & 0xff;
            priorPixel = curr[i + coff - bpp] & 0xff;
            priorRow = prev[i + poff] & 0xff;

            curr[i + coff] = (byte)(raw + (priorPixel + priorRow)/2);
        }
    }

    private static int paethPredictor(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);

        if ((pa <= pb) && (pa <= pc)) {
            return a;
        } else if (pb <= pc) {
            return b;
        } else {
            return c;
        }
    }

    private static void decodePaethFilter(byte[] curr, int coff,
                                          byte[] prev, int poff,
                                          int count, int bpp) {
        int raw, priorPixel, priorRow, priorRowPixel;

        for (int i = 0; i < bpp; i++) {
            raw = curr[i + coff] & 0xff;
            priorRow = prev[i + poff] & 0xff;

            curr[i + coff] = (byte)(raw + priorRow);
        }

        for (int i = bpp; i < count; i++) {
            raw = curr[i + coff] & 0xff;
            priorPixel = curr[i + coff - bpp] & 0xff;
            priorRow = prev[i + poff] & 0xff;
            priorRowPixel = prev[i + poff - bpp] & 0xff;

            curr[i + coff] = (byte)(raw + paethPredictor(priorPixel,
                                                         priorRow,
                                                         priorRowPixel));
        }
    }

    /** Bandoffsets */
    private static final int[][] bandOffsets = {
        null,
        // 灰度（G）
        { 0 },
        // 灰度+透明（GA 顺序）
        { 0, 1 },
        // RGB 顺序
        { 0, 1, 2 },
        // RGBA 顺序
        { 0, 1, 2, 3 }
    };

    /**
     * 创建Raster
     * @param width width
     * @param height height
     * @param bands bands
     * @param scanlineStride scanlineStride
     * @param bitDepth bitDepth
     */
    private WritableRaster createRaster(int width, int height, int bands,
                                        int scanlineStride,
                                        int bitDepth) {

        DataBuffer dataBuffer;
        WritableRaster ras = null;
        Point origin = new Point(0, 0);
        if ((bitDepth < 8) && (bands == 1)) {
            dataBuffer = new DataBufferByte(height*scanlineStride);
            ras = Raster.createPackedRaster(dataBuffer,
                                            width, height,
                                            bitDepth,
                                            origin);
        } else if (bitDepth <= 8) {
            dataBuffer = new DataBufferByte(height*scanlineStride);
            ras = Raster.createInterleavedRaster(dataBuffer,
                                                 width, height,
                                                 scanlineStride,
                                                 bands,
                                                 bandOffsets[bands],
                                                 origin);
        } else {
            dataBuffer = new DataBufferUShort(height*scanlineStride);
            ras = Raster.createInterleavedRaster(dataBuffer,
                                                 width, height,
                                                 scanlineStride,
                                                 bands,
                                                 bandOffsets[bands],
                                                 origin);
        }

        return ras;
    }

    /**
     * SkipPass
     * @param passWidth passWidth
     * @param passHeight passHeight
     */
    private void skipPass(int passWidth, int passHeight)
        throws IOException {
        if ((passWidth == 0) || (passHeight == 0)) {
            return;
        }

        int inputBands = inputBandsForColorType[metadata.IHDR_colorType];
        int bitsPerRow = Math.
                multiplyExact((inputBands * metadata.IHDR_bitDepth), passWidth);
        int bytesPerRow = (bitsPerRow + 7) / 8;

        // 逐行读取图像
        for (int srcY = 0; srcY < passHeight; srcY++) {
            // 跳过 filter 字节及该行剩余字节
            pixelStream.skipBytes(1 + bytesPerRow);
        }
    }

    /**
     * 更新ImageProgress
     * @param newPixels newPixels
     */
    private void updateImageProgress(int newPixels) {
        pixelsDone += newPixels;
        processImageProgress(100.0F*pixelsDone/totalPixels);
    }

    /**
     * 解码Pass
     * @param passNum passNum
     * @param xStart xStart
     * @param yStart yStart
     * @param xStep xStep
     * @param yStep yStep
     * @param passWidth passWidth
     * @param passHeight passHeight
     */
    private void decodePass(int passNum,
                            int xStart, int yStart,
                            int xStep, int yStep,
                            int passWidth, int passHeight) throws IOException {

        if ((passWidth == 0) || (passHeight == 0)) {
            return;
        }

        WritableRaster imRas = theImage.getWritableTile(0, 0);
        int dstMinX = imRas.getMinX();
        int dstMaxX = dstMinX + imRas.getWidth() - 1;
        int dstMinY = imRas.getMinY();
        int dstMaxY = dstMinY + imRas.getHeight() - 1;

        // 确定本次 pass 需要更新的像素
        int[] vals =
          ReaderUtils.computeUpdatedPixels(sourceRegion,
                                          destinationOffset,
                                          dstMinX, dstMinY,
                                          dstMaxX, dstMaxY,
                                          sourceXSubsampling,
                                          sourceYSubsampling,
                                          xStart, yStart,
                                          passWidth, passHeight,
                                          xStep, yStep);
        int updateMinX = vals[0];
        int updateMinY = vals[1];
        int updateWidth = vals[2];
        int updateXStep = vals[4];
        int updateYStep = vals[5];

        int bitDepth = metadata.IHDR_bitDepth;
        int inputBands = inputBandsForColorType[metadata.IHDR_colorType];
        int bytesPerPixel = (bitDepth == 16) ? 2 : 1;
        bytesPerPixel *= inputBands;

        int bitsPerRow = Math.multiplyExact((inputBands * bitDepth), passWidth);
        int bytesPerRow = (bitsPerRow + 7) / 8;
        int eltsPerRow = (bitDepth == 16) ? bytesPerRow/2 : bytesPerRow;

        // 若没有像素需要更新，直接跳过输入数据
        if (updateWidth == 0) {
            for (int srcY = 0; srcY < passHeight; srcY++) {
                // 更新已读取像素计数
                updateImageProgress(passWidth);
                /*
                 * 若读取已被中止，直接返回，
                 * 稍后会调用 processReadAborted
                 */
                if (abortRequested()) {
                    return;
                }
                // 跳过 filter 字节及该行剩余字节
                pixelStream.skipBytes(1 + bytesPerRow);
            }
            return;
        }

        // 目标像素反向映射：
        // (dstX = updateMinX + k*updateXStep)
        // 先映射到源像素 (sourceX)，再
        // 映射到 passRow 中的偏移与步长 (srcX 与 srcXStep)
        int sourceX =
            (updateMinX - destinationOffset.x)*sourceXSubsampling +
            sourceRegion.x;
        int srcX = (sourceX - xStart)/xStep;

        // 计算源端步长系数
        int srcXStep = updateXStep*sourceXSubsampling/xStep;

        byte[] byteData = null;
        short[] shortData = null;
        byte[] curr = new byte[bytesPerRow];
        byte[] prior = new byte[bytesPerRow];

        // 创建一行高的 Raster 承载数据
        WritableRaster passRow = createRaster(passWidth, 1, inputBands,
                                              eltsPerRow,
                                              bitDepth);

        // 创建适合容纳单个像素的数组
        int[] ps = passRow.getPixel(0, 0, (int[])null);

        DataBuffer dataBuffer = passRow.getDataBuffer();
        int type = dataBuffer.getDataType();
        if (type == DataBuffer.TYPE_BYTE) {
            byteData = ((DataBufferByte)dataBuffer).getData();
        } else {
            shortData = ((DataBufferUShort)dataBuffer).getData();
        }

        processPassStarted(theImage,
                           passNum,
                           sourceMinProgressivePass,
                           sourceMaxProgressivePass,
                           updateMinX, updateMinY,
                           updateXStep, updateYStep,
                           destinationBands);

        // 处理源与目标 band
        if (sourceBands != null) {
            passRow = passRow.createWritableChild(0, 0,
                                                  passRow.getWidth(), 1,
                                                  0, 0,
                                                  sourceBands);
        }
        if (destinationBands != null) {
            imRas = imRas.createWritableChild(0, 0,
                                              imRas.getWidth(),
                                              imRas.getHeight(),
                                              0, 0,
                                              destinationBands);
        }

        // 判断相关输出 band 是否与源数据
        // 位深完全一致
        boolean adjustBitDepths = false;
        int[] outputSampleSize = imRas.getSampleModel().getSampleSize();
        for (int b = 0; b < inputBands; b++) {
            if (outputSampleSize[b] != bitDepth) {
                adjustBitDepths = true;
                break;
            }
        }

        // 若位深不同，为每个 band 建立查找表以完成位深转换
        // the conversion
        int[][] scale = null;
        if (adjustBitDepths) {
            int maxInSample = (1 << bitDepth) - 1;
            int halfMaxInSample = maxInSample/2;
            scale = new int[inputBands][];
            for (int b = 0; b < inputBands; b++) {
                int maxOutSample = (1 << outputSampleSize[b]) - 1;
                scale[b] = new int[maxInSample + 1];
                for (int s = 0; s <= maxInSample; s++) {
                    scale[b][s] =
                        (s*maxOutSample + halfMaxInSample)/maxInSample;
                }
            }
        }

        // 将 passRow 限制在相关区域，
        // 以便用 setRect 拷贝连续跨度
        boolean useSetRect = srcXStep == 1 &&
            updateXStep == 1 &&
            !adjustBitDepths &&
            ("sun.awt.image.ByteInterleavedRaster".equals(imRas.getClass().getName()));

        if (useSetRect) {
            passRow = passRow.createWritableChild(srcX, 0,
                                                  updateWidth, 1,
                                                  0, 0,
                                                  null);
        }

        // 逐行解码（子）图像
        for (int srcY = 0; srcY < passHeight; srcY++) {
            // 更新已读取像素计数
            updateImageProgress(passWidth);
            /*
             * 若读取已被中止，直接返回，
             * 稍后会调用 processReadAborted
             */
            if (abortRequested()) {
                return;
            }
            // 读取 filter 类型字节与一行数据
            int filter = pixelStream.read();
            try {
                // 交换 curr 与 prior
                byte[] tmp = prior;
                prior = curr;
                curr = tmp;

                pixelStream.readFully(curr, 0, bytesPerRow);
            } catch (java.util.zip.ZipException ze) {
                // TODO[@L1472] - throw a more meaningful exception
                throw ze;
            }

            switch (filter) {
            case PNG_FILTER_NONE:
                break;
            case PNG_FILTER_SUB:
                decodeSubFilter(curr, 0, bytesPerRow, bytesPerPixel);
                break;
            case PNG_FILTER_UP:
                decodeUpFilter(curr, 0, prior, 0, bytesPerRow);
                break;
            case PNG_FILTER_AVERAGE:
                decodeAverageFilter(curr, 0, prior, 0, bytesPerRow,
                                    bytesPerPixel);
                break;
            case PNG_FILTER_PAETH:
                decodePaethFilter(curr, 0, prior, 0, bytesPerRow,
                                  bytesPerPixel);
                break;
            default:
                throw new IIOException("Unknown row filter type (= " +
                                       filter + ")!");
            }

            // 逐字节将数据拷入 passRow
            if (bitDepth < 16) {
                System.arraycopy(curr, 0, byteData, 0, bytesPerRow);
            } else {
                int idx = 0;
                for (int j = 0; j < eltsPerRow; j++) {
                    shortData[j] =
                        (short)((curr[idx] << 8) | (curr[idx + 1] & 0xff));
                    idx += 2;
                }
            }

            // True Y position in source
            int sourceY = srcY*yStep + yStart;
            if ((sourceY >= sourceRegion.y) &&
                (sourceY < sourceRegion.y + sourceRegion.height) &&
                (((sourceY - sourceRegion.y) %
                  sourceYSubsampling) == 0)) {

                int dstY = destinationOffset.y +
                    (sourceY - sourceRegion.y)/sourceYSubsampling;
                if (dstY < dstMinY) {
                    continue;
                }
                if (dstY > dstMaxY) {
                    break;
                }

               /*
                * 对颜色类型为 PNG_COLOR_RGB 或 PNG_COLOR_GRAY 且带有
                * 指定透明色（由 tRNS chunk 给出）的 PNG 图像，
                * 将解码后的像素色与 tRNS 给出的透明色比较，
                * 以此设置目标图像的 alpha。
                */
                boolean tRNSTransparentPixelPresent =
                    theImage.getSampleModel().getNumBands() == inputBands + 1 &&
                    metadata.hasTransparentColor();
                if (useSetRect &&
                    !tRNSTransparentPixelPresent) {
                    imRas.setRect(updateMinX, dstY, passRow);
                } else {
                    int newSrcX = srcX;

                    /*
                     * 当 tRNSTransparentPixelPresent 为真时，
                     * 创建中间数组以补齐多余的 alpha 通道。
                     */
                    final int[] temp = new int[inputBands + 1];
                    final int opaque = (bitDepth < 16) ? 255 : 65535;
                    for (int dstX = updateMinX;
                         dstX < updateMinX + updateWidth;
                         dstX += updateXStep) {

                        passRow.getPixel(newSrcX, 0, ps);
                        if (adjustBitDepths) {
                            for (int b = 0; b < inputBands; b++) {
                                ps[b] = scale[b][ps[b]];
                            }
                        }
                        if (tRNSTransparentPixelPresent) {
                            if (metadata.tRNS_colorType == PNG_COLOR_RGB) {
                                temp[0] = ps[0];
                                temp[1] = ps[1];
                                temp[2] = ps[2];
                                if (ps[0] == metadata.tRNS_red &&
                                    ps[1] == metadata.tRNS_green &&
                                    ps[2] == metadata.tRNS_blue) {
                                    temp[3] = 0;
                                } else {
                                    temp[3] = opaque;
                                }
                            } else {
                                // tRNS_colorType 为 PNG_COLOR_GRAY 时
                                temp[0] = ps[0];
                                if (ps[0] == metadata.tRNS_gray) {
                                    temp[1] = 0;
                                } else {
                                    temp[1] = opaque;
                                }
                            }
                            imRas.setPixel(dstX, dstY, temp);
                        } else {
                            imRas.setPixel(dstX, dstY, ps);
                        }
                        newSrcX += srcXStep;
                    }
                }

                processImageUpdate(theImage,
                                   updateMinX, dstY,
                                   updateWidth, 1,
                                   updateXStep, updateYStep,
                                   destinationBands);
            }
        }

        processPassComplete(theImage);
    }

    /**
     * 解码Image
     * @param width width
     * @param height height
     */
    private void decodeImage(int width, int height)
        throws IOException {

        this.pixelsDone = 0;
        this.totalPixels = width*height;

        if (metadata.IHDR_interlaceMethod == 0) {
            decodePass(0, 0, 0, 1, 1, width, height);
        } else {
            for (int i = 0; i <= sourceMaxProgressivePass; i++) {
                int XOffset = adam7XOffset[i];
                int YOffset = adam7YOffset[i];
                int XSubsampling = adam7XSubsampling[i];
                int YSubsampling = adam7YSubsampling[i];
                int xbump = adam7XSubsampling[i + 1] - 1;
                int ybump = adam7YSubsampling[i + 1] - 1;

                if (i >= sourceMinProgressivePass) {
                    decodePass(i,
                               XOffset,
                               YOffset,
                               XSubsampling,
                               YSubsampling,
                               (width + xbump)/XSubsampling,
                               (height + ybump)/YSubsampling);
                } else {
                    skipPass((width + xbump)/XSubsampling,
                             (height + ybump)/YSubsampling);
                }

                /*
                 * 若读取已被中止，直接返回，
                 * 稍后会调用 processReadAborted
                 */
                if (abortRequested()) {
                    return;
                }
            }
        }
    }

    /**
     * 读取Image
     * @param imageIndex imageIndex
     * @param param param
     */
    private void readImage(int imageIndex, ImageReadParam param) throws IIOException {
        readMetadata();

        if (!isAnimated && imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex != 0!");
        }

        boolean isAnimated = this.isAnimated;
        if (param != null && ((PNGImageReadParam) param).isForceReadIDAT()) {
            isAnimated = false;
        }
        int width, height;
        if (isAnimated) {
            PNGMetadata metadata = frameMetadata.get(imageIndex);
            if (metadata == null) {
                throw new IIOException("Frame metadata not exist!");
            }
            width = metadata.fcTL_width;
            height = metadata.fcTL_height;
        } else {
            width = metadata.IHDR_width;
            height = metadata.IHDR_height;
        }

        if ((long)width * height > Integer.MAX_VALUE - 2) {
            // 无法正确解码像素数超过 Integer.MAX_VALUE - 2 的图像
            throw new IIOException("Can not read image of the size "
                    + width + " by " + height);
        }

        // Init default values
        sourceXSubsampling = 1;
        sourceYSubsampling = 1;
        sourceMinProgressivePass = 0;
        sourceMaxProgressivePass = 6;
        sourceBands = null;
        destinationBands = null;
        destinationOffset = new Point(0, 0);

        // 若提供 ImageReadParam，则从中取值
        if (param != null) {
            sourceXSubsampling = param.getSourceXSubsampling();
            sourceYSubsampling = param.getSourceYSubsampling();

            sourceMinProgressivePass =
                Math.max(param.getSourceMinProgressivePass(), 0);
            sourceMaxProgressivePass =
                Math.min(param.getSourceMaxProgressivePass(), 6);

            sourceBands = param.getSourceBands();
            destinationBands = param.getDestinationBands();
            destinationOffset = param.getDestinationOffset();
        }
        Inflater inf = null;
        try {
            boolean fdAT = false;
            long position = imageStartPosition;
            if (isAnimated && !(imageIndex == 0 && animContainsIDAT)) {
                position = frameImageStartPositions.get(imageIndex);
                fdAT = true;
            }
            stream.seek(position);

            Enumeration<InputStream> e = new PNGImageDataEnumeration(stream, fdAT);
            InputStream is = new SequenceInputStream(e);

           /* InflaterInputStream 内部使用 Inflater，会占用本地资源（GC 不可见）。
            * 通常关闭流时会隐式释放，但此处 InflaterInputStream 包装的是
            * 调用方提供的输入流，我们不能关闭它；而应用也可能依赖 GC 终结来关流。
            * 因此为保证本地资源及时释放，这里显式创建 Inflater 实例，
            * 并在 InflaterInputStream 用完后调用 inf.end(); 释放其资源。
            */
            inf = new Inflater();
            is = new InflaterInputStream(is, inf);
            is = new BufferedInputStream(is);
            this.pixelStream = new DataInputStream(is);

            /*
             * PNG 规范规定宽高合法范围为 [1, 2^31-1]，因此受内存限制，
             * 这里可能无法为目标图像分配缓冲。
             *
             * 若读取过程触发 OutOfMemoryError，PNGImageReader.read 方法
             * 会将其包装为 IIOException 抛出。
             *
             * 该场景的恢复策略应由应用层定义，因此这里不尝试估算所需内存，
             * 也不做任何 OOM 处理。
             */
            theImage = getDestination(param,
                                      getImageTypes(0),
                                      width,
                                      height);

            Rectangle destRegion = new Rectangle(0, 0, 0, 0);
            sourceRegion = new Rectangle(0, 0, 0, 0);
            computeRegions(param, width, height,
                           theImage,
                           sourceRegion, destRegion);
            destinationOffset.setLocation(destRegion.getLocation());

            // 此时头部已读取，已知图像 band 数量，
            // 因此执行 band 校验
            // of the read param.
            int colorType = metadata.IHDR_colorType;
            if (theImage.getSampleModel().getNumBands()
                == inputBandsForColorType[colorType] + 1
                && metadata.hasTransparentColor()) {
                checkReadParamBandSettings(param,
                    inputBandsForColorType[colorType] + 1,
                    theImage.getSampleModel().getNumBands());
            } else {
                checkReadParamBandSettings(param,
                    inputBandsForColorType[colorType],
                    theImage.getSampleModel().getNumBands());
            }

            clearAbortRequest();
            processImageStarted(0);
            if (abortRequested()) {
                processReadAborted();
            } else {
                decodeImage(width, height);
                if (abortRequested()) {
                    processReadAborted();
                } else {
                    processImageComplete();
                }
            }

        } catch (IOException e) {
            throw new IIOException("Error reading PNG image data", e);
        } finally {
            if (inf != null) {
                inf.end();
            }
        }
    }

    @Override
    /**
     * 获取NumImages
     * @param allowSearch allowSearch
     */
    public int getNumImages(boolean allowSearch) throws IIOException {
        if (stream == null) {
            throw new IllegalStateException("No input source set!");
        }
        if (seekForwardOnly && allowSearch) {
            throw new IllegalStateException
                ("seekForwardOnly and allowSearch can't both be true!");
        }
        readMetadata();
        return isAnimated ? metadata.acTL_num_frames : 1;
    }

    @Override
    /**
     * 获取Width
     * @param imageIndex imageIndex
     */
    public int getWidth(int imageIndex) throws IIOException {

        readMetadata();

        if (isAnimated) {
            PNGMetadata metadata = frameMetadata.get(imageIndex);
            if (metadata == null) {
                throw new IIOException("No image metadata exist this index: " + imageIndex);
            }
            return metadata.fcTL_width;
        } else if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex != 0!");
        }

        return metadata.IHDR_width;
    }

    @Override
    /**
     * 获取Height
     * @param imageIndex imageIndex
     */
    public int getHeight(int imageIndex) throws IIOException {

        readMetadata();

        if (isAnimated) {
            PNGMetadata metadata = frameMetadata.get(imageIndex);
            if (metadata == null) {
                throw new IIOException("No image metadata exist this index: " + imageIndex);
            }
            return metadata.fcTL_height;
        } else if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex != 0!");
        }

        return metadata.IHDR_height;
    }

    @Override
    /**
     * 获取ImageTypes
     * @param imageIndex imageIndex
     */
    public Iterator<ImageTypeSpecifier> getImageTypes(int imageIndex)
      throws IIOException
    {

        readMetadata();

        if (!isAnimated && imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex != 0!");
        }

        ArrayList<ImageTypeSpecifier> l =
            new ArrayList<ImageTypeSpecifier>(1);

        ColorSpace rgb;
        ColorSpace gray;
        int[] bandOffsets;

        int bitDepth = metadata.IHDR_bitDepth;
        int colorType = metadata.IHDR_colorType;

        int dataType;
        if (bitDepth <= 8) {
            dataType = DataBuffer.TYPE_BYTE;
        } else {
            dataType = DataBuffer.TYPE_USHORT;
        }

        switch (colorType) {
        /*
         * 对颜色类型为 PNG_COLOR_RGB 或 PNG_COLOR_GRAY 且带指定透明色
         * （由 tRNS chunk 给出）的 PNG 图像，把支持透明度的
         * ImageTypeSpecifier 追加到受支持图像类型列表中。
         */
        case PNG_COLOR_GRAY:
            // Need tRNS chunk
            readMetadata();

            if (metadata.hasTransparentColor()) {
                gray = ColorSpace.getInstance(ColorSpace.CS_GRAY);
                bandOffsets = new int[2];
                bandOffsets[0] = 0;
                bandOffsets[1] = 1;
                l.add(ImageTypeSpecifier.createInterleaved(gray,
                                                           bandOffsets,
                                                           dataType,
                                                           true,
                                                           false));
            }
            // Packed grayscale
            l.add(ImageTypeSpecifier.createGrayscale(bitDepth,
                                                     dataType,
                                                     false));
            break;

        case PNG_COLOR_RGB:
            // Need tRNS chunk
            readMetadata();

            if (bitDepth == 8) {
                if (metadata.hasTransparentColor()) {
                    l.add(ImageTypeSpecifier.createFromBufferedImageType(
                            BufferedImage.TYPE_4BYTE_ABGR));
                }
                // 若干可作为目标使用的标准 buffered image 类型
                l.add(ImageTypeSpecifier.createFromBufferedImageType(
                          BufferedImage.TYPE_3BYTE_BGR));

                l.add(ImageTypeSpecifier.createFromBufferedImageType(
                          BufferedImage.TYPE_INT_RGB));

                l.add(ImageTypeSpecifier.createFromBufferedImageType(
                          BufferedImage.TYPE_INT_BGR));

            }

            if (metadata.hasTransparentColor()) {
                rgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
                bandOffsets = new int[4];
                bandOffsets[0] = 0;
                bandOffsets[1] = 1;
                bandOffsets[2] = 2;
                bandOffsets[3] = 3;

                l.add(ImageTypeSpecifier.
                    createInterleaved(rgb, bandOffsets,
                                      dataType, true, false));
            }
            // Component R, G, B
            rgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
            bandOffsets = new int[3];
            bandOffsets[0] = 0;
            bandOffsets[1] = 1;
            bandOffsets[2] = 2;
            l.add(ImageTypeSpecifier.createInterleaved(rgb,
                                                       bandOffsets,
                                                       dataType,
                                                       false,
                                                       false));
            break;

        case PNG_COLOR_PALETTE:
            // Need tRNS chunk
            readMetadata();

            /*
             * PLTE chunk 规范说明：
             *
             * 调色板条目数不得超过图像位深可表示的范围（如位深 4 时为
             * 2^4 = 16）；允许条目数少于位深允许的上限，此时图像数据中
             * 出现的越界像素值即为错误。
             *
             * http://www.libpng.org/pub/png/spec/1.2/PNG-Chunks.html#C.PLTE
             *
             * 因此在 PNG 规范看来，调色板长度小于 2^bitDepth 是合法的。
             *
             * 但 createIndexed() 方法却要求调色板长度与可能的条目数
             * （2^bitDepth）严格相等。
             *
             * {@link javax.imageio.ImageTypeSpecifier.html#createIndexed}
             *
             * 为消除这一矛盾，需要把调色板数组扩展到 bitDepth 上限。
             */

            int plength = 1 << bitDepth;

            byte[] red = metadata.PLTE_red;
            byte[] green = metadata.PLTE_green;
            byte[] blue = metadata.PLTE_blue;

            if (metadata.PLTE_red.length < plength) {
                red = Arrays.copyOf(metadata.PLTE_red, plength);
                Arrays.fill(red, metadata.PLTE_red.length, plength,
                            metadata.PLTE_red[metadata.PLTE_red.length - 1]);

                green = Arrays.copyOf(metadata.PLTE_green, plength);
                Arrays.fill(green, metadata.PLTE_green.length, plength,
                            metadata.PLTE_green[metadata.PLTE_green.length - 1]);

                blue = Arrays.copyOf(metadata.PLTE_blue, plength);
                Arrays.fill(blue, metadata.PLTE_blue.length, plength,
                            metadata.PLTE_blue[metadata.PLTE_blue.length - 1]);

            }

            // tRNS 的 Alpha 条目可能少于 PLTE 的 RGB LUT；
            // 若如此，以 255 填充。
            byte[] alpha = null;
            if (metadata.tRNS_present && (metadata.tRNS_alpha != null)) {
                if (metadata.tRNS_alpha.length == red.length) {
                    alpha = metadata.tRNS_alpha;
                } else {
                    alpha = Arrays.copyOf(metadata.tRNS_alpha, red.length);
                    Arrays.fill(alpha,
                                metadata.tRNS_alpha.length,
                                red.length, (byte)255);
                }
            }

            l.add(ImageTypeSpecifier.createIndexed(red, green,
                                                   blue, alpha,
                                                   bitDepth,
                                                   DataBuffer.TYPE_BYTE));
            break;

        case PNG_COLOR_GRAY_ALPHA:
            // Component G, A
            gray = ColorSpace.getInstance(ColorSpace.CS_GRAY);
            bandOffsets = new int[2];
            bandOffsets[0] = 0;
            bandOffsets[1] = 1;
            l.add(ImageTypeSpecifier.createInterleaved(gray,
                                                       bandOffsets,
                                                       dataType,
                                                       true,
                                                       false));
            break;

        case PNG_COLOR_RGB_ALPHA:
            if (bitDepth == 8) {
                // 若干可作为目标使用的标准 buffered image 类型
                l.add(ImageTypeSpecifier.createFromBufferedImageType(
                          BufferedImage.TYPE_4BYTE_ABGR));

                l.add(ImageTypeSpecifier.createFromBufferedImageType(
                          BufferedImage.TYPE_INT_ARGB));
            }

            // Component R, G, B, A (non-premultiplied)
            rgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
            bandOffsets = new int[4];
            bandOffsets[0] = 0;
            bandOffsets[1] = 1;
            bandOffsets[2] = 2;
            bandOffsets[3] = 3;

            l.add(ImageTypeSpecifier.createInterleaved(rgb,
                                                       bandOffsets,
                                                       dataType,
                                                       true,
                                                       false));
            break;

        default:
            break;
        }

        return l.iterator();
    }

    /*
     * 父类实现将图像类型列表首元素视为 raw image type，并在
     * ImageReadParam 未另行指定时，以首元素作为默认目标类型。
     *
     * 但对 RGB/RGBA 颜色类型，raw image type 产生自定义类型的
     * buffered image，会导致后续渲染操作性能下降。
     *
     * 为解决该矛盾，这里把标准图像类型放在列表前部（默认产出标准
     * 图像），把 raw image type（自定义类型）放在列表末尾。
     *
     * 相应地需覆写 getRawImageType()，返回列表最后一个元素。
     */
    @Override
    public ImageTypeSpecifier getRawImageType(int imageIndex)
      throws IOException {

        Iterator<ImageTypeSpecifier> types = getImageTypes(imageIndex);
        ImageTypeSpecifier raw = null;
        do {
            raw = types.next();
        } while (types.hasNext());
        return raw;
    }

    @Override
    /** 获取Default读取Param */
    public ImageReadParam getDefaultReadParam() {
        
        return new PNGImageReadParam();
    
    }

    @Override
    /** 获取流式输出Metadata */
    public IIOMetadata getStreamMetadata()
        throws IIOException {
        return null;
    }

    @Override
    /**
     * 获取ImageMetadata
     * @param imageIndex imageIndex
     */
    public IIOMetadata getImageMetadata(int imageIndex) throws IIOException {
        readMetadata();

        if (isAnimated) {
            PNGMetadata metadata = frameMetadata.get(imageIndex);
            if (metadata == null) {
                throw new IIOException("No image metadata exist for this index: " + imageIndex);
            }
            return metadata;
        } else if (imageIndex != 0) {
            throw new IndexOutOfBoundsException("imageIndex != 0!");
        }

        return metadata;
    }

    @Override
    /**
     * 读取
     * @param imageIndex imageIndex
     * @param param param
     */
    public BufferedImage read(int imageIndex, ImageReadParam param)
        throws IIOException {

        try {
            readImage(imageIndex, param);
        } catch (IOException |
                 IllegalStateException |
                 IllegalArgumentException e)
        {
            throw e;
        } catch (Throwable e) {
            throw new IIOException("Caught exception during read: ", e);
        }
        return theImage;
    }

    @Override
    /** Reset */
    public void reset() {
        super.reset();
        resetStreamSettings();
    }

    /** Reset流式输出Settings */
    private void resetStreamSettings() {
        gotHeader = false;
        gotMetadata = false;
        metadata = null;
        pixelStream = null;
        imageStartPosition = -1L;
        frameImageStartPositions.clear();
        frameMetadata.clear();
        isAnimated = false;
        nextImageIndex = 0;
        animContainsIDAT = false;
    }
}

