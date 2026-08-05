package com.chua.common.support.lang.process;


/**
 * 进度模拟器，模拟进度从 0 到 100 的变化过程。
 * <p>
 * 支持多种进度变化曲线：慢-快-慢（S 曲线）、先快后慢、先慢后快、线性，默认总进度为 100。
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
public final class ProgressSimulator {

    /**
     * 进度变化类型枚举
     * <p>
     * 定义不同的进度曲线算法。
     *
     * @author CH
     * @since 2024-01-01
     */
    public enum Type {
        /**
         * 先慢后快再慢（S 型曲线，Ease In-Out 效果）
         */
        SLOW_FAST_SLOW,
        /**
         * 先快后慢（Ease Out 效果）
         */
        FAST_SLOW,
        /**
         * 先慢后快（Ease In 效果）
         */
        SLOW_FAST,
        /**
         * 匀速
         */
        LINEAR
    }

    /**
     * 圆周率常量
     */
    private static final double PI = Math.PI;

    /**
     * 圆周率的一半
     */
    private static final double PI_OVER_2 = Math.PI / 2.0;

    /**
     * 进度曲线类型
     */
    private final Type type;

    /**
     * 总进度数
     */
    private final int total;

    /**
     * 当前进度值
     */
    private double current;

    /**
     *             
     * <p>
     *                                                                 100                
     */
    public ProgressSimulator() {
        this(Type.SLOW_FAST_SLOW, 100);
    }

    /**
     *             
     * <p>
     *                                      100                
     *
     * @param type             
     */
    public ProgressSimulator(Type type) {
        this(type, 100);
    }

    /**
     *             
     * <p>
     *                                              
     *
     * @param type             
     * @param total                    > 0   
     */
    public ProgressSimulator(Type type, int total) {
        this.type = type == null ? Type.SLOW_FAST_SLOW : type;
        this.total = Math.max(1, total);
        this.current = 0.0;
    }

    /**
     *                      
     *
     * @return                            [0, total]                           
     */
    public double getCurrentProgress() {
        return current;
    }

    /**
     *                
     *
     * @return          
     */
    public int getTotal() {
        return total;
    }

    /**
     *                            
     *
     * @return                   0-100   
     */
    public double getPercentage() {
        if (total <= 0) {
            return 0.0;
        }
        return Math.min(100.0, (current / total) * 100.0);
    }

    /**
     *                      
     * <p>
     *                                           
     *
     * @return true                   false                
     */
    public boolean isFinish() {
        return current >= total;
    }

    /**
     *                0
     */
    public void reset() {
        this.current = 0.0;
    }

    /**
     *                      
     * <p>
     *                                1    "            "         
     *
     * @return                                     current   
     */
    public double next() {
        this.current = next(this.current, 1.0);
        return this.current;
    }

    /**
     *                      
     * <p>
     *                                               1
     *
     * @param currentProgress                         [0, total]         
     * @return                                              
     */
    public double next(double currentProgress) {
        return next(currentProgress, 1.0);
    }

    /**
     *                                                                
     * <p>
     *                                                                                                                                     
     *              total                   
     *
     * @param currentProgress                [0, total]   
     * @param baseStep                       > 0             1   
     * @return                                           
     */
    public double next(double currentProgress, double baseStep) {
        if (Double.isNaN(currentProgress) || Double.isInfinite(currentProgress)) {
            currentProgress = 0.0;
        }
        double cur = clamp(currentProgress, 0.0, total);
        if (cur >= total) {
            return total;
        }
        double step = Math.max(1e-9, baseStep);
        double t = total > 0 ? (cur / total) : 0.0; //              [0,1]

        double speedFactor;
        switch (type) {
            case SLOW_FAST_SLOW -> {
                //        sin(  t)                      0->1->0               /2                                     baseStep
                speedFactor = PI_OVER_2 * Math.sin(PI * t);
            }
            case FAST_SLOW -> {
                //        cos(  t/2)                      1->0                     
                speedFactor = PI_OVER_2 * Math.cos(PI * t / 2.0);
            }
            case SLOW_FAST -> {
                //        sin(  t/2)                      0->1                     
                speedFactor = PI_OVER_2 * Math.sin(PI * t / 2.0);
            }
            case LINEAR -> {
                speedFactor = 1.0;
            }
            default -> {
                speedFactor = 1.0;
            }
        }

        //                   t      0   1                  0                                                               
        double minFactor = 1.0 / total;
        if (speedFactor < minFactor) {
            speedFactor = minFactor;
        }

        double next = cur + step * speedFactor;
        if (next >= total) {
            return total;
        }
        //                                total       
        if (total - next < 1e-9) {
            return total;
        }
        return next;
    }

    /**
     *                               
     *
     * @param v                
     * @param min          
     * @param max          
     * @return                
     */
    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    @Override
    public String toString() {
        return "ProgressSimulator{" +
                "type=" + type +
                ", total=" + total +
                ", current=" + String.format("%.2f", current) +
                '}';
    }
}