package com.chua.common.support.concurrent.collapse;

import com.chua.common.support.spi.ServiceProvider;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 折叠执行器门面，提供链式配置与受保护执行。
 *
 * <p>通过 SPI 加载 {@link CollapseExecutorFactory} 创建执行器；未引入实现模块
 * （utils-support-collapse-starter）时自动降级为直接调用，语义不变、无折叠收益。</p>
 *
 * <p>两种模式：</p>
 * <ul>
 *   <li><b>同参折叠（广播）</b>：{@link #of(String, CollapseBatchFunction)}，
 *       相同入参的并发调用合并执行一次并广播结果，适合幂等热点查询；</li>
 *   <li><b>合并拆分（回填）</b>：{@link #ofMapped(String, CollapseResultMapper)}，
 *       窗口内全部调用合并执行一次（实参并集），按调用者回填各自子结果，适合集合入参的批量查询。</li>
 * </ul>
 *
 * <pre>{@code
 * // 批量合并：N 个并发调用者各带小集合，合并一次执行后拆回自己的子 Map
 * CollapseFlow<List<Long>, Map<Long, User>> flow =
 *         CollapseFlow.ofMapped("user-batch", ids -> userMapper.selectByIds(unionAll(ids)));
 * flow.threshold(20).collectingWaitTime(2);
 * Map<Long, User> mine = flow.execute(myIds);
 *
 * // 同参折叠：并发相同入参只执行一次
 * CollapseFlow<String, User> detail = CollapseFlow.of("user-detail", keys -> userMapper.get(keys.iterator().next()));
 * User user = detail.execute("u-1001");
 * }</pre>
 *
 * @param <INPUT>  单次调用的入参类型
 * @param <OUTPUT> 单次调用的返回类型
 * @author CH
 * @since 2026/09/03
 */
public final class CollapseFlow<INPUT, OUTPUT> implements AutoCloseable {

    /**
     * 折叠执行器工厂的 SPI 名称
     */
    private static final String DEFAULT_FACTORY_NAME = "collapse";

    /**
     * 折叠配置（execute 前完成链式配置）
     */
    private final CollapseConfig config = new CollapseConfig();

    /**
     * 批量执行函数（同参折叠模式），与结果映射器二选一
     */
    private final CollapseBatchFunction<INPUT, OUTPUT> batchFunction;

    /**
     * 折叠结果映射器（合并拆分模式），与批量执行函数二选一
     */
    private final CollapseResultMapper<INPUT, OUTPUT> resultMapper;

    /**
     * 折叠执行器（懒加载）
     */
    private volatile CollapseExecutor<INPUT, OUTPUT> executor;

    /**
     * 是否已完成 SPI 探测（避免重复查询）
     */
    private volatile boolean checked;

    /**
     * 自定义执行器工厂回调（设置后优先于 SPI 探测）
     */
    private volatile CollapseExecutorFactory executorFactory;

    /**
     * 构造门面。
     *
     * @param name          执行器名称
     * @param batchFunction 批量执行函数
     * @param resultMapper  折叠结果映射器
     */
    private CollapseFlow(String name,
                         CollapseBatchFunction<INPUT, OUTPUT> batchFunction,
                         CollapseResultMapper<INPUT, OUTPUT> resultMapper) {
        this.config.setName(name);
        this.batchFunction = batchFunction;
        this.resultMapper = resultMapper;
    }

    /**
     * 创建同参折叠门面（相同入参合并执行一次并广播结果）。
     *
     * @param name          执行器名称
     * @param batchFunction 批量执行函数
     * @param <INPUT>       单次调用的入参类型
     * @param <OUTPUT>      单次调用的返回类型
     * @return 折叠门面实例
     */
    public static <INPUT, OUTPUT> CollapseFlow<INPUT, OUTPUT> of(String name,
                                                                 CollapseBatchFunction<INPUT, OUTPUT> batchFunction) {
        return new CollapseFlow<>(name, Objects.requireNonNull(batchFunction, "batchFunction must not be null."), null);
    }

    /**
     * 创建合并拆分门面（窗口内全部调用合并执行一次，按调用者回填子结果）。
     *
     * @param name         执行器名称
     * @param resultMapper 折叠结果映射器
     * @param <INPUT>      单次调用的入参类型
     * @param <OUTPUT>     单次调用的返回类型
     * @return 折叠门面实例
     */
    public static <INPUT, OUTPUT> CollapseFlow<INPUT, OUTPUT> ofMapped(String name,
                                                                       CollapseResultMapper<INPUT, OUTPUT> resultMapper) {
        return new CollapseFlow<>(name, null, Objects.requireNonNull(resultMapper, "resultMapper must not be null."));
    }

    /**
     * 设置批量收集的最小阈值。
     *
     * @param waitThreshold 批量收集阈值
     * @return this
     */
    public CollapseFlow<INPUT, OUTPUT> threshold(int waitThreshold) {
        config.setWaitThreshold(waitThreshold);
        return this;
    }

    /**
     * 设置未达到阈值时的补收等待时间（毫秒）。
     *
     * <p>小于 0：立即执行；等于 0（默认）：让出收集线程时间片后补收一次；大于 0：等待指定毫秒。</p>
     *
     * @param collectingWaitTime 补收等待时间（毫秒）
     * @return this
     */
    public CollapseFlow<INPUT, OUTPUT> collectingWaitTime(long collectingWaitTime) {
        config.setCollectingWaitTime(collectingWaitTime);
        return this;
    }

    /**
     * 设置是否启用虚拟线程（JDK 21+，默认启用）。
     *
     * @param virtualThread 是否启用虚拟线程
     * @return this
     */
    public CollapseFlow<INPUT, OUTPUT> virtualThread(boolean virtualThread) {
        config.setVirtualThread(virtualThread);
        return this;
    }

    /**
     * 设置自定义执行器工厂回调。
     *
     * <p>设置后优先使用回调创建折叠执行器——可注入纯 JDK 实现的折叠执行器工厂，
     * 无需依赖 utils-support-collapse-starter 模块与 SPI 发现；未设置时回退 SPI 探测，
     * 探测不到实现则降级为直接执行（语义不变、无折叠收益）。</p>
     *
     * <p>需在首次 {@link #execute(Object)} 之前设置；之后设置将抛出
     * {@link IllegalStateException}（执行器已解析，回调不再生效）。</p>
     *
     * @param executorFactory 执行器工厂回调，不可为空
     * @return this
     */
    public CollapseFlow<INPUT, OUTPUT> executorFactory(CollapseExecutorFactory executorFactory) {
        if (checked) {
            throw new IllegalStateException("executorFactory 必须在首次 execute() 之前设置。");
        }
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory must not be null.");
        return this;
    }

    /**
     * 执行一次折叠调用。
     *
     * @param input 单次调用的入参
     * @return 单次调用的返回结果
     * @throws Throwable 执行异常（原样透传）
     */
    public OUTPUT execute(INPUT input) throws Throwable {
        CollapseExecutor<INPUT, OUTPUT> collapseExecutor = getExecutor();
        if (collapseExecutor == null) {
            return directExecute(input);
        }
        return collapseExecutor.execute(input);
    }

    /**
     * 获取折叠执行器（SPI 加载，懒初始化）。
     *
     * @return 折叠执行器，无 SPI 实现时返回 null
     */
    private CollapseExecutor<INPUT, OUTPUT> getExecutor() {
        if (checked) {
            return executor;
        }
        synchronized (this) {
            if (!checked) {
                CollapseExecutorFactory factory = resolveFactory();
                if (factory != null) {
                    if (resultMapper != null) {
                        config.setMergeAll(true);
                        executor = factory.create(config, resultMapper);
                    } else {
                        executor = factory.create(config, batchFunction);
                    }
                }
                checked = true;
            }
        }
        return executor;
    }

    /**
     * 解析执行器工厂：优先使用回调注入的工厂，其次 SPI 探测。
     *
     * @return 执行器工厂，不可用时返回 null
     */
    private CollapseExecutorFactory resolveFactory() {
        CollapseExecutorFactory factory = executorFactory;
        if (factory != null) {
            return factory;
        }
        return ServiceProvider.of(CollapseExecutorFactory.class).getExtension(DEFAULT_FACTORY_NAME);
    }

    /**
     * 无 SPI 实现时的降级直调：按单调用者执行一次，语义与折叠一致。
     *
     * @param input 单次调用的入参
     * @return 执行结果
     * @throws Throwable 执行异常
     */
    private OUTPUT directExecute(INPUT input) throws Throwable {
        if (resultMapper != null) {
            Map<INPUT, OUTPUT> mapped = resultMapper.map(Collections.singletonList(input));
            return mapped == null ? null : mapped.get(input);
        }
        return batchFunction.executeBatch(Collections.singletonList(input));
    }

    @Override
    public void close() {
        CollapseExecutor<INPUT, OUTPUT> collapseExecutor = executor;
        if (collapseExecutor != null) {
            collapseExecutor.close();
        }
    }
}
