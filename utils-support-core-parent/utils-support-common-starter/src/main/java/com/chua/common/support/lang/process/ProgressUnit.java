package com.chua.common.support.lang.process;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
 * 进度单位接口，定义进度数值的格式化方法。
 * <p>
 * 不同的单位实现将进度数值格式化为不同的显示形式（如无单位、字节、原始值等）。
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
public interface ProgressUnit {

    /**
     * 格式化进度数值
     * 根据单位类型将原始数值转换为可读的字符串格式。
     *
     * @param num 进度数值
     * @return 格式化后的字符串
     */
    String format(long num);
}
