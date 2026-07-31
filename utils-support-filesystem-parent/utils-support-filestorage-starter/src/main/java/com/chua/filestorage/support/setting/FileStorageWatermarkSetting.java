package com.chua.filestorage.support.setting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 水印配置。
 *
 * <p>支持文字水印和图片水印两种模式。</p>
 *
 * @author CH
 * @since 2024/12/28
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileStorageWatermarkSetting {

    /**
     * 是否启用水印。
     */
    @Builder.Default
    private boolean enabled = false;

    /**
     * 水印类型：text | image
     */
    @Builder.Default
    private String type = "text";

    /**
     * 文字水印内容（type=text 时有效）。
     */
    private String text;

    /**
     * 图片水印 URL（type=image 时有效）。
     */
    private String imageUrl;

    /**
     * 水印位置。
     * <p>取值：
     * <ul>
     *   <li>LEFT_TOP / TOP_LEFT</li>
     *   <li>TOP_CENTER / TOP</li>
     *   <li>RIGHT_TOP / TOP_RIGHT</li>
     *   <li>CENTER_LEFT / LEFT</li>
     *   <li>CENTER / MIDDLE</li>
     *   <li>CENTER_RIGHT / RIGHT</li>
     *   <li>BOTTOM_LEFT / LEFT_BOTTOM</li>
     *   <li>BOTTOM_CENTER / BOTTOM</li>
     *   <li>BOTTOM_RIGHT / RIGHT_BOTTOM</li>
     *   <li>TILE（平铺）</li>
     * </ul>
     * </p>
     */
    @Builder.Default
    private String position = "BOTTOM_RIGHT";

    /**
     * 文字大小（type=text 时有效）。
     */
    @Builder.Default
    private int fontSize = 24;

    /**
     * 文字颜色（CSS 格式，如 "rgba(0,0,0,0.3)"）。
     */
    @Builder.Default
    private String color = "rgba(180,180,180,0.3)";

    /**
     * 字体名称（type=text 时有效）。
     */
    private String fontFamily;

    /**
     * 透明度 0.0 ~ 1.0。
     */
    @Builder.Default
    private float opacity = 0.3f;

    /**
     * X 方向偏移（像素）。
     */
    @Builder.Default
    private int offsetX = 20;

    /**
     * Y 方向偏移（像素）。
     */
    @Builder.Default
    private int offsetY = 20;

    /**
     * 旋转角度（度）。
     */
    @Builder.Default
    private float rotation = 0f;

    /**
     * 平铺时 X 间距（像素）。
     */
    @Builder.Default
    private int tileSpacingX = 50;

    /**
     * 平铺时 Y 间距（像素）。
     */
    @Builder.Default
    private int tileSpacingY = 50;
}
