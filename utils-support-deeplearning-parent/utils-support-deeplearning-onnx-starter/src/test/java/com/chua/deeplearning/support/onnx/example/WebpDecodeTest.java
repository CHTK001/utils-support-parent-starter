package com.chua.deeplearning.support.onnx.example;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

class WebpDecodeTest {
    @Test
    void webp() throws Exception {
        for (String n : new String[]{"1safety-helmet.webp", "6safety-helmet.webp"}) {
            byte[] b = Files.readAllBytes(new File("G:/images/" + n).toPath());
            try {
                var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
                System.out.println(n + " ImageIO: " + (img == null ? "null" : img.getWidth() + "x" + img.getHeight()));
            } catch (Exception e) {
                System.out.println(n + " ImageIO ERROR: " + e.getMessage());
            }
        }
    }
}