package com.chua.deeplearning.support.image;

import java.util.List;

/**
 * 指令式视觉推理请求。
 *
 * <p>字段与模型侧任务参数一一对应，不在此层拼提示词：提示词由具体实现按自己的任务
 * 约定生成（WeVisOne-VL 的措辞在 {@code prompts.py}，其他实现可能完全不同）。
 *
 * <ul>
 *   <li>{@code query}：检测类别（可多行/多值）、指代描述、待定位文字、或自由指令；
 *       多标签由实现按自己的分隔约定处理；</li>
 *   <li>{@code prompt}：完全自定义提示词，非空时实现应直接透传（优先于 {@code query}）；</li>
 *   <li>{@code referenceBoxes}：视觉提示任务的参考框，归一化坐标；</li>
 *   <li>{@code decodeMatting}：是否解码 alpha matte（仅分割/抠图任务有意义，
 *       解码成本高，默认关闭）。</li>
 * </ul>
 *
 * @param imageData       图像字节（JPEG/PNG 等原始编码）
 * @param task            任务类型
 * @param query           任务输入文本，可为空
 * @param prompt          自定义提示词，可为空
 * @param referenceBoxes  参考框列表，不能为 null
 * @param decodeMatting   是否解码 alpha matte
 * @author CH
 * @since 4.0.0.51
 */
public record VlmRequest(byte[] imageData, VlmTask task, String query, String prompt,
                         List<VlmBox> referenceBoxes, boolean decodeMatting) {

    /**
     * 规范构造器：校验必填项并对列表做防御性拷贝。
     *
     * @param imageData      图像字节
     * @param task           任务类型
     * @param query          任务输入文本
     * @param prompt         自定义提示词
     * @param referenceBoxes 参考框列表
     * @param decodeMatting  是否解码 alpha matte
     */
    public VlmRequest {
        if (imageData == null || imageData.length == 0) {
            throw new IllegalArgumentException("图像数据不能为空");
        }
        if (task == null) {
            throw new IllegalArgumentException("任务类型不能为空");
        }
        referenceBoxes = referenceBoxes == null ? List.of() : List.copyOf(referenceBoxes);
    }

    /**
     * 构造最简请求（无参考框、不解码 matte）。
     *
     * @param imageData 图像字节
     * @param task      任务类型
     * @param query     任务输入文本
     * @return 请求
     */
    public static VlmRequest of(byte[] imageData, VlmTask task, String query) {
        return new VlmRequest(imageData, task, query, null, List.of(), false);
    }
}