package com.chua.filestorage.support.preview.provider;

import com.chua.common.support.collection.wrap.Wraps;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;
import com.chua.filestorage.support.preview.FileStoragePreviewProvider;
import com.chua.filestorage.support.preview.PreviewResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MOBI / AZW / AZW3 / PRC 电子书预览提供器。
 *
 * <p>SPI 类型：{@code preview-mobi}。不依赖任何第三方库，直接实现：</p>
 * <ul>
 *   <li>Palm Database (PDB) 容器解析（记录表、BOOKMOBI 标识）；</li>
 *   <li>PalmDOC LZ77 解压（compression == 2）；</li>
 *   <li>HUFF/CDIC 解压（compression == 0x4448，AZW3/KF8 主流压缩）；</li>
 *   <li>MOBI/EXTH 元数据（书名、作者、出版社、ISBN、语言等）；</li>
 *   <li>每条文本记录尾部条目（trailing entries / 多字节重叠）剥离。</li>
 * </ul>
 * <p>规范来源：MobileRead wiki（MOBI / PalmDOC）与 KindleUnpack 的
 * {@code mobi_header.py} / {@code mobi_uncompress.py} 实现口径。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("preview-mobi")
public class MobiPreviewProvider implements FileStoragePreviewProvider {

    private static final Set<String> SUPPORTED_EXTS = Set.of("mobi", "azw", "azw3", "prc"); // 支持exts
    private static final long MAX_FILE_SIZE = 128L * 1024 * 1024; // 最大文件大小
    /** 解压文本上限（字节），超过即停止，防止超大电子书拖垮内存 */
    private static final int MAX_TEXT_BYTES = 512 * 1024;

    @Override
    public boolean supports(String ext, String mime) {
        if (ext != null && SUPPORTED_EXTS.contains(ext.toLowerCase(Locale.ENGLISH))) {
            return true;
        }
        return mime != null && mime.toLowerCase(Locale.ENGLISH).contains("mobipocket");
    }

    @Override
    public PreviewResult preview(byte[] content, String ext, String mime) throws IOException {
        if (content.length == 0) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件为空")).build();
        }
        if (content.length > MAX_FILE_SIZE) {
            return PreviewResult.builder().htmlContent(unavailableHtml("文件过大（超过 128 MB），暂不支持预览")).build();
        }
        try {
            return PreviewResult.builder().htmlContent(parse(content)).build();
        } catch (Exception e) {
            return PreviewResult.builder()
                    .htmlContent(unavailableHtml("电子书解析失败: " + escape(e.getMessage()))).build();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 解析主流程                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 解析 MOBI/PDB 文件并产出预览 HTML。
     *
     * @param content 文件字节
     * @return 完整 HTML
     * @throws IOException 结构非法时抛出
     */
    private String parse(byte[] content) throws IOException {
        int[] offsets = recordOffsets(content);
        byte[] rec0 = slice(content, offsets[0], offsets.length > 1 ? offsets[1] : content.length);

        // PalmDOC 头（record 0 前 16 字节）
        int compression = be16(rec0, 0);
        int encryption = be16(rec0, 12);
        int recordCount = be16(rec0, 8);

        // MOBI 头（record 0 偏移 16 起）
        boolean bookmobi = matchesAscii(content, 60, "BOOK") && matchesAscii(content, 64, "MOBI");
        boolean hasMobi = rec0.length >= 24 && matchesAscii(rec0, 16, "MOBI");
        int mobiLength = hasMobi ? be32(rec0, 20) : 0;
        int version = hasMobi && rec0.length >= 108 ? be32(rec0, 104) : 0;
        int encoding = hasMobi && rec0.length >= 32 ? be32(rec0, 28) : 1252;
        Charset charset = charsetOf(encoding);

        Map<String, String> meta = Wraps.linkedMap();
        meta.put("压缩", compressionName(compression));
        meta.put("编码", encoding == 65001 ? "UTF-8" : "Windows-1252");
        if (recordCount > 0) {
            meta.put("文本记录", String.valueOf(recordCount));
        }

        // 书名：full name，随后被 EXTH updatedtitle 覆盖
        String title = "";
        if (hasMobi && rec0.length >= 92) {
            int toff = be32(rec0, 84);
            int tlen = be32(rec0, 88);
            if (toff > 0 && tlen > 0 && toff + tlen <= rec0.length) {
                title = new String(rec0, toff, tlen, charset).trim();
            }
        }
        if (title.isEmpty()) {
            title = new String(rec0, 0, Math.min(32, rec0.length), StandardCharsets.US_ASCII)
                    .replace("\u0000", "").trim();
        }

        // EXTH 元数据
        Map<String, String> exth = Wraps.linkedMap();
        int flags = 0;
        if (hasMobi && mobiLength >= 0xE4 && bookmobi && rec0.length >= 0xF4) {
            // 尾部条目标志（仅 BOOKMOBI + MOBI 头长度 ≥ 0xE4 时有效）
            flags = be16(rec0, 0xF2);
        }
        if (hasMobi && rec0.length >= 132 && (be32(rec0, 0x80) & 0x40) != 0) {
            parseExth(rec0, 16 + mobiLength, charset, exth);
        }
        if (exth.containsKey("updatedtitle") && !exth.get("updatedtitle").isBlank()) {
            title = exth.get("updatedtitle");
        }
        putIfPresent(meta, "书名", title);
        putIfPresent(meta, "作者", exth.get("author"));
        putIfPresent(meta, "出版社", exth.get("publisher"));
        putIfPresent(meta, "ISBN", exth.get("isbn"));
        putIfPresent(meta, "语言", exth.get("language"));
        putIfPresent(meta, "ASIN", exth.get("asin"));
        String desc = exth.get("description");

        // 正文
        StringBuilder body = new StringBuilder();
        String notice = null;
        if (encryption != 0) {
            notice = "该电子书受 DRM 加密保护，仅能展示元数据。";
        } else {
            String raw = decodeText(content, offsets, rec0, compression, recordCount, version, flags, bookmobi);
            if (raw == null || raw.isBlank()) {
                notice = "未能解出正文（compression=" + compression + "）。";
            } else {
                body.append(htmlToText(raw));
            }
        }
        return buildHtml(content.length, title, meta, desc, body.toString(), notice);
    }

    /**
     * 解压全部文本记录并拼接。
     *
     * @return 解码后的原文（HTML 片段）；解压失败返回 {@code null}
     */
    private String decodeText(byte[] content, int[] offsets, byte[] rec0, int compression,
                              int recordCount, int version, int flags, boolean bookmobi) {
        boolean hasMobi = rec0.length >= 24 && matchesAscii(rec0, 16, "MOBI");
        int mobiLength = hasMobi ? be32(rec0, 20) : 0;
        boolean trimmable = bookmobi && mobiLength >= 0xE4 && version >= 5;
        int encoding = hasMobi && rec0.length >= 32 ? be32(rec0, 28) : 1252;

        HuffReader huff = null;
        if (compression == 0x4448) {
            if (rec0.length < 0x78) {
                return null;
            }
            int huffOff = be32(rec0, 0x70);
            int huffNum = be32(rec0, 0x74);
            if (huffOff <= 0 || huffNum <= 0 || huffOff + huffNum > offsets.length) {
                return null;
            }
            List<byte[]> seeds = Wraps.list(huffNum);
            for (int i = 0; i < huffNum; i++) {
                int idx = huffOff + i;
                seeds.add(slice(content, offsets[idx], offsets[idx + 1]));
            }
            huff = new HuffReader();
            try {
                huff.load(seeds);
            } catch (RuntimeException e) {
                return null;
            }
        } else if (compression != 1 && compression != 2) {
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(MAX_TEXT_BYTES, 64 * 1024));
        int last = Math.min(recordCount, offsets.length - 2);
        for (int i = 1; i <= last; i++) {
            byte[] raw = slice(content, offsets[i], offsets[i + 1]);
            byte[] trimmed = trimmable ? trimTrailing(raw, flags) : raw;
            byte[] dec;
            try {
                if (compression == 1) {
                    dec = trimmed;
                } else if (compression == 2) {
                    dec = palmdoc(trimmed);
                } else {
                    dec = huff.unpack(trimmed);
                }
            } catch (RuntimeException e) {
                dec = null;
            }
            if (dec == null) {
                // 单条解压失败：保留已解出的部分，避免整篇空白
                break;
            }
            out.write(dec, 0, dec.length);
            if (out.size() >= MAX_TEXT_BYTES) {
                break;
            }
        }
        return new String(out.toByteArray(), charsetOf(encoding));
    }

    /* ------------------------------------------------------------------ */
    /* PDB / EXTH                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 解析 PDB 记录表，返回记录起始偏移（长度 = 记录数 + 1，末位为文件长度）。
     *
     * @param content 文件字节
     * @return 记录偏移数组
     * @throws IOException 结构非法时抛出
     */
    private static int[] recordOffsets(byte[] content) throws IOException {
        if (content.length < 80) {
            throw new IOException("不是合法的 PDB/MOBI 文件");
        }
        int n = be16(content, 76);
        if (n <= 0 || 78 + n * 8 > content.length) {
            throw new IOException("PDB 记录表非法");
        }
        int[] offs = new int[n + 1];
        for (int i = 0; i < n; i++) {
            offs[i] = be32(content, 78 + i * 8);
        }
        offs[n] = content.length;
        for (int i = 1; i <= n; i++) {
            if (offs[i] < offs[i - 1] || offs[i - 1] >= content.length) {
                throw new IOException("PDB 记录偏移乱序");
            }
        }
        if (offs[0] < 78 + n * 8) {
            throw new IOException("PDB 记录偏移越界");
        }
        return offs;
    }

    /**
     * 解析 EXTH 记录段（识别已知字段名）。
     *
     * @param rec0 record 0 数据
     * @param off  EXTH 起始偏移（相对 record 0）
     * @param cs   文本编码
     * @param out  结果映射
     */
    private static void parseExth(byte[] rec0, int off, Charset cs, Map<String, String> out) {
        if (off < 0 || off + 12 > rec0.length || !matchesAscii(rec0, off, "EXTH")) {
            return;
        }
        int total = be32(rec0, off + 4);
        int count = be32(rec0, off + 8);
        int p = off + 12;
        for (int i = 0; i < count && p + 8 <= rec0.length && p - off < total; i++) {
            int type = be32(rec0, p);
            int len = be32(rec0, p + 4);
            if (len < 8 || p + len > rec0.length) {
                break;
            }
            String name = exthName(type);
            if (name != null && !out.containsKey(name)) {
                String v = exthValue(type, rec0, p + 8, p + len, cs);
                if (!v.isBlank()) {
                    out.put(name, v);
                }
            }
            p += len;
        }
    }

    /**
     * EXTH 类型 → 字段名（未识别返回 {@code null}）。
     *
     * @param type EXTH 类型号
     * @return 字段名
     */
    private static String exthName(int type) {
        return switch (type) {
            case 100 -> "author";
            case 101 -> "publisher";
            case 103 -> "description";
            case 104 -> "isbn";
            case 113, 504 -> "asin";
            case 503 -> "updatedtitle";
            case 524 -> "language";
            default -> null;
        };
    }

    /**
     * EXTH 值转文本。
     *
     * @param type  EXTH 类型号
     * @param rec0  record 0 数据
     * @param start 值起始
     * @param end   值结束
     * @param cs    文本编码
     * @return 文本
     */
    private static String exthValue(int type, byte[] rec0, int start, int end, Charset cs) {
        int len = end - start;
        if (type == 524 && len == 4) {
            int loc = be32(rec0, start);
            return switch (loc & 0xFF) {
                case 9 -> "英语";
                case 4 -> "中文";
                case 7 -> "德语";
                case 10 -> "西班牙语";
                case 12 -> "法语";
                case 16 -> "日语";
                default -> String.valueOf(loc);
            };
        }
        if (len <= 0 || len > 4096) {
            return "";
        }
        return new String(rec0, start, len, cs).replace("\u0000", "").trim();
    }

    /**
     * 写入非空元数据项。
     *
     * @param meta  元数据映射
     * @param key   键
     * @param value 值（可空）
     */
    private static void putIfPresent(Map<String, String> meta, String key, String value) {
        if (value != null && !value.isBlank()) {
            meta.putIfAbsent(key, value.trim());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 尾部条目剥离                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * 剥离文本记录末尾的 trailing entries（口径与 KindleUnpack 一致）。
     *
     * <p>先按 bit1..bit15 的数量从末尾逐个剥掉 {@code <data><size>} 条目，
     * 最后若有 bit0（多字节重叠）再剥 {@code (last & 3) + 1} 字节。</p>
     *
     * @param data  原始记录
     * @param flags Extra Record Data Flags
     * @return 剥离后的记录（解析失败时原样返回，宁可留少量垃圾字节也不破坏正文）
     */
    private static byte[] trimTrailing(byte[] data, int flags) {
        if (flags == 0 || data.length == 0) {
            return data;
        }
        boolean multibyte = (flags & 1) != 0;
        int trailers = Integer.bitCount(flags >>> 1);
        byte[] buf = data;
        for (int i = 0; i < trailers; i++) {
            int num = trailingEntrySize(buf);
            if (num <= 0 || num > buf.length) {
                return data;
            }
            byte[] next = new byte[buf.length - num];
            System.arraycopy(buf, 0, next, 0, next.length);
            buf = next;
        }
        if (multibyte && buf.length > 0) {
            int num = (buf[buf.length - 1] & 3) + 1;
            if (num > buf.length) {
                return data;
            }
            byte[] next = new byte[buf.length - num];
            System.arraycopy(buf, 0, next, 0, next.length);
            buf = next;
        }
        return buf;
    }

    /**
     * 读取末尾条目大小：取最后 4 字节中从首个最高位为 1 的字节起累计的 7 位组。
     *
     * @param data 记录数据
     * @return 条目大小
     */
    private static int trailingEntrySize(byte[] data) {
        int num = 0;
        boolean inRange = false;
        for (int i = Math.max(0, data.length - 4); i < data.length; i++) {
            int b = data[i] & 0xFF;
            if ((b & 0x80) != 0) {
                num = 0;
                inRange = true;
            }
            if (inRange) {
                num = (num << 7) | (b & 0x7F);
            }
        }
        return num;
    }

    /* ------------------------------------------------------------------ */
    /* 解压                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * PalmDOC LZ77 解压（规范见 MobileRead wiki「PalmDoc byte pair compression」）。
     *
     * @param in 压缩数据（已剥离尾部条目）
     * @return 解压结果
     */
    private static byte[] palmdoc(byte[] in) {
        byte[] out = new byte[Math.max(64, in.length * 4)];
        int len = 0;
        int p = 0;
        while (p < in.length) {
            int c = in[p++] & 0xFF;
            if (c >= 1 && c <= 8) {
                int n = Math.min(c, in.length - p);
                for (int i = 0; i < n; i++) {
                    out = ensure(out, len + 1);
                    out[len++] = in[p + i];
                }
                p += n;
            } else if (c < 0x80) {
                out = ensure(out, len + 1);
                out[len++] = (byte) c;
            } else if (c >= 0xC0) {
                out = ensure(out, len + 2);
                out[len++] = ' ';
                out[len++] = (byte) (c ^ 0x80);
            } else {
                if (p >= in.length) {
                    break;
                }
                int pair = (c << 8) | (in[p++] & 0xFF);
                int dist = (pair >> 3) & 0x7FF;
                int n = (pair & 7) + 3;
                if (dist == 0 || dist > len) {
                    continue;
                }
                for (int i = 0; i < n; i++) {
                    out = ensure(out, len + 1);
                    out[len] = out[len - dist];
                    len++;
                }
            }
        }
        byte[] res = new byte[len];
        System.arraycopy(out, 0, res, 0, len);
        return res;
    }

    /**
     * 数组扩容（按需增长）。
     *
     * @param buf  原数组
     * @param need 需要的最小长度
     * @return 可容纳 {@code need} 的数组
     */
    private static byte[] ensure(byte[] buf, int need) {
        if (buf.length >= need) {
            return buf;
        }
        byte[] next = new byte[Math.max(need, buf.length * 2)];
        System.arraycopy(buf, 0, next, 0, Math.min(buf.length, need));
        return next;
    }

    /* ------------------------------------------------------------------ */
    /* HUFF/CDIC 解压                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * HUFF/CDIC 解压器（AZW3/KF8 使用的 {@code 0x4448} 压缩）。
     *
     * <p>实现口径与 KindleUnpack 的 {@code HuffcdicReader} 一致：
     * HUFF 记录提供 256 项 dict1 与 32 组 min/max code，
     * CDIC 记录提供分片词典；解码为滑动 64 位窗口、每次消耗 32 位。</p>
     */
    private static final class HuffReader {

        private final int[] dict1Len = new int[256];
        private final boolean[] dict1Term = new boolean[256];
        private final long[] dict1Max = new long[256];
        private final long[] mincode = new long[33];
        private final long[] maxcode = new long[33];
        private byte[][] dict;
        private boolean[] dictPlain;
        private int dictSize;

        /**
         * 载入 HUFF 种子（seeds[0]）与全部 CDIC 种子（seeds[1..]）。
         *
         * @param seeds 记录 huffoff..huffoff+huffnum-1 的内容
         */
        void load(List<byte[]> seeds) {
            byte[] huff = seeds.get(0);
            if (huff.length < 16 || !matchesAscii(huff, 0, "HUFF")) {
                throw new IllegalStateException("invalid HUFF header");
            }
            int off1 = be32(huff, 8);
            int off2 = be32(huff, 12);
            if (off1 < 0 || off1 + 256 * 4 > huff.length || off2 < 0 || off2 + 64 * 4 > huff.length) {
                throw new IllegalStateException("HUFF table out of range");
            }
            for (int i = 0; i < 256; i++) {
                int v = be32(huff, off1 + i * 4);
                int codelen = v & 0x1F;
                if (codelen == 0) {
                    dict1Len[i] = 0;
                    continue;
                }
                dict1Len[i] = codelen;
                dict1Term[i] = (v & 0x80) != 0;
                long maxc = v >>> 8;
                dict1Max[i] = ((maxc + 1) << (32 - codelen)) - 1;
            }
            long[] dict2 = new long[64];
            for (int i = 0; i < 64; i++) {
                dict2[i] = be32(huff, off2 + i * 4);
            }
            for (int cl = 1; cl <= 32; cl++) {
                mincode[cl] = dict2[(cl - 1) * 2] << (32 - cl);
                maxcode[cl] = ((dict2[(cl - 1) * 2 + 1] + 1) << (32 - cl)) - 1;
            }
            dict = new byte[1 << 16][];
            dictPlain = new boolean[1 << 16];
            dictSize = 0;
            for (int i = 1; i < seeds.size(); i++) {
                loadCdic(seeds.get(i));
            }
            if (dictSize == 0) {
                throw new IllegalStateException("empty CDIC dictionary");
            }
        }

        /**
         * 载入一片 CDIC 词典。
         *
         * @param cdic CDIC 记录内容
         */
        private void loadCdic(byte[] cdic) {
            if (cdic.length < 16 || !matchesAscii(cdic, 0, "CDIC")) {
                throw new IllegalStateException("invalid CDIC header");
            }
            int phrases = be32(cdic, 8);
            int bits = be32(cdic, 12);
            if (bits < 0 || bits > 15) {
                return;
            }
            int n = Math.min(1 << bits, phrases - dictSize);
            if (n <= 0 || 16 + n * 2 > cdic.length) {
                return;
            }
            for (int i = 0; i < n && dictSize < dict.length; i++) {
                int off = be16(cdic, 16 + i * 2);
                int at = 16 + off;
                if (at + 2 > cdic.length) {
                    break;
                }
                int blen = be16(cdic, at);
                int slen = blen & 0x7FFF;
                if (18 + off + slen > cdic.length) {
                    break;
                }
                byte[] part = new byte[slen];
                System.arraycopy(cdic, 18 + off, part, 0, slen);
                dict[dictSize] = part;
                dictPlain[dictSize] = (blen & 0x8000) != 0;
                dictSize++;
            }
        }

        /**
         * 解压一段 HUFF/CDIC 编码数据。
         *
         * @param data 压缩数据（已剥离尾部条目）
         * @return 解压结果
         */
        byte[] unpack(byte[] data) {
            return unpack(data, 0);
        }

        private byte[] unpack(byte[] data, int depth) {
            if (depth > 64 || data.length == 0) {
                return new byte[0];
            }
            byte[] padded = new byte[data.length + 8];
            System.arraycopy(data, 0, padded, 0, data.length);
            long bitsleft = (long) data.length * 8;
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16, data.length));
            int pos = 0;
            long x = readU64(padded, 0);
            int n = 32;
            while (true) {
                if (n <= 0) {
                    pos += 4;
                    if (bitsleft <= 0) {
                        break;
                    }
                    x = readU64(padded, pos);
                    n += 32;
                }
                long code = (x >>> n) & 0xFFFFFFFFL;
                int codelen = dict1Len[(int) (code >>> 24)];
                if (codelen <= 0 || codelen > 32) {
                    break;
                }
                long upper;
                if (dict1Term[(int) (code >>> 24)]) {
                    upper = dict1Max[(int) (code >>> 24)];
                } else {
                    while (codelen <= 32 && code < mincode[codelen]) {
                        codelen++;
                    }
                    if (codelen > 32) {
                        break;
                    }
                    upper = maxcode[codelen];
                }
                n -= codelen;
                bitsleft -= codelen;
                if (bitsleft < 0) {
                    break;
                }
                long r = (upper - code) >> (32 - codelen);
                if (r < 0 || r >= dictSize) {
                    break;
                }
                int ri = (int) r;
                byte[] part = dict[ri];
                if (part == null) {
                    break;
                }
                if (!dictPlain[ri]) {
                    dict[ri] = null;
                    part = unpack(part, depth + 1);
                    dict[ri] = part;
                    dictPlain[ri] = true;
                }
                out.write(part, 0, part.length);
            }
            return out.toByteArray();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 正文 HTML → 纯文本                                                  */
    /* ------------------------------------------------------------------ */

    /**
     * 把电子书正文 HTML 转成可读纯文本（保留段落结构、去脚本样式）。
     *
     * @param html 解压得到的 HTML 片段
     * @return 纯文本
     */
    private static String htmlToText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</\\s*(p|div|h[1-6]|li|tr|table|blockquote|section|article|html|body)>", "\n\n");
        s = s.replaceAll("(?i)<\\s*(p|div|h[1-6]|li|tr|blockquote|section|article)[^>]*>", "\n");
        s = s.replaceAll("(?i)</\\s*t[dh]\\s*>", "\t");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = unescape(s);
        s = s.replace('\u00a0', ' ');
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    /**
     * 基本 HTML 实体反转义（含数字实体）。
     *
     * @param s 原文
     * @return 反转义结果
     */
    private static String unescape(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }
            int semi = s.indexOf(';', i + 1);
            if (semi < 0 || semi - i > 10) {
                sb.append(c);
                i++;
                continue;
            }
            String entity = s.substring(i + 1, semi);
            String rep = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                case "nbsp", "#160" -> " ";
                case "mdash" -> "—";
                case "ndash" -> "–";
                case "hellip" -> "…";
                case "copy" -> "©";
                case "rsquo" -> "’";
                case "lsquo" -> "‘";
                case "rdquo" -> "”";
                case "ldquo" -> "“";
                default -> null;
            };
            if (rep != null) {
                sb.append(rep);
                i = semi + 1;
                continue;
            }
            try {
                if (entity.startsWith("#x") || entity.startsWith("#X")) {
                    sb.append((char) Integer.parseInt(entity.substring(2), 16));
                } else if (entity.startsWith("#")) {
                    sb.appendCodePoint(Integer.parseInt(entity.substring(1)));
                } else {
                    sb.append(c);
                    i++;
                    continue;
                }
                i = semi + 1;
            } catch (NumberFormatException e) {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 输出构建                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 构建预览 HTML（元数据 + 正文文章视图）。
     *
     * @param size   文件字节
     * @param title  书名
     * @param meta   元数据项
     * @param desc   简介（可空）
     * @param body   正文纯文本
     * @param notice 附加提示（可空）
     * @return 完整 HTML
     */
    private String buildHtml(long size, String title, Map<String, String> meta,
                             String desc, String body, String notice) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>")
                .append("body{margin:0;background:#f8f9fa;color:#1a1a2e;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;padding:20px}")
                .append(".header{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:20px;margin-bottom:20px}")
                .append("h1{margin:0 0 12px;font-size:20px;font-weight:600;line-height:1.4}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:8px;font-size:13px}")
                .append(".k{color:#9ca3af}.v{color:#1f2937;word-break:break-all}")
                .append(".desc{margin-top:12px;font-size:13px;color:#6b7280;line-height:1.7}")
                .append(".notice{margin-top:12px;padding:8px 12px;background:#fffbeb;border:1px solid #fde68a;border-radius:8px;color:#92400e;font-size:13px}")
                .append(".article{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:24px;font-size:15px;line-height:1.9;white-space:pre-wrap;word-break:break-word}")
                .append(".foot{color:#9ca3af;font-size:12px;margin-top:12px}")
                .append("</style></head><body>")
                .append("<div class=\"header\"><h1>")
                .append(title.isBlank() ? "电子书预览" : escape(title)).append("</h1>")
                .append("<div class=\"grid\">");
        meta.forEach((k, v) -> sb.append("<div><span class=\"k\">").append(escape(k))
                .append("</span><br><span class=\"v\">").append(escape(v)).append("</span></div>"));
        sb.append("</div><div class=\"k\">文件大小: ").append(readableSize(size)).append("</div>");
        if (desc != null && !desc.isBlank()) {
            sb.append("<div class=\"desc\">")
                    .append(escape(desc.length() > 600 ? desc.substring(0, 600) + "…" : desc))
                    .append("</div>");
        }
        if (notice != null && !notice.isBlank()) {
            sb.append("<div class=\"notice\">").append(escape(notice)).append("</div>");
        }
        sb.append("</div>");
        if (!body.isEmpty()) {
            sb.append("<div class=\"article\">").append(escape(body)).append("</div>");
            if (body.length() >= MAX_TEXT_BYTES / 2) {
                sb.append("<div class=\"foot\">正文较长，仅展示前部分内容。</div>");
            }
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * 构建不可预览页面。
     *
     * @param message 提示信息
     * @return 完整 HTML
     */
    private String unavailableHtml(String message) {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><style>"
                + "body{margin:0;background:#f8f9fa;color:#666;display:flex;justify-content:center;align-items:center;min-height:100vh;font-family:sans-serif}"
                + ".box{text-align:center;padding:40px;border:2px dashed #ddd;border-radius:12px}"
                + "</style></head><body><div class=\"box\">" + escape(message) + "</div></body></html>";
    }

    /**
     * 可读文件大小。
     *
     * @param bytes 字节数
     * @return 可读大小文本
     */
    private String readableSize(long bytes) {
        return com.chua.common.support.utils.FileUtils.readableFileSize(bytes);
    }

    /**
     * HTML 转义。
     *
     * @param text 原始文本
     * @return 转义后文本
     */
    private String escape(String text) {
        return text == null ? "" : StringUtils.escapeHtml(text);
    }

    /* ------------------------------------------------------------------ */
    /* 字节工具                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 按范围截取字节。
     *
     * @param src  源数组
     * @param from 起始
     * @param to   结束（不含）
     * @return 截取结果（越界自动夹取）
     */
    private static byte[] slice(byte[] src, int from, int to) {
        int a = Math.max(0, Math.min(from, src.length));
        int b = Math.max(a, Math.min(to, src.length));
        byte[] res = new byte[b - a];
        System.arraycopy(src, a, res, 0, b - a);
        return res;
    }

    /**
     * 大端 16 位读取。
     *
     * @param b   数组
     * @param off 偏移
     * @return 值
     */
    private static int be16(byte[] b, int off) {
        if (off < 0 || off + 2 > b.length) {
            return 0;
        }
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    /**
     * 大端 32 位读取。
     *
     * @param b   数组
     * @param off 偏移
     * @return 值
     */
    private static int be32(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) {
            return 0;
        }
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /**
     * 大端 64 位读取（不足补 0）。
     *
     * @param b   数组
     * @param off 偏移
     * @return 值
     */
    private static long readU64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int idx = off + i;
            v = (v << 8) | (idx >= 0 && idx < b.length ? (b[idx] & 0xFFL) : 0L);
        }
        return v;
    }

    /**
     * 判断偏移处是否为指定 ASCII 串。
     *
     * @param b   数组
     * @param off 偏移
     * @param s   期望串
     * @return 是否匹配
     */
    private static boolean matchesAscii(byte[] b, int off, String s) {
        if (off < 0 || off + s.length() > b.length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (b[off + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 文本编码码位 → Charset。
     *
     * @param encoding MOBI codepage（1252 / 65001）
     * @return Charset
     */
    private static Charset charsetOf(int encoding) {
        if (encoding == 65001) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName("windows-1252");
        } catch (RuntimeException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /**
     * 压缩方式展示名。
     *
     * @param compression PalmDOC 头的 compression 字段
     * @return 中文名
     */
    private static String compressionName(int compression) {
        return switch (compression) {
            case 1 -> "无压缩";
            case 2 -> "PalmDOC";
            case 0x4448 -> "HUFF/CDIC";
            default -> "未知 (" + compression + ")";
        };
    }
}
