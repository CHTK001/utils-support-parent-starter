package com.demo.lib;

/**
 * 打包加密测试用样例依赖类（字节码被嵌入合成依赖 jar）
 *
 * @author CH
 * @since 2026-08-26
 */
public class DemoLib {

    /**
     * 私有构造（静态工具形态）
     */
    private DemoLib() {
    }

    /**
     * 提供可执行校验的方法
     *
     * @return 固定标识
     */
    public static String tag() {
        return "lib-ok";
    }
}
