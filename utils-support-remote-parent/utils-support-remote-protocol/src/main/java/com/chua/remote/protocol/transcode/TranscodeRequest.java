package com.chua.remote.protocol.transcode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 转码请求。
 *
 * <p>当控制端和被控端的编解码能力不一致时，网关生成转码指令。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranscodeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话标识 */
    private String sessionId;

    /** 原始编码格式 */
    private String sourceEncoding;

    /** 目标编码格式 */
    private String targetEncoding;

    /** 原始宽度 */
    private int sourceWidth;

    /** 目标宽度 */
    private int targetWidth;

    /** 原始高度 */
    private int sourceHeight;

    /** 目标高度 */
    private int targetHeight;

    /** 质量（0-100） */
    private int quality;

    /** 原始载荷 */
    private byte[] sourceData;
}
