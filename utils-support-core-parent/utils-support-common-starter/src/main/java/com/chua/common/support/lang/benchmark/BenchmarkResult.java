package com.chua.common.support.lang.benchmark;

import com.chua.common.support.lang.document.BenchmarkDocumentData;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
* 压测结果：承载各并发档位的指标行，可转换为 {@link BenchmarkDocumentData} 用于报告生成。
*
* @author CH
* @since 2026/08/15
 */
@Data
public class BenchmarkResult {

    /**
    * 压测配置（回显用）。
    */
    private BenchmarkConfig config;

    /**
    * 结果行（实现 × 并发 × 指标）。
    */
    private List<BenchmarkDocumentData.BenchmarkRow> rows = new ArrayList<>();

    /**
    * 添加一行结果。
    *
    * @param row 结果行
    * @return 当前实例（链式）
    */
    public BenchmarkResult add(BenchmarkDocumentData.BenchmarkRow row) {
        rows.add(row);
        return this;
    }

    /**
    * 转换为报告文档数据。
    *
    * @return BenchmarkDocumentData
    */
    public BenchmarkDocumentData toDocumentData() {
        BenchmarkDocumentData data = new BenchmarkDocumentData();
        data.setTitle(config != null ? config.getTitle() : "HTTP 服务器压测报告");
        data.setEnvironment(config != null ? config.getEnvironment() : null);
        data.setTool(config != null ? config.getTool() : null);
        data.setScenario(config != null ? config.getScenario() : null);
        data.getRows().addAll(rows);
        return data;
    }
}
