package com.chua.common.support.datasearch.usage.spi;

import com.chua.common.support.ai.AiUsage;
import com.chua.common.support.ai.chat.aggregate.AggregateChatClient;
import com.chua.common.support.ai.chat.usage.UsagePersistChatClient;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用量同步工具 — 从全部 UsageParser SPI 解析外部工具本地数据，同步到 Engine
 *
 * <p>配合 {@link AggregateChatClient#syncUsage} 或 {@link UsagePersistChatClient#syncUsage} 使用。</p>
 *
 * <p>使用示例：
 * <pre>{@code
 *   // 同步全部 parser 到 Engine
 *   UsageSyncer.syncAllToEngine(engine);
 *
 *   // 同步全部 parser 到 AggregateChatClient
 *   UsageSyncer.syncAllToClient(aggregateClient);
 *
 *   // 同步指定 parser
 *   UsageSyncer.syncToEngine(engine, "opencode", "vscode");
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public final class UsageSyncer {

    /** 日志 */
    private static final Logger log = LoggerFactory.getLogger(UsageSyncer.class);

    private UsageSyncer() {}

    /**
     * 从全部 UsageParser 解析并同步到 Engine
     *
     * @param engine Engine 实例
     * @return 同步的总条数
     */
    public static int syncAllToEngine(Engine engine) {
        if (engine == null) {
            return 0;
        }
        List<AiUsage> all = parseAll();
        if (all.isEmpty()) {
            return 0;
        }
        persistToEngine(all, engine);
        return all.size();
    }

    /**
     * 从全部 UsageParser 解析并同步到 AggregateChatClient
     *
     * @param client AggregateChatClient 实例
     * @return 同步的总条数
     */
    public static int syncAllToClient(AggregateChatClient client) {
        if (client == null) {
            return 0;
        }
        List<AiUsage> all = parseAll();
        if (all.isEmpty()) {
            return 0;
        }
        client.syncUsage(all);
        return all.size();
    }

    /**
     * 从全部 UsageParser 解析并同步到 UsagePersistChatClient
     *
     * @param client UsagePersistChatClient 实例
     * @return 同步的总条数
     */
    public static int syncAllToClient(UsagePersistChatClient client) {
        if (client == null) {
            return 0;
        }
        List<AiUsage> all = parseAll();
        if (all.isEmpty()) {
            return 0;
        }
        client.syncUsage(all);
        return all.size();
    }

    /**
     * 从指定 UsageParser 解析并同步到 Engine
     *
     * @param engine Engine 实例
     * @param names  parser 名称（如 "opencode", "vscode"）
     * @return 同步的总条数
     */
    public static int syncToEngine(Engine engine, String... names) {
        if (engine == null || names == null || names.length == 0) {
            return 0;
        }
        List<AiUsage> all = parse(names);
        if (all.isEmpty()) {
            return 0;
        }
        persistToEngine(all, engine);
        return all.size();
    }

    /**
     * 从全部 UsageParser 解析数据（不持久化）
     *
     * @return 全部 parser 的用量数据合并列表
     */
    public static List<AiUsage> parseAll() {
        Map<String, UsageParser> parsers = ServiceProvider.of(UsageParser.class).list();
        if (parsers == null || parsers.isEmpty()) {
            log.debug("未发现任何 UsageParser 实现");
            return List.of();
        }
        List<AiUsage> result = new ArrayList<>();
        for (Map.Entry<String, UsageParser> entry : parsers.entrySet()) {
            try {
                List<AiUsage> usage = entry.getValue().parseAll();
                if (usage != null) {
                    result.addAll(usage);
                }
            } catch (Exception e) {
                log.warn("UsageParser[{}] 解析失败: {}", entry.getKey(), e.getMessage());
            }
        }
        return result;
    }

    /**
     * 从指定 UsageParser 解析数据
     */
    private static List<AiUsage> parse(String[] names) {
        List<AiUsage> result = new ArrayList<>();
        for (String name : names) {
            try {
                UsageParser parser = ServiceProvider.of(UsageParser.class).getNewExtension(name);
                if (parser == null) {
                    parser = ServiceProvider.of(UsageParser.class).getExtension(name);
                }
                if (parser != null) {
                    List<AiUsage> usage = parser.parseAll();
                    if (usage != null) {
                        result.addAll(usage);
                    }
                }
            } catch (Exception e) {
                log.warn("UsageParser[{}] 解析失败: {}", name, e.getMessage());
            }
        }
        return result;
    }

    private static void persistToEngine(List<AiUsage> usages, Engine engine) {
        for (AiUsage usage : usages) {
            if (usage == null) {
                continue;
            }
            com.chua.common.support.ai.chat.usage.AiUsageRecord.from(usage).asyncSave(engine);
        }
        log.info("同步 {} 条用量到 Engine", usages.size());
    }
}
