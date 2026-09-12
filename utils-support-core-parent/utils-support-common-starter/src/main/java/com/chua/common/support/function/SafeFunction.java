package com.chua.common.support.function;

import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;


/**
* 安全的函数式接口，扩展自 {@link Function}，提供异常安全的函数执行。
*
* <p>该接口包装了 {@link Function#apply} 方法，在执行过程中捕获所有异常，
* 避免异常向上传播。当发生异常时，返回 null 值。</p>
*
* <p>典型使用场景：</p>
* <ul>
*   <li>Stream 操作中需要忽略异常的场景</li>
*   <li>数据转换时可能失败但不希望中断流程</li>
*   <li>批量处理中个别元素转换失败的情况</li>
* </ul>
*
* <p>使用示例：</p>
* <pre>{@code
* List<String> numbers = Arrays.asList("1", "2", "abc", "4");
* List<Integer> result = numbers.stream()
*     .map(SafeFunction.of(str -> Integer.parseInt(str)))
*     .filter(Objects::nonNull)
*     .collect(Collectors.toList());
* // result: [1, 2, 4]
* }</pre>
*
* @param <T> 输入参数类型
* @param <R> 返回值类型
* @author CH
* @version 1.0.0
* @since 1.0
 */
public interface SafeFunction<T, R> extends Function<T, R> {

    /**
    * 将此函数应用于给定参数。
    *
    * <p>该方法会捕获执行过程中抛出的所有异常，包括受检异常和非受检异常。
    * 如果发生异常，会打印堆栈跟踪并返回 null。</p>
    *
    * @param t 函数参数
    * @return 函数结果，如果发生异常则返回 null
     */
    @Override
    default R apply(T t) {
        try {
            return safeApply(t);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return null;
        }
    }

    /**
    * 安全地将此函数应用于给定参数。
    *
    * <p>这是实际的函数实现方法，允许抛出任何类型的异常。
    * 这些异常将被 {@link #apply} 方法捕获并处理。</p>
    *
    * @param t 函数参数
    * @return 函数结果
    * @throws Throwable 可能抛出的任何异常
     */
    R safeApply(T t) throws Throwable;
}