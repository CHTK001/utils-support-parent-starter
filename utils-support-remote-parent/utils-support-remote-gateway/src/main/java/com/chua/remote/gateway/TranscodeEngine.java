package com.chua.remote.gateway;

import com.chua.remote.protocol.capability.CodecProfile;
import com.chua.remote.protocol.model.Session;
import com.chua.remote.protocol.transcode.TranscodeRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 转码引擎。
 *
 * <p>当控制端和被控端的编解码能力不一致时，生成转码指令或执行转码。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class TranscodeEngine {

    /**
     * 协商编解码配置。
     *
     * <p>优先取被控端编码能力与控制端解码能力的交集；
     * 若交集为空，则生成转码指令（需网关兜底转码）。</p>
     *
     * @param encodingAbility  被控端编码能力
     * @param decodingAbility 控制端解码能力
     * @return 协商后的编解码配置
     */
    public Session.NegotiatedCodec negotiate(CodecProfile encodingAbility, CodecProfile decodingAbility) {
        if (encodingAbility == null || decodingAbility == null) {
            return Session.NegotiatedCodec.builder()
                    .transcoded(true)
                    .build();
        }

        // 寻找交集编码格式
        String finalEncoding = findCommonEncoding(encodingAbility.getEncodings(), decodingAbility.getEncodings());
        int finalWidth = Math.min(encodingAbility.getMaxWidth(), decodingAbility.getMaxWidth());
        int finalHeight = Math.min(encodingAbility.getMaxHeight(), decodingAbility.getMaxHeight());
        int finalQuality = Math.min(encodingAbility.getQuality(), decodingAbility.getQuality());
        boolean needTranscode = finalEncoding == null || !finalEncoding.equals("JPEG");

        log.info("编解码协商结果: encoding={}, width={}, height={}, transcoded={}",
                finalEncoding, finalWidth, finalHeight, needTranscode);

        return Session.NegotiatedCodec.builder()
                .encoding(finalEncoding != null ? finalEncoding : "JPEG")
                .width(finalWidth)
                .height(finalHeight)
                .quality(finalQuality)
                .transcoded(needTranscode)
                .build();
    }

    /**
     * 寻找两个编码列表的交集。
     *
     * @param source 源编码列表
     * @param target 目标编码列表
     * @return 交集编码格式，无交集返回 null
     */
    private String findCommonEncoding(java.util.List<String> source, java.util.List<String> target) {
        if (source == null || target == null) {
            return null;
        }
        for (String enc : source) {
            if (target.contains(enc)) {
                return enc;
            }
        }
        return null;
    }

    /**
     * 创建转码请求。
     *
     * @param session 会话
     * @param sourceData 原始数据
     * @return 转码请求
     */
    public TranscodeRequest createTranscodeRequest(Session session, byte[] sourceData) {
        var negotiated = session.getNegotiatedCodec();
        return TranscodeRequest.builder()
                .sessionId(session.getSessionId())
                .sourceEncoding(session.getAgentId())
                .targetEncoding(negotiated.getEncoding())
                .sourceWidth(negotiated.getWidth())
                .targetWidth(negotiated.getWidth())
                .sourceHeight(negotiated.getHeight())
                .targetHeight(negotiated.getHeight())
                .quality(negotiated.getQuality())
                .sourceData(sourceData)
                .build();
    }
}
