package com.chua.deeplearning.support.pipeline;

import com.chua.deeplearning.support.utils.ImageUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
* 管线调试工具：将管线各阶段中间数据落盘。
* <p>
* 自动将 镜像pipeline 各步骤的图片保存到 <code>G:/镜像/输出/调试/管线名/步骤名_时间戳.png</code>，
* 方便查看管线真实流程效果。
* </p>
*
* @author CH
* @since 4.0.0.42
* @param pipelineName pipeline名称
 */
public class PipelineDebugUtil {

    private static final String DEBUG_ROOT = "G:/images/output/debug"; // 调试根

    private final String pipelineName; // pipeline名称
    private final boolean enabled; // 已启用
/**
* pipeline调试util。
* @param pipelineName pipeline名称
* @param enabled 已启用
 */

    public PipelineDebugUtil(String pipelineName, boolean enabled) {
        this.pipelineName = pipelineName;
        this.enabled = enabled;
    }

    /**
     * 构造方法，创建 PipelineDebugUtil 实例。
     *
     * @param pipelineName pipeline名称，不允许为 null
     */
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
        if (!enabled || data == null) {
            return;
        }
        try {
            byte[] imageBytes = toBytes(data);
            if (imageBytes == null) {
                return;
            }
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmmssSSS"));
            Path dir = Path.of(DEBUG_ROOT, pipelineName);
            Files.createDirectories(dir);
            Path file = dir.resolve(stepName + "_" + ts + ".png");
            Files.write(file, imageBytes);
        } catch (Exception ignored) {
        }
    }

    /**
    * 保存带索引的步骤快照。
    * 在步骤名后追加 "_索引" 作为唯一快照名，委托给无索引版本落盘。
    *
    * @param stepName 步骤名称
    * @param index    步骤索引
    * @param data     图片/数组数据
    */
    public void snapshot(String stepName, int index, Object data) {
        snapshot(stepName + "_" + index, data);
    }

    /**
     * 转为字节数组。
     *
     * @param data 数据，不允许为 null
     * @return 结果值
     */
    private static byte[] toBytes(Object data) {
        if (data instanceof byte[] b) {
            return b;
        }
        if (data instanceof java.awt.image.BufferedImage bi) {
            return ImageUtils.encode(ImageUtils.toMat(bi));
        }
        return null;
    }
}
