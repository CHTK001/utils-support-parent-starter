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
     * 规范构造器：对编译目标与配置文件列表做防御性拷贝。
     *
     * <p>value class 前置条件——集合组件必须深不可变。两个列表的构造点
     * （{@link #ofDefault(String)} 与 {@code DeployOperation}）均传入非空不可变列表，
     * 元素亦非空，故使用拒绝 null 元素的 {@link java.util.List#copyOf(java.util.Collection)}。</p>
     */
    public DeployConfig {
        goals = java.util.List.copyOf(java.util.Objects.requireNonNull(goals, "goals 不能为 null"));
        profiles = java.util.List.copyOf(java.util.Objects.requireNonNull(profiles, "profiles 不能为 null"));
    }

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
