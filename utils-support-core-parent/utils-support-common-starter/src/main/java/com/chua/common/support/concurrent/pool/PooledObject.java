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

    /**
     * 构造方法，创建 Pooled对象 实例。
     *
     * @param object 对象，不允许为 null
     */
    PooledObject(T object) {
        this.object = object;
        this.status = Status.IDLE;
        this.lastBorrowTime = 0;
        this.lastReturnTime = System.currentTimeMillis();
        this.borrowCount = 0;
    }

    /**
     * 获取对象。
     *
     * @return T 对象
     */
    T getObject() {
        return object;
    }

    /**
     * 获取状态。
     *
     * @return 状态 对象
     */
    Status getStatus() {
        return status;
    }

    /**
     * 设置状态。
     *
     * @param status 状态，不允许为 null
     */
    void setStatus(Status status) {
        this.status = status;
    }

    /**
     * 获取最后一个Borrow时间。
     *
     * @return 结果数值
     */
    long getLastBorrowTime() {
        return lastBorrowTime;
    }

    /**
     * 获取最后一个Return时间。
     *
     * @return 结果数值
     */
    long getLastReturnTime() {
        return lastReturnTime;
    }

    /**
     * 获取Borrow数量。
     *
     * @return 结果数值
     */
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
    /** ToString */
    public String toString() {
        return "PooledObject{status=" + status
                + ", borrowCount=" + borrowCount
                + ", idleTime=" + getIdleTimeMillis() + "ms"
                + ", object=" + object + "}";
    }
}
