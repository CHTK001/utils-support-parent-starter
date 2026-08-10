import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.*;
import java.io.*;
import java.util.jar.*;

public class ClasspathModelTest {

    public static void main(String[] args) throws Exception {
        System.out.println("=== 离线模型 classpath 加载测试 ===\n");

        // 测试 1: 从 classpath 加载各个模型
        String[] testPaths = {
            "ocr/PP-OCRv6/tiny/det_infer/inference.onnx",
            "ocr/PP-OCRv6/tiny/rec_infer/inference.onnx",
            "ocr/PP-OCRv6/tiny/rec_infer/dict.txt",
            "ocr/PP-OCRv6/medium/det_infer/inference.onnx",
            "ocr/PP-OCRv6/medium/rec_infer/inference.onnx",
            "audio/asr/whisper-tiny/onnx/encoder_model_quantized.onnx",
            "audio/asr/whisper-tiny/onnx/decoder_model_merged_quantized.onnx",
            "audio/asr/whisper-tiny/tokenizer_config.json",
            "audio/asr/whisper-tiny/vocab.json",
            "vision/detection/yolov5_plate/yolov5_plate_detect.onnx",
            "vision/detection/yolov5_plate/yolov5_plate_rec_color.onnx",
            "vision/detection/yolov5_plate/configuration.json",
            "cv/card_correction/card_detection.onnx",
            "vision/classification/efficientnet/efficientnet-lite4-11.onnx",
            "face/expression/FER/FER.onnx",
            "nlp/embedding/minilm/MiniLM.onnx",
        };

        int ok = 0;
        int fail = 0;
        for (String p : testPaths) {
            URL url = ClasspathModelTest.class.getClassLoader().getResource(p);
            if (url == null) {
                System.out.println("✗ NOT FOUND: " + p);
                fail++;
            } else {
                long size = 0;
                if (url.getProtocol().equals("jar")) {
                    // 从 jar 中读取
                    String jarFile = url.getPath().substring(5, url.getPath().indexOf("!"));
                    try (JarFile jar = new JarFile(URLDecoder.decode(jarFile, "UTF-8"))) {
                        JarEntry entry = jar.getJarEntry(p);
                        if (entry != null) size = entry.getSize();
                    }
                } else if (url.getProtocol().equals("file")) {
                    size = new File(url.toURI()).length();
                }
                System.out.println("✓ " + p + " (" + (size / 1024) + " KB)");
                ok++;
            }
        }

        System.out.println("\n=== 解压测试（ModelRegistry 流程）===\n");

        // 测试 2: 模拟 ModelRegistry 解压流程到 java.io.tmpdir/chua-dl-models/
        Path tmpRoot = Paths.get(System.getProperty("java.io.tmpdir"), "chua-dl-models");
        Files.createDirectories(tmpRoot);
        System.out.println("解压目录: " + tmpRoot);

        int extracted = 0;
        for (String p : testPaths) {
            if (!p.endsWith(".onnx") && !p.endsWith(".json") && !p.endsWith(".txt")) continue;
            URL url = ClasspathModelTest.class.getClassLoader().getResource(p);
            if (url == null) continue;

            Path target = tmpRoot.resolve(p);
            Files.createDirectories(target.getParent());

            if (url.getProtocol().equals("jar")) {
                String jarFile = url.getPath().substring(5, url.getPath().indexOf("!"));
                try (JarFile jar = new JarFile(URLDecoder.decode(jarFile, "UTF-8"));
                     InputStream in = jar.getInputStream(jar.getJarEntry(p))) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            extracted++;
        }
        System.out.println("解压文件数: " + extracted);

        // 验证解压后的文件可读
        Path testFile = tmpRoot.resolve("ocr/PP-OCRv6/tiny/det_infer/inference.onnx");
        if (Files.exists(testFile)) {
            byte[] header = new byte[8];
            try (InputStream in = Files.newInputStream(testFile)) {
                int n = in.read(header);
                // ONNX magic: 0x08 0x07 (onnx magic bytes) - actually protobuf header
                String hex = String.format("%02X %02X %02X %02X %02X %02X %02X %02X",
                    header[0]&0xff, header[1]&0xff, header[2]&0xff, header[3]&0xff,
                    header[4]&0xff, header[5]&0xff, header[6]&0xff, header[7]&0xff);
                System.out.println("PP-OCRv6-tiny ONNX 头: " + hex + " (正常: 08 01 12 ...) ");
            }
        }

        System.out.println("\n=== 汇总 ===");
        System.out.println("✓ 找到: " + ok + "/" + testPaths.length);
        if (fail > 0) System.out.println("✗ 缺失: " + fail);
        System.exit(0);
    }
}
