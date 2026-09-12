package com.chua.common.support.objects.creator;

import com.chua.common.support.lang.compile.AsmCompiler;
import com.chua.common.support.lang.compile.Compiler;
import com.chua.common.support.spi.ServiceProvider;

import java.lang.reflect.Method;

/**
   * 验证引入 utils-support-asm-starter 后 Quick 的 {@code dynamic()}/{@code compile()}
 * 自动切换到 ASM 编译器实现（{@link Compiler} SPI 优先解析 {@code "asm"}）。
 *
 * <p>asm-starter 的测试类路径天然包含本模块（{@link AsmCompiler} 及其 SPI 注册资源）
   * 与 common-starter（{@link Quick}/{@link DefaultQuick}），单向依赖无 reactor 循环引用，
 * 因此本测试是“asm-starter 在测试类路径”场景的规范验证位置。</p>
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.common.support.objects.creator.AsmQuickTest}
 * 任一校验失败抛出计数并输出 FAIL，全部通过输出 PASS。
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 * @see AsmCompiler
 * @see DefaultQuick
 * @see Quick
 */
public class AsmQuickTest {

    /** 失败计数 */
    private static int failureCount = 0;

    /** 成功计数 */
    private static int passCount = 0;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) {
        testSpiResolvesAsm();
        testResolveCompilerPrefersAsm();
        testDynamicWithAsm();
        testCompileWithAsm();
        testExecuteWithAsm();

        System.out.println("========================================");
        System.out.println("AsmQuickTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /** SPI 注册：asm=asmcompiler */
    static void testSpiResolvesAsm() {
        ServiceProvider<Compiler> provider = ServiceProvider.of(Compiler.class);
        Compiler asm = provider.getExtension("asm");
        check(asm instanceof AsmCompiler, "Compiler SPI 解析到 AsmCompiler");
    }

    /** 默认quick.resolvecompiler() 优先选择 asmcompiler */
    static void testResolveCompilerPrefersAsm() {
        DefaultQuick quick = new DefaultQuick();
        try {
            Compiler resolved = resolveCompilerReflectively(quick);
            check(resolved instanceof AsmCompiler, "DefaultQuick 优先解析 AsmCompiler");
        } finally {
            quick.close();
        }
    }

    /** dynamic() 在 ASM 编译器下生成子类 */
    static void testDynamicWithAsm() {
        DefaultQuick quick = new DefaultQuick();
        Runnable runnable = quick.dynamic(Runnable.class, "public void run() { System.out.println(\"asm dynamic ok\"); }");
        check(runnable != null, "dynamic() ASM 生成接口子类");
        if (runnable != null) {
            runnable.run();
        }
        quick.close();
    }

    /** compile() 在 ASM 编译器下编译完整类并可调用 */
    static void testCompileWithAsm() {
        DefaultQuick quick = new DefaultQuick();
        Class<?> clazz = quick.compile("public class AsmProbe { public static String hello() { return \"asm-hello\"; } }");
        check(clazz != null, "compile() ASM 编译完整类");
        if (clazz != null) {
            try {
                Object result = clazz.getMethod("hello").invoke(null);
                check(eq("asm-hello", result), "ASM 编译类静态方法可调用");
            } catch (Exception e) {
                check(false, "ASM 编译类调用失败: " + e);
            }
        }
        quick.close();
    }

    /** 执行() 脚本经 ASM 编译器执行 */
    static void testExecuteWithAsm() {
        DefaultQuick quick = new DefaultQuick();
        Object result = quick.execute("1 + 2");
        check(result instanceof Number && ((Number) result).intValue() == 3, "execute() 经 ASM 编译器执行表达式");
        quick.close();
    }

    /**
     * 反射调用 {@link DefaultQuick#resolveCompiler()} 获取当前解析到的编译器。
     *
     * @param quick 默认quick 实例
     * @return 当前编译器实现
     */
    private static Compiler resolveCompilerReflectively(DefaultQuick quick) {
        try {
            Method method = DefaultQuick.class.getDeclaredMethod("resolveCompiler");
            method.setAccessible(true);
            return (Compiler) method.invoke(quick);
        } catch (Exception e) {
            throw new IllegalStateException("反射获取编译器失败", e);
        }
    }

    /**
     * 校验并计数。
     *
     * @param condition 条件
     * @param message   校验说明
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }

    /**
      * 对象相等比较（处理 空）。
     *
     * @param expected 期望值
     * @param actual   实际值
     * @return 是否相等
     */
    private static boolean eq(Object expected, Object actual) {
        return expected == null ? actual == null : expected.equals(actual);
    }
}