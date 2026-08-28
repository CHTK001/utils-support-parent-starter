package com.chua.deeplearning.support.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 图片落盘回调实现：将管线各步骤的中间图片保存到 G:/images/output/debug/管线名/ 目录。
 * <p>
 * 可替代单独编写的 BatchBlack / BatchMatting / BatchLayout 等测试脚本，
 * 直接在管线执行过程中查看每一步的中间效果。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class DebugSaveCallback implements PipelineCallback {

    private static final String DEBUG_ROOT = "G:/images/output/debug";
    private final String pipelineName;

    public DebugSaveCallback(String pipelineName) {
        this.pipelineName = pipelineName;
    }

    @Override
    public void onStep(String stepName, Object stageData) {
        save(stepName, stageData);
    }

    @Override
    public void onStep(String stepName, int index, Object stageData) {
        save(stepName + "_" + index, stageData);
    }

    private void save(String name, Object data) {
        if (data == null) return;
        try {
            byte[] bytes = toBytes(data);
            if (bytes == null) return;
            Path dir = Path.of(DEBUG_ROOT, pipelineName);
            Files.createDirectories(dir);
            Files.write(dir.resolve(name + ".png"), bytes);
        } catch (Exception ignored) {
        }
    }

    private static byte[] toBytes(Object data) {
        if (data instanceof byte[] b) return b;
        return null;
    }
}