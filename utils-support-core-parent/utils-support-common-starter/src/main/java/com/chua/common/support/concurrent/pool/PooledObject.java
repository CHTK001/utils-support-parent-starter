package com.chua.common.support.concurrent.pool;


/**
 * 池化对象包装器
 *
 * <p>封装实际对象及其元数据（状态、借出时间、空闲时间等），
 * 供对象池内部管理使用。调用方无需直接操作此类。
 *
 * @param <T> 被包装的对象类型
 * @author CH
 * @since 2026/07/16
 */
class PooledObject<T> {

    /**
     * 对象状态
     */
    enum Status {

        /** 空闲，可被借出 */
        IDLE,

        /** 已借出，正在被使用 */
        BORROWED,

        /** 已失效，待销毁 */
        INVALID
    }

    /** 被包装的实际对象 */
    private final T object;

    /** 当前状态 */
    /**
     * 状态
     */
    private volatile Status status;

    /** 最后一次借出时间（毫秒时间戳） */
    private volatile long lastBorrowTime;

    /** 最后一次归还时间（毫秒时间戳） */
    private volatile long lastReturnTime;

    /** 累计借出次数 */
    private volatile int borrowCount;

    PooledObject(T object) {
        this.object = object;
        this.status = Status.IDLE;
        this.lastBorrowTime = 0;
        this.lastReturnTime = System.currentTimeMillis();
        this.borrowCount = 0;
    }

    T getObject() {
        return object;
    }

    Status getStatus() {
        return status;
    }

    void setStatus(Status status) {
        this.status = status;
    }

    long getLastBorrowTime() {
        return lastBorrowTime;
    }

    long getLastReturnTime() {
        return lastReturnTime;
    }

    int getBorrowCount() {
        return borrowCount;
    }

    /**
     * 标记为已借出
     */
    void markBorrowed() {
        this.status = Status.BORROWED;
        this.lastBorrowTime = System.currentTimeMillis();
        this.borrowCount++;
    }

    /**
     * 标记为已归还
     */
    void markReturned() {
        this.status = Status.IDLE;
        this.lastReturnTime = System.currentTimeMillis();
    }

    /**
     * 计算空闲时长（毫秒）
     *
     * @return 从上次归还到当前的空闲时长
     */
    long getIdleTimeMillis() {
        if (status != Status.IDLE) {
            return 0;
        }
        return System.currentTimeMillis() - lastReturnTime;
    }

    @Override
    public String toString() {
        return "PooledObject{status=" + status
                + ", borrowCount=" + borrowCount
                + ", idleTime=" + getIdleTimeMillis() + "ms"
                + ", object=" + object + "}";
    }
}
