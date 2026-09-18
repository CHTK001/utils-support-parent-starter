package com.chua.filestorage.support.operation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
* 文件操作配置。
*
* <p>由 {@link com.chua.filestorage.support.spi.FileStorageFileSetting}
* 从 URL 参数解析生成，传递给处理器执行具体的图片操作。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileOperationSetting {

    /**
    * 缩放尺寸，如 "200x200" 或 "50%"
    */
    private String size;

    /**
    * 输出格式，如 "webp"、"jpeg"、"png"、"avif"
    */
    private String format;

    /**
    * 图片质量 0-100（仅对有损格式有效）
    */
    private Integer quality;

    /**
    * 裁剪区域 "x,y,w,h"
    */
    private String crop;

    /**
    * 旋转角度（度）
    */
    private Integer rotate;

    /**
    * 翻转方向：horizontal | vertical | both
    */
    private String flip;

    /**
    * 是否灰度
    */
    private Boolean grayscale;

    /**
    * 高斯模糊半径，> 0 生效
    */
    private Float blur;

    /**
    * 锐化强度，> 0 生效
    */
    private Float sharpen;

    /**
    * 是否自动根据 EXIF 旋转
    */
    @Builder.Default
    /** Autoorient */
    private Boolean autoOrient = false;

    /**
    * 水印文本（操作级，覆盖全局）
    */
    private String watermarkText;

    /**
    * 水印图片 URL（操作级）
    */
    private String watermarkImage;

    /**
    * 指定存储名称（路由）
    */
    private String storageName;

    /**
    * 是否强制下载（忽略预览逻辑）
    */
    @Builder.Default
    /** Force下载 */
    private Boolean forceDownload = false;

    /**
    * PDF 转换时页码（默认 1）
    */
    private Integer pdfPage;

    /**
    * PDF 转换时页尺寸：A4 | Letter | Legal
    */
    private String pdfPageSize;

    /**
    * PDF 转换时方向：portrait | landscape
    */
    private String pdfOrientation;

    /**
    * 判断是否有任意操作需要执行。
    * @return 是否包含operation的结果
    */
    public boolean hasOperation() {
        return size != null || format != null || quality != null
                || crop != null || rotate != null || flip != null
                || grayscale != null || blur != null || sharpen != null
                || autoOrient != null || watermarkText != null
                || watermarkImage != null;
    }
}
