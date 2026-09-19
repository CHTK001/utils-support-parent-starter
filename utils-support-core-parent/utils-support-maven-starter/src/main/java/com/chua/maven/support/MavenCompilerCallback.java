package com.chua.maven.support;

import java.util.List;

/**
 * Maven 编译回调接口，提供编译生命周期各阶段的回调。
 * <p>
 * 可通过此接口在编译前、编译后、发生错误或成功时执行自定义逻辑。
 * 通过 {@link MavenClientBuilder#onCallback(MavenCompilerCallback)} 注册。
 * </p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * MavenClient.create()
 *     .projectPath("/path/to/project")
 *     .onCallback(new MavenCompilerCallback() {
 *         @Override
 *         public void onStart(String projectPath) {
 *             System.out.println("开始编译: " + projectPath);
 *         }
 *
 *         @Override
 *         public void onSuccess(MavenCompileResult result) {
 *             System.out.println("编译成功, 耗时: " + result.durationMillis() + "ms");
 *         }
 *
 *         @Override
 *         public void onFailure(MavenCompileResult result) {
 *             System.err.println("编译失败: " + result.errors());
 *         }
 *     })
 *     .compile();
 * }</pre> *     })
 *     .compile();
 * }</pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public interface MavenCompilerCallback {

    /**
     * 编译开始回调
     *
     * @param projectPath 项目路径
     */
    default void onStart(String projectPath) {
    }

    /**
     * 编译结束回调（成功或失败都会调用）
     *
     * @param result 编译结果，包含退出码、输出、耗时等信息
     */
    default void onComplete(MavenCompileResult result) {
    }

    /**
     * 编译成功回调
     *
     * @param result 编译结果
     */
    default void onSuccess(MavenCompileResult result) {
    }

    /**
     * 编译失败回调
     *
     * @param result 编译结果（包含错误信息）
     */
    default void onFailure(MavenCompileResult result) {
    }

    /**
     * 编译取消回调
     */
    default void onCancel() {
    }
}
