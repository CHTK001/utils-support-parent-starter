package com.chua.deeplearning.support.ocr;

import com.chua.deeplearning.support.model.PredictRectangle;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
* OCR 管线磁盘回调：自动将各阶段中间数据落盘，供人工/脚本查看真实流程效果。
*
* <p>各阶段数据输出到指定目录：检测框、识别结果、矫正图片、高清化图片。</p>
*
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public class OcrPipelineDiskCallback implements OcrPipelineCallback {

    /**
    * 输出目录。
    */
    private final Path outputDir;

    /**
    * 构造磁盘回调。
    *
    * @param outputDir 输出目录
    */
    public OcrPipelineDiskCallback(Path outputDir) {
        this.outputDir = outputDir;
        try {
            Files.createDirectories(outputDir);
        } catch (Exception e) {
            log.warn("[ocr-callback] 创建输出目录失败: {}", outputDir, e);
        }
    }

    @Override
    public void onDetect(byte[] imageData, List<PredictRectangle> boxes) {
        log.info("[ocr-callback] 检测到文字框 {} 个", boxes.size());
    }

    @Override
    public void onRecognize(PredictRectangle box, String text, float conf, int index, int total) {
        log.info("[ocr-callback] 识别 {}/{}: {} ({})", index + 1, total, text, conf);
    }

    @Override
    public void onCorrect(byte[] corrected) {
        write(corrected, "corrected.png");
    }

    @Override
    public void onEnhance(byte[] enhanced) {
        write(enhanced, "enhanced.png");
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
            log.info("[ocr-callback] 已落盘: {}", path);
        } catch (Exception e) {
            log.warn("[ocr-callback] 落盘失败 {}: {}", name, e.getMessage());
        }
    }
}
