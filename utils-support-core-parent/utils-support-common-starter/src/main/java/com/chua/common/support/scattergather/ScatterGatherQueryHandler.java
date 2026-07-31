package com.chua.common.support.scattergather;

/**
 * 本地业务数据查询器。
 * <p>节点服务通过此接口执行本地查询逻辑。</p>
 *
 * @author CH
 */
public interface ScatterGatherQueryHandler {

    /**
     * 本地查询。
     *
     * @param context 查询上下文
     * @return 查询结果
     */
    Object localQuery(ScatterGatherContext context);
}
