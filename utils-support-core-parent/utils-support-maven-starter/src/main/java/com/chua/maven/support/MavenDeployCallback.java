package com.chua.maven.support;

import java.util.List;

/**
* Maven 部署回调接口，用于接收部署过程中的实时反馈。
* <p>
* 通过 {@link MavenDeployClient#onDeploy(MavenDeployCallback)} 注册。
* </p>
*
* <h2>使用示例</h2>
* <pre>{@code
* MavenClient.create()
*     .projectPath("pom.xml")
*     .goal("clean", "package")
*     .compileAndDeploy()
*     .onDeploy(new MavenDeployCallback() {
*         @Override
*         public void onDeployProgress(String message, int percent) {
*             System.out.printf("[部署 %d%%] %s%n", percent, message);
*         }
*
*         @Override
*         public void onDeploySuccess(List&lt;String&gt; deployedPaths) {
*             System.out.println("部署成功: " + deployedPaths);
*         }
*     })
*     .deployTo("/opt/app/");
* }</pre>         }
*     })
* .deploy转为("/opt/app/");
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface MavenDeployCallback {

    /**
    * 部署开始回调
    *
    * @param projectPath 项目路径
     */
    default void onDeployStart(String projectPath) {
    }

    /**
    * 部署进度回调
    *
    * @param message 当前进度描述
    * @param percent 进度百分比（0-100）
     */
    default void onDeployProgress(String message, int percent) {
    }

    /**
    * 部署成功回调
    *
    * @param deployedPaths 部署后的文件路径列表
     */
    default void onDeploySuccess(List<String> deployedPaths) {
    }

    /**
    * 部署失败回调
    *
    * @param exception 异常信息
     */
    default void onDeployFailure(Exception exception) {
    }
}