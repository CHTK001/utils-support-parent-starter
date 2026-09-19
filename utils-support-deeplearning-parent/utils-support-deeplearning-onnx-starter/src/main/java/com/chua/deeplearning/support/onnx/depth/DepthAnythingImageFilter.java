package com.chua.deeplearning.support.onnx.depth;

import com.chua.common.support.image.filter.ImageFilter;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.deeplearning.support.engine.ModelRegistry;
import com.chua.deeplearning.support.translator.ITranslator;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 深度-Anything V2 深度估计滤镜。
 *
 * <p>输入任意图像，输出深度图（近处亮、远处暗），以 ImageFilter SPI 方式提供，
 * 可直接通过 {@code ServiceProvider.of(ImageFilter.class).getExtension("depth-anything")} 调用。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("depth-anything")
public class DepthAnythingImageFilter implements ImageFilter {

    private volatile ITranslator<Object, Object> translator; // translator

    /**
     * translator。
     * @return translator的结果
     */
    private ITranslator<Object, Object> translator() {
        if (translator == null) {
            synchronized (this) {
                if (translator == null) {
                    ModelRegistry.discoverAll();
                    translator = (ITranslator<Object, Object>) ModelRegistry.createTranslator("depth-anything", null);
                }
            }
        }
        return translator;
    }

    @Override
    public BufferedImage converter(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);
        byte[] result = (byte[]) translator().translate(baos.toByteArray());
        return ImageIO.read(new ByteArrayInputStream(result));
    }

    @Override
    public OutputStream converter(InputStream image) throws Exception {
        byte[] bytes = image.readAllBytes();
        byte[] result = (byte[]) translator().translate(bytes);
        OutputStream os = new ByteArrayOutputStream();
        os.write(result);
        return os;
    }

    @Override
    public String getImageFormat(String name) {
        return "PNG";
    }

    @Override
    public String getImageFormat() {
        return "PNG";
    }
}
