package com.chua.common.support.image.filter;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
/**
 * @author CH
 */

public interface ImageFilter {

    BufferedImage converter(BufferedImage image) throws Exception;

    OutputStream converter(InputStream image) throws Exception;

    String getImageFormat(String name);

    String getImageFormat();
}
