package com.chua.common.support.image;

import java.awt.image.BufferedImage;
/**
* @author CH
* @since 4.0.0.42
 */

public interface Imaging {

    Imaging image(BufferedImage image);

    Imaging image(byte[] image);

    Imaging image(java.io.File file);

    Imaging type(String type);

    Imaging outputQuality(float outputQuality);

    com.chua.common.support.media.MediaType mediaType();

    BufferedImage getBufferedImage();
}
