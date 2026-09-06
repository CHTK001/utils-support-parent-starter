package com.chua.remote.protocol.capability;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 编解码能力配置。
 *
 * <p>描述一端（控制端或被控端）支持的编码/解码格式、分辨率、质量等参数。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodecProfile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 支持的编码格式（如 JPEG, PNG, WebP, H264） */
    private List<String> encodings;

    /** 最大宽度 */
    private int maxWidth;

    /** 最大高度 */
    private int maxHeight;

    /** 质量（0-100） */
    private int quality;

    /** 是否支持缩放 */
    private boolean scalingSupported;

    /** 是否支持缩略图 */
    private boolean thumbnailSupported;

    /** 帧率上限 */
    private int maxFps;

    /** 编解码器名称（自定义编解码器标识） */
    private String codecName;

    /** 是否为自定义编解码器（纯自研） */
    private boolean custom;
}
