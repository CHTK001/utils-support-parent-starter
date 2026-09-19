package com.chua.image.support.imaging;

import com.chua.common.support.utils.BufferedImageUtils;
import com.chua.common.support.image.Imaging;
import com.chua.common.support.media.MediaType;
import com.chua.common.support.media.MediaTypeFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 默认处理
 * @author CH
 * @since 4.0.0.42
 */
public class DefaultImaging implements Imaging{

    /**
     * 缓冲图片
    */
    private BufferedImage bufferedImage;
    /**
     * 类型
    */
    private String type;

    @Override
    /**
     * 镜像
    */
    public Imaging image(BufferedImage image) {
        this.bufferedImage = image;
        return this;
    }

    @Override
    /**
     * 镜像
    */
    public Imaging image(byte[] image) {
        this.bufferedImage = BufferedImageUtils.toBufferedImage(image);
        return this;
    }

    @Override
    /**
     * 镜像
    */
    public Imaging image(File file) {
        this.bufferedImage = BufferedImageUtils.toBufferedImage(file);
        return this;
    }

    @Override
    /**
     * 类型
    */
    public Imaging type(String type) {
        this.type = type;
        return this;
    }

    @Override
    /**
     * 输出quality
    */
    public Imaging outputQuality(float outputQuality) {
        ImageWriter imageWriter = ImageIO.getImageWritersByFormatName(type).next();
        ImageWriteParam imageWriteParam = imageWriter.getDefaultWriteParam();
        if(!imageWriteParam.canWriteCompressed()) {
            return this;
        }
        imageWriteParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        imageWriteParam.setCompressionQuality(outputQuality);
        imageWriteParam.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        imageWriter.reset();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            imageWriter.setOutput(ImageIO.createImageOutputStream(out));
            imageWriter.write(null, new IIOImage(bufferedImage, null, null), imageWriteParam);
            return image(out.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    /**
     * media类型
    */
    public MediaType mediaType() {
        
        return MediaTypeFactory.getMediaTypeNullable(type);
    
    }


    @Override
    /**
     * 获取缓冲镜像
    */
    public BufferedImage getBufferedImage() {
        
        return this.bufferedImage;
    
    }
}

