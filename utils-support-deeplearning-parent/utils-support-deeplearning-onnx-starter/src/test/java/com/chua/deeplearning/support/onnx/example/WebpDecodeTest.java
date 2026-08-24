package com.chua.deeplearning.support.onnx.example;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.BufferedImageFactory;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

class WebpDecodeTest {
    @Test
    void webp() throws Exception {
        for (String n : new String[]{"1safety-helmet.webp", "1safety-helmet1.png"}) {
            byte[] b = Files.readAllBytes(new File("G:/images/" + n).toPath());
            var bi = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
            Image img = new BufferedImageFactory().fromImage(bi);
            System.out.printf("%s: %dx%d%n", n, img.getWidth(), img.getHeight());
            try (NDManager m = NDManager.newBaseManager()) {
                Image resized = img.resize(640, 640, true);
                NDArray nd = resized.toNDArray(m, Image.Flag.COLOR).toType(ai.djl.ndarray.types.DataType.FLOAT32, false);
                System.out.println("  resized shape: " + nd.getShape());
                float[] data = nd.toFloatArray();
                float max = 0, sum = 0;
                for (float v : data) { if (v > max) max = v; sum += v; }
                System.out.printf("  pixel max=%.0f mean=%.2f std=%.2f%n", max, sum / data.length, std(data));
            }
        }
    }
    private double std(float[] d) {
        double mean = 0; for (float v : d) mean += v; mean /= d.length;
        double s = 0; for (float v : d) s += (v - mean) * (v - mean); return Math.sqrt(s / d.length);
    }
}