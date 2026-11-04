package com.chua.common.support.apm.model;

import java.util.List;

/**
 * 性能剖析分析结果（对齐 SkyWalking 的 {@code ProfileAnalyzation}）。
 * <p>
 * OAP 返回的是「若干棵调用树」：每棵树的节点是栈帧签名，
 * 带自身耗时、含子耗时与采样次数。前端把 {@code duration - selfDurationMs}
 * 归一成火焰条长度即可画出火焰图，不需要后端再算百分比。
 * </p>
 *
 * @param taskId 任务 ID
 * @param tip    后端提示（例如「快照过多，只分析了部分数据」），无提示为空串
 * @param trees  调用树，一个线程一根
 * @author CH
 * @since 4.0.0.51
 */
public record OtnlProfileAnalysis(String taskId, String tip, List<OtnlProfileStack> trees) {

    /**
     * 空结果
     *
     * @param taskId 任务 ID
     * @param tip    提示
     * @return 分析结果
     */
    public static OtnlProfileAnalysis empty(String taskId, String tip) {
        return new OtnlProfileAnalysis(taskId, tip == null ? "" : tip, List.of());
    }

    /**
     * 栈帧节点
     *
     * @param id               节点 ID（同一签名可能出现多次，故不复用 codeSignature 当键）
     * @param parentId         父节点 ID，根节点为空串
     * @param codeSignature    栈帧签名（类名.方法名(参数)）
     * @param durationMs       含子节点耗时
     * @param selfDurationMs   不含子节点耗时
     * @param count            采样命中次数
     * @param children         子节点
     */
    public record OtnlProfileStack(String id,
                                   String parentId,
                                   String codeSignature,
                                   int durationMs,
                                   int selfDurationMs,
                                   int count,
                                   List<OtnlProfileStack> children) {
    }
}
