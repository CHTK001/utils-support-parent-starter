package com.chua.common.support.lang.benchmark;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.common.support.spi.annotations.Spi;

import java.io.File;

/**
* 通用压测 SPI 接口：一键式压测并生成报告。
*
* <p>屏蔽底层压测引擎（k6 / wrk / ab 等）差异，统一暴露：</p>
* <ul>
*   <li>{@link #configure(BenchmarkConfig)} — 动态配置指标与场景（并发档位、迭代数、URL、payload、报告指标等）</li>
*   <li>{@link #run()} — 执行压测，返回结构化结果（兼容 {@code BenchmarkDocumentData}）</li>
*   <li>{@link #report(String)} — 生成 HTML 压测报告（ECharts 图表）</li>
* </ul>
*
* <h2>一键式用法</h2>
* <pre>{@code
* try (Benchmark benchmark = Benchmark.create("k6")) {
*     benchmark.configure(BenchmarkConfig.builder()
*             .targetUrl("http://127.0.0.1:8100/echo")
*             .concurrencyLevels(new int[]{100, 500, 1000, 2000, 5000})
*             .iterationsPerVus(1)
*             .implementation("nio")
*             .reportPath("target/http-server-benchmark.html")
*             .build());
*     BenchmarkResult result = benchmark.run();       // 执行压测
*     benchmark.report();                              // 生成 HTML 报告
* }
* }</pre>
*
* @author CH
* @since 2026/08/15
 */
@Spi
public interface Benchmark extends AutoCloseable {

    /**
    * 通过 SPI 创建压测引擎实例。
    *
    * @param type 引擎类型（"k6" / "wrk" / "ab" ...）
    * @return Benchmark 实例
    */
    static Benchmark create(String type) {
        return ServiceProvider.of(Benchmark.class).getExtension(type);
    }

    /**
    * 获取压测引擎名称。
    *
    * @return 引擎名称
    */
    String getType();

    /**
    * 动态配置压测场景与指标。
    *
    * @param config 压测配置（并发档位、迭代数、URL、payload、报告指标等）
    * @return 当前实例（支持链式调用）
    */
    Benchmark configure(BenchmarkConfig config);

    /**
    * 获取当前压测配置。
    *
    * @return 压测配置
    */
    BenchmarkConfig config();

    /**
    * 执行压测。
    *
    * @return 压测结果（结构化指标行，兼容 {@code BenchmarkDocumentData}）
    * @throws Exception 压测执行失败
    */
    BenchmarkResult run() throws Exception;

    /**
    * 生成压测报告（默认路径来自配置）。
    *
    * @return 报告文件
    * @throws Exception 报告生成失败
    */
    default File report() throws Exception {
        return report(config().getReportPath());
    }

    /**
    * 生成压测报告（HTML，ECharts 图表）。
    *
    * @param reportPath 报告输出路径
    * @return 报告文件
    * @throws Exception 报告生成失败
    */
    File report(String reportPath) throws Exception;

    /**
    * 释放压测引擎资源。
    */
    @Override
    void close();
}
