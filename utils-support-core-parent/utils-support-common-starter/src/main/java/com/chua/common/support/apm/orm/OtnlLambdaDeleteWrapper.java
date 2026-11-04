package com.chua.common.support.apm.orm;

import com.chua.common.support.lang.datasource.engine.wrapper.LambdaDeleteWrapper;

/**
 * Otnl 引擎的 Lambda 删除包装器。
 * <p>
 * 与 {@link OtnlLambdaUpdateWrapper} 同口径：观测数据不归本系统所有，
 * 删除一律显式拒绝，避免出现「以为清掉了告警，实际只是本地无操作」。
 * </p>
 *
 * @param <T> 实体类型
 * @author CH
 * @since 4.0.0.51
 */
public class OtnlLambdaDeleteWrapper<T> extends LambdaDeleteWrapper<T> {

    /**
     * 创建包装器
     *
     * @param entityClass 实体类型
     */
    public OtnlLambdaDeleteWrapper(Class<T> entityClass) {
        super(entityClass);
    }

    /**
     * 只读引擎拒绝删除
     *
     * @return 永不返回
     */
    @Override
    public int remove() {
        throw new UnsupportedOperationException(
                "APM 引擎是只读观测通道，不支持删除: " + getEntityClass().getSimpleName());
    }
}
