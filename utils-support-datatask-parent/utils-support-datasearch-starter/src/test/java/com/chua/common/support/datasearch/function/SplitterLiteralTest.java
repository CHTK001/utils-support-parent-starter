package com.chua.common.support.datasearch.function;

import java.util.List;

/**
 * 分隔符按字面量切分的回归测试。
 *
 * <p>旧实现 {@code input.split(delimiter)} 把分隔符当正则：MacCMS 的 {@code $$$}
 * 作为正则永不匹配，多平台字段塌成一条；且默认 limit 会丢弃尾部空串，
 * 使名称列表与地址列表下标错位。</p>
 *
 * @author CH
 * @since 4.0.0.45
 */
public class SplitterLiteralTest {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 主方法。
     *
     * @param args args
     */
    public static void main(String[] args) {
        check("$$$ 字面量可切分",
                Splitter.on("$$$").splitToList("KG$$$bd").equals(List.of("KG", "bd")));
        check("含正则元字符的 $$$ 不再失配",
                Splitter.on("$$$").splitToList("a$$$b$$$c").equals(List.of("a", "b", "c")));
        check("尾部空串保留以维持下标对齐",
                Splitter.on("$$$").splitToList("KG$$$bd$$$").equals(List.of("KG", "bd", "")));
        check("单平台不产生多余条目",
                Splitter.on("$$$").splitToList("only").equals(List.of("only")));
        check("普通分隔符行为不变",
                Splitter.on(",").splitToList("a,b,c").equals(List.of("a", "b", "c")));
        check("空输入返回空列表",
                Splitter.on("$$$").splitToList("").equals(List.of()));

        System.out.println("[SplitterLiteralTest] pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS " + name);
        } else {
            fail++;
            System.out.println("  FAIL " + name);
        }
    }
}
