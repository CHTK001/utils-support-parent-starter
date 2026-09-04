package com.chua.common.support.lang.view;

import com.chua.common.support.spi.ServiceProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ViewParser} 系列解析器回归测试。
 *
 * <p>覆盖：SPI 注册完整性、各解析器的 support 判定与 render 输出、order 排序与自动检测链路。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class ViewParserTest {

    /**
     * 是否支持渲染的测试数据：行列表
     */
    private static final List<Map<String, Object>> ROWS = List.of(
            mapOf("id", 1, "name", "zhangsan", "age", 30),
            mapOf("id", 2, "name", "lisi", "age", 25));

    /**
     * 是否支持渲染的测试数据：键值对
     */
    private static final Map<String, Object> KV = mapOf("name", "alice", "role", "admin", "score", 99);

    /**
     * 是否支持渲染的测试数据：嵌套树
     */
    private static final Map<String, Object> TREE = Map.of(
            "home", Map.of("user", "alice", "logs", List.of("a.log", "b.log")),
            "etc", "config");

    /**
     * 构造有序 Map。
     *
     * @param kvs 交替键值
     * @return 有序 Map
     */
    private static Map<String, Object> mapOf(Object... kvs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < kvs.length; i += 2) {
            result.put((String) kvs[i], kvs[i + 1]);
        }
        return result;
    }

    /**
     * SPI 注册完整性：9 个解析器按别名全部注册。
     * <p>注意：{@link ServiceProvider#list()} 的 key 为别名转大写（{@code ServiceDefinitionUtils#buildDefinitionAlias}），
     * 而 {@link ServiceProvider#getExtension(String)} 通过 equalsIgnoreCase 匹配，大小写均可。</p>
     */
    @Test
    void spiRegistersAllNineParsers() {
        Map<String, ViewParser> parsers = ServiceProvider.of(ViewParser.class).list();
        assertEquals(9, parsers.size(), "应注册 9 个 ViewParser 实现");
        assertTrue(containsKeyIgnoreCase(parsers, "table"));
        assertTrue(containsKeyIgnoreCase(parsers, "plain"));
        assertTrue(containsKeyIgnoreCase(parsers, "list"));
        assertTrue(containsKeyIgnoreCase(parsers, "md"));
        assertTrue(containsKeyIgnoreCase(parsers, "kv"));
        assertTrue(containsKeyIgnoreCase(parsers, "card"));
        assertTrue(containsKeyIgnoreCase(parsers, "tree"));
        assertTrue(containsKeyIgnoreCase(parsers, "barchart"));
        assertTrue(containsKeyIgnoreCase(parsers, "json"));
    }

    /**
     * 大小写不敏感地判断 Map 是否包含指定键。
     *
     * @param map  待检查的映射
     * @param name 键名（忽略大小写）
     * @return 存在返回 true
     */
    private static boolean containsKeyIgnoreCase(Map<String, ?> map, String name) {
        return map.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
    }

    /**
     * TableViewParser：support 判定与表格渲染
     */
    @Test
    void tableRendersRowsAndMap() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("table");
        assertTrue(parser.support(ROWS));
        assertTrue(parser.support(KV));
        assertTrue(parser.support(new int[]{1, 2}));
        assertFalse(parser.support("str"));
        assertFalse(parser.support(null));

        String table = parser.render(ROWS);
        assertTrue(table.contains("name"), "表格应包含列名 name");
        assertTrue(table.contains("zhangsan"), "表格应包含数据 zhangsan");
        assertTrue(table.contains("lisi"), "表格应包含数据 lisi");
    }

    /**
     * TableViewParser：无边框模式不污染 SPI 单例
     */
    @Test
    void borderlessDoesNotPolluteSpiSingleton() {
        TableViewParser parser = (TableViewParser) ServiceProvider.of(ViewParser.class).getExtension("table");
        TableViewParser borderless = parser.setBorderless(true);

        String framed = parser.render(ROWS);
        assertTrue(framed.contains("┌"), "原始单例应保持框线模式");

        String plain = borderless.render(ROWS);
        assertFalse(plain.chars().anyMatch(c -> "┌├└│".indexOf(c) >= 0), "新实例应为无边框模式");
    }

    /**
     * TableViewParser：无边框模式不绘制框线字符
     */
    @Test
    void tableBorderlessModeStripsFrames() {
        TableViewParser parser = (TableViewParser) ServiceProvider.of(ViewParser.class).getExtension("table");
        String framed = parser.render(ROWS);
        assertTrue(framed.contains("┌"), "默认模式应包含顶线字符");

        String borderless = parser.setBorderless(true).render(ROWS);
        assertFalse(borderless.chars().anyMatch(c -> "┌├└│".indexOf(c) >= 0), "无边框模式不应包含框线字符");
        assertTrue(borderless.contains("zhangsan"));
    }

    /**
     * PlainTableViewParser：纯文本表格无 Unicode 框线
     */
    @Test
    void plainTableIsAsciiOnly() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("plain");
        assertTrue(parser.support(ROWS));
        String output = parser.render(ROWS);
        assertTrue(output.contains("name"), "应包含列名 name");
        assertTrue(output.contains("zhangsan"));
        assertFalse(output.chars().anyMatch(c -> c > 127), "纯文本表格不应包含非 ASCII 字符");
    }

    /**
     * ListViewParser：带编号列表
     */
    @Test
    void listRendersNumberedItems() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("list");
        assertTrue(parser.support(List.of(1, 2)));
        assertFalse(parser.support("str"));

        String output = parser.render(List.of("foo", "bar", "baz"));
        String[] lines = output.split("\n");
        assertEquals(3, lines.length, "三行数据");
        assertTrue(lines[0].startsWith("1."), "首行应以编号 1 开头: " + lines[0]);
        assertTrue(lines[1].startsWith("2."), "次行应以编号 2 开头");
        assertTrue(lines[2].startsWith("3."), "第三行应以编号 3 开头");
    }

    /**
     * MarkdownTableViewParser：Markdown 分隔行
     */
    @Test
    void markdownRendersPipeTable() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("md");
        assertTrue(parser.support(ROWS));
        String output = parser.render(ROWS);
        assertTrue(output.contains("|"), "Markdown 表格应使用 | 分隔");
        assertTrue(output.contains("zhangsan"));
        String[] lines = output.split("\n");
        assertTrue(lines.length >= 2 && lines[1].contains("-"), "第二行应为分隔行且含连字符");
    }

    /**
     * KeyValueViewParser：键值对渲染（key 后对齐空格 + value）
     */
    @Test
    void keyValueRendersPairs() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("kv");
        assertTrue(parser.support(KV));
        String output = parser.render(KV);
        assertTrue(output.contains("name"), "应包含键 name");
        assertTrue(output.contains("alice"), "应包含值 alice");
        // 实现为「key 对齐空格 + value」，无冒号分隔符
        String[] lines = output.split("\n");
        assertEquals(3, lines.length, "三对键值");
        assertTrue(lines[0].startsWith("name"), "首行应以 key 开头");
        assertTrue(lines[0].contains("alice"), "首行应包含对应值");
    }

    /**
     * CardViewParser：卡片与标题
     */
    @Test
    void cardRendersBorderedBox() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("card");
        assertTrue(parser.support(KV));
        String output = parser.render(KV);
        assertTrue(output.contains("┌"), "卡片应有顶线");
        assertTrue(output.contains("└"), "卡片应有底线");
        assertTrue(output.contains("name"), "卡片应包含字段");
        assertTrue(output.contains("alice"));
    }

    /**
     * TreeViewParser：嵌套结构树渲染
     */
    @Test
    void treeRendersNestedStructure() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("tree");
        assertTrue(parser.support(TREE));
        String output = parser.render(TREE);
        assertTrue(output.contains("home"), "树应包含节点 home");
        assertTrue(output.contains("logs"), "树应包含节点 logs");
        assertTrue(output.contains("a.log"), "树应包含叶子 a.log");
    }

    /**
     * BarChartViewParser：条形图归一化与占位
     */
    @Test
    void barChartRendersBarsAndPlaceholders() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("barchart");
        assertTrue(parser.support(Map.of("a", 10)));
        assertFalse(parser.support(Map.of()), "空 Map 不支持");

        String output = parser.render(Map.of("任务A", 80, "任务B", 50, "任务C", 100));
        assertTrue(output.contains("%"), "条形图应包含百分比");
        assertTrue(output.contains("100%"), "最大值应归一化为 100%");

        assertEquals("(all zeros)", parser.render(Map.of("a", 0, "b", 0)), "全零数据应返回占位文本");
        assertEquals("(empty)", parser.render(Map.of()), "空 Map 应返回占位文本");
    }

    /**
     * JsonViewParser：兜底渲染与空值占位
     */
    @Test
    void jsonRendersAnyObject() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("json");
        assertTrue(parser.support(ROWS));
        assertTrue(parser.support(42), "JSON 解析器应无条件支持");

        String output = parser.render(ROWS);
        assertTrue(output.contains("zhangsan"), "JSON 输出应包含数据");
        assertEquals("(null)", parser.render(null), "空数据应返回占位文本");
    }

    /**
     * order 排序：与设计值一致
     */
    @Test
    void orderMatchesDesign() {
        assertEquals(0, ServiceProvider.of(ViewParser.class).getExtension("table").getOrder());
        assertEquals(1, ServiceProvider.of(ViewParser.class).getExtension("plain").getOrder());
        assertEquals(5, ServiceProvider.of(ViewParser.class).getExtension("list").getOrder());
        assertEquals(8, ServiceProvider.of(ViewParser.class).getExtension("md").getOrder());
        assertEquals(10, ServiceProvider.of(ViewParser.class).getExtension("kv").getOrder());
        assertEquals(15, ServiceProvider.of(ViewParser.class).getExtension("card").getOrder());
        assertEquals(20, ServiceProvider.of(ViewParser.class).getExtension("tree").getOrder());
        assertEquals(25, ServiceProvider.of(ViewParser.class).getExtension("barchart").getOrder());
        assertEquals(Integer.MAX_VALUE, ServiceProvider.of(ViewParser.class).getExtension("json").getOrder());
    }

    /**
     * 自动检测链路：按 order 升序取首个 support 的解析器
     */
    @Test
    void autoDetectPicksFirstSupportingByOrder() {
        Map<String, ViewParser> parsers = ServiceProvider.of(ViewParser.class).list();
        List<ViewParser> sorted = parsers.values().stream()
                .sorted(Comparator.comparingInt(ViewParser::getOrder))
                .collect(Collectors.toList());
        ViewParser hit = sorted.stream().filter(p -> p.support(ROWS)).findFirst().orElse(null);
        assertTrue(hit instanceof TableViewParser, "List<Map> 数据应由 table 命中，实际 " + hit);
        assertNull(parsers.get("unknown"), "未知别名应返回 null");
    }

    /**
     * 空数据占位：各解析器对空集合的渲染
     */
    @Test
    void emptyDataPlaceholders() {
        ViewParser table = ServiceProvider.of(ViewParser.class).getExtension("table");
        assertEquals("(empty)", table.render(List.of()), "空列表应返回 (empty)");

        ViewParser list = ServiceProvider.of(ViewParser.class).getExtension("list");
        assertEquals("(empty)", list.render(List.of()));

        ViewParser kv = ServiceProvider.of(ViewParser.class).getExtension("kv");
        assertEquals("(empty)", kv.render(Map.of()));

        ViewParser tree = ServiceProvider.of(ViewParser.class).getExtension("tree");
        assertEquals("(empty)", tree.render(null));
    }

    /**
     * 简单类型数组渲染为单列列表格
     */
    @Test
    void simpleArrayRendersSingleColumn() {
        ViewParser parser = ServiceProvider.of(ViewParser.class).getExtension("table");
        String output = parser.render(new String[]{"foo", "bar"});
        assertTrue(output.contains("foo"), "应包含元素 foo");
        assertTrue(output.contains("#"), "单列模式应有 # 表头");
    }
}