package com.chua.deeplearning.support.utils;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.ndarray.NDArray;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
* 模型资源定位工具。
*
* @author CH
* @since 4.0.0.42
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class LocationUtils {

    /**
    * ndarray 转 镜像。
    *
    * @param array ndarray
    * @return Image
    */
    public static Image getImage(NDArray array) {
        return ImageFactory.getInstance().fromNDArray(array);
    }

    /**
    * 解析模型路径为 URL 列表。
    * <p>支持文件系统绝对/相对路径、classpath 资源。</p>
    *
    * @param modelPath   路径
    * @param isDirectory 是否目录
    * @return URL 列表
    */
    public static List<String> getUrl(String modelPath, boolean isDirectory) {
        if (modelPath == null || modelPath.isBlank()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        Path path = Paths.get(modelPath);
        if (Files.exists(path)) {
            result.add(path.toAbsolutePath().normalize().toString());
            return result;
        }
        String cp = modelPath.startsWith("/") ? modelPath.substring(1) : modelPath;
        URL url = LocationUtils.class.getClassLoader().getResource(cp);
        if (url != null) {
            result.add(url.toString());
        }
        return result;
    }
}
