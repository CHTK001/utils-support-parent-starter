package com.chua.image.support.filter;

import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.spi.annotations.SpiDescribe;
import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.utils.ThreadUtils;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 楂樻柉妯＄硦鍥惧儚婊ら暅
 *
 * 瀹炵幇楂樻柉妯＄硦鏁堟灉鐨勫浘鍍忔护闀滐紝閫氳繃搴旂敤楂樻柉鏍稿嚱鏁板鍥惧儚杩涜鍗风Н杩愮畻锛?
 * 浜х敓骞虫粦鐨勬ā绯婃晥鏋溿€傛敮鎸佸绾跨▼骞惰澶勭悊浠ユ彁楂樻€ц兘銆?
 *
 * 鎶€鏈師鐞嗭細
 * - 鍩轰簬楂樻柉鍒嗗竷鍑芥暟鐢熸垚鍗风Н鏍?
 * - 鍒嗗埆杩涜姘村钩鍜屽瀭鐩存柟鍚戠殑涓€缁村嵎绉?
 * - 浣跨敤鍙垎绂诲嵎绉彁楂樿绠楁晥鐜?
 * - 澶氱嚎绋嬪苟琛屽鐞哛GB涓変釜棰滆壊閫氶亾
 *
 * 绠楁硶鐗圭偣锛?
 * - 鍙皟鑺傛ā绯婂己搴︼紙sigma鍙傛暟锛?
 * - 鑷€傚簲鏍稿ぇ灏忚绠?
 * - 杈圭晫鍍忕礌澶勭悊
 * - 鍐呭瓨浼樺寲鐨勫疄鐜版柟寮?
 *
 * 搴旂敤鍦烘櫙锛?
 * - 鍥惧儚闄嶅櫔锛氬幓闄ゅ浘鍍忎腑鐨勯珮棰戝櫔澹?
 * - 鑹烘湳鏁堟灉锛氬垱寤烘煍鍜屻€佹ⅵ骞荤殑瑙嗚鏁堟灉
 * - 鑳屾櫙铏氬寲锛氱獊鍑轰富浣擄紝妯＄硦鑳屾櫙
 * - 鍥惧儚棰勫鐞嗭細涓哄悗缁鐞嗗噯澶囧钩婊戠殑鍥惧儚
 * - 缂╃暐鍥剧敓鎴愶細鍑忓皯缁嗚妭浠ラ€傚簲灏忓昂瀵告樉绀?
 *
 * @author CH
 * @version 1.0.0
 * @since 4.0.0.42
 */
@Slf4j
@SpiDescribe("楂樻柉妯＄硦婊ら暅")
@Spi("gaussianBlur")
@NoArgsConstructor
public class ImageGaussianBlurFilter extends AbstractImageFilter {

    /**
     * 楂樻柉鍗风Н鏍告暟缁?
     */
    private float[] kernel = new float[0];

    /**
     * 楂樻柉鍒嗗竷鐨勬爣鍑嗗樊锛屾帶鍒舵ā绯婄▼搴?
     */
    private double sigma = 2;

    /** 绾跨▼姹犳墽琛屽櫒 */
    ExecutorService mExecutor;

    /** 瀹屾垚鏈嶅姟锛岀鐞嗗苟鍙戜换鍔?*/
    CompletionService<Void> service;

    /**
     * 鏋勯€犲嚱鏁帮紝浣跨敤鑷畾涔夌殑鍗风Н鏍稿拰鏍囧噯宸?
     *
     * @param kernel 楂樻柉鍗风Н鏍告暟缁?
     * @param sigma  楂樻柉鍒嗗竷鐨勬爣鍑嗗樊
     */
    public ImageGaussianBlurFilter(float[] kernel, double sigma) {
        this.kernel = kernel;
        this.sigma = sigma;
    }

    /**
     * 鎵ц涓€缁撮珮鏂ā绯婂嵎绉?
     *
     * 瀵瑰浘鍍忕殑涓€涓鑹查€氶亾杩涜涓€缁撮珮鏂嵎绉繍绠椼€傞€氳繃鍒嗙鐨勬按骞冲拰鍨傜洿鍗风Н
     * 鏉ュ疄鐜颁簩缁撮珮鏂ā绯婏紝杩欑鏂规硶姣旂洿鎺ヤ簩缁村嵎绉洿楂樻晥銆?
     *
     * @param inPixels  杈撳叆鍍忕礌鏁版嵁鏁扮粍
     * @param outPixels 杈撳嚭鍍忕礌鏁版嵁鏁扮粍
     * @param width     鍥惧儚瀹藉害
     * @param height    鍥惧儚楂樺害
     */
    private void blur(byte[] inPixels, byte[] outPixels, int width, int height) {
        int subCol = 0;
        int index = 0, index2 = 0;
        float sum = 0;
        int k = kernel.length - 1;

        // 閫愯澶勭悊鍥惧儚
        for (int row = 0; row < height; row++) {
            int c = 0;
            index = row;

            // 閫愬垪澶勭悊鍍忕礌
            for (int col = 0; col < width; col++) {
                sum = 0;

                // 搴旂敤楂樻柉鍗风Н鏍?
                for (int m = -k; m < kernel.length; m++) {
                    subCol = col + m;

                    // 杈圭晫澶勭悊锛氳秴鍑鸿竟鐣屾椂浣跨敤杈圭晫鍍忕礌鍊?
                    if (subCol < 0 || subCol >= width) {
                        subCol = 0;
                    }

                    index2 = row * width + subCol;
                    c = inPixels[index2] & 0xff;
                    sum += c * kernel[Math.abs(m)];
                }

                // 闄愬埗缁撴灉鍦ㄦ湁鏁堣寖鍥村唴骞跺瓨鍌?
                outPixels[index] = (byte) BufferedImageUtils.clamp(sum);
                index += height;
            }
        }
    }

    /**
     * 鎵ц楂樻柉妯＄硦婊ら暅澶勭悊
     *
     * 瀵硅緭鍏ュ浘鍍忓簲鐢ㄩ珮鏂ā绯婃晥鏋溿€備娇鐢ㄥ绾跨▼骞惰澶勭悊RGB涓変釜棰滆壊閫氶亾锛?
     * 鍏堣繘琛屾按骞虫柟鍚戠殑妯＄硦锛屽啀杩涜鍨傜洿鏂瑰悜鐨勬ā绯婏紝瀹炵幇瀹屾暣鐨勪簩缁撮珮鏂ā绯娿€?
     *
     * @param src 婧愬浘鍍?
     * @param dst 鐩爣鍥惧儚锛堟鍙傛暟鏈娇鐢級
     * @return 搴旂敤楂樻柉妯＄硦鍚庣殑鍥惧儚
     */
    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        final int size = width * height;
        // RGB涓変釜棰滆壊閫氶亾
        // = 3;
        int dims = 3;

        // 鐢熸垚楂樻柉鍗风Н鏍?
        makeGaussianKernel(sigma, 0.002, Math.min(width, height));

        // 鍒涘缓绾跨▼姹犺繘琛屽苟琛屽鐞?
        mExecutor = ThreadUtils.newFixedThreadExecutor(dims, "gaussian-blur-task");
        service = new ExecutorCompletionService<>(mExecutor);

        // 涓烘瘡涓鑹查€氶亾鎻愪氦澶勭悊浠诲姟
        for (int i = 0; i < dims; i++) {
            final int channelIndex = i;
            service.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    byte[] inPixels = toColorByte(channelIndex);
                    byte[] tempPixels = new byte[size];

                    // 鍏堣繘琛屾按骞虫柟鍚戠殑楂樻柉妯＄硦
                    blur(inPixels, tempPixels, width, height);

                    // 鍐嶈繘琛屽瀭鐩存柟鍚戠殑楂樻柉妯＄硦
                    blur(tempPixels, inPixels, height, width);

                    return null;
                }
            });
        }

        // 绛夊緟鎵€鏈変换鍔″畬鎴?
        for (int i = 0; i < dims; i++) {
            try {
                service.take();
            } catch (InterruptedException e) {
                log.error("楂樻柉妯＄硦澶勭悊绾跨▼琚腑鏂?, e);
                Thread.currentThread().interrupt();
            }
        }

        // 鍏抽棴绾跨▼姹?
        mExecutor.shutdown();

        // 灏嗗鐞嗗悗鐨凴GB鏁版嵁杞崲涓築ufferedImage
        return toBitmap();
    }


    /**
     * 鐢熸垚楂樻柉鍗风Н鏍?
     *
     * 鏍规嵁缁欏畾鐨勬爣鍑嗗樊鍜岀簿搴﹁姹傜敓鎴愪竴缁撮珮鏂嵎绉牳銆?
     * 鍗风Н鏍哥殑澶у皬浼氭牴鎹爣鍑嗗樊鑷姩璁＄畻锛岀‘淇濆湪鎸囧畾绮惧害涓嬬殑楂樻柉鍒嗗竷杩戜技銆?
     *
     * @param sigma     楂樻柉鍒嗗竷鐨勬爣鍑嗗樊锛屾帶鍒舵ā绯婄▼搴?
     * @param accuracy  绮惧害瑕佹眰锛岀‘瀹氬嵎绉牳鐨勬埅鏂偣
     * @param maxRadius 鏈€澶у嵎绉牳鍗婂緞锛岄槻姝㈠嵎绉牳杩囧ぇ
     */
    public void makeGaussianKernel(final double sigma, final double accuracy, int maxRadius) {
        // 鏍规嵁绮惧害瑕佹眰璁＄畻鍗风Н鏍稿崐寰?
        int kRadius = (int) Math.ceil(sigma * Math.sqrt(-2 * Math.log(accuracy))) + 1;

        // 纭繚鏈€澶у崐寰勪笉灏忎簬50
        if (maxRadius < 50) {
            maxRadius = 50;
        }

        // 闄愬埗鍗风Н鏍稿ぇ灏?
        if (kRadius > maxRadius) {
            kRadius = maxRadius;
        }

        // 鍒涘缓鍗风Н鏍告暟缁?
        kernel = new float[kRadius];

        // 璁＄畻楂樻柉鍑芥暟鍊?
        for (int i = 0; i < kRadius; i++) {
            kernel[i] = (float) (Math.exp(-0.5 * i * i / sigma / sigma));
        }

        // 璁＄畻褰掍竴鍖栧洜瀛?
        double sum;
        if (kRadius < maxRadius) {
            // 绮剧‘璁＄畻褰掍竴鍖栧洜瀛?
            sum = kernel[0];
            for (int i = 1; i < kRadius; i++) {
                // 瀵圭О鎬э紝姣忎釜闈為浂椤硅绠椾袱娆?
                // * kernel[i];
                sum += 2 * kernel[i];
            }
        } else {
            // 浣跨敤鐞嗚鍊间綔涓哄綊涓€鍖栧洜瀛?
            sum = sigma * Math.sqrt(2 * Math.PI);
        }

        // 褰掍竴鍖栧嵎绉牳锛岀‘淇濇墍鏈夋潈閲嶄箣鍜屼负1
        for (int i = 0; i < kRadius; i++) {
            kernel[i] = (float) (kernel[i] / sum);
        }
    }
}
