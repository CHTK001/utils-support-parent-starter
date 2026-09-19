package com.chua.common.support.taskdistribution.task;

/**
 * 任务优先级枚举。
 *
 * <p>优先级越高的任务越先被派发。HIGH 优先于 MEDIUM，MEDIUM 优先于 LOW。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public enum TaskPriority {
    /**
     * 低优先级
     */
    LOW(0),

    /**
     * 中优先级（默认）
     */
    MEDIUM(5),

    /**
     * 高优先级
     * @param 10 方法入参 10
     */
    HIGH(10);

    /**
     * 级别
    */
    private final int level;

    /**
     * 构造方法，创建 TaskPriority 实例。
     *
     * @param level 级别，不允许为 null
     */
    TaskPriority(int level) {
        this.level = level;
    }

    /**
     * 获取优先级等级数值。
     *
     * @return 等级值（越大优先级越高）
     */
    public int getLevel() {
        return level;
    }
}
