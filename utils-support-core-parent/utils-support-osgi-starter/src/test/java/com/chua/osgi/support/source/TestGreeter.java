package com.chua.osgi.support.source;

/**
 * 源码扩展点测试用 SPI 接口。
 *
 * <p><b>刻意做成独立顶层类</b>：源码编译产物需要 import 它，
 * 而嵌套接口的二进制名是 {@code 外层类$内部类}，用点号书写时 javac 按包路径解析并报
 * 「无法访问」——该报错会掩盖真正要测的行为（编译产物是否进了 SPI 表）。</p>
 *
 * @author CH
 * @since 4.0.0.43
 */
public interface TestGreeter {

    /**
     * 打招呼。
     *
     * @param name 名字
     * @return 问候语
     */
    String greet(String name);
}
