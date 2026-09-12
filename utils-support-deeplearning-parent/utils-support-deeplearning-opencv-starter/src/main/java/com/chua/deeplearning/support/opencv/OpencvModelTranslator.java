package com.chua.deeplearning.support.opencv;

import com.chua.deeplearning.support.translator.ITranslator;
import com.chua.deeplearning.support.utils.ImageUtils;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
* 纯 打开cv 翻译器抽象基类。
* <p>
* 不依赖 DJL，直接使用 打开cv 传统视觉算法或 DNN 做预处理、推理、后处理。
* 子类只需实现 {@link #doTranslate(Object)} 完成具体模型推理逻辑。
* </p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public abstract class OpencvModelTranslator implements ITranslator<Object, Object> {

    /**
    * 模型名称，对应模型标识。
     */
    private final String modelName;

    /**
    * 构造翻译器并确保原生库已加载。
    *
    * @param modelName 模型名称
     */
    protected OpencvModelTranslator(String modelName) {
        OpencvNative.ensureLoaded();
        this.modelName = modelName;
    }

    @Override
    /** 名称 */
    public String name() {
        return modelName;
    }

    @Override
    /** Translate */
    public Object translate(Object input) {
        return doTranslate(input);
    }

    /**
    * 执行翻译（推理）。
    *
    * @param input 输入对象，通常为 byte[]（图像字节数组）
    * @return 业务输出对象
     */
    protected abstract Object doTranslate(Object input);

    /**
    * byte[] 转 Mat（BGR）。
    *
    * @param imageBytes 图像字节数组
    * @return OpenCV Mat 对象
     */
    protected static Mat bytesToMat(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("图像字节数组为空");
        }
        Mat mat = ImageUtils.decode(imageBytes);
        if (mat == null || mat.empty()) {
            throw new IllegalArgumentException("无法解析图像字节数组，格式不支持");
        }
        return mat;
    }

    /**
    * 缓冲镜像 转 Mat（BGR）。
    *
    * @param image 缓冲镜像 对象
    * @return OpenCV Mat 对象
     */
    protected static Mat bufferedToMat(BufferedImage image) {
        if (image == null) {
            throw new IllegalArgumentException("图像对象为空");
        }
        try {
            return ImageUtils.toMat(image);
        } catch (Exception e) {
            throw new RuntimeException("BufferedImage 转 Mat 失败", e);
        }
    }

    /**
    * Mat 转 byte[]（PNG 格式）。
    *
    * @param mat 打开cv Mat 对象
    * @return PNG 格式字节数组
     */
    protected static byte[] matToBytes(Mat mat) {
        if (mat == null || mat.empty()) {
            throw new IllegalArgumentException("Mat 对象为空");
        }
        return ImageUtils.encode(mat);
    }

    /**
    * Mat 转 缓冲镜像。
    *
    * @param mat 打开cv Mat 对象
    * @return BufferedImage 对象
     */
    protected static BufferedImage matToBufferedImage(Mat mat) {
        if (mat == null || mat.empty()) {
            throw new IllegalArgumentException("Mat 对象为空");
        }
        Mat source = mat;
        if (mat.channels() == 3) {
            source = new Mat();
            Imgproc.cvtColor(mat, source, Imgproc.COLOR_BGR2RGB);
        }
        int type = BufferedImage.TYPE_3BYTE_BGR;
        if (source.channels() == 1) {
            type = BufferedImage.TYPE_BYTE_GRAY;
        } else if (source.channels() == 4) {
            type = BufferedImage.TYPE_4BYTE_ABGR;
        }
        BufferedImage image = new BufferedImage(source.cols(), source.rows(), type);
        byte[] pixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        source.get(0, 0, pixels);
        return image;
    }

    /**
    * 从文件系统或 类路径 解析模型文件。
    * <p>
    * 类路径 资源会复制到临时目录，保证 cascadeclassifier 等 API 可按路径读取。
    * </p>
    *
    * @param modelPath 模型路径（文件系统绝对/相对路径，或 类路径 路径）
    * @return 模型文件的 文件 对象
     */
    public static File resolveModelPath(String modelPath) {
        if (modelPath == null || modelPath.isBlank()) {
            throw new IllegalArgumentException("模型路径为空");
        }
        Path path = Path.of(modelPath);
        if (Files.exists(path)) {
            return path.toFile();
        }

        String cpPath = modelPath.startsWith("/") ? modelPath.substring(1) : modelPath;
        ClassLoader classLoader = OpencvModelTranslator.class.getClassLoader();
        InputStream inputStream = classLoader.getResourceAsStream(cpPath);
        if (inputStream == null && !cpPath.startsWith("models/opencv/")) {
            String fileName = Path.of(cpPath).getFileName().toString();
            inputStream = classLoader.getResourceAsStream("models/opencv/" + fileName);
        }
        if (inputStream == null && cpPath.startsWith("models/opencv/")) {
            String fileName = Path.of(cpPath).getFileName().toString();
            inputStream = classLoader.getResourceAsStream("models/" + fileName);
        }
        if (inputStream == null) {
            throw new IllegalArgumentException("模型文件不存在: " + modelPath);
        }
        try (InputStream in = inputStream) {
            String fileName = Path.of(cpPath).getFileName().toString();
            Path tempFile = Files.createTempFile("opencv-model-", "-" + fileName);
            tempFile.toFile().deleteOnExit();
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            return tempFile.toFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("加载模型文件失败: " + modelPath, e);
        }
    }
}
