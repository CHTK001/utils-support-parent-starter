package com.chua.common.support.lang.process;

import com.chua.common.support.math.unit.size.SizeValue;
import com.chua.common.support.utils.NumberUtils;

import static com.chua.common.support.constant.CommonConstant.SYMBOL_EMPTY;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.jspecify.annotations.NullUnmarked;


/**
 * 进度单位类型枚举，定义不同格式的进度数值显示。
 * <p>
 * 支持无单位、字节单位、原始数值、大小单位等多种格式。
 *
 * @author CH
 * @since 2024-01-01
 * @version 1.0.0
 */
@NullUnmarked
public enum ProgressUnitType implements ProgressUnit {

    /**
     * 无单位
     * <p>
     * 返回空字符串，不显示任何数值。
     */
    NONE() {
        @Override
        public String format(long num) {
            return SYMBOL_EMPTY;
        }
    },

    /**
     * 字节单位
     * <p>
     * 自动转换为 B、KB、MB、GB 等单位显示，使用 SizeValue 进行格式化。
     */
    BYTE() {
        @Override
        public String format(long num) {
            return SizeValue.format(num);
        }
    },

    /**
     * 原始数值
     * <p>
     * 直接显示原始数字，不进行单位转换。
     */
    ORIGINAL() {
        @Override
        public String format(long num) {
            return String.valueOf(num);
        }
    },

    /**
     * 大小单位
     * <p>
     * 使用 SizeValue 格式化为 B、KB、MB、GB 等单位，基于 BigDecimal 计算。
     */
    SIZE() {
        @Override
        public String format(long num) {
            return SizeValue.format(NumberUtils.toBigDecimal(num).longValue());
        }
    }
}
