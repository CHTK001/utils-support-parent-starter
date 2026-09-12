package com.chua.common.support.lang.process;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 进度条渲染器接口，负责将 {@link ProgressState} 渲染为字符串。
* <p>
* 根据进度状态和最大显示长度，生成可视化的进度条文本。
*
* @author CH
* @since 2023-09-20
* @version 1.0.0
 */
@FunctionalInterface
public interface ProgressBarRenderer {

    /**
    * 将进度状态渲染为字符串
    *
    * @param progress 进度状态，包含任务名称、当前进度、最大值等信息
    *                 示例：ProgressState{taskName='处理中', current=50, max=100}
    * @param maxLength 最大显示长度
    *                  例如 80 表示在 80 个字符宽度内渲染进度条
    * @return 渲染后的字符串
    *         示例："处理中 [==============>      ] 50/100 (50%)"
     */
    String render(ProgressState progress, int maxLength);

}