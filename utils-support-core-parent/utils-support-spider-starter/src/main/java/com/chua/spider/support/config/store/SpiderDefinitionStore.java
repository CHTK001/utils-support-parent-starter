package com.chua.spider.support.config.store;

import com.chua.common.support.utils.StringUtils;
import com.chua.spider.support.config.model.SpiderDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
* 爬虫定义内存存储。
*
* <p>基于 ConcurrentHashMap 的轻量级内存实现，用于开发调试阶段，
* 后续可替换为数据库持久化存储。</p>
*
* @author CH
* @since 4.0.0.42
 */
public class SpiderDefinitionStore {

    /**
    * 自增 标识 生成器
    */
    private final AtomicLong idGenerator = new AtomicLong(1);

    /**
    * 爬虫编码 → 爬虫定义映射
    */
    private final Map<String, SpiderDefinition> storage = new ConcurrentHashMap<>();

    /**
    * 分页查询爬虫定义。
    *
    * @param pageNo   页码
    * @param pageSize 每页条数
    * @param keyword  关键字（按编码/名称模糊匹配）
    * @return 分页结果
    */
    public PageResult<SpiderDefinition> page(int pageNo, int pageSize, String keyword) {
        List<SpiderDefinition> all = new ArrayList<>(storage.values());
        // 关键字过滤
        if (StringUtils.isNotEmpty(keyword)) {
            String kw = keyword.toLowerCase();
            all.removeIf(d -> !d.getSpiderCode().toLowerCase().contains(kw)
                    && !d.getSpiderName().toLowerCase().contains(kw));
        }
        // 按更新时间倒序
        all.sort((a, b) -> {
            if (a.getUpdateTime() == null || b.getUpdateTime() == null) {
                return 0;
            }
            return b.getUpdateTime().compareTo(a.getUpdateTime());
        });
        int total = all.size();
        int from = (pageNo - 1) * pageSize;
        int to = Math.min(from + pageSize, total);
        List<SpiderDefinition> records = from >= total ? Collections.emptyList()
                : all.subList(from, to);
        return new PageResult<>(records, total, pageSize, pageNo, (total + pageSize - 1) / pageSize);
    }

    /**
    * 按编码查询爬虫定义。
    *
    * @param spiderCode 爬虫编码
    * @return 爬虫定义，不存在时返回 空
    */
    public SpiderDefinition get(String spiderCode) {
        return storage.get(spiderCode);
    }

    /**
    * 保存或更新爬虫定义。
    *
    * @param definition 爬虫定义
    * @return 保存后的爬虫定义
    */
    public SpiderDefinition save(SpiderDefinition definition) {
        if (definition.getSpiderId() == null) {
            // 新增
            definition.setSpiderId(idGenerator.getAndIncrement());
            definition.setSpiderStatus(definition.getSpiderStatus() != null ? definition.getSpiderStatus() : 1);
            definition.setCreateTime(java.time.LocalDateTime.now());
            definition.setUpdateTime(definition.getCreateTime());
        } else {
            // 更新
            definition.setUpdateTime(java.time.LocalDateTime.now());
        }
        storage.put(definition.getSpiderCode(), definition);
        return definition;
    }

    /**
    * 按编码删除爬虫定义。
    *
    * @param spiderCode 爬虫编码
    * @return 删除成功返回 true
    */
    public boolean remove(String spiderCode) {
        return storage.remove(spiderCode) != null;
    }

    /**
    * 更新爬虫状态。
    *
    * @param spiderCode   爬虫编码
    * @param spiderStatus 状态（0 禁用 / 1 启用）
    * @return 更新成功返回 true
    */
    public boolean updateStatus(String spiderCode, int spiderStatus) {
        SpiderDefinition def = storage.get(spiderCode);
        if (def == null) {
            return false;
        }
        def.setSpiderStatus(spiderStatus);
        def.setUpdateTime(java.time.LocalDateTime.now());
        return true;
    }

    /**
    * 简单分页结果。
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
