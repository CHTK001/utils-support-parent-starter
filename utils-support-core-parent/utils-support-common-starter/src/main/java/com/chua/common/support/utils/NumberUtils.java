package com.chua.common.support.utils;
import com.chua.common.support.converter.Converter;
import com.chua.common.support.lang.algorithm.crypto.Hex;
import com.chua.common.support.value.NumberValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import static com.chua.common.support.constant.CommonConstant.*;
import static com.chua.common.support.constant.RegexConstant.DECIMALS;
import static com.chua.common.support.constant.RegexConstant.INT_PATTERN;
/**
 * 数字工具类，提供数值计算、类型转换、格式化、比较等操作。
 *
 * <p>包含以下功能：
 * <ul>
 *   <li>数值计算 — 加法、减法、乘法、除法、百分比、平均值、方差、标准差</li>
 *   <li>类型转换 — 字符串与各种数字类型的互相转换（int、long、double、BigDecimal 等）</li>
 *   <li>格式化 — 数字格式化、金额格式化、百分比格式化、科学计数法</li>
 *   <li>比较运算 — 安全比较、范围判断、最大值最小值、舍入</li>
 *   <li>数值生成 — 范围遍历、随机数、等差数列</li>
 *   <li>进制转换 — 二进制、八进制、十进制、十六进制互转</li>
 *   <li>中文数字 — 中文大写金额、中文数字与阿拉伯数字互转</li>
 * </ul>
 *
 * @author CH
 * @since 4.0.0
 */
public class NumberUtils {
    /**
     * 数字工具。
     */
    private NumberUtils() {
    }
    /**
     * Long_最小
    */
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    /**
     * Long_最大
    */
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    /**
     * A
    */
    private static final int A = 'A';
    /**
     * Z 轴
    */
    private static final int Z = 'Z';
    /**
     * 中文数字字符 → 数值映射（小写+大写+两）
    */
    private static final Map<Character, Integer> CN_DIGITS = new HashMap<>();
    /**
     * 中文单位字符 → 数值映射（十/百/千/万/亿）
    */
    private static final Map<Character, Integer> CN_UNITS = new HashMap<>();
    static {
        CN_DIGITS.put('零', 0);
        CN_DIGITS.put('一', 1);
        CN_DIGITS.put('壹', 1);
        CN_DIGITS.put('两', 2);
        CN_DIGITS.put('二', 2);
        CN_DIGITS.put('贰', 2);
        CN_DIGITS.put('三', 3);
        CN_DIGITS.put('叁', 3);
        CN_DIGITS.put('四', 4);
        CN_DIGITS.put('肆', 4);
        CN_DIGITS.put('五', 5);
        CN_DIGITS.put('伍', 5);
        CN_DIGITS.put('六', 6);
        CN_DIGITS.put('陆', 6);
        CN_DIGITS.put('七', 7);
        CN_DIGITS.put('柒', 7);
        CN_DIGITS.put('八', 8);
        CN_DIGITS.put('捌', 8);
        CN_DIGITS.put('九', 9);
        CN_DIGITS.put('玖', 9);
        CN_UNITS.put('十', 10);
        CN_UNITS.put('拾', 10);
        CN_UNITS.put('百', 100);
        CN_UNITS.put('佰', 100);
        CN_UNITS.put('千', 1000);
        CN_UNITS.put('仟', 1000);
        CN_UNITS.put('万', 10000);
        CN_UNITS.put('亿', 100000000);
    }
    /**
     * 逼近函数（Sigmoid 变形），将输入映射到 (0, 1) 区间。
     *
     * <p>公式：f(x) = 1 / (1 + e^(-0.1 * x))
     *
     * @param x 输入值
     * @return 逼近结果 (0, 1)
     */
    public static double approachOneFunction(int x) {
        return 1 / (1 + Math.exp(-0.1 * x));
    }
    /**
     * 保留 bigdecimal 精度。
     *
     * <p>使用四舍五入模式保留指定位数的小数。
     *
     * @param value 待处理的 bigdecimal
     * @param scale 小数位数
     * @return 处理后的 bigdecimal
     */
    public static BigDecimal reserve(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.HALF_UP);
    }
    /**
     * 保留 float 精度。
     *
     * <p>将 float 转 BigDecimal 后使用四舍五入保留指定位数小数。
     *
     * @param value 待处理的 float 值
     * @param scale 小数位数
     * @return 处理后的 bigdecimal
     */
    public static BigDecimal reserve(float value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
    /**
     * 保留 double 精度。
     *
     * <p>将 double 转 BigDecimal 后使用四舍五入保留指定位数小数。
     *
     * @param value 待处理的 double 值
     * @param scale 小数位数
     * @return 处理后的 bigdecimal
     */
    public static BigDecimal reserve(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
    /**
     * 比较两个 long 值的大小。
     *
     * @param o1 第一个值
     * @param o2 第二个值
     * @return 负数表示 o1 &lt; o2，0 表示相等，正数表示 o1 &gt; o2
     */
    public static int compareLong(long o1, long o2) {
        return Long.compare(o1, o2);
    }
    /**
     * 获取指定范围内的随机整数。
     *
     * @param start 起始值（包含）
     * @param end   结束值（包含）
     * @return [start, 结束] 区间内的随机整数
     */
    public static int getNum(int start, int end) {
        return (int) (Math.random() * (end - start + 1) + start);
    }
    /**
     * 加法运算（float + float）。
     *
     * @param v1 加数
     * @param v2 加数
     * @return 和
     */
    public static double add(float v1, float v2) {
        return add(Float.toString(v1), Float.toString(v2)).doubleValue();
    }
    /**
     * 加法运算（float + double）。
     *
     * @param v1 加数
     * @param v2 加数
     * @return 和
     */
    public static double add(float v1, double v2) {
        return add(Float.toString(v1), Double.toString(v2)).doubleValue();
    }
    /**
     * 加法运算（double + float）。
     *
     * @param v1 加数
     * @param v2 加数
     * @return 和
     */
    public static double add(double v1, float v2) {
        return add(Double.toString(v1), Float.toString(v2)).doubleValue();
    }
    /**
     * 加法运算（double + double）。
     *
     * @param v1 加数
     * @param v2 加数
     * @return 和
     */
    public static double add(double v1, double v2) {
        return add(Double.toString(v1), Double.toString(v2)).doubleValue();
    }
    /**
     * 加法运算（Double + Double），空值按 0 处理。
     *
     * @param v1 加数
     * @param v2 加数
     * @return 和
     * @since 3.1.1
     */
    public static double add(Double v1, Double v2) {
        return add(v1, (Number) v2).doubleValue();
    }
    /**
     * 加法运算（数字 + 数字），空值按 0 处理。
     *
     * @param v1 加数
     * @param v2 加数
     * @return 和
     */
    public static BigDecimal add(Number v1, Number v2) {
        return add(new Number[]{v1, v2});
    }
    /**
     * 累加多个 数字 值，空值按 0 处理。
     *
     * @param values 数值数组
     * @return 总和
     * @since 4.0.0
     */
    public static BigDecimal add(Number... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        Number value = values[0];
        BigDecimal result = null == value ? BigDecimal.ZERO : new BigDecimal(value.toString());
        for (int i = 1; i < values.length; i++) {
            value = values[i];
            if (null != value) {
                result = result.add(new BigDecimal(value.toString()));
            }
        }
        return result;
    }
    /**
     * 累加多个字符串数值，空值按 0 处理。
     *
     * @param values 数字字符串数组
     * @return 总和
     * @since 4.0.0
     */
    public static BigDecimal add(String... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        String value = values[0];
        BigDecimal result = null == value ? BigDecimal.ZERO : new BigDecimal(value);
        for (int i = 1; i < values.length; i++) {
            value = values[i];
            if (null != value) {
                result = result.add(new BigDecimal(value));
            }
        }
        return result;
    }
    /**
     * 累加多个 bigdecimal 值，空值按 0 处理。
     *
     * @param values bigdecimal 数组
     * @return 总和
     * @since 4.0.0
     */
    public static BigDecimal add(BigDecimal... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = values[0];
        BigDecimal result = null == value ? BigDecimal.ZERO : value;
        for (int i = 1; i < values.length; i++) {
            value = values[i];
            if (null != value) {
                result = result.add(value);
            }
        }
        return result;
    }
    /**
     * 向上取整除法（ceil 分部）。
     *
     * <p>返回大于或等于两数之商的最小整数。JDK 8 无 Math.ceilDiv，此方法用 Math.ceil 模拟。
     *
     * @param v1 被除数
     * @param v2 除数
     * @return 向上取整后的商
     * @since 5.3.3
     */
    public static int ceilDiv(int v1, int v2) {
        return (int) Math.ceil((double) v1 / v2);
    }
    /**
     * 除法运算（float / float），精度 10 位。
     *
     * @param v1 被除数
     * @param v2 除数
     * @return 商
     */
    public static double div(float v1, float v2) {
        return div(v1, v2, 10);
    }
    /**
     * 除法运算（float / double），精度 10 位。
     *
     * @param v1 被除数
     * @param v2 除数
     * @return 商
     */
    public static double div(float v1, double v2) {
        return div(v1, v2, 10);
    }
    /**
     * 除法运算（double / float），精度 10 位。
     *
     * @param v1 被除数
     * @param v2 除数
     * @return 商
     */
    public static double div(double v1, float v2) {
        return div(v1, v2, 10);
    }
    /**
     * 除法运算（double / double），精度 10 位。
     *
     * @param v1 被除数
     * @param v2 除数
     * @return 商
     */
    public static double div(double v1, double v2) {
        return div(v1, v2, 10);
    }
    /**
     * 除法运算（Double / Double），精度 10 位。
     *
     * @param v1 被除数
     * @param v2 除数
     * @return 商
     */
    public static double div(Double v1, Double v2) {
        return div(v1, v2, 10);
    }
    /**
     * 除法运算（数字 / 数字），精度 10 位。
     *
     * @param v1 被除数
     * @param v2 除数
     * @return 商
     * @since 3.1.0
     */
    public static BigDecimal div(Number v1, Number v2) {
        return div(v1, v2, 10);
    }
    /**
     * 除法运算（字符串数值），精度 10 位。
     *
     * @param v1 被除数（数字字符串）
     * @param v2 除数（数字字符串）
     * @return 商
     */
    public static BigDecimal div(String v1, String v2) {
        return div(v1, v2, 10);
    }
    /**
     *       (      )                     ,                              ,   scale               ,                     
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @return the 结果
     */
    public static double div(float v1, float v2, int scale) {
        return div(v1, v2, scale, RoundingMode.HALF_UP);
    }
    /**
     *       (      )                     ,                              ,   scale               ,                     
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @return the 结果
     */
    public static double div(float v1, double v2, int scale) {
        return div(v1, v2, scale, RoundingMode.HALF_UP);
    }
    /**
     *       (      )                     ,                              ,   scale               ,                     
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @return the 结果
     */
    public static double div(double v1, float v2, int scale) {
        return div(v1, v2, scale, RoundingMode.HALF_UP);
    }
    /**
     *       (      )                     ,                              ,   scale               ,                     
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @return the 结果
     */
    public static double div(double v1, double v2, int scale) {
        return div(v1, v2, scale, RoundingMode.HALF_UP);
    }
    /**
     *       (      )                     ,                              ,   scale               ,                     
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @return the 结果
     */
    public static double div(Double v1, Double v2, int scale) {
        return div(v1, v2, scale, RoundingMode.HALF_UP);
    }
    /**
     *       (      )                     ,                              ,   scale               ,                     
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @return the 结果
     * @since 3.1.0
     */
    public static BigDecimal div(Number v1, Number v2, int scale) {
        return div(v1, v2, scale, RoundingMode.HALF_UP);
    }
    /**
     *       (      )                     ,                              ,   scale               ,                     
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @return the 结果
     */
    public static BigDecimal div(String v1, String v2, int scale) {
        return div(v1, v2, scale, RoundingMode.HALF_UP);
    }
    /**
     *       (      )                     ,                              ,   scale               
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     */
    public static double div(float v1, float v2, int scale, RoundingMode roundingMode) {
        return div(Float.toString(v1), Float.toString(v2), scale, roundingMode).doubleValue();
    }
    /**
     *       (      )                     ,                              ,   scale               
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     */
    public static double div(float v1, double v2, int scale, RoundingMode roundingMode) {
        return div(Float.toString(v1), Double.toString(v2), scale, roundingMode).doubleValue();
    }
    /**
     *       (      )                     ,                              ,   scale               
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     */
    public static double div(double v1, float v2, int scale, RoundingMode roundingMode) {
        return div(Double.toString(v1), Float.toString(v2), scale, roundingMode).doubleValue();
    }
    /**
     *       (      )                     ,                              ,   scale               
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     */
    public static double div(double v1, double v2, int scale, RoundingMode roundingMode) {
        return div(Double.toString(v1), Double.toString(v2), scale, roundingMode).doubleValue();
    }
    /**
     *       (      )                     ,                              ,   scale               
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     */
    public static double div(Double v1, Double v2, int scale, RoundingMode roundingMode) {
        return div(v1, (Number) v2, scale, roundingMode).doubleValue();
    }
    /**
     *       (      )                     ,                              ,   scale               
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     * @since 3.1.0
     */
    public static BigDecimal div(Number v1, Number v2, int scale, RoundingMode roundingMode) {
        return div(v1.toString(), v2.toString(), scale, roundingMode);
    }
    /**
     *       (      )                     ,                              ,   scale               
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     */
    public static BigDecimal div(String v1, String v2, int scale, RoundingMode roundingMode) {
        return div(new BigDecimal(v1), new BigDecimal(v2), scale, roundingMode);
    }
    /**
     *       (      )                     ,                              ,   scale               
     *
     * @param v1 v1
     * @param v2 v2
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     * @since 3.0.9
     */
    public static BigDecimal div(BigDecimal v1, BigDecimal v2, int scale, RoundingMode roundingMode) {
        if (null == v1) {
            return BigDecimal.ZERO;
        }
        if (scale < 0) {
            scale = -scale;
        }
        return v1.divide(v2, scale, roundingMode);
    }
    /**
     *                                           
     *
     * @param numerator numerator
     * @param denominator denominator
     * @param scale scale
     * @return the 结果
     */
    public static double divide(long numerator, long denominator, int scale) {
        BigDecimal numeratorBd = new BigDecimal(numerator);
        BigDecimal denominatorBd = new BigDecimal(denominator);
        return numeratorBd.divide(denominatorBd, scale, RoundingMode.HALF_UP).doubleValue();
    }
    /**
     *                                                                      
     *
     * @param numerator numerator
     * @param denominator denominator
     * @return the 结果
     */
    public static double divide(long numerator, long denominator) {
        return divide(numerator, denominator, 2);
    }
    /**
     *                                           
     *
     * @param numerator numerator
     * @param denominator denominator
     * @param scale scale
     * @return the 结果
     */
    public static double divide(double numerator, double denominator, int scale) {
        BigDecimal numeratorBd = BigDecimal.valueOf(numerator);
        BigDecimal denominatorBd = BigDecimal.valueOf(denominator);
        return numeratorBd.divide(denominatorBd, scale, RoundingMode.HALF_UP).doubleValue();
    }
    /**
     *                                                                      
     *
     * @param numerator numerator
     * @param denominator denominator
     * @return the 结果
     */
    public static double divide(double numerator, double denominator) {
        return divide(numerator, denominator, 2);
    }
    /**
     *                
     *
     * @param m m
     * @param n n
     * @return the 结果
     */
    public static int divisor(int m, int n) {
        while (m % n != 0) {
            int temp = m % n;
            m = n;
            n = temp;
        }
        return n;
    }
    /**
     * <p>
     * n! = n * (n-1) * ... * 结束
     * </p>
     *
     * @param start 启动
     * @param end 结束
     * @return the 结果
     * @since 4.1.0
     */
    public static long factorial(long start, long end) {
        if (0L == start || start == end) {
            return 1L;
        }
        if (start < end) {
            return 0L;
        }
        return start * factorial(start - 1, end);
    }
    /**
     * <p>
     * n! = n * (n-1) * ... * 2 * 1
     * </p>
     *
     * @param n n
     * @return the 结果
     */
    public static long factorial(long n) {
        return factorial(n, 1);
    }
    /**
     *       
     *
     * @param max 最大
     * @param min 最小
     * @return int
     */
    public static int fences(long max, long min) {
        final Long before = max / min;
        final Long after = max % min == 0L ? 0L : 1L;
        final long sum = (before + after);
        return (int) sum;
    }
    /**
     *                                                                                                 1000            
     *
     * @param numberAsString 数字as字符串
     * @return                                     1000         
     */
    public static boolean isThousandSeparator(String numberAsString) {
        //                                        
        String cleanedNumber = numberAsString.replace(",", "");
        return isDecimals(cleanedNumber);
    }
    /**
     *                                                             
     *
     * @param decimals                0-9                           1.23   233.30
     * @return                   true                     false
     */
    public static boolean isDecimals(String decimals) {
        decimals = decimals.replaceAll("(\\s+|,)", "");
        return Pattern.matches(DECIMALS.pattern(), decimals);
    }
    /**
     * 是否 integer 字符串.
     *
     * @param str str
     * @return is integer
     */
    public static boolean isInteger(String str) {
        if (str == null || str.length() == 0) {
            return false;
        }
        return INT_PATTERN.matcher(str).matches();
    }
    /**
     *                
     *
     * @param str str
     * @return boolean
     */
    public static boolean isNumber(final CharSequence str) {
        if (null == str) {
            return false;
        }
        int sz = str.length();
        for (int i = 0; i < sz; i++) {
            if (!Character.isDigit(str.charAt(i))) {
                return false;
            }
        }
        return true;
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double mul(float v1, float v2) {
        return mul(Float.toString(v1), Float.toString(v2)).doubleValue();
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double mul(float v1, double v2) {
        return mul(Float.toString(v1), Double.toString(v2)).doubleValue();
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double mul(double v1, float v2) {
        return mul(Double.toString(v1), Float.toString(v2)).doubleValue();
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double mul(double v1, double v2) {
        return mul(Double.toString(v1), Double.toString(v2)).doubleValue();
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double mul(Double v1, Double v2) {
        return mul(v1, (Number) v2).doubleValue();
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static BigDecimal mul(Number v1, Number v2) {
        return mul(new Number[]{v1, v2});
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param values 值
     * @return the 结果
     * @since 4.0.0
     */
    public static BigDecimal mul(Number... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        Number value = values[0];
        BigDecimal result = new BigDecimal(value.toString());
        for (int i = 1; i < values.length; i++) {
            value = values[i];
            result = result.multiply(new BigDecimal(value.toString()));
        }
        return result;
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     * @since 3.0.8
     */
    public static BigDecimal mul(String v1, String v2) {
        return mul(new BigDecimal(v1), new BigDecimal(v2));
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param values 值
     * @return the 结果
     * @since 4.0.0
     */
    public static BigDecimal mul(String... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        BigDecimal result = new BigDecimal(values[0]);
        for (int i = 1; i < values.length; i++) {
            result = result.multiply(new BigDecimal(values[i]));
        }
        return result;
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param values 值
     * @return the 结果
     * @since 4.0.0
     */
    public static BigDecimal mul(BigDecimal... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        BigDecimal result = values[0];
        for (int i = 1; i < values.length; i++) {
            result = result.multiply(values[i]);
        }
        return result;
    }
    /**
     *                
     *
     * @param m m
     * @param n n
     * @return the 结果
     */
    public static int multiple(int m, int n) {
        return m * n / divisor(m, n);
    }
    /**
     *    int                                                               
     *
     * @param num1       1
     * @param num2       2
     * @return the 结果
     */
    public static int multiply(int num1, double num2) {
        return multiply((double) num1, num2);
    }
    /**
     *    long                                                               
     *
     * @param num1       1
     * @param num2       2
     * @return the 结果
     */
    public static int multiply(long num1, double num2) {
        double num1D = ((Long) num1).doubleValue();
        return multiply(num1D, num2);
    }
    /**
     *    double                                                         
     *
     * @param num1       1
     * @param num2       2
     * @return the 结果
     */
    public static int multiply(double num1, double num2) {
        BigDecimal num1Bd = BigDecimal.valueOf(num1);
        BigDecimal num2Bd = BigDecimal.valueOf(num2);
        MathContext mathContext = new MathContext(num1Bd.precision(), RoundingMode.HALF_UP);
        return num1Bd.multiply(num2Bd, mathContext).intValue();
    }
    /**
     * 字符串         数字
     *
     * @param <T>               
     * @param text 文本
     * @param targetClass 目标类
     * @return Number
     * @param number 数字
     */
@SuppressWarnings("all")
    public static <T extends Number> T parseNumber(Number number, Class<T> targetClass) {
        if (targetClass.isInstance(number)) {
            return (T) number;
        } else if (Byte.class == targetClass) {
            long value = checkedLongValue(number, targetClass);
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                raiseOverflowException(number, targetClass);
            }
            return (T) Byte.valueOf(number.byteValue());
        } else if (Short.class == targetClass) {
            long value = checkedLongValue(number, targetClass);
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                raiseOverflowException(number, targetClass);
            }
            return (T) Short.valueOf(number.shortValue());
        } else if (Integer.class == targetClass) {
            long value = checkedLongValue(number, targetClass);
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                raiseOverflowException(number, targetClass);
            }
            return (T) Integer.valueOf(number.intValue());
        } else if (Long.class == targetClass) {
            long value = checkedLongValue(number, targetClass);
            return (T) Long.valueOf(value);
        } else if (BigInteger.class == targetClass) {
            if (number instanceof BigDecimal) {
                return (T) ((BigDecimal) number).toBigInteger();
            } else {
                return (T) BigInteger.valueOf(number.longValue());
            }
        } else if (Float.class == targetClass) {
            return (T) Float.valueOf(number.floatValue());
        } else if (Double.class == targetClass) {
            return (T) Double.valueOf(number.doubleValue());
        } else if (BigDecimal.class == targetClass) {
            return (T) new BigDecimal(number.toString());
        } else {
            throw new IllegalArgumentException("Could not convert number [" + number + "] of type [" +
                    number.getClass().getName() + "] to unsupported target class [" + targetClass.getName() + "]");
        }
    }
    /**
     *  an <em>overflow</em> 异常 for the given 数字 和 Target 类.
     *
     * @param number      the 数字 we tried 转为 转换
     * @param targetClass the Target 类 we tried 转为 转换 转为
     * @throws IllegalArgumentException if there 是否 an overflow
     */
    private static void raiseOverflowException(Number number, Class<?> targetClass) {
        throw new IllegalArgumentException("Could not convert number [" + number + "] of type [" +
                number.getClass().getName() + "] to target class [" + targetClass.getName() + "]: overflow");
    }
    /**
     * 检查 for a {@code BigInteger}/{@code BigDecimal} long overflow
     * 之前 返回 the given 数字 as a long 值.
     *
     * @param number      the 数字 转为 转换
     * @param targetClass the Target 类 转为 转换 转为
     * @return the long 值, if 转换 without overflow
     * @throws IllegalArgumentException if there 是否 an overflow
     * @see #raiseOverflowException
     */
    private static long checkedLongValue(Number number, Class<? extends Number> targetClass) {
        BigInteger bigInt = null;
        if (number instanceof BigInteger) {
            bigInt = (BigInteger) number;
        } else if (number instanceof BigDecimal) {
            bigInt = ((BigDecimal) number).toBigInteger();
        }
        boolean b = bigInt != null && (bigInt.compareTo(LONG_MIN) < 0 || bigInt.compareTo(LONG_MAX) > 0);
        if (b) {
            raiseOverflowException(number, targetClass);
        }
        return number.longValue();
    }
    /**
     * 字符串         数字
     *
     * @param <T>               
     * @param text 文本
     * @param targetClass 目标类
     * @return Number
     */
    public static <T extends Number> T converterNumber(String text, Class<T> targetClass) {
        String trimmed = StringUtils.trimAllWhitespace(text);
        if (Byte.class == targetClass) {
            return (T) (isHexNumber(trimmed) ? Byte.decode(trimmed) : Byte.valueOf(trimmed));
        } else if (Short.class == targetClass) {
            return (T) (isHexNumber(trimmed) ? Short.decode(trimmed) : Short.valueOf(trimmed));
        } else if (Integer.class == targetClass) {
            return (T) (isHexNumber(trimmed) ? Integer.decode(trimmed) : Integer.valueOf(trimmed));
        } else if (Long.class == targetClass) {
            return (T) (isHexNumber(trimmed) ? Long.decode(trimmed) : Long.valueOf(trimmed));
        } else if (BigInteger.class == targetClass) {
            return (T) (isHexNumber(trimmed) ? decodeBigInteger(trimmed) : new BigInteger(trimmed));
        } else if (Float.class == targetClass) {
            return (T) Float.valueOf(trimmed);
        } else if (Double.class == targetClass) {
            return (T) Double.valueOf(trimmed);
        } else if (BigDecimal.class == targetClass || Number.class == targetClass) {
            return (T) new BigDecimal(trimmed);
        } else {
            throw new IllegalArgumentException(
                    "Cannot convert String [" + text + "] to target class [" + targetClass.getName() + "]");
        }
    }
    /**
     *                               N                           <br>
     *                                  +1
     *
     * @param total total
     * @param partCount part数量
     * @return the 结果
     * @since 4.0.7
     */
    public static int partValue(int total, int partCount) {
        return partValue(total, partCount, true);
    }
    /**
     *                               N                           <br>
     * 是否plusoneWhen.js是否包含rem   true                                       +1
     *
     * @param total total
     * @param partCount part数量
     * @param isPlusOneWhenHasRem                            +1
     * @return the 结果
     * @since 4.0.7
     */
    public static int partValue(int total, int partCount, boolean isPlusOneWhenHasRem) {
        int partValue = total / partCount;
        if (isPlusOneWhenHasRem && total % partCount == 0) {
            partValue++;
        }
        return partValue;
    }
    /**
     *          
     *
     * @param current 当前
     * @param total total
     * @return the 结果
     */
    public static double percentage(double current, double total) {
        return current / total * 100.;
    }
    /**
     *                
     *
     * @param start 启动
     * @param end 结束
     * @return the 结果
     * @see IntStream
     */
    public static IntStream range(int start, int end) {
        return IntStream.range(start, end);
    }
    /**
     *                
     *
     * @param end 结束
     * @return the 结果
     * @see IntStream
     */
    public static IntStream range(int end) {
        return IntStream.range(0, end);
    }
    /**
     *                
     *
     * @param end 结束
     * @param consumer consumer
     * @see IntStream
     */
    public static void range(int end, IntConsumer consumer) {
        IntStream.range(0, end).forEach(consumer);
    }
    /**
     *                
     *
     * @param start 启动
     * @param end 结束
     * @param consumer consumer
     * @see IntStream
     */
    public static void range(int start, int end, IntConsumer consumer) {
        IntStream.range(start, end).forEach(consumer);
    }
    /**
     *                
     *
     * @param end 结束
     * @param consumer consumer
     * @see IntStream
     */
    public static void rangeClosed(int end, IntConsumer consumer) {
        IntStream.rangeClosed(0, end).forEach(consumer);
    }
    /**
     *                
     *
     * @param start 启动
     * @param end 结束
     * @return the 结果
     * @see IntStream
     */
    public static IntStream rangeClosed(int start, int end) {
        return IntStream.rangeClosed(start, end);
    }
    /**
     *                         <br>
     *                          {@link RoundingMode#HALF_UP}<br>
     *             2            123.456789 =    123.46
     *
     * @param v v
     * @param scale scale
     * @return the 结果
     */
    public static BigDecimal round(double v, int scale) {
        return round(v, scale, RoundingMode.HALF_UP);
    }
    /**
     *                         <br>
     *                          {@link RoundingMode#HALF_UP}<br>
     *             2            123.456789 =    123.46
     *
     * @param numberStr 数字str
     * @param scale scale
     * @return the 结果
     */
    public static BigDecimal round(String numberStr, int scale) {
        return round(numberStr, scale, RoundingMode.HALF_UP);
    }
    /**
     *                         <br>
     *                          {@link RoundingMode#HALF_UP}<br>
     *             2            123.456789 =    123.46
     *
     * @param number 数字
     * @param scale scale
     * @return the 结果
     * @since 4.1.0
     */
    public static BigDecimal round(BigDecimal number, int scale) {
        return round(number, scale, RoundingMode.HALF_UP);
    }
    /**
     *                         <br>
     *                            123.456789 =    123.4567
     *
     * @param v v
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     */
    public static BigDecimal round(double v, int scale, RoundingMode roundingMode) {
        return round(Double.toString(v), scale, roundingMode);
    }
    /**
     *                         <br>
     *                            123.456789 =    123.4567
     *
     * @param numberStr 数字str
     * @param scale                                               0            0
     * @param roundingMode                       {@link RoundingMode}               空
     * @return the 结果
     */
    public static BigDecimal round(String numberStr, int scale, RoundingMode roundingMode) {
        if (scale < 0) {
            scale = 0;
        }
        return round(toBigDecimal(numberStr), scale, roundingMode);
    }
    /**
     *                         <br>
     *                            123.456789 =    123.4567
     *
     * @param number 数字
     * @param scale                                               0            0
     * @param roundingMode                       {@link RoundingMode}               空
     * @return the 结果
     */
    public static BigDecimal round(BigDecimal number, int scale, RoundingMode roundingMode) {
        if (null == number) {
            number = BigDecimal.ZERO;
        }
        if (scale < 0) {
            scale = 0;
        }
        if (null == roundingMode) {
            roundingMode = RoundingMode.HALF_UP;
        }
        return number.setScale(scale, roundingMode);
    }
    /**
     *                                              
     *
     * @param number 数字
     * @param scale scale
     * @return the 结果
     * @since 4.1.0
     */
    public static BigDecimal roundDown(Number number, int scale) {
        return roundDown(toBigDecimal(number), scale);
    }
    /**
     *                                              
     *
     * @param value 值
     * @param scale scale
     * @return the 结果
     * @since 4.1.0
     */
    public static BigDecimal roundDown(BigDecimal value, int scale) {
        return round(value, scale, RoundingMode.DOWN);
    }
    /**
     * <p>
     *                                                                                                          
     * </p>
     *
     * <pre>
     *             :
     *                         
     *                         
     *                         
     *                         
     *                         
     * </pre>
     *
     * @param number 数字
     * @param scale scale
     * @return the 结果
     * @since 4.1.0
     */
    public static BigDecimal roundHalfEven(Number number, int scale) {
        return roundHalfEven(toBigDecimal(number), scale);
    }
    /**
     * <p>
     *                                                                                                          
     * </p>
     *
     * <pre>
     *             :
     *                         
     *                         
     *                         
     *                         
     *                         
     * </pre>
     *
     * @param value 值
     * @param scale scale
     * @return the 结果
     * @since 4.1.0
     */
    public static BigDecimal roundHalfEven(BigDecimal value, int scale) {
        return round(value, scale, RoundingMode.HALF_EVEN);
    }
    /**
     *                         <br>
     *                          {@link RoundingMode#HALF_UP}<br>
     *             2            123.456789 =    123.46
     *
     * @param v v
     * @param scale scale
     * @return the 结果
     */
    public static String roundStr(double v, int scale) {
        return round(v, scale).toString();
    }
    /**
     *                         <br>
     *                          {@link RoundingMode#HALF_UP}<br>
     *             2            123.456789 =    123.46
     *
     * @param numberStr 数字str
     * @param scale scale
     * @return the 结果
     * @since 3.2.2
     */
    public static String roundStr(String numberStr, int scale) {
        return round(numberStr, scale).toString();
    }
    /**
     *                         <br>
     *                            123.456789 =    123.4567
     *
     * @param v v
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     * @since 3.2.2
     */
    public static String roundStr(double v, int scale, RoundingMode roundingMode) {
        return round(v, scale, roundingMode).toString();
    }
    /**
     *                         <br>
     *                            123.456789 =    123.4567
     *
     * @param numberStr 数字str
     * @param scale scale
     * @param roundingMode                       {@link RoundingMode}
     * @return the 结果
     * @since 3.2.2
     */
    public static String roundStr(String numberStr, int scale, RoundingMode roundingMode) {
        return round(numberStr, scale, roundingMode).toString();
    }
    /**
     *          Integer      
     *
     * @param value 值
     * @return the 结果
     */
    public static int saturatedCast(long value) {
        if (value > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (value < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) value;
    }
    /**
     *                   {scale}      
     *
     * @param value 值
     * @param scale scale
     * @return the 结果
     */
    public static BigDecimal scale(Long value, int scale) {
        if (null == value) {
            return BigDecimal.ZERO;
        }
        BigDecimal bigDecimal = BigDecimal.valueOf(value);
        return bigDecimal.setScale(scale < 0 ? 2 : scale, RoundingMode.HALF_UP);
    }
    /**
     *             
     *
     * @param size 大小
     * @param realSize real大小
     * @param thread thread
     * @return the 结果
     */
    public static List<Map.Entry<Long, Long>> split(long size, long realSize, int thread) {
        List<Map.Entry<Long, Long>> result = new LinkedList<>();
        int cols = (int) (realSize / thread);
        if (cols == 0) {
            return Collections.emptyList();
        }
        int less = (int) (realSize % thread);
        IntStream.range(0, thread).forEach(it -> {
            Map<Long, Long> item = new HashMap<>(1);
            int less1 = less < thread ? 1 : 0;
            if (result.isEmpty()) {
                item.put(size + (long) it * cols, Math.min(size + (long) (it + 1) * cols + less1, realSize));
            } else if (it == thread - 1) {
                item.put(result.get(result.size() - 1).getValue() + 1, Math.min(size + (long) (it + 1) * cols + less1 + less, realSize));
            } else {
                item.put(result.get(result.size() - 1).getValue() + 1, Math.min(size + (long) (it + 1) * cols + less1, realSize));
            }
            result.addAll(item.entrySet());
        });
        return result;
    }
    /**
     *                <br>
     *              {@link Math#sqrt(double)}
     *
     * @param x x
     * @return the 结果
     */
    public static long sqrt(long x) {
        long y = 0;
        long b = (~Long.MAX_VALUE) >>> 1;
        while (b > 0) {
            if (x >= y + b) {
                x -= y + b;
                y >>= 1;
                y += b;
            } else {
                y >>= 1;
            }
            b >>= 2;
        }
        return y;
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double sub(float v1, float v2) {
        return sub(Float.toString(v1), Float.toString(v2)).doubleValue();
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double sub(float v1, double v2) {
        return sub(Float.toString(v1), Double.toString(v2)).doubleValue();
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double sub(double v1, float v2) {
        return sub(Double.toString(v1), Float.toString(v2)).doubleValue();
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double sub(double v1, double v2) {
        return sub(Double.toString(v1), Double.toString(v2)).doubleValue();
    }
    /**
     *                            
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static double sub(Double v1, Double v2) {
        return sub(v1, (Number) v2).doubleValue();
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param v1 v1
     * @param v2 v2
     * @return the 结果
     */
    public static BigDecimal sub(Number v1, Number v2) {
        return sub(new Number[]{v1, v2});
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param values 值
     * @return the 结果
     * @since 4.0.0
     */
    public static BigDecimal sub(Number... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        Number value = values[0];
        BigDecimal result = null == value ? BigDecimal.ZERO : new BigDecimal(value.toString());
        for (int i = 1; i < values.length; i++) {
            value = values[i];
            if (null != value) {
                result = result.subtract(new BigDecimal(value.toString()));
            }
        }
        return result;
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param values 值
     * @return the 结果
     * @since 4.0.0
     */
    public static BigDecimal sub(String... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        String value = values[0];
        BigDecimal result = null == value ? BigDecimal.ZERO : new BigDecimal(value);
        for (int i = 1; i < values.length; i++) {
            value = values[i];
            if (null != value) {
                result = result.subtract(new BigDecimal(value));
            }
        }
        return result;
    }
    /**
     *                            <br>
     * 空                     0
     *
     * @param values 值
     * @return the 结果
     * @since 4.0.0
     */
    public static BigDecimal sub(BigDecimal... values) {
        if (ArrayUtils.isEmpty(values)) {
            return BigDecimal.ZERO;
        }
        BigDecimal value = values[0];
        BigDecimal result = null == value ? BigDecimal.ZERO : value;
        for (int i = 1; i < values.length; i++) {
            value = values[i];
            if (null != value) {
                result = result.subtract(value);
            }
        }
        return result;
    }
    /**
     *                                     
     *
     * @param minuend minuend
     * @param reduction 减少
     * @param scale                         (                        )
     * @return the 结果
     */
    public static double subtract(double minuend, double reduction, int scale) {
        BigDecimal minuendBd = BigDecimal.valueOf(minuend);
        BigDecimal reductionBd = BigDecimal.valueOf(reduction);
        MathContext mathContext = new MathContext(scale, RoundingMode.HALF_UP);
        return minuendBd.subtract(reductionBd, mathContext).doubleValue();
    }
    /**
     *          
     *
     * @param minuend minuend
     * @param reduction 减少
     * @return the 结果
     */
    public static double subtract(double minuend, double reduction) {
        BigDecimal minuendBd = BigDecimal.valueOf(minuend);
        BigDecimal reductionBd = BigDecimal.valueOf(reduction);
        return minuendBd.subtract(reductionBd).doubleValue();
    }
    /**
     *          {@link BigDecimal}
     *
     * @param number 数字
     * @return {@link bigdecimal}
     * @since 4.0.9
     */
    public static BigDecimal toBigDecimal(Number number) {
        if (null == number) {
            return BigDecimal.ZERO;
        }
        if (number instanceof BigDecimal) {
            return (BigDecimal) number;
        } else if (number instanceof Long) {
            return new BigDecimal((Long) number);
        } else if (number instanceof Integer) {
            return new BigDecimal((Integer) number);
        } else if (number instanceof BigInteger) {
            return new BigDecimal((BigInteger) number);
        }
        return toBigDecimal(number.toString());
    }
    /**
     *          {@link BigDecimal}
     *
     * @param number 数字
     * @return {@link bigdecimal}
     * @since 4.0.9
     */
    public static BigDecimal toBigDecimal(String number) {
        if(isThousandSeparator(number)) {
            number = number.replaceAll(",", "");
        }
        return (null == number) ? BigDecimal.ZERO : new BigDecimal(number);
    }
    /**
     *          {@link BigInteger}
     *
     * @param number 数字
     * @return {@link biginteger}
     * @since 5.4.5
     */
    public static BigInteger toBigInteger(Number number) {
        if (null == number) {
            return BigInteger.ZERO;
        }
        if (number instanceof BigInteger) {
            return (BigInteger) number;
        } else if (number instanceof Long) {
            return BigInteger.valueOf((Long) number);
        }
        return toBigInteger(number.longValue());
    }
    /**
     *          {@link BigInteger}
     *
     * @param number 数字
     * @return {@link biginteger}
     * @since 5.4.5
     */
    public static BigInteger toBigInteger(String number) {
        return (null == number) ? BigInteger.ZERO : new BigInteger(number);
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>byte</code>，转换失败时返回
     * <code>zero</code>（即 0）。</p>
     *
     * <p>若字符串为 <code>null</code>，则返回 <code>zero</code>（即 0）。</p>
     *
     * <pre>
     *   NumberHelper.toByte(null) = 0
     *   NumberHelper.toByte("")   = 0
     *   NumberHelper.toByte("1")  = 1
     * </pre>
     *
     * @param str str
     * @return the byte represented by the 字符串, 或 <code>zero</code> if
     * 转换 失败
     * @since 2.5
     */
    public static byte toByte(final String str) {
        return toByte(str, (byte) 0);
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>byte</code>，转换失败时返回默认值。</p>
     *
     * <p>若字符串为 <code>null</code>，则返回默认值。</p>
     *
     * <pre>
     *   NumberHelper.toByte(null, 1) = 1
     *   NumberHelper.toByte("", 1)   = 1
     *   NumberHelper.toByte("1", 0)  = 1
     * </pre>
     *
     * @param str str
     * @param defaultValue 默认值
     * @return the byte represented by the 字符串, 或 the 默认 if 转换 失败
     * @since 2.5
     */
    public static byte toByte(final String str, final byte defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        try {
            return Byte.parseByte(str);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>double</code>，转换失败时返回
     * <code>0.0d</code>。</p>
     *
     * <p>若字符串 <code>str</code> 为 <code>null</code>，
     * 则返回 <code>0.0d</code>。</p>
     *
     * <pre>
     *   NumberHelper.toDouble(null)   = 0.0d
     *   NumberHelper.toDouble("")     = 0.0d
     *   NumberHelper.toDouble("1.5")  = 1.5d
     * </pre>
     *
     * @param str the 字符串 转为 转换, may be <code>空</code>
     * @return the double represented by the 字符串, 或 <code>0.0d</code>
     * if 转换 失败
     * @since 2.1
     */
    public static double toDouble(final String str) {
        return toDouble(str, 0.0d);
    }
    /**
     * 数字            double<br>
     * float                                                            
     *
     * @param value             float   
     * @return double   
     * @since 5.7.8
     */
    public static double toDouble(Number value) {
        if (value instanceof Float) {
            return Double.parseDouble(value.toString());
        } else {
            return value.doubleValue();
        }
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>double</code>，转换失败时返回
     * 默认值。</p>
     *
     * <p>若字符串 <code>str</code> 为 <code>null</code>，则返回
     * 默认值。</p>
     *
     * <pre>
     *   NumberHelper.toDouble(null, 1.1d)   = 1.1d
     *   NumberHelper.toDouble("", 1.1d)     = 1.1d
     *   NumberHelper.toDouble("1.5", 0.0d)  = 1.5d
     * </pre>
     *
     * @param str          the 字符串 转为 转换, may be <code>空</code>
     * @param defaultValue the 默认 值
     * @return the double represented by the 字符串, 或 默认值
     * if 转换 失败
     * @since 2.1
     */
    public static double toDouble(final String str, final double defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(str);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * <p>将 <code>BigDecimal</code> 转换为 <code>double</code>。</p>
     *
     * <p>若 <code>BigDecimal</code> 参数 <code>value</code> 为
     * <code>null</code>，则返回指定的默认值。</p>
     *
     * <pre>
     *   NumberHelper.toDouble(null)                     = 0.0d
     *   NumberHelper.toDouble(BigDecimal.valueOf(8.5d)) = 8.5d
     * </pre>
     *
     * @param value the <code>bigdecimal</code> 转为 转换, may be <code>空</code>.
     * @return <code>bigdecimal</code> 所表示的 double 值，若
     * <code>BigDecimal</code> 为 <code>null</code> 则返回 <code>0.0d</code>。
     * @since 3.8
     */
    public static double toDouble(final BigDecimal value) {
        return toDouble(value, 0.0d);
    }
    /**
     * <pre>
     *   NumberHelper.toDouble(null, 1.1d)                     = 1.1d
     *   NumberHelper.toDouble(BigDecimal.valueOf(8.5d), 1.1d) = 8.5d
     * </pre>
     *
     * @param value        the <code>bigdecimal</code> 转为 转换, may be <code>空</code>.
     * @param defaultValue the 默认 值
     * @return the double represented by the <code>bigdecimal</code> 或 the
     * 默认值 if the <code>bigdecimal</code> 是否 <code>空</code>.
     * @since 3.8
     */
    public static double toDouble(final BigDecimal value, final double defaultValue) {
        return value == null ? defaultValue : value.doubleValue();
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>double</code>，转换失败时返回
     * <code>0.0d</code>。</p>
     *
     * <p>若字符串 <code>str</code> 为 <code>null</code>，
     * 则返回 <code>0.0d</code>。</p>
     *
     * <pre>
     *   NumberHelper.toDoubleValue(null)   = 0.0d
     *   NumberHelper.toDoubleValue("")     = 0.0d
     *   NumberHelper.toDoubleValue("1.5")  = 1.5d
     * </pre>
     *
     * @param str the 字符串 转为 转换, may be <code>空</code>
     * @return the double represented by the 字符串, 或 <code>0.0d</code>
     * if 转换 失败
     * @since 2.1
     */
    public static double toDoubleValue(final String str) {
        return toDoubleValue(str, 0.0d);
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>double</code>，转换失败时返回
     * 默认值。</p>
     *
     * <p>若字符串 <code>str</code> 为 <code>null</code>，则返回
     * 默认值。</p>
     *
     * <pre>
     *   NumberHelper.toDoubleValue(null, 1.1d)   = 1.1d
     *   NumberHelper.toDoubleValue("", 1.1d)     = 1.1d
     *   NumberHelper.toDoubleValue("1.5", 0.0d)  = 1.5d
     * </pre>
     *
     * @param str          the 字符串 转为 转换, may be <code>空</code>
     * @param defaultValue the 默认 值
     * @return the double represented by the 字符串, 或 默认值
     * if 转换 失败
     * @since 2.1
     */
    public static double toDoubleValue(final String str, final double defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(str);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>float</code>，转换失败时返回
     * <code>0.0f</code>。</p>
     *
     * <p>若字符串 <code>str</code> 为 <code>null</code>，
     * 则返回 <code>0.0f</code>。</p>
     *
     * <pre>
     *   NumberHelper.toFloat(null)   = 0.0f
     *   NumberHelper.toFloat("")     = 0.0f
     *   NumberHelper.toFloat("1.5")  = 1.5f
     * </pre>
     *
     * @param str the 字符串 转为 转换, may be <code>空</code>
     * @return the float represented by the 字符串, 或 <code>0.0f</code>
     * if 转换 失败
     * @since 2.1
     */
    public static Float toFloat(final String str) {
        return toFloat(str, null);
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>float</code>，转换失败时返回
     * 默认值。</p>
     *
     * <p>若字符串 <code>str</code> 为 <code>null</code>，则返回
     * 默认值。</p>
     *
     * <pre>
     *   NumberHelper.toFloat(null, 1.1f)   = 1.0f
     *   NumberHelper.toFloat("", 1.1f)     = 1.1f
     *   NumberHelper.toFloat("1.5", 0.0f)  = 1.5f
     * </pre>
     *
     * @param str          the 字符串 转为 转换, may be <code>空</code>
     * @param defaultValue the 默认 值
     * @return the float represented by the 字符串, 或 默认值
     * if 转换 失败
     * @since 2.1
     */
    public static Float toFloat(final String str, final Float defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(str);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>float</code>，转换失败时返回
     * <code>0.0f</code>。</p>
     *
     * <p>若字符串 <code>str</code> 为 <code>null</code>，
     * 则返回 <code>0.0f</code>。</p>
     *
     * <pre>
     *   NumberHelper.toFloatValue(null)   = 0.0f
     *   NumberHelper.toFloatValue("")     = 0.0f
     *   NumberHelper.toFloatValue("1.5")  = 1.5f
     * </pre>
     *
     * @param str the 字符串 转为 转换, may be <code>空</code>
     * @return the float represented by the 字符串, 或 <code>0.0f</code>
     * if 转换 失败
     * @since 2.1
     */
    public static float toFloatValue(final String str) {
        return toFloatValue(str, 0.0f);
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>float</code>，转换失败时返回
     * 默认值。</p>
     *
     * <p>若字符串 <code>str</code> 为 <code>null</code>，则返回
     * 默认值。</p>
     *
     * <pre>
     *   NumberHelper.toFloatValue(null, 1.1f)   = 1.1f
     *   NumberHelper.toFloatValue("", 1.1f)     = 1.1f
     *   NumberHelper.toFloatValue("1.5", 0.0f)  = 1.5f
     * </pre>
     *
     * @param str          the 字符串 转为 转换, may be <code>空</code>
     * @param defaultValue the 默认 值
     * @return the float represented by the 字符串, 或 默认值
     * if 转换 失败
     * @since 2.1
     */
    public static float toFloatValue(final String str, final float defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        try {
            return Float.parseFloat(str);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * <pre>
     *   NumberHelper.toInt(null) = 0
     *   NumberHelper.toInt("")   = 0
     *   NumberHelper.toInt("1")  = 1
     * </pre>
     *
     * @param str str
     * @return int
     */
    public static int toInt(final String str) {
        return toInt(str, 0);
    }
    /**
     * <pre>
     *   NumberHelper.toInt(null) = 0
     *   NumberHelper.toInt(1L)  = 1
     * </pre>
     *
     * @param longValue long值
     * @return int       
     */
    public static Integer toInt(final Long longValue) {
        return null == longValue ? null : longValue.intValue();
    }
    /**
     * <pre>
     *   NumberHelper.toInt(null, 1) = 1
     *   NumberHelper.toInt("", 1)   = 1
     *   NumberHelper.toInt("1", 0)  = 1
     * </pre>
     *
     * @param source 源
     * @param defaultValue 默认值
     * @return 转为int的结果
     */
    public static int toInt(final String source, final int defaultValue) {
        if (source == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(source);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * <pre>
     *   NumberHelper.toIntValue(null) = 0
     *   NumberHelper.toIntValue(1L)  = 1
     * </pre>
     *
     * @param longValue long值
     * @return int       
     */
    public static int toIntValue(final Long longValue) {
        return null == longValue ? 0 : longValue.intValue();
    }
    /**
     * <pre>
     *   NumberHelper.toInt(null) = 0
     *   NumberHelper.toInt("")   = 0
     *   NumberHelper.toInt("1")  = 1
     * </pre>
     *
     * @param str str
     * @return int
     */
    public static int toInteger(final String str) {
        return toInteger(str, null);
    }
    /**
     * <pre>
     *   NumberHelper.Integer(null, 1) = 1
     *   NumberHelper.Integer("", 1)   = 1
     *   NumberHelper.Integer("1", 0)  = 1
     * </pre>
     *
     * @param source 源
     * @param defaultValue 默认值
     * @return 转为integer的结果
     */
    public static Integer toInteger(final String source, final Integer defaultValue) {
        if (source == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(source);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * <pre>
     *   NumberHelper.toLong(null) = null
     *   NumberHelper.toLong("")   = null
     *   NumberHelper.toLong("1")  = 1L
     * </pre>
     *
     * @param str str
     * @since 2.1
     * @return 转为long的结果
     */
    public static Long toLong(final String str) {
        return toLong(str, 0L);
    }
    /**
     * <pre>
     *   NumberHelper.toLong(null, 1L) = 1L
     *   NumberHelper.toLong("", 1L)   = 1L
     *   NumberHelper.toLong("1", 0L)  = 1L
     * </pre>
     *
     * @param str str
     * @param defaultValue 默认值
     * @return the long represented by the 字符串, 或 the 默认 if 转换 失败
     * @since 2.1
     */
    public static Long toLong(final String str, final Long defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(str);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * <pre>
     *   NumberHelper.toLongValue(null) = 0L
     *   NumberHelper.toLongValue("")   = 0L
     *   NumberHelper.toLongValue("1")  = 1L
     * </pre>
     *
     * @param str str
     * @since 2.1
     * @return 转为long值的结果
     */
    public static long toLongValue(final String str) {
        return toLongValue(str, 0L);
    }
    /**
     * <pre>
     *   NumberHelper.toLongValue(null, 1L) = 1L
     *   NumberHelper.toLongValue("", 1L)   = 1L
     *   NumberHelper.toLongValue("1", 0L)  = 1L
     * </pre>
     *
     * @param str str
     * @param defaultValue 默认值
     * @return the long represented by the 字符串, 或 the 默认 if 转换 失败
     * @since 2.1
     */
    public static long toLongValue(final String str, final long defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(str);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     * @param value 值
     * @since 3.8
     * @return 转为scaledbigdecimal的结果
     */
    public static BigDecimal toScaledBigDecimal(final BigDecimal value) {
        return toScaledBigDecimal(value, 2, RoundingMode.HALF_EVEN);
    }
    /**
     * @param value 值
     * @param scale scale
     * @param roundingMode roundingmode
     * @since 3.8
     * @return 转为scaledbigdecimal的结果
     */
    public static BigDecimal toScaledBigDecimal(final BigDecimal value, final int scale, final RoundingMode roundingMode) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value.setScale(scale, (roundingMode == null) ? RoundingMode.HALF_EVEN : roundingMode);
    }
    /**
     * 转为scaledbigdecimal
     *
     * @param value 值
     * @return BigDecimal
     * @since 3.8
     */
    public static BigDecimal toScaledBigDecimal(final Float value) {
        return toScaledBigDecimal(value, 2, RoundingMode.HALF_EVEN);
    }
    /**
     * 转为scaledbigdecimal
     *
     * @param value 值
     * @param scale scale
     * @param roundingMode roundingmode
     * @return BigDecimal
     * @since 3.8
     */
    public static BigDecimal toScaledBigDecimal(final Float value, final int scale, final RoundingMode roundingMode) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return toScaledBigDecimal(
                BigDecimal.valueOf(value),
                scale,
                roundingMode
        );
    }
    /**
     * 转为scaledbigdecimal
     *
     * @param value 值
     * @return BigDecimal
     * @since 3.8
     */
    public static BigDecimal toScaledBigDecimal(final Double value) {
        return toScaledBigDecimal(value, 2, RoundingMode.HALF_EVEN);
    }
    /**
     * 转为scaledbigdecimal
     *
     * @param value 值
     * @param scale scale
     * @param roundingMode roundingmode
     * @return BigDecimal
     * @since 3.8
     */
    public static BigDecimal toScaledBigDecimal(final Double value, final int scale, final RoundingMode roundingMode) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return toScaledBigDecimal(
                BigDecimal.valueOf(value),
                scale,
                roundingMode
        );
    }
    /**
     * <p>将 <code>String</code> 转换为 <code>short</code>，转换失败时返回
     * <code>zero</code>（即 0）。</p>
     *
     * <p>若字符串为 <code>null</code>，则返回 <code>zero</code>（即 0）。</p>
     *
     * <pre>
     *   NumberHelper.toShort(null) = 0
     *   NumberHelper.toShort("")   = 0
     *   NumberHelper.toShort("1")  = 1
     * </pre>
     *
     * @param str str
     * @return the short represented by the 字符串, 或 <code>zero</code> if
     * 转换 失败
     * @since 2.5
     */
    public static short toShort(final String str) {
        return toShort(str, (short) 0);
    }
    /**
     * <p>Convert a <code>String</code> to an <code>short</code>, returning a
     * 默认 值 if the 转换 失败.</p>
     *
     * <p>若字符串为 <code>null</code>，则返回默认值。</p>
     *
     * <pre>
     *   NumberHelper.toShort(null, 1) = 1
     *   NumberHelper.toShort("", 1)   = 1
     *   NumberHelper.toShort("1", 0)  = 1
     * </pre>
     *
     * @param str str
     * @param defaultValue 默认值
     * @return the short represented by the 字符串, 或 the 默认 if 转换 失败
     * @since 2.5
     */
    public static short toShort(final String str, final short defaultValue) {
        if (str == null) {
            return defaultValue;
        }
        try {
            return Short.parseShort(str);
        } catch (final NumberFormatException nfe) {
            return defaultValue;
        }
    }
    /**
     *       bigint
     *
     * @param value 值
     * @return BigInteger
     */
    private static BigInteger decodeBigInteger(String value) {
        int radix = 10;
        int index = 0;
        boolean negative = false;
        if (value.startsWith(SYMBOL_MINS)) {
            negative = true;
            index++;
        }
        if (value.startsWith(Hex.PREFIX, index) || value.startsWith(Hex.PREFIX_UPPER, index)) {
            index += 2;
            radix = 16;
        } else if (value.startsWith(SYMBOL_HASH, index)) {
            index++;
            radix = 16;
        } else if (value.startsWith(SYMBOL_NUMBERS_ZERO_STRING, index) && value.length() > 1 + index) {
            index++;
            radix = 8;
        }
        BigInteger result = new BigInteger(value.substring(index), radix);
        return (negative ? result.negate() : result);
    }
    /**
     *          Hex         
     *
     * @param value 值
     * @return          Hex         
     */
    private static boolean isHexNumber(final String value) {
        int index = (value.startsWith("-") ? 1 : 0);
        return (value.startsWith(Hex.PREFIX, index) || value.startsWith(Hex.PREFIX_UPPER, index) || value.startsWith(SYMBOL_HASH, index));
    }
    /**
     *                         
     *
     * @param x x
     * @param y y
     * @return x==y      0   x&lt;y            0         x&gt;y            0      
     * @see Character#compare(char, char)
     * @since 3.0.1
     */
    public static int compare(char x, char y) {
        return Character.compare(x, y);
    }
    /**
     *                         
     *
     * @param x x
     * @param y y
     * @return x==y      0   x&lt;y            0         x&gt;y            0      
     * @see Double#compare(double, double)
     * @since 3.0.1
     */
    public static int compare(double x, double y) {
        return Double.compare(x, y);
    }
    /**
     *                         
     *
     * @param x x
     * @param y y
     * @return x==y      0   x&lt;y            0         x&gt;y            0      
     * @see Integer#compare(int, int)
     * @since 3.0.1
     */
    public static int compare(int x, int y) {
        return Integer.compare(x, y);
    }
    /**
     *                         
     *
     * @param x x
     * @param y y
     * @return x==y      0   x&lt;y            0         x&gt;y            0      
     * @see Long#compare(long, long)
     * @since 3.0.1
     */
    public static int compare(long x, long y) {
        return Long.compare(x, y);
    }
    /**
     *                         
     *
     * @param x x
     * @param y y
     * @return x==y      0   x&lt;y            0         x&gt;y            0      
     * @see Short#compare(short, short)
     * @since 3.0.1
     */
    public static int compare(short x, short y) {
        return Short.compare(x, y);
    }
    /**
     *                         
     *
     * @param x x
     * @param y y
     * @return x==y      0   x&lt;y      -1   x&gt;y      1
     * @see Byte#compare(byte, byte)
     * @since 3.0.1
     */
    public static int compare(byte x, byte y) {
        return Byte.compare(x, y);
    }
    /**
     *                      1 &gt;       2       true
     *
     * @param bigNum1       1
     * @param bigNum2       2
     * @return the 结果
     * @since 3.0.9
     */
    public static boolean isGreater(BigDecimal bigNum1, BigDecimal bigNum2) {
        return bigNum1.compareTo(bigNum2) > 0;
    }
    /**
     *                      1 &gt;=       2       true
     *
     * @param bigNum1       1
     * @param bigNum2       2
     * @return the 结果
     * @since 3, 0.9
     */
    public static boolean isGreaterOrEqual(BigDecimal bigNum1, BigDecimal bigNum2) {
        return bigNum1.compareTo(bigNum2) >= 0;
    }
    /**
     *                      1 &lt;       2       true
     *
     * @param bigNum1       1
     * @param bigNum2       2
     * @return the 结果
     * @since 3, 0.9
     */
    public static boolean isLess(BigDecimal bigNum1, BigDecimal bigNum2) {
        return bigNum1.compareTo(bigNum2) < 0;
    }
    /**
     *                      1&lt;=      2       true
     *
     * @param bigNum1       1
     * @param bigNum2       2
     * @return the 结果
     * @since 3, 0.9
     */
    public static boolean isLessOrEqual(BigDecimal bigNum1, BigDecimal bigNum2) {
        return bigNum1.compareTo(bigNum2) <= 0;
    }
    /**
     *                                true<br>
     *                      {@link Double#doubleToLongBits(double)}                           <br>
     *                                                 0.00 == 0
     *
     * @param num1       1
     * @param num2       2
     * @return the 结果
     * @since 5.4.2
     */
    public static boolean equals(double num1, double num2) {
        return Double.doubleToLongBits(num1) == Double.doubleToLongBits(num2);
    }
    /**
     *                                true<br>
     *                      {@link Float#floatToIntBits(float)}                           <br>
     *                                                 0.00 == 0
     *
     * @param num1       1
     * @param num2       2
     * @return the 结果
     * @since 5.4.5
     */
    public static boolean equals(float num1, float num2) {
        return Float.floatToIntBits(num1) == Float.floatToIntBits(num2);
    }
    /**
     *                                true<br>
     *                      {@link Float#floatToIntBits(float)}                           <br>
     *                                                 0.00 == 0
     *
     * @param num1       1
     * @param num2       2
     * @return the 结果
     * @since 5.4.5
     */
    public static boolean equals(int num1, int num2) {
        return num1 == num2;
    }
    /**
     *                                true<br>
     *                      {@link BigDecimal#compareTo(BigDecimal)}                           <br>
     *                                                 0.00 == 0
     *
     * @param bigNum1       1
     * @param bigNum2       2
     * @return the 结果
     */
    public static boolean equals(BigDecimal bigNum1, BigDecimal bigNum2) {
        if (bigNum1.equals(bigNum2)) {
            return true;
        }
        if (bigNum1 == null || bigNum2 == null) {
            return false;
        }
        return 0 == bigNum1.compareTo(bigNum2);
    }
    /**
     * zero
     *
     * @param value 值
     * @param defaultValue 默认值
     * @return the 结果
     */
    public static int isValid(Integer value, int defaultValue) {
        return null == value || value == 0 ? defaultValue : value;
    }
    /**
     * zero
     *
     * @param value 值
     * @param defaultValue 默认值
     * @return the 结果
     */
    public static int isZero(int value, int defaultValue) {
        return value == 0 ? defaultValue : value;
    }
    /**
     * zero
     *
     * @param value 值
     * @param defaultValue 默认值
     * @return the 结果
     */
    public static double isZero(double value, double defaultValue) {
        return Double.compare(0.0d, value) == 0 ? defaultValue : value;
    }
    /**
     * excel         
     *
     * @param value 值
     * @return the 结果
     */
    public static String toExcelCell(int value) {
        value = value - 65;
        int size = value / 26;
        int less = value % 26;
        return StringUtils.repeat("A", size) + (char) (less + 65);
    }
    /**
     *                             --> 12354
     *
     * @param chinese chinese
     * @return the 结果
     */
    public static String getNumberFromChinese(String chinese) {
        String result = "0";
        List<String> lists = new ArrayList<>();
        int lastLevelIndex = 0;
        for (int i = NumberValue.HIGH_LEVEL.size() - 1; i >= 0; i--) {
            int levelIndex = chinese.indexOf(NumberValue.HIGH_LEVEL.get(i));
            if (levelIndex > 0) {
                lists.add(chinese.substring(0, levelIndex));
                chinese = chinese.substring(levelIndex + 1);
            } else if (levelIndex == -1) {
                lists.add(NumberValue.NUMBER.getFirst());
            } else if (levelIndex == 0) {
                while (levelIndex > 1) {
                    levelIndex--;
                    lists.add(NumberValue.NUMBER.getFirst());
                }
                lists.add(chinese);
            }
        }
        for (int i = 0; i < lists.size(); i++) {
            Integer highLevelIndex = lists.size() - i - 1;
            String single = lists.get(i);
            if (single.equalsIgnoreCase(chinese)) {
                throw new NumberFormatException("");
            }
            String nextResult = getNumberFromChinese(single);
            if (INDEX_NOT_FOUND_STRING.equals(nextResult)) {
                throw new NumberFormatException();
            }
            long next = (long) Integer.parseInt(result) * (int) (Math.pow(10, 4)) + Integer.parseInt(nextResult);
            result = Long.toString(next);
        }
        result = result.replaceFirst("^(0+)", "");
        return result;
    }
    /**
     * 判断字符串是否为中文数字。
     * <p>仅包含中文字符（一壹二贰…九玖零）和单位（十拾百佰千仟万亿）时返回 true。</p>
     *
     * @param str 待检字符串
     * @return true 表示为中文数字
     */
    public static boolean isChineseNumber(String str) {
        if (StringUtils.isEmpty(str)) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!CN_DIGITS.containsKey(c) && !CN_UNITS.containsKey(c)) {
                return false;
            }
        }
        return true;
    }
    /**
     * 将中文数字字符串解析为阿拉伯数字字符串。
     * <p>支持以下格式：</p>
     * <ul>
     *   <li>个位数 — 零、一、二、…、九</li>
     *   <li>简单组合 — 十二、一百二十三、三千零五十</li>
     *   <li>大数量级 — 十二万、三亿五千万、两万三千零七</li>
     *   <li>大小写混用 — 壹佰贰拾叁、五万陆仟</li>
     *   <li>「两」替代「二」— 两百、两万</li>
     * </ul>
     *
     * @param chinese 中文数字字符串
     * @return 对应的阿拉伯数字字符串
     * @throws NumberFormatException 如果输入不是有效的中文数字
     */
    public static String parseChineseNumber(String chinese) {
        if (StringUtils.isEmpty(chinese)) {
            throw new NumberFormatException("Empty Chinese number string");
        }
        // 单独处理 "零" → "0"
        if (chinese.length() == 1 && CN_DIGITS.getOrDefault(chinese.charAt(0), -1) == 0) {
            return "0";
        }
        // 最终结果
        long result = 0;
        // 当前段内的累计值（万/亿以下）
        long current = 0;
        // 待处理的个位数字
        int pendingDigit = 0;
        // 是否有待处理的数字
        boolean hasPending = false;
        for (int i = 0; i < chinese.length(); i++) {
            char c = chinese.charAt(i);
            Integer digit = CN_DIGITS.get(c);
            if (digit != null) {
                pendingDigit = digit;
                hasPending = true;
                continue;
            }
            Integer unit = CN_UNITS.get(c);
            if (unit != null) {
                if (unit >= 10000) {
                    // 万 / 亿：将当前累计值乘以段单位
                    long value = current;
                    if (hasPending) {
                        value += pendingDigit;
                    }
                    if (value == 0) {
                        value = 1;
                    }
                    result += value * unit;
                    current = 0;
                    hasPending = false;
                    pendingDigit = 0;
                } else {
                    // 十 / 百 / 千：将前面的数字乘以单位
                    int multiplier = hasPending ? pendingDigit : 1;
                    current += (long) multiplier * unit;
                    hasPending = false;
                    pendingDigit = 0;
                }
                continue;
            }
            throw new NumberFormatException("Invalid Chinese number character: " + c);
        }
        // 加上末尾零头
        if (hasPending) {
            current += pendingDigit;
        }
        result += current;
        return Long.toString(result);
    }
    /**
     * 阿拉伯数字转换为中文数字
     * 12354 --> 一万二千三百五十四
     *
     * @param alabo alabo
     * @return the 结果
     */
    public static String getNumberFromAlamo(String alabo) {
        StringBuilder result = new StringBuilder();
        List<String> list = new ArrayList<>();
        for (int length = alabo.length() - 1; length >= 0; length--) {
            list.add(String.valueOf(alabo.charAt(length)));
        }
        List<List<String>> lists = CollectionUtils.averageAssign(list, 4);
        Collections.reverse(lists);
        if (CollectionUtils.isNotEmpty(lists)) {
            for (int index = 0; index < lists.size(); index++) {
                List<String> singleNumList = lists.get(index);
                Collections.reverse(singleNumList);
                Boolean zeroflag = false;
                StringBuilder chinese = new StringBuilder();
                for (int j = 0; j < singleNumList.size(); j++) {
                    Integer number = Integer.valueOf(singleNumList.get(j));
                    if (number == 0 && !zeroflag && afterNotAllZero(singleNumList, j)) {
                        chinese.append(NumberValue.NUMBER.get(number));
                        zeroflag = true;
                    } else if (number != 0) {
                        chinese.append(NumberValue.NUMBER.get(number)).append(NumberValue.LEVEL.get(singleNumList.size() - j - 1));
                    }
                }
                if (index == lists.size() && chinese.substring(0, 1).equals(NumberValue.NUMBER.getFirst())) {
                    chinese = new StringBuilder(chinese.substring(1));
                }
                if (chinese.length() > 0 && !NumberValue.HIGH_LEVEL.contains(chinese.substring(chinese.length() - 1))) {
                    result.append(chinese).append(NumberValue.HIGH_LEVEL.get(lists.size() - 1 - index));
                }
            }
        }
        return result.toString();
    }
    /**
     * 单个num列表   j                        0
     *
     * @param singleNumList 单个num列表
     * @param offset 偏移量
     * @return                0
     */
    private static boolean afterNotAllZero(List<String> singleNumList, int offset) {
        for (int i = offset + 1; i < singleNumList.size(); i++) {
            if (!"0".equals(singleNumList.get(i))) {
                return true;
            }
        }
        return false;
    }
    /**
     *       
     *
     * @param data 数据
     * @return the 结果
     */
    public static double getVariance(double[] data) {
        int m = data.length;
        double sum = 0;
        for (int i = 0; i < m; i++) {
            sum += data[i];
        }
        double dAve = sum / m;
        double dVar = 0;
        for (int i = 0; i < m; i++) {
            dVar += (data[i] - dAve) * (data[i] - dAve);
        }
        return dVar / m;
    }
    /**
     *          
     *
     * @return the 结果
     * @param data 数据
     */
    public static double getAverage(double[] data) {
        BigDecimal bigDecimal = BigDecimal.ZERO;
        for (double datum : data) {
            bigDecimal = bigDecimal.add(BigDecimal.valueOf(datum));
        }
        return bigDecimal.divide(BigDecimal.valueOf(data.length), 15, RoundingMode.DOWN).doubleValue();
    }
    /**
     * sigma
     *
     * @return sigma
     * @param data 数据
     */
    public static double getStandardDeviation(double[] data) {
        int m = data.length;
        double sum = 0;
        for (int i = 0; i < m; i++) {
            sum += data[i];
        }
        double dAve = sum / m;
        double dVar = 0;
        for (int i = 0; i < m; i++) {
            dVar += (data[i] - dAve) * (data[i] - dAve);
        }
        return Math.sqrt(dVar / m);
    }
    /**
     *       
     *
     * @param x            x   
     * @param mean mean
     * @param variance variance
     * @param stdDeviation stddeviation
     * @return the 结果
     */
    public static double getY(double x, double mean, double variance, double stdDeviation) {
        return Math.pow(Math.exp(-(((x - mean) * (x - mean)) / ((2 * variance)))), 1 / (stdDeviation * Math.sqrt(2 * Math.PI)));
    }
    /**
     *                
     *
     * @param value 值
     * @param defaultValue 默认值
     * @return int
     */
    public static int isPositive(Integer value, int defaultValue) {
        return null == value || value <= 0 ? defaultValue : value;
    }
    /**
     * 空            0
     *
     * @param value                            空
     * @param defaultValue                      空         0
     * @return                      null               0                                          
     */
    public static Number defaultIfNullOrPositive(Number value, Number defaultValue) {
 // 空         0
        return null == value || value.intValue() <= 0 ? defaultValue : value;
    }
    /**
     * 数字
     *
     * @param data                            空
     * @return          null         空                        数字                           空
     */
    public static <T> Number parseNumber(T data) {
        if (null == data) {
            return null;
        }
        if (data instanceof Number number) {
            return number;
        }
        return Converter.convertIfNecessary(data, Number.class);
    }
    /**
     * @param value 值
     * @return  boolean
     */
    public static boolean isDigits(String value) {
        return isNumber(value);
    }
}
