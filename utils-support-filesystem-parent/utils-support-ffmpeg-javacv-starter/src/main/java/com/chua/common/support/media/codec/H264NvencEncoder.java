package com.chua.common.support.media.codec;

import com.chua.common.support.reflection.ReflectUtils;
import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

import static org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext;
import static org.bytedeco.ffmpeg.global.swscale.sws_scale;
import static org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR;

/**
* 鍩轰簬 NVENC (h264_nvenc) 鐨勭‖浠?H.264 缂栫爜鍣ㄣ€?*
* <p>浣跨敤 FFmpegFrameRecorder 灏佽锛岄€氳繃 {@code setVideoCodecName("h264_nvenc")} 寮哄埗鎸囧畾纭欢鍔犻€熴€?/p>
* <p>浠呮帴鍙?YUV420P 鏍煎紡鐨?Frame锛岃緭鍏ュ垎杈ㄧ巼瓒呰繃 1080p 鏃惰嚜鍔ㄧ缉鏀惧埌 1080p銆?/p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
@Spi(value = {"h264", "nvenc", "javacv-ffmpeg"}, order = 10)
public class H264NvencEncoder implements VideoEncoder {

    /**
    * 鏈€澶х紪鐮佸搴︼紙1080p锛?     */
    private static final int MAX_WIDTH = 1920;

    /**
    * 鏈€澶х紪鐮侀珮搴︼紙1080p锛?     */
    private static final int MAX_HEIGHT = 1080;

    /**
    * GOP 澶у皬锛堝叧閿抚闂撮殧锛?     */
    private static final int GOP_SIZE = 150;

    /**
    * 鍐呭瓨杈撳嚭娴佸垵濮嬪閲?     */
    private static final int MEMORY_STREAM_INITIAL_CAPACITY = 64 * 1024;

    /**
    * ffmpeg 甯у綍鍒跺櫒
     */
    private FFmpegFrameRecorder recorder;

    /**
    * 鍐呭瓨杈撳嚭娴?     */
    private ByteArrayOutputStream memoryStream;

    /**
    * 鍐呭瓨娴?鐨勬墿灞?holder锛屽厑璁?drain 閮ㄥ垎瀛楄妭骞?reset 閬垮厤鍙嶅鍏ㄩ噺澶嶅埗銆?     */
    private DrainableByteArrayOutputStream memoryStreamHolder;

    /**
    * 鑹插僵绌洪棿杞崲涓婁笅鏂囷紙缂╂斁鐢級
     */
    private SwsContext swsCtx;

    /**
    * 缂╂斁杈撳嚭甯х紦鍐插尯
     */
    private java.nio.ByteBuffer scaledBuf;

    /**
    * 缂栫爜瀹藉害锛堚墹1080p锛?     */
    private int encWidth;

    /**
    * 缂栫爜楂樺害锛堚墹1080p锛?     */
    private int encHeight;

    /**
    * 鐩爣甯х巼
     */
    private int fps;

    /**
    * 甯ф椂闂存埑
     */
    private long pts;

    /**
    * 鏄惁璇锋眰浜嗗叧閿抚
     */
    private boolean keyFrameRequested;

    /**
    * 鏄惁宸插惎鍔?     */
    private boolean started;

    /**
    * 甯ц鏁板櫒
     */
    private long frameIndex;

    /**
    * 涓婁竴涓叧閿抚绱㈠紩
     */
    private long lastKeyFrameIndex = -1;

    /**
    * 涓婁竴甯х殑 NAL 棣栧瓧鑺?     */
    private int prevFirstNalType = -1;

    /**
    * NVENC 鏄惁鍦ㄤ笅涓€甯у己鍒?IDR锛堥€氳繃 forced-idr 鏈熸潈 鎴?av_opt_璁剧疆锛?     */
    private boolean pendingForceIdr;

    /**
    * 绱Н鐨?NAL 缂撳啿鍖猴紝鐢ㄤ簬缂撳瓨褰撳墠 GOP 鍐呮墍鏈?NAL
     */
    private final java.io.ByteArrayOutputStream gopBuffer = new java.io.ByteArrayOutputStream(256 * 1024);

    /**
    * 涓婃 flush 鏃?gop缂撳啿 瀛楄妭鏁?     */
    private int lastGopSize = 0;

    /**
    * 涓婃 flush 鏃舵槸鍚﹀寘鍚?IDR
     */
    private boolean lastGopHasIdr = false;

    /**
    * 缂栫爜鍣ㄥ悕绉帮紙灏濊瘯涓嶅悓骞冲彴锛?     */
    private String codecName;

    /**
    * 鍙嶅皠鑾峰彇鐨?av鏍煎紡鍖栦笂涓嬫枃 瀛楁
     */
    private Field ocField;

    /**
    * 鍙嶅皠鑾峰彇鐨?avcodec涓婁笅鏂?瀛楁
     */
    private Field videoCField;

    /**
    * 浠庣紪鐮佸櫒 extradata 鎻愬彇鐨?SPS/PPS锛圓nnex B 鏍煎紡锛夛紝鐢ㄤ簬鎷兼帴鍒板叧閿抚澶撮儴
     */
    private byte[] spsPpsAnnexB;

    /**
    * 绌烘瀯閫犮€?     */
    public H264NvencEncoder() {
        ocField = ReflectUtils.findField(FFmpegFrameRecorder.class, "oc");
        videoCField = ReflectUtils.findField(FFmpegFrameRecorder.class, "video_c");
        if (ocField == null || videoCField == null) {
            log.warn("[H264NvencEncoder] 鍙嶅皠瀛楁鑾峰彇澶辫触: {}", ocField == null ? "oc" : "video_c");
        }
    }

    /**
    * 鍒濆鍖?NVENC 缂栫爜鍣ㄣ€?    *
    * @param width  杈撳叆瀹藉害
    * @param height 杈撳叆楂樺害
    * @param fps    鐩爣甯х巼
     */
    private void init(int width, int height, int fps) {
        close();
        this.encWidth = Math.min(ensureEven(width), MAX_WIDTH);
        this.encHeight = Math.min(ensureEven(height), MAX_HEIGHT);
        this.fps = Math.max(1, fps);
        this.pts = 0;
        this.frameIndex = 0;
        this.keyFrameRequested = false;
        this.memoryStreamHolder = new DrainableByteArrayOutputStream(MEMORY_STREAM_INITIAL_CAPACITY);
        this.memoryStream = memoryStreamHolder.asByteArrayOutputStream();

        // 鎸夊钩鍙颁紭鍏堢骇灏濊瘯纭欢缂栫爜鍣?        // 娉ㄦ剰锛歜ytedeco ffmpeg 7.1.1-1.5.12 鐨?h264_nvenc 鍦ㄦ湰鏈哄垵濮嬪寲鎴愬姛浣嗕笉宸ヤ綔锛坋ncodedLen=0锛夛紝
        // 鍥犳璺宠繃 nvenc锛岀洿鎺ュ皾璇?qsv/amf/vaapi銆?        String[] candidates = {"h264_qsv", "h264_amf", "h264_videotoolbox", "h264_vaapi", "h264_nvenc"};
        for (String name : candidates) {
            if (tryInitCodec(name)) {
                this.codecName = name;
                this.started = true;
                log.info("[H264NvencEncoder] 宸插惎鍔? {} {}x{} {}fps", name, encWidth, encHeight, fps);
                return;
            }
        }
        log.warn("[H264NvencEncoder] 鎵€鏈夌‖浠剁紪鐮佸櫒鍧囦笉鍙敤");
    }
    /**
    * 灏濊瘯鍒濆鍖栨寚瀹氱紪鐮佸櫒銆?    *
    * @param codecName 缂栫爜鍣ㄥ悕绉?    * @return 鍒濆鍖栨垚鍔熻繑鍥?true
     */
    private boolean tryInitCodec(String codecName) {
        try {
            FFmpegFrameRecorder r = new FFmpegFrameRecorder(
                    new MemoryOutputStream(memoryStream), encWidth, encHeight);
            r.setFormat("h264");
            r.setVideoCodecName(codecName);
            r.setFrameRate(fps);
            r.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            r.setInterleaved(true);
            r.setGopSize(60);
            r.setOption("preset", "p1");
            r.setOption("tune", "ll");
            r.setOption("zerolatency", "1");
            r.setVideoOption("repeat_headers", "1");
            r.setVideoOption("global_header", "0");
            r.setVideoOption("rc", "cbr");
            r.setVideoOption("delay", "0");
            r.start();
            this.recorder = r;
            loadSpsPpsFromExtradata();
            if (spsPpsAnnexB == null) {
                clearGlobalHeader();
            }
            log.info("[H264NvencEncoder] {} 鍒濆鍖栨垚鍔?loaded-from={} format=h264",
                    codecName,
                    getClass().getProtectionDomain() != null && getClass().getProtectionDomain().getCodeSource() != null
                            ? getClass().getProtectionDomain().getCodeSource().getLocation() : "unknown");
            return true;
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] {} 鍒濆鍖栧け璐? {}", codecName, e.getMessage());
            if (recorder != null) {
                try { recorder.stop(); } catch (Throwable ignored) {}
                try { recorder.release(); } catch (Throwable ignored) {}
                recorder = null;
            }
            return false;
        }
    }

    @Override
    /** 鑾峰彇codec鍚嶇О */
    public String getCodecName() {
        return codecName != null ? codecName : "none";
    }

    @Override
    /** 鑾峰彇codecid */
    public int getCodecId() {
        return org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
    }

    @Override
    /** 鏄惁hardware鍔犻€?*/
    public boolean isHardwareAccelerated() {
        return true;
    }

    @Override
    /** force閿抚 */
    public synchronized void forceKeyFrame() {
        this.keyFrameRequested = true;
        this.pendingForceIdr = true;
        forceNvencIdr();
    }

    /**
    * 閫氳繃 NVENC 绉佹湁閫夐」 {@code forced-idr} 寮哄埗涓嬩竴涓?IDR 甯с€?    * 浼樺厛灏濊瘯 {@code av_opt_set}锛屽け璐ユ椂鍥為€€鍒?{@code av_dict_set}銆?     */
    private void forceNvencIdr() {
        if (videoCField == null || recorder == null) {
            return;
        }
        try {
            AVCodecContext videoC = (AVCodecContext) ReflectUtils.getField(recorder, "video_c");
            if (videoC == null) {
                return;
            }
            org.bytedeco.ffmpeg.avutil.AVDictionary opts = new org.bytedeco.ffmpeg.avutil.AVDictionary();
            int r1 = org.bytedeco.ffmpeg.global.avutil.av_dict_set(opts, "forced-idr", "1", 0);
            org.bytedeco.ffmpeg.global.avutil.av_dict_free(opts);
            log.info("[H264NvencEncoder] forced-idr dict_set rc={}", r1);
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] forceNvencIdr 澶辫触: {}", e.getMessage());
        }
    }

    @Override
    /** 缂栫爜 */
    public synchronized byte[] encode(Frame frame) {
        if (frame == null) {
            return new byte[0];
        }
        int inW = frame.imageWidth;
        int inH = frame.imageHeight;
        if (inW <= 0 || inH <= 0) {
            return new byte[0];
        }
        if (!started || recorder == null) {
            init(inW, inH, 30);
            if (!started) {
                return new byte[0];
            }
        }
        try {
            return encodeFrame(frame);
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] 缂栫爜澶辫触: {}", e.getMessage());
            return new byte[0];
        }
    }

    /**
    * 缂栫爜鍗曞抚 YUV420P 鏁版嵁銆?    * <p>
    * 绛栫暐锛氱疮绉?NVENC 鐨勮繛缁?NAL 杈撳嚭锛屾瘡閬囧埌 IDR NAL 灏辨妸涓婃绱Н鐨勫唴瀹逛綔涓?GOP 鍧楄繑鍥烇紙鍚?SPS/PPS/IDR锛夛紝鍏朵粬鏃堕棿杩斿洖绌恒€?    * 杩欐牱淇濊瘉鍓嶇姣忔鏀跺埌鐨勯兘鏄畬鏁?GOP 鐨?IDR + SEI + IDR slice銆?    * </p>
    *
    * @param frame 杈撳叆 YUV 甯?    * @return 缂栫爜鍚庣殑 H264 鏁版嵁锛堝惈瀹屾暣 GOP IDR锛夛紝鎴栫┖锛堜腑闂寸殑 P/SEI 甯э級
     */
    private byte[] encodeFrame(Frame frame) throws Exception {
        long t0 = System.nanoTime();
        int inW = frame.imageWidth;
        int inH = frame.imageHeight;

        boolean requestKey = keyFrameRequested || frameIndex == 0;
        if (requestKey) {
            frame.keyFrame = true;
            keyFrameRequested = false;
        }
        frame.timestamp = pts++;

        long captureSize = memoryStream.size();

        long t1 = System.nanoTime();
        if (inW == encWidth && inH == encHeight) {
            recorder.record(frame);
        } else {
            Frame scaled = scaleFrame(frame, inW, inH);
            scaled.keyFrame = frame.keyFrame;
            scaled.timestamp = frame.timestamp;
            recorder.record(scaled);
        }
        long t2 = System.nanoTime();

        flushOutput();
        long t3 = System.nanoTime();

        // 淇 O(n虏) 鍐呭瓨鎷疯礉锛氫互鍓嶆瘡娆?toByteArray() 澶嶅埗鏁翠釜绱 buffer锛?0s 鍚?100MB+锛夛紝
 // 鐒跺悗 绯荤粺.arraycopy 鎴彇澧為噺銆傛敼涓轰粠 鍐呭瓨娴乭older 璇诲彇澧為噺鍚?reset銆?        long totalLen = memoryStream.size();
        long len = totalLen - captureSize;
        byte[] frameBytes;
        if (len <= 0) {
            memoryStreamHolder.reset();
            frameIndex++;
            return new byte[0];
        }
        frameBytes = memoryStreamHolder.drain((int) captureSize, (int) len);
        long t4 = System.nanoTime();

        boolean hasIdr = containsNalType(frameBytes, 5);
        long t5 = System.nanoTime();

        if (frameIndex < 16) {
            log.info("[H264NvencEncoder] STEP-TIMING idx={} recordUs={} flushUs={} copyUs={} nalScanUs={} totalUs={} len={} hasIdr={}",
                    frameIndex,
                    (t2 - t1) / 1000, (t3 - t2) / 1000, (t4 - t3) / 1000, (t5 - t4) / 1000,
                    (t5 - t0) / 1000, len, hasIdr);
        }

        if (spsPpsAnnexB == null && frameIndex < 16) {
            long te = System.nanoTime();
            extractSpsPpsFromFrame(frameBytes);
            long tf = System.nanoTime();
            if (frameIndex < 4) {
                log.info("[H264NvencEncoder] EXTRACT-SPS-PPS idx={} extractUs={} spsPps={}",
                        frameIndex, (tf - te) / 1000, spsPpsAnnexB != null ? spsPpsAnnexB.length : 0);
            }
        }

        // 淇锛氭瘡甯ч兘杩斿洖锛堜笉鍐嶄粎鍦?IDR 鏃惰繑鍥烇級銆係PS/PPS 浠呭湪棣栧抚鎴?IDR 鏃舵嫾鎺ュ埌澶撮儴銆? // P 甯т笉瑙ｇ爜涓嶅奖鍝嶆祻瑙堝櫒浣跨敤鈥斺€斿墠绔?webcodecs/webassembly 瑙ｇ爜鍣?鑳芥纭鐞?I/P 甯ф祦銆?        byte[] result;
        if (hasIdr && spsPpsAnnexB != null) {
            result = new byte[spsPpsAnnexB.length + frameBytes.length];
            System.arraycopy(spsPpsAnnexB, 0, result, 0, spsPpsAnnexB.length);
            System.arraycopy(frameBytes, 0, result, spsPpsAnnexB.length, frameBytes.length);
        } else {
            result = frameBytes;
        }
        frameIndex++;
        return result;
    }

    /**
    * 妫€鏌ュ抚鏁版嵁涓槸鍚﹀寘鍚寚瀹氱被鍨嬬殑 NAL銆?    *
    * @param data H264 鏁版嵁锛圓nnex B 鏍煎紡锛?    * @param nalType NAL 绫诲瀷锛?-31锛?    * @return true 琛ㄧず鍖呭惈
     */
    private static boolean containsNalType(byte[] data, int nalType) {
        if (data == null || data.length < 5) {
            return false;
        }
        for (int i = 0; i < data.length - 4; i++) {
            if ((data[i] & 0xff) == 0 && (data[i + 1] & 0xff) == 0
                    && (data[i + 2] & 0xff) == 0 && (data[i + 3] & 0xff) == 1) {
                if ((data[i + 4] & 0x1f) == nalType) {
                    return true;
                }
            } else if ((data[i] & 0xff) == 0 && (data[i + 1] & 0xff) == 0
                    && (data[i + 2] & 0xff) == 1) {
                if ((data[i + 3] & 0x1f) == nalType) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
    * 浠庡抚鏁版嵁涓壂鎻?NAL 鎻愬彇 SPS锛?x67锛夊拰 PPS锛?x68锛夈€?    *
    * @param data 甯у師濮?H264 鏁版嵁锛圓nnex B 鏍煎紡锛屽惈 00 00 00 01 璧峰鐮侊級
     */
    private void extractSpsPpsFromFrame(byte[] data) {
        if (data == null || data.length < 8) {
            return;
        }
        byte[] sps = null;
        byte[] pps = null;
        int i = 0;
        int start = -1;
        int headerLen = 0;
        while (i < data.length - 4) {
            if ((data[i] & 0xff) == 0 && (data[i + 1] & 0xff) == 0
                    && (data[i + 2] & 0xff) == 0 && (data[i + 3] & 0xff) == 1) {
                headerLen = 4;
            } else if ((data[i] & 0xff) == 0 && (data[i + 1] & 0xff) == 0
                    && (data[i + 2] & 0xff) == 1) {
                headerLen = 3;
            } else {
                i++;
                continue;
            }
            if (i + headerLen >= data.length) {
                break;
            }
            int nalType = data[i + headerLen] & 0x1f;
            int nextStart = data.length;
            for (int j = i + headerLen + 1; j < data.length - 3; j++) {
                if (((data[j] & 0xff) == 0 && (data[j + 1] & 0xff) == 0
                        && (data[j + 2] & 0xff) == 0 && (data[j + 3] & 0xff) == 1)
                        || ((data[j] & 0xff) == 0 && (data[j + 1] & 0xff) == 0
                        && (data[j + 2] & 0xff) == 1)) {
                    nextStart = j;
                    break;
                }
            }
            int nalLen = nextStart - (i + headerLen);
            if (nalLen > 0) {
                byte[] nalUnit = new byte[headerLen + nalLen];
                System.arraycopy(data, i, nalUnit, 0, headerLen + nalLen);
                if (nalType == 7 && sps == null) {
                    sps = nalUnit;
                } else if (nalType == 8 && pps == null) {
                    pps = nalUnit;
                }
            }
            i = nextStart;
        }
        if (sps != null && pps != null) {
            byte[] combined = new byte[sps.length + pps.length];
            System.arraycopy(sps, 0, combined, 0, sps.length);
            System.arraycopy(pps, 0, combined, sps.length, pps.length);
            this.spsPpsAnnexB = combined;
            log.info("[H264NvencEncoder] 浠?IDR 甯ф暟鎹腑鎻愬彇鍒?SPS ({}B) + PPS ({}B)", sps.length, pps.length);
        }
    }

    /**
    * bytes杞负hex
    *
    * @param data 鏁版嵁
    * @param n n
    * @return bytes杞负hex鐨勭粨鏋?     */
    private static String bytesToHex(byte[] data, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x ", data[i] & 0xff));
        }
        return sb.toString().trim();
    }

    /**
    * 娓呴櫎 AV_CODEC_FLAG_鍏ㄥ眬_澶撮儴 鏍囧織锛屽己鍒剁紪鐮佸櫒鍦ㄦ瘡甯т腑鍐欏叆 SPS/PPS銆?     */
    private void clearGlobalHeader() {
        if (videoCField == null || recorder == null) {
            return;
        }
        try {
            AVCodecContext videoC = (AVCodecContext) ReflectUtils.getField(recorder, "video_c");
            if (videoC != null) {
                int flags = videoC.flags();
                videoC.flags(flags & ~(1 << 22));
            }
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] clearGlobalHeader 澶辫触: {}", e.getMessage());
        }
    }

    /**
    * 浠庣紪鐮佸櫒 extradata 涓彁鍙?SPS/PPS 骞惰浆鎹负 Annex B 鏍煎紡銆?     */
    private void loadSpsPpsFromExtradata() {
        if (videoCField == null || recorder == null) {
            return;
        }
        try {
            AVCodecContext videoC = (AVCodecContext) ReflectUtils.getField(recorder, "video_c");
            if (videoC == null) {
                return;
            }
            BytePointer extradata = videoC.extradata();
            int extradataSize = videoC.extradata_size();
            if (extradata == null || extradataSize < 7) {
                log.warn("[H264NvencEncoder] extradata 涓虹┖鎴栬繃灏? {}", extradataSize);
                return;
            }
            byte[] data = new byte[extradataSize];
            extradata.get(data);
 // avcc 鏍煎紡: 5 瀛楄妭澶?+ SPS 鍒楄〃 + PPS 鍒楄〃
            if (data[0] != 1) {
                log.warn("[H264NvencEncoder] extradata version != 1: {}", data[0]);
                return;
            }
 // numsps 鍦?鏁版嵁[5] 鐨勪綆 5 浣?            int numSPS = data[5] & 0x1f;
            int pos = 6;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int i = 0; i < numSPS; i++) {
                if (pos + 2 > data.length) {
                    break;
                }
                int spsLen = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
                pos += 2;
                if (pos + spsLen > data.length) {
                    break;
                }
                baos.write(0x00); baos.write(0x00); baos.write(0x00); baos.write(0x01);
                baos.write(data, pos, spsLen);
                pos += spsLen;
            }
            if (pos + 1 > data.length) {
                log.warn("[H264NvencEncoder] extradata 缂哄皯 PPS 鍒楄〃: {}", data.length);
                return;
            }
            int numPPS = data[pos] & 0x1f;
            pos += 1;
            for (int i = 0; i < numPPS; i++) {
                if (pos + 2 > data.length) {
                    break;
                }
                int ppsLen = ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff);
                pos += 2;
                if (pos + ppsLen > data.length) {
                    break;
                }
                baos.write(0x00); baos.write(0x00); baos.write(0x00); baos.write(0x01);
                baos.write(data, pos, ppsLen);
                pos += ppsLen;
            }
            spsPpsAnnexB = baos.toByteArray();
            log.info("[H264NvencEncoder] 鎴愬姛鎻愬彇 SPS/PPS, 澶у皬: {} 瀛楄妭", spsPpsAnnexB.length);
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] 鍔犺浇 SPS/PPS 澶辫触: {}", e.getMessage());
        }
    }

    /**
    * 鍒锋柊 AVIO 杈撳嚭缂撳啿鍖猴紝纭繚缂栫爜鏁版嵁鍐欏叆 鍐呭瓨娴併€?     */
    private void flushOutput() {
        if (ocField == null || recorder == null) {
            return;
        }
        try {
            org.bytedeco.ffmpeg.avformat.AVFormatContext oc = (org.bytedeco.ffmpeg.avformat.AVFormatContext) ReflectUtils.getField(recorder, "oc");
            if (oc != null && oc.pb() != null && oc.pb().buffer() != null) {
                org.bytedeco.ffmpeg.global.avformat.avio_flush(oc.pb());
            }
        } catch (Throwable e) {
            log.warn("[H264NvencEncoder] flushOutput 澶辫触: {}", e.getMessage());
        }
    }

    /**
    * 缂╂斁 YUV420P 甯у埌鐩爣灏哄銆?    *
    * @param frame 杈撳叆甯?    * @param inW   杈撳叆瀹藉害
    * @param inH   杈撳叆楂樺害
    * @return 缂╂斁鍚庣殑 甯?     */
    private Frame scaleFrame(Frame frame, int inW, int inH) {
        BytePointer srcData;
        if (frame.image[0] instanceof java.nio.ByteBuffer buf) {
            srcData = new BytePointer(buf).position(0);
        } else {
            srcData = new BytePointer(new org.bytedeco.javacpp.Pointer(frame.image[0]).position(0));
        }

        // 鍒嗛厤鎴栧鐢ㄧ缉鏀剧紦鍐插尯
        int ySize = encWidth * encHeight;
        int uSize = (encWidth / 2) * (encHeight / 2);
        int totalSize = ySize + uSize * 2;
        if (scaledBuf == null || scaledBuf.capacity() < totalSize) {
            scaledBuf = java.nio.ByteBuffer.allocateDirect(totalSize);
        }
        scaledBuf.clear();

 // sws_scale 杈撳嚭鍒颁复鏃?av甯?        org.bytedeco.ffmpeg.avutil.AVFrame tmpFrame = org.bytedeco.ffmpeg.global.avutil.av_frame_alloc();
        int size = org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size(
                avutil.AV_PIX_FMT_YUV420P, encWidth, encHeight, 1);
        BytePointer tmpBuf = new BytePointer(org.bytedeco.ffmpeg.global.avutil.av_malloc(size));
        org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays(
                new PointerPointer(tmpFrame), tmpFrame.linesize(), tmpBuf,
                avutil.AV_PIX_FMT_YUV420P, encWidth, encHeight, 1);
        tmpFrame.format(avutil.AV_PIX_FMT_YUV420P);
        tmpFrame.width(encWidth);
        tmpFrame.height(encHeight);

        SwsContext sws = sws_getCachedContext(swsCtx, inW, inH, avutil.AV_PIX_FMT_YUV420P,
                encWidth, encHeight, avutil.AV_PIX_FMT_YUV420P, SWS_BILINEAR, null, null, (double[]) null);
        this.swsCtx = sws;

        int[] srcStride = new int[]{inW, inW / 2, inW / 2};
        sws_scale(sws, new PointerPointer(srcData), new IntPointer(srcStride),
                0, inH, new PointerPointer(tmpFrame), tmpFrame.linesize());

 // 澶嶅埗鍒?javacv 甯?        byte[] plane = new byte[Math.max(ySize, uSize)];
        new BytePointer(tmpFrame.data(0)).get(plane, 0, ySize);
        scaledBuf.put(plane, 0, ySize);
        new BytePointer(tmpFrame.data(1)).get(plane, 0, uSize);
        scaledBuf.put(plane, 0, uSize);
        new BytePointer(tmpFrame.data(2)).get(plane, 0, uSize);
        scaledBuf.put(plane, 0, uSize);
        scaledBuf.rewind();

        org.bytedeco.ffmpeg.global.avutil.av_frame_free(tmpFrame);
        org.bytedeco.ffmpeg.global.avutil.av_free(tmpBuf);

        Frame result = new Frame(encWidth, encHeight, Frame.DEPTH_UBYTE, 2);
        result.imageWidth = encWidth;
        result.imageHeight = encHeight;
        result.imageStride = encWidth;
        result.image[0] = scaledBuf;
        return result;
    }

    @Override
    /** 璁剧疆Crf */
    public synchronized void setCrf(int crf) {
 // NVENC 閫氳繃 閽诲ご_rate 鎺у埗璐ㄩ噺锛宺ecorder 涓嶇洿鎺ユ敮鎸佸姩鎬佷慨鏀?    }

    @Override
    /** 鍏抽棴 */
    public synchronized void close() {
        if (!started) {
            return;
        }
        started = false;
        if (recorder != null) {
            try { recorder.flush(); } catch (Throwable ignored) {}
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
        }
        if (swsCtx != null) {
            org.bytedeco.ffmpeg.global.swscale.sws_freeContext(swsCtx);
            swsCtx = null;
        }
        if (memoryStreamHolder != null) {
            memoryStreamHolder.reset();
        }
    }

    /**
    * 纭繚鏁板€间负鍋舵暟銆?    *
    * @param v 鍘熷鏁板€?    * @return 璋冩暣鍚庣殑鍋舵暟
     */
    private static int ensureEven(int v) {
        return v + (v & 1);
    }

    /**
    * 鍐呭瓨杈撳嚭娴侀€傞厤鍣ㄣ€?     */
    private static final class MemoryOutputStream extends OutputStream {

        /**
        * 搴曞眰瀛楄妭鏁扮粍杈撳嚭娴?         */
        private final ByteArrayOutputStream backing;

        /**
        * 鏋勯€犲唴瀛樿緭鍑烘祦銆?        *
        * @param backing 搴曞眰瀛楄妭鏁扮粍杈撳嚭娴?         */
        MemoryOutputStream(ByteArrayOutputStream backing) {
            this.backing = backing;
        }

        @Override
        /** 鍐欏叆 */
        public void write(int b) {
            backing.write(b);
        }

        @Override
        /** 鍐欏叆 */
        public void write(byte[] b, int off, int len) {
            backing.write(b, off, len);
        }

        @Override
        /** 鍏抽棴 */
        public void close() {
        }
    }

    /**
    * 鍙閲忔帓鍑猴紙drain锛夊瓧鑺傜殑 bytearray杈撳嚭娴併€?    * <p>
    * 瑙ｅ喅 H264nvenc缂栫爜鍣?鏃у疄鐜颁腑 {@code memoryStream.toByteArray() + System.arraycopy}
    * 甯︽潵鐨?O(n虏) 鍐呭瓨鎷疯礉闂锛氭瘡娆＄紪鐮佷竴甯у墠锛岀紪鐮佸櫒璁板綍 {@code memoryStream.size()}锛?    * 缂栫爜 + flush 涔嬪悗闇€瑕佽鍙栧閲忓苟娓呯┖銆傚鏋滅洿鎺ヨ皟鐢?{@code toByteArray()}锛?    * 30 绉掑悗绱 缂撳啿 杈惧埌 100MB+ 鍚庯紝姣忔閮戒細瀹屾暣澶嶅埗 100MB銆?    * </p>
    * <p>
    * 鏈被閫氳繃 {@link #drain(int, int)} 鐩存帴璇诲彇骞舵竻绌烘寚瀹氬尯闂达紝閬垮厤閲嶅鎵弿銆?    * </p>
     */
    private static final class DrainableByteArrayOutputStream extends ByteArrayOutputStream {
        DrainableByteArrayOutputStream(int capacity) {
            super(capacity);
        }

        /**
        * 璇诲彇 [鍋忕Щ閲? 鍋忕Щ閲?闀垮害) 鍖洪棿鐨勫瓧鑺傚苟 reset() 娓呯┖鏁翠釜绱鍖恒€?        *
        * @param offset 鍋忕Щ閲?        * @param length 闀垮害
        * @return drain鐨勭粨鏋?         */
        synchronized byte[] drain(int offset, int length) {
            byte[] out = new byte[length];
            System.arraycopy(this.buf, offset, out, 0, length);
            this.reset();
            return out;
        }

        /** 鏆撮湶搴曞眰 bytearray杈撳嚭娴?瑙嗗浘锛堢敤浜?鍐呭瓨杈撳嚭娴?閫傞厤锛夈€?*/
        ByteArrayOutputStream asByteArrayOutputStream() {
            return this;
        }
    }
}
