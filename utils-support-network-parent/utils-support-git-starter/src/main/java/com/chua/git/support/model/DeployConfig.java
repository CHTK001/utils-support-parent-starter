package com.chua.git.support.model;

/**
* Git 部署配置，用于描述 拉手 后的 Maven 编译与部署参数。
*
* <p>该配置由 {@link com.chua.git.support.operation.DeployOperation} 消费，
* 将仓库拉取后的本地目录与编译参数绑定传递给 {@link Deployer} 实现。</p>
*
* @param projectPath   项目根目录下的 pom.xml 相对路径（如 "pom.xml" 或 "module-a/pom.xml"）
* @param goals           Maven 编译目标列表（如 ["clean", "compile", "包"]）
* @param profiles        Maven 配置文件 列表
* @param skipTests      是否跳过测试
* @param jdkVersion     JDK 版本（如 "25"）
* @param deployTargetPath  部署目标路径（本地目录或远程路径）
*
* @author CH
* @since 4.0.0.42
 */
public record DeployConfig(
        String projectPath,
        java.util.List<String> goals,
        java.util.List<String> profiles,
        boolean skipTests,
        String jdkVersion,
        String deployTargetPath
) {

    /**
    * 创建默认编译参数（compile + 包）。
    *
    * @param projectPath Maven pom.xml 路径
    * @return 默认部署描述
     */
    public static DeployConfig ofDefault(String projectPath) {
        return new DeployConfig(
                projectPath,
                java.util.List.of("clean", "compile", "package"),
                java.util.List.of(),
                true,
                null,
                null
        );
    }
}