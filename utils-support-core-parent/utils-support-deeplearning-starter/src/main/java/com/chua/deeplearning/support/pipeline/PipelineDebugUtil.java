package com.chua.deeplearning.support.pipeline;

import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 管线调试工具：将管线各阶段中间数据落盘。
 * <p>
 * 自动将 ImagePipeline 各步骤的图片保存到 <code>G:/images/output/debug/管线名/步骤名_时间戳.png</code>，
 * 方便查看管线真实流程效果。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class PipelineDebugUtil {

    private static final String DEBUG_ROOT = "G:/images/output/debug";

    private final String pipelineName;
    private final boolean enabled;

    public PipelineDebugUtil(String pipelineName, boolean enabled) {
        this.pipelineName = pipelineName;
        this.enabled = enabled;
    }

    public PipelineDebugUtil(String pipelineName) {
        this(pipelineName, true);
    }

    /**
     * 保存步骤快照。
     *
     * @param stepName 步骤名
     * @param data     图片字节或可转为图片的对象
     */
    public void snapshot(String stepName, Object data) {
        if (!enabled || data == null) return;
        try {
            byte[] imageBytes = toBytes(data);
            if (imageBytes == null) return;
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmssSSS"));
            Path dir = Path.of(DEBUG_ROOT, pipelineName);
            Files.createDirectories(dir);
            Path file = dir.resolve(stepName + "_" + ts + ".png");
            Files.write(file, imageBytes);
        } catch (Exception ignored) {
        }
    }

    /**
     * 保存步骤快照（带索引）。
     *
     * @param stepName 步骤名
     * @param index    索引
     * @param data     图片数据
     */
    public void snapshot(String stepName, int index, Object data) {
        snapshot(stepName + "_" + index, data);
    }

    private static byte[] toBytes(Object data) {
        if (data instanceof byte[] b) return b;
        if (data instanceof java.awt.image.BufferedImage bi) return ImageUtils.encode(bi);
        return null;
    }
}