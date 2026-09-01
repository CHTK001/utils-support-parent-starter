package com.chua.common.support.ai.audio;

import com.chua.common.support.ai.chat.ModelDefinition;
import com.chua.common.support.pool.PooledObjectClient;
import com.chua.common.support.spi.ServiceProvider;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * AI 璇煶璇嗗埆锛圓SR锛夊鎴风鎺ュ彛
 *
 * <p>鎻愪緵缁熶竴鐨勮闊宠浆鏂囧瓧鏈嶅姟鎶借薄锛屾敮鎸佸悓姝ヨ浆鍐欏拰寮傛浠诲姟涓ょ妯″紡銆? * 瀹炵幇绫婚€氳繃 SPI 鏈哄埗鎸?provider 鍚嶇О娉ㄥ唽锛岃皟鐢ㄦ柟閫氳繃宸ュ巶鏂规硶鑾峰彇瀹炰緥銆? *
 * <p>鍚屾杞啓绀轰緥锛? * <pre>{@code
 *   String text = VirtualClient.create("whisper", "sk-xxx")
 *       .model("whisper-1")
 *       .language("zh")
 *       .transcribe(Path.of("audio.wav"));
 * }</pre>
 *
 * <p>寮傛浠诲姟绀轰緥锛? * <pre>{@code
 *   String taskId = VirtualClient.create("alibaba-asr", "sk-xxx")
 *       .model("paraformer-v2")
 *       .createTask(Path.of("audio.wav"));
 *
 *   // 杞鏌ヨ浠诲姟缁撴灉
 *   AudioResponse resp = client.queryTask(taskId);
 *   if (resp.getStatus() == AudioResponse.Status.SUCCESS) {
 *       String transcript = resp.getTranscript();
 *   }
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface VirtualClient extends AutoCloseable, PooledObjectClient<VirtualClient> {

    /**
     * 鍒涘缓鎸囧畾 provider 鐨勮闊宠瘑鍒鎴风
     *
     * @param provider AI 鏈嶅姟鍟嗗悕绉帮紝濡?"openai"銆?whisper"銆?alibaba-asr" 绛?     * @param apiKey   API 瀵嗛挜
     * @return VirtualClient 瀹炰緥
     */
    static VirtualClient create(String provider, String apiKey) {
        return ServiceProvider.of(VirtualClient.class)
                .getNewExtension(provider, AudioClientSetting.builder()
                        .provider(provider).appKey(apiKey).build());
    }

    /**
     * 閫氳繃瀹屾暣閰嶇疆鍒涘缓璇煶璇嗗埆瀹㈡埛绔?     *
     * @param setting 瀹㈡埛绔厤缃紝鍖呭惈 provider銆乤piKey銆乥aseUrl銆乵odel 绛?     * @return VirtualClient 瀹炰緥
     */
    static VirtualClient create(AudioClientSetting setting) {
        return ServiceProvider.of(VirtualClient.class)
                .getNewExtension(setting.getProvider(), setting);
    }

    /**
     * 鍒涘缓鎸囧畾 provider 鍜岃嚜瀹氫箟鍦板潃鐨勮闊宠瘑鍒鎴风
     *
     * @param provider AI 鏈嶅姟鍟嗗悕绉?     * @param apiKey   API 瀵嗛挜
     * @param baseUrl  鑷畾涔?API 鍩哄湴鍧€
     * @return VirtualClient 瀹炰緥
     */
    static VirtualClient create(String provider, String apiKey, String baseUrl) {
        return ServiceProvider.of(VirtualClient.class)
                .getNewExtension(provider, AudioClientSetting.builder()
                        .provider(provider).appKey(apiKey).baseUrl(baseUrl).build());
    }

    /**
     * 璁剧疆 AI 鏈嶅姟鍟?     *
     * @param provider 鏈嶅姟鍟嗗悕绉?     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient provider(String provider) {
        return this;
    }

    /**
     * 璁剧疆妯″瀷鍚嶇О
     *
     * @param model 妯″瀷鍚嶇О锛屽 "whisper-1"銆?whisper-tiny"銆?paraformer-v2" 绛?     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient model(String model) {
        return this;
    }

    /**
     * 璁剧疆闊抽璇█
     *
     * <p>鏄惧紡鎸囧畾闊抽璇█鍙彁鍗囪瘑鍒噯纭巼涓庨€熷害銆?     * 鐣欑┖鍒欑敱妯″瀷鑷姩妫€娴嬶紙濡?Whisper 鑷姩璇嗗埆璇锛夈€?     * 甯歌鍊硷細"zh"锛堜腑鏂囷級銆?en"锛堣嫳鏂囷級銆?ja"锛堟棩鏂囷級绛?ISO 639-1 浠ｇ爜銆?     *
     * @param language 璇█浠ｇ爜
     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient language(String language) {
        return this;
    }

    /**
     * 璁剧疆閲囨牱鐜?     *
     * <p>澶у鏁?ASR 鏈嶅姟浼氳嚜鍔ㄩ噸閲囨牱锛屽彲閫夎鐩栥€?     * 甯歌鍊硷細16000銆?4000銆?4100銆?8000銆?     *
     * @param sampleRate 閲囨牱鐜囷紙Hz锛?     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient sampleRate(Integer sampleRate) {
        return this;
    }

    /**
     * 璁剧疆闊抽鏍煎紡
     *
     * <p>濡?"wav"銆?mp3"銆?m4a"銆?flac" 绛夈€?     * 鐣欑┖鍒欑敱瀹炵幇绫讳粠鏂囦欢鎵╁睍鍚嶆帹鏂€?     *
     * @param format 闊抽鏍煎紡
     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient format(String format) {
        return this;
    }

    /**
     * 璁剧疆鎻愮ず璇?     *
     * <p>鐢ㄤ簬寮曞妯″瀷璇嗗埆鐗瑰畾鏈銆佷笓鏈夊悕璇嶆垨璇椋庢牸銆?     * 渚嬪鍖诲浼氳銆佸鏈嶅綍闊充腑鐨勪笓鏈夎瘝姹囥€?     *
     * @param prompt 鎻愮ず璇?     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient prompt(String prompt) {
        return this;
    }

    /**
     * 璁剧疆娓╁害
     *
     * <p>閲囨牱娓╁害锛? 琛ㄧず鏈€纭畾锛堣椽蹇冿級锛岃秺楂樼粨鏋滆秺鍙戞暎銆?     * 澶у鏁?ASR 鏈嶅姟榛樿 0锛屾俯搴︿粎鍦ㄥ皯鏁版敮鎸侀噰鏍风殑 ASR 妯″瀷涓敓鏁堛€?     *
     * @param temperature 娓╁害
     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient temperature(Double temperature) {
        return this;
    }

    /**
     * 璁剧疆闅忔満绉嶅瓙
     *
     * @param seed 闅忔満绉嶅瓙
     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient seed(Long seed) {
        return this;
    }

    /**
     * 璁剧疆闊抽瀛楄妭鏁版嵁
     *
     * <p>閮ㄥ垎鏈嶅姟鏀寔鐩存帴涓婁紶闊抽浜岃繘鍒讹紝
     * 浼樺厛浜庢枃浠惰矾寰勬柟寮忋€?     *
     * @param audio 闊抽瀛楄妭
     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient audio(byte[] audio) {
        return this;
    }

    /**
     * 璁剧疆闊抽杈撳叆娴?     *
     * @param input 闊抽杈撳叆娴?     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient audio(InputStream input) {
        return this;
    }

    /**
     * 璁剧疆闊抽鏂囦欢璺緞
     *
     * @param path 闊抽鏂囦欢璺緞
     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient audio(Path path) {
        return this;
    }

    /**
     * 璁剧疆璇磋瘽浜烘暟
     *
     * <p>閮ㄥ垎鏈嶅姟鏀寔璇磋瘽浜哄垎绂伙紙diarization锛夛紝
     * 0 琛ㄧず鐢辨ā鍨嬭嚜鍔ㄦ帹鏂€?     *
     * @param speakers 璇磋瘽浜烘暟
     * @return 褰撳墠瀹㈡埛绔疄渚嬶紝鏀寔閾惧紡璋冪敤
     */
    default VirtualClient speakers(Integer speakers) {
        return this;
    }

    /**
     * 鍚屾杞啓闊抽涓烘枃瀛?     *
     * <p>闃诲绛夊緟鏈嶅姟绔繑鍥炲畬鏁磋瘑鍒粨鏋溿€?     *
     * @param path 闊抽鏂囦欢璺緞
     * @return 杞啓寰楀埌鐨勬枃瀛?     */
    String transcribe(Path path);

    /**
     * 鍚屾杞啓闊抽涓烘枃瀛楋紙浣跨敤宸查厤缃殑 audio 瀛楄妭锛?     *
     * @return 杞啓寰楀埌鐨勬枃瀛?     */
    default String transcribe() {
        return transcribe((Path) null);
    }

    // ==================== 娴佸紡杞綍闂ㄩ潰 ====================

    /**
     * 娴佸紡杞啓锛氫竴娆℃€т紶鍏ュ畬鏁撮煶棰戞牱鏈紝杩斿洖澧為噺璇嗗埆缁撴灉銆?     *
     * <p>閫傜敤浜庨害鍏嬮瀹炴椂杈撳叆鍦烘櫙锛屽唴閮ㄦ寜 chunk 閫佸叆妯″瀷骞剁疮绉緭鍑恒€?     * 涓嶆敮鎸佹祦寮忕殑瀹㈡埛绔洿鎺ュ鎵樼粰 {@link #transcribe()}銆?     *
     * <pre>{@code
     *   VirtualClient client = VirtualClient.create("zipformer-zh", "");
     *   // 浠庨害鍏嬮閫愮墖璇诲叆 16kHz float samples锛堟瘡鐗囩害 2560 samples = 160ms锛?     *   StringBuilder sb = new StringBuilder();
     *   while (hasMore) {
     *       float[] chunk = readMicrophoneChunk();
     *       client.feedAudio(chunk);
     *       String incremental = client.getResult();
     *       if (incremental != null && !incremental.isEmpty()) {
     *           sb.append(incremental);
     *           System.out.println("[stream] " + sb);
     *       }
     *   }
     *   String finalText = client.complete();
     * }</pre>
     *
     * @param samples 闊抽鏍锋湰鏁扮粍锛?6kHz mono锛宖loat 鑼冨洿 [-1, 1]锛?     */
    default void feedAudio(float[] samples) {
        // 涓嶆敮鎸佹祦寮忕殑瀹㈡埛绔拷鐣ユ鏂规硶
    }

    /**
     * 鑾峰彇褰撳墠娴佸紡杞綍鐨勫閲忔枃鏈€?     *
     * <p>鍙娆¤皟鐢紝姣忔杩斿洖鑷笂娆¤皟鐢ㄤ互鏉ユ柊澧炵殑璇嗗埆鏂囨湰锛堝鏈夛級銆?     * 娴佸紡妯″瀷浼氱紦瀛樺閲忥紝闈炴祦寮忔ā鍨嬭繑鍥?{@code null}銆?     *
     * @return 澧為噺鏂囨湰锛屼笉鏀寔娴佸紡鏃惰繑鍥?{@code null}
     */
    default String getResult() {
        return null;
    }

    /**
     * 瀹屾垚娴佸紡杞綍锛岄噴鏀剧姸鎬侊紝杩斿洖鏈€缁堝畬鏁存枃鏈€?     *
     * <p>璋冪敤鍚庨渶閲嶆柊璋冪敤 {@link #feedAudio} 寮€濮嬫柊鐨勮浆褰曘€?     * 涓嶆敮鎸佹祦寮忕殑瀹㈡埛绔洿鎺ュ鎵樼粰 {@link #transcribe()}銆?     *
     * @return 鏈€缁堝畬鏁磋瘑鍒枃鏈?     */
    default String complete() {
        return transcribe();
    }

    /**
     * 涓€娆℃€ф祦寮忚浆鍐欎究鎹锋柟娉曘€?     *
     * <p>鍐呴儴鑷姩绠＄悊 feed/getResult/complete 鐢熷懡鍛ㄦ湡锛?     * 閫傜敤浜庡凡鐭ュ畬鏁撮煶棰戠墖娈典絾涓嶉渶瑕佸閲忓洖璋冪殑鍦烘櫙銆?     *
     * @param samples 瀹屾暣闊抽鏍锋湰锛?6kHz mono锛宖loat [-1, 1]锛?     * @return 璇嗗埆鏂囨湰
     */
    default String streamingTranscribe(float[] samples) {
        feedAudio(samples);
        String result = getResult();
        complete();
        return result != null ? result : transcribe();
    }

    /**
     * 鍒涘缓璇煶璇嗗埆浠诲姟锛堝紓姝ユā寮忥級
     *
     * <p>鎻愪氦浠诲姟鍚庣珛鍗宠繑鍥烇紝涓嶇瓑寰呬换鍔″畬鎴愩€傞渶閰嶅悎 {@link #queryTask(String)} 杞缁撴灉銆?     *
     * @param path 闊抽鏂囦欢璺緞
     * @return 浠诲姟 ID锛岀敤浜庡悗缁煡璇换鍔＄姸鎬佸拰缁撴灉
     */
    String createTask(Path path);

    /**
     * 鏌ヨ璇煶璇嗗埆浠诲姟鐘舵€佸拰缁撴灉
     *
     * @param taskId 浠诲姟 ID锛岀敱 {@link #createTask(Path)} 杩斿洖
     * @return 浠诲姟鐘舵€佸強缁撴灉
     */
    AudioResponse queryTask(String taskId);

    /**
     * 鍏抽棴瀹㈡埛绔紝閲婃斁搴曞眰璧勬簮
     */
    @Override
    default void close() {
    }

    /**
     * 鑾峰彇鏈嶅姟鍟嗘敮鎸佺殑妯″瀷鍒楄〃
     *
     * @return 鍙敤妯″瀷瀹氫箟鍒楄〃
     */
    default List<ModelDefinition> models() {
        return List.of();
    }

    /**
     * 鏌ヨ褰撳墠鑳藉姏涓嬪叏閮ㄥ彲鐢ㄦā鍨?ID銆?     *
     * <p>鍩轰簬 {@link #models()} 鎻愬彇妯″瀷 ID 鍒楄〃锛屼緵缁熶竴鑳藉姏娓呭崟涓庡墠绔寜鑳藉姏绛涢€変娇鐢ㄣ€?/p>
     *
     * @return 妯″瀷 ID 鍒楄〃
     */
    default List<String> listModels() {
        List<ModelDefinition> defs = models();
        if (defs == null || defs.isEmpty()) {
            return List.of();
        }
        return defs.stream()
                .filter(d -> d != null && d.getId() != null && !d.getId().isBlank())
                .map(ModelDefinition::getId)
                .toList();
    }
}
