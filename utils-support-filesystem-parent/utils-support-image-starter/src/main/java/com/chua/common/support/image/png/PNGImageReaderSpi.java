package com.chua.common.support.image.png;

import javax.imageio.ImageReader;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
* PNG 图像阅读器服务提供者类
* 该类继承自 镜像读取spi，描述 PNG 图像格式的读取支持
*
* @author CH
* @since 4.0.0.42
*/
public class PNGImageReaderSpi extends ImageReaderSpi {
    // 定义供应商名称
    /** 供应商名称 */
    private static final String vendorName = "Karstian Lee";

    // 定义版本号
    /** 版本 */
    private static final String version = "1.0";

    // 支持的文件名列表，包括 png 和 apng
    /** 名称 */
    private static final String[] names = { "png", "PNG", "apng", "APNG" };

    // 支持的文件后缀列表
    /** Suffixes */
    private static final String[] suffixes = { "png", "apng" };

    // 支持的 MIME 类型列表
    /** Mimetypes */
    private static final String[] MIMETypes = { "image/png", "image/x-png", "image/apng" };

    // 定义 PNG 图像阅读器的类名
    /** 读取器类名称 */
    private static final String readerClassName =
            "com.tianscar.imageio.plugins.png.PNGImageReader";

    // 支持的图像写入服务提供者名称列表
    /** 写入器spinames */
    private static final String[] writerSpiNames = {
            "com.tianscar.imageio.plugins.png.PNGImageWriterSpi"
    };

    /**
    * 构造函数
    * 初始化 镜像读取spi 的基本信息
     */
    public PNGImageReaderSpi() {
        super(vendorName,
                version,
                names,
                suffixes,
                MIMETypes,
                readerClassName,
                new Class<?>[] { ImageInputStream.class },
                writerSpiNames,
                false,
                null, null,
                null, null,
                true,
                PNGMetadata.nativeMetadataFormatName,
                "com.tianscar.imageio.plugins.png.PNGMetadataFormat",
                null, null
        );
    }

    /**
    * 获取描述信息
    *
    * @param locale 本地化设置，返回相应语言的描述信息
    * @return 返回描述信息字符串
     */
    public String getDescription(Locale locale) {
        return "PNG/APNG image reader";
    }

    /**
    * 检查输入对象是否可以解码
    *
    * @param input 输入对象，通常是一个 镜像输入流 对象
    * @return 如果可以解码，则返回 true；否则返回 false
    * @throws IOException 如果发生 I/O 错误
     */
    public boolean canDecodeInput(Object input) throws IOException {
 // 检查输入对象是否为 镜像输入流 类型
        if (!(input instanceof ImageInputStream stream)) {
            return false;
        }

 // 强制转换为 镜像输入流 对象

        // 创建一个字节数组，读取文件头信息
        byte[] b = new byte[8];

        // 设置书签，以便之后恢复位置
        stream.mark();

        // 读取文件头信息到字节数组中
        stream.readFully(b);

        // 恢复到书签位置
        stream.reset();

        // 检查文件头信息是否符合 PNG 格式
        return (b[0] == (byte)137 &&
                b[1] == (byte)80 &&
                b[2] == (byte)78 &&
                b[3] == (byte)71 &&
                b[4] == (byte)13 &&
                b[5] == (byte)10 &&
                b[6] == (byte)26 &&
                b[7] == (byte)10);
    }

    /**
    * 创建图像阅读器实例
    *
    * @param extension 扩展对象，可以为空
    * @return 返回新PNGImageReader 实例
     */
    public ImageReader createReaderInstance(Object extension) {
        return new PNGImageReader(this);
    }
}
