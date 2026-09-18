package com.chua.spider.support.config.store;

import com.chua.spider.support.config.model.SpiderExecutionRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
* 爬虫执行记录内存存储。
*
* @author CH
* @since 4.0.0.42
 */
public class SpiderExecutionStore {

    /**
    * 自增批次号生成器
    */
    private final AtomicLong seq = new AtomicLong(1);

    /**
    * 批次号 → 执行记录
    */
    private final Map<String, SpiderExecutionRecord> storage = new ConcurrentHashMap<>();

    /**
    * 生成新的批次号。
    *
    * <p>格式：{@code exec-{时间戳毫秒}-{序号}}</p>
    *
    * @return 新的批次号
    */
    public String nextExecutionNo() {
        long ts = System.currentTimeMillis();
        long n = seq.getAndIncrement();
        return "exec-" + ts + "-" + n;
    }

    /**
    * 保存执行记录。
    *
    * @param record 执行记录
    */
    public void save(SpiderExecutionRecord record) {
        storage.put(record.getExecutionNo(), record);
    }

    /**
    * 按批次号查询。
    *
    * @param executionNo 批次号
    * @return 执行记录，不存在返回 空
    */
    public SpiderExecutionRecord get(String executionNo) {
        return storage.get(executionNo);
    }

    /**
    * 按爬虫编码查询所有执行记录（按开始时间倒序）。
    *
    * @param spiderCode 爬虫编码
    * @return 执行记录列表
    */
    public List<SpiderExecutionRecord> listByCode(String spiderCode) {
        return storage.values().stream()
                .filter(r -> spiderCode == null || spiderCode.equals(r.getSpiderCode()))
                .sorted((a, b) -> {
                    if (a.getStartTime() == null || b.getStartTime() == null) {
                        return 0;
                    }
                    return b.getStartTime().compareTo(a.getStartTime());
                })
                .collect(Collectors.toList());
    }

    /**
    * 分页查询执行记录。
    *
    * @param pageNo   页码
    * @param pageSize 每页条数
    * @param spiderCode 可选爬虫编码过滤
    * @return 分页结果
    */
    public PageResult<SpiderExecutionRecord> page(int pageNo, int pageSize, String spiderCode) {
        List<SpiderExecutionRecord> all = listByCode(spiderCode);
        int total = all.size();
        int from = (pageNo - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<SpiderExecutionRecord> records = from >= total
                ? Collections.emptyList()
                : all.subList(from, to);
        return new PageResult<>(records, total, pageSize, pageNo, (total + pageSize - 1) / pageSize);
    }

    /**
    * 分页结果。
    */
    public record PageResult<T>(
            List<T> records,
            long total,
            int size,
            int current,
            int pages
    ) {
    }
}
