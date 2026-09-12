package com.chua.common.support.ai.memory;

import com.chua.common.support.ai.chat.ChatClient;
import com.chua.common.support.lang.datasource.engine.Engine;
import com.chua.common.support.spi.ServiceProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
* 记忆管理器
*
* <p>提供记忆体的高层操作 API，整合存储和 AI 总结能力。
* Agent 通过此管理器保存、搜索和管理记忆条目。
*
* <h3>工作流程</h3>
* <pre>
*   Agent.run(input)
*     → autoSaveMemory(input, output)
*       → saveFromConversation("用户: ...\n助手: ...", sessionId, agentId)
*         → [若配置 summarizerClient] AI 总结 → 结构化 MemoryEntry → 存储
*         → [若未配置] 原始文本直接存储
*
*   下次对话
*     → search("关键词", limit) → 返回相关记忆
*     → 注入到 system prompt 或 context 中
* </pre>
*
* <h3>存储实现</h3>
* <pre>
*   默认：FileMemoryStore（基于工作间 JSON 文件）
*   可替换：通过 SPI MemoryStoreProvider 注册自定义实现
*     → 数据库实现（MySQL/PostgreSQL）
*     → 向量数据库实现（Milvus/Chroma/Pinecone）→ 支持语义搜索
*     → Redis 实现（高速缓存）
* </pre>
*
* @author CH
* @since 2026/07/16
 */
@Slf4j
@SuppressWarnings("unchecked")
public class MemoryManager implements AutoCloseable {

    /** 记忆存储 */
    private final MemoryStore store;

    /** 记忆配置 */
    private final MemoryConfig config;

    /** 默认总结 prompt */
    private static final String DEFAULT_SUMMARIZER_PROMPT =
            "请从以下对话内容中提炼出关键信息，生成一条高质量的记忆条目。\n"
            + "要求：\n"
            + "1. 提取核心事实、用户偏好、关键决策\n"
            + "2. 用简洁的自然语言描述\n"
            + "3. 评估重要性（0.0-1.0）\n"
            + "4. 生成合适的类型标签（fact/preference/decision/context）\n"
            + "5. 生成 2-5 个检索标签\n\n"
            + "输出格式（JSON）：\n"
            + "{\"content\": \"提炼后的记忆内容\", \"type\": \"fact\", \"importance\": 0.8, "
            + "\"tags\": [\"标签1\", \"标签2\"]}\n\n"
            + "对话内容：\n{content}";

    /**
    * 创建记忆管理器
    *
    * @param config 记忆体配置
     */
    public MemoryManager(MemoryConfig config) {
        this.config = config;
        this.store = createStore(config);
    }

    /**
    * 使用指定存储实现创建管理器。
     */
    public MemoryManager(MemoryConfig config, MemoryStore store) {
        this.config = config != null ? config : MemoryConfig.builder().build();
        this.store = store != null ? store : createStore(this.config);
    }

    /**
    * Engine 记忆快捷构造（{@link EngineMemoryStore}）。
     */
    public static MemoryManager ofEngine(Engine engine) {
        return ofEngine(engine, MemoryConfig.builder().storeType("engine").engine(engine).build());
    }

    /**
    * Engine 记忆快捷构造。
     */
    public static MemoryManager ofEngine(Engine engine, MemoryConfig config) {
        MemoryConfig cfg = config != null ? config : MemoryConfig.builder().build();
        cfg.setStoreType("engine");
        cfg.setEngine(engine);
        return new MemoryManager(cfg, new EngineMemoryStore(engine, cfg));
    }

    /** 创建Store */
    private static MemoryStore createStore(MemoryConfig config) {
        if (config != null && "engine".equalsIgnoreCase(config.getStoreType())
                && config.getEngine() != null) {
            return new EngineMemoryStore(config.getEngine(), config);
        }
        try {
            MemoryStoreProvider provider = ServiceProvider.of(MemoryStoreProvider.class)
                    .getExtension("default");
            if (provider != null) {
                return provider.create(config);
            }
        } catch (Exception e) {
            log.warn("SPI MemoryStoreProvider 加载失败，使用默认 FileMemoryStore: {}", e.getMessage());
        }
        return new FileMemoryStore(config);
    }

    /**
    * 从对话内容保存记忆
    *
    * <p>自动调用 ChatClient 总结对话，生成高质量记忆条目。
    * 若未配置 summarizerClient，则直接保存原始对话内容。
    *
    * @param conversation 对话内容
    * @param sessionId    会话 ID
    * @param agentId      Agent 标识
     */
    public void saveFromConversation(String conversation, String sessionId, String agentId) {
        if (conversation == null || conversation.isBlank()) {
            return;
        }
        if (config.isAutoSummarize() && config.getSummarizerClient() != null) {
            summarizeAndSave(conversation, sessionId, agentId);
        } else {
            saveRaw(conversation, sessionId, agentId);
        }
    }

    /**
    * 直接保存一条记忆
    *
    * @param entry 记忆条目
     */
    public void save(MemoryEntry entry) {
        store.save(entry);
    }

    /**
    * 搜索记忆
    *
    * @param keyword 搜索关键词
    * @param limit   最大返回数量
    * @return 匹配的记忆列表
     */
    public List<MemoryEntry> search(String keyword, int limit) {
        return store.search(keyword, limit);
    }

    /**
    * 按类型检索
    *
    * @param type  记忆类型
    * @param limit 最大返回数量
    * @return 记忆列表
     */
    public List<MemoryEntry> listByType(String type, int limit) {
        return store.listByType(type, limit);
    }

    /**
    * 按会话 ID 检索
    *
    * @param sessionId 会话 ID
    * @return 记忆列表
     */
    public List<MemoryEntry> listBySession(String sessionId) {
        return store.listBySession(sessionId);
    }

    /**
    * 删除记忆
    *
    * @param id 记忆 ID
    * @return 是否成功
     */
    public boolean delete(String id) {
        return store.delete(id);
    }

    /**
    * 获取记忆总数
    *
    * @return 记忆条数
     */
    public int count() {
        return store.count();
    }

    /**
    * 备份记忆
    *
    * @param path 备份文件路径
     */
    public void backup(String path) {
        store.backup(path);
    }

    /**
    * 恢复记忆
    *
    * @param path 备份文件路径
     */
    public void restore(String path) {
        store.restore(path);
    }

    /**
    * 获取底层存储实例
    *
    * @return 记忆存储
     */
    public MemoryStore getStore() {
        return store;
    }

    @Override
    /** 关闭 */
    public void close() {
        store.close();
    }

    /**
    * AI 总结并保存
    *
    * <p>调用 ChatClient 将对话内容提炼为结构化记忆条目。
     */
    private void summarizeAndSave(String conversation, String sessionId, String agentId) {
        try {
            String prompt = buildSummarizerPrompt(conversation);
            String result = config.getSummarizerClient().chatSync(prompt);
            MemoryEntry parsed = parseSummaryResult(result, sessionId, agentId);
            if (parsed != null) {
                store.save(parsed);
            }
        } catch (Exception e) {
            log.warn("AI 总结失败，回退到原始保存: {}", e.getMessage());
            saveRaw(conversation, sessionId, agentId);
        }
    }

    /**
    * 原始保存（不做 AI 总结）
     */
    private void saveRaw(String content, String sessionId, String agentId) {
        MemoryEntry entry = MemoryEntry.builder()
                .id(UUID.randomUUID().toString())
                .content(content.length() > config.getMaxContentLength()
                        ? content.substring(0, config.getMaxContentLength()) : content)
                .type("raw")
                .sessionId(sessionId)
                .agentId(agentId)
                .createdAt(System.currentTimeMillis())
                .importance(0.5)
                .build();
        store.save(entry);
    }

    /**
    * 构建总结 prompt
     */
    private String buildSummarizerPrompt(String content) {
        String template = config.getSummarizerPrompt() != null
                ? config.getSummarizerPrompt() : DEFAULT_SUMMARIZER_PROMPT;
        return template.replace("{content}", content);
    }

    /**
    * 解析 AI 总结结果
     */
    private MemoryEntry parseSummaryResult(String result, String sessionId, String agentId) {
        try {
            // 尝试从 JSON 中提取
            String json = extractJson(result);
            Map<String, Object> map = com.chua.common.support.lang.json.Json.fromJson(json, Map.class);
            return MemoryEntry.builder()
                    .id(UUID.randomUUID().toString())
                    .content((String) map.get("content"))
                    .type((String) map.getOrDefault("type", "summary"))
                    .sessionId(sessionId)
                    .agentId(agentId)
                    .createdAt(System.currentTimeMillis())
                    .importance(toDouble(map.get("importance")))
                    .tags(toTagList(map.get("tags")))
                    .build();
        } catch (Exception e) {
            // 解析失败，用原始文本
            return MemoryEntry.builder()
                    .id(UUID.randomUUID().toString())
                    .content(result.length() > config.getMaxContentLength()
                            ? result.substring(0, config.getMaxContentLength()) : result)
                    .type("summary")
                    .sessionId(sessionId)
                    .agentId(agentId)
                    .createdAt(System.currentTimeMillis())
                    .importance(0.6)
                    .build();
        }
    }

    /** ExtractJson */
    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return "{}";
        }
        // 尝试直接解析整段文本
        try {
            com.chua.common.support.lang.json.Json.fromJson(text, Map.class);
            return text;
        } catch (Exception ignored) {}
        // 提取 markdown 代码块中的 JSON
        int codeBlock = text.indexOf("```");
        if (codeBlock >= 0) {
            int start = text.indexOf('{', codeBlock);
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                String candidate = text.substring(start, end + 1);
                try {
                    com.chua.common.support.lang.json.Json.fromJson(candidate, Map.class);
                    return candidate;
                } catch (Exception ignored) {}
            }
        }
        // 按括号匹配提取第一个顶层 JSON 对象
        int start = text.indexOf('{');
        if (start >= 0) {
            int depth = 0;
            for (int i = start; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                }
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        String candidate = text.substring(start, i + 1);
                        try {
                            com.chua.common.support.lang.json.Json.fromJson(candidate, Map.class);
                            return candidate;
                        } catch (Exception ignored) {}
                        break;
                    }
                }
            }
            // 括号不匹配时的兜底
            int end = text.lastIndexOf('}');
            if (end > start) {
                return text.substring(start, end + 1);
            }
        }
        return "{}";
    }

    /** ToTagList */
    private List<String> toTagList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** ToDouble */
    private Double toDouble(Object obj) {
        if (obj instanceof Number n) {
            return n.doubleValue();
        }
        if (obj instanceof String s) {
            return Double.parseDouble(s);
        }
        return 0.5;
    }
}
