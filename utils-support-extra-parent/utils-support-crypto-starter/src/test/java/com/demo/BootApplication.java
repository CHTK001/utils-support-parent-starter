package com.demo;

/**
 * 打包加密测试用样例应用类（随测试源码编译，字节码被嵌入合成 FatJar）
 *
 * @author CH
 * @since 2026-08-26
 */
public class BootApplication {

    /**
     * 默认构造
     */
    public BootApplication() {
    }

    /**
     * 提供可执行校验的方法
     *
     * @return 固定标识
     */
    public String greet() {
        return join("boot", "-ok");
    }

    /**
     * 私有辅助方法（用于私有成员重命名测试）
     *
     * @param left  左段
     * @param right 右段
     * @return 拼接结果
     */
    private String join(String left, String right) {
        return left + right;
    }
}
