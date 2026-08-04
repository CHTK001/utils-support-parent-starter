package com.chua.common.support.lang.qr;

import lombok.Data;

import java.awt.Rectangle;
import java.util.List;
import org.jspecify.annotations.NullUnmarked;

/**
 * 二维码结果数据模型。
 * 用于存储二维码解析后的文本内容以及其在图像中的边界框信息。
 *
 * @author CH
 */
@NullUnmarked
@Data
public class QrResult {

    /**
     * 二维码包含的文本内容。
     */
    private String text;

    /**
     * 二维码在原始图像中的所有边界框列表。
     * 每个 Rectangle 对象表示一个二维码的位置和大小。
     */
    private List<Rectangle> bounds;
}
