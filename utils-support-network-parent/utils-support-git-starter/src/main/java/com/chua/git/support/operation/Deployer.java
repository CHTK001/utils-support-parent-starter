package com.chua.git.support.operation;

import com.chua.git.support.GitClient;
import com.chua.git.support.model.DeployConfig;
import com.chua.git.support.model.DeployResult;

/**
* 仓库部署器接口（SPI）。
*
* <p>定义 "拉取仓库 → 编译 → 部署" 的统一契约。
* 实现类通过 {@code META-INF/extensions} 文件注册，由 {@code ServiceProvider.of(Deployer.class)} 发现。</p>
*
* <p>典型实现场景：</p>
* <ul>
*   <li>本地 Maven 编译（maven-invoker）</li>
*   <li>Docker 容器内编译</li>
*   <li>CI/CD Pipeline 触发</li>
* </ul>
*
* <pre>使用示例（SPI 自动发现）：
* {@code
* // git-starter 自动通过 ServiceProvider 加载 Deployer 实现
* DeployResult result = (DeployResult) client.deploy()
*         .projectPath("module-a/pom.xml")
*         .goals("clean", "package")
*         .execute();
* }</pre>als("clean", "package")
*         .execute();
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface Deployer {

    /**
    * 部署器显示名称。
    *
    * @return 名称标识（如 "Maven"、"Gradle"、"Docker"）
    */
    String name();

    /**
    * 是否支持传入的部署描述符。
    *
    * @param config 部署配置
    * @return true 表示该部署器可处理此配置
    */
    boolean supports(DeployConfig config);

    /**
    * 执行部署。
    *
    * @param gitClient 已打开的 Git客户端，提供本地仓库路径
    * @param config    部署配置
    * @return 部署结果
    */
    DeployResult deploy(GitClient gitClient, DeployConfig config);
}
