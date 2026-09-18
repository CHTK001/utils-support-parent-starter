package com.chua.maven.support;

import java.util.List;

/**
* Maven 编译进度回调接口，用于接收编译过程中的实时进度反馈。
* <p>
* 实现此接口可获取编译各阶段的进度信息，包括文件编译、依赖解析、打包等。
* 通过 {@link MavenClientBuilder#onProgress(MavenCompilerProgress)} 注册。
* </p>
*
* <h2>使用示例</h2>
* <pre>{@code
* MavenClient.create()
*     .projectPath("/path/to/project")
*     .onProgress((message, percent) -> System.out.printf("[%d%%] %s%n", percent, message))
*     .compile();
* }</pre>age))
*     .compile();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
@FunctionalInterface
public interface MavenCompilerProgress {

    /**
    * 编译进度回调
    *
    * @param message 当前进度描述信息
    * @param percent 当前进度百分比（0-100）
    */
    void onProgress(String message, int percent);
}
