package com.chua.deeplearning.support.face;

import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
* 人脸管线磁盘回调：自动将各阶段中间图片落盘，供人工/脚本查看真实流程效果。
*
* <p>各阶段图片输出到指定目录：对齐人脸、修复人脸。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class FacePipelineDiskCallback implements FacePipelineCallback {

    /**
    * 输出目录。
    */
    private final Path outputDir;

    /**
    * 构造磁盘回调。
    *
    * @param outputDir 输出目录
    */
    public FacePipelineDiskCallback(Path outputDir) {
        this.outputDir = outputDir;
        try {
            Files.createDirectories(outputDir);
        } catch (Exception e) {
            log.warn("[face-callback] 创建输出目录失败: {}", outputDir, e);
        }
    }

    @Override
    public void onDetect(byte[] imageData, List<PredictRectangle> boxes) {
        log.info("[face-callback] 检测到人脸 {} 张", boxes.size());
    }

    @Override
    public void onAlign(int faceIndex, PredictRectangle box, byte[] aligned) {
        write(aligned, "align_" + faceIndex + ".png");
    }

    @Override
    public void onRestore(int faceIndex, byte[] restored) {
        write(restored, "restore_" + faceIndex + ".png");
    }

    /**
    * 写图片到输出目录。
    *
    * @param data 图片字节
    * @param name 文件名
    */
    private void write(byte[] data, String name) {
        try {
            Path path = outputDir.resolve(name);
            Files.write(path, data);
            log.info("[face-callback] 已落盘: {}", path);
        } catch (Exception e) {
            log.warn("[face-callback] 落盘失败 {}: {}", name, e.getMessage());
        }
    }
}
