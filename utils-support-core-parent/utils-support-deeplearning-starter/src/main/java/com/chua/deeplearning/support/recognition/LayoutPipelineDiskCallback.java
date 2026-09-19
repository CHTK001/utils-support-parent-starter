package com.chua.deeplearning.support.recognition;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 版面分析管线磁盘回调：自动将各阶段中间数据落盘，供人工/脚本查看真实流程效果。
 *
 * <p>各阶段数据输出到指定目录：预处理后的图像。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public class LayoutPipelineDiskCallback implements LayoutPipelineCallback {

    /**
     * 输出目录。
     */
    private final Path outputDir;

    /**
     * 构造磁盘回调。
     *
     * @param outputDir 输出目录
     */
    public LayoutPipelineDiskCallback(Path outputDir) {
        this.outputDir = outputDir;
        try {
            Files.createDirectories(outputDir);
        } catch (Exception e) {
            log.warn("[layout-callback] 创建输出目录失败: {}", outputDir, e);
        }
    }

    @Override
    public void onPreprocess(byte[] imageData, byte[] processed) {
        write(processed, "preprocessed.png");
    }

    @Override
    public void onRecognize(Object result) {
        log.info("[layout-callback] 版面识别完成: {}", result);
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
            log.info("[layout-callback] 已落盘: {}", path);
        } catch (Exception e) {
            log.warn("[layout-callback] 落盘失败 {}: {}", name, e.getMessage());
        }
    }
}
