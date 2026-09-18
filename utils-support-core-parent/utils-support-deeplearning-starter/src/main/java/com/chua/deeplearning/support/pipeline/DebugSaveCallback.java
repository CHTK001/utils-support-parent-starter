package com.chua.deeplearning.support.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;

/**
* 图片落盘回调实现：将管线各步骤的中间图片保存到 G:/镜像/输出/调试/管线名/ 目录。
* <p>
* 可替代单独编写的 batchblack / batchmatting / batchlayout 等测试脚本，
* 直接在管线执行过程中查看每一步的中间效果。
* </p>
*
* @author CH
* @since 4.0.0.42
* @param data 数据
* @return 转为bytes的结果
* @param stepName step名称
* @param index 索引
* @param stageData Stage数据
* @param pipelineName pipeline名称
 */
public class DebugSaveCallback implements PipelineCallback {

    private static final String DEBUG_ROOT = "G:/images/output/debug"; // 调试根
    private final String pipelineName; // pipeline名称

    /**
    * onstep。
    * @param stepName step名称
    * @param stageData Stage数据
    * @param pipelineName pipeline名称
    */
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
    /**
    * 保存。
    * @param name 名称
    * @param data 数据
    * @return 转为bytes的结果
    */
    }

    /**
     * 保存。
     *
     * @param name 名称，不允许为 null
     * @param data 数据，不允许为 null
     */
    private void save(String name, Object data) {
        if (data == null) {
            return;
        }
        try {
            byte[] bytes = toBytes(data);
            if (bytes == null) {
                return;
            }
            Path dir = Path.of(DEBUG_ROOT, pipelineName);
            Files.createDirectories(dir);
            Files.write(dir.resolve(name + ".png"), bytes);
        } catch (Exception ignored) {
        }
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
        return null;
    }
}
