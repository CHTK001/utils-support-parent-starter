package com.chua.filesystem.support.restore;

import com.chua.common.support.spi.ServiceProvider;
import com.chua.filesystem.support.data.datasource.jdbc.option.DataSourceOptions;

import java.io.File;
import java.io.InputStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * 数据恢复接口
 * <p>
 * 定义了数据恢复功能的标准接口，所有恢复实现都必须实现。
 * 支持多种恢复类型：
 * - 类型恢复（DDL）：仅恢复表结构定义
 * - 结构恢复：恢复表结构和索引
 * - 数据恢复：恢复完整数据
 * </p>
 *
 * <p>
 * 使用示例：
 * <pre>
 * // 创建MySQL恢复实例
 * Restore mysqlRestore = Restore.createRestore("mysql",
 *     DataSourceOptions.newBuilder()
 *         .url("jdbc:mysql://localhost:3306/test")
 *         .username("root")
 *         .password("password")
 *         .build(),
 *     RestoreSetting.builder()
 *         .restoreType(RestoreType.ALL)
 *         .build()
 * );
 *
 * // 从IBD文件恢复
 * mysqlRestore.restore(new File("user.ibd"));
 *
 * // 关闭恢复服务
 * mysqlRestore.close();
 * </pre>
 * </p>
 *
 * @author CH
 * @version 1.0.0
 * @since 2024/12/25
 */
public interface Restore extends AutoCloseable {

    /**
     * 创建一个恢复对象
     * <p>
     * 通过SPI机制创建指定名称的恢复实例。
     * </p>
     *
     * @param name            恢复的名称（如"mysql"、"postgresql"等）
     * @param databaseOptions 数据库选项，定义了与数据库相关的配置
     * @param restoreSetting  恢复选项，定义了恢复操作的特定配置
     * @return 恢复对象实例
     */
    static Restore createRestore(String name, DataSourceOptions databaseOptions, RestoreSetting restoreSetting) {
        return ServiceProvider.of(Restore.class).getNewExtension(name, databaseOptions, restoreSetting);
    }

    /**
     * 创建一个恢复对象（使用默认恢复设置）
     *
     * @param name            恢复的名称
     * @param databaseOptions 数据库选项
     * @return 恢复对象实例
     */
    static Restore createRestore(String name, DataSourceOptions databaseOptions) {
        return createRestore(name, databaseOptions, RestoreSetting.builder().build());
    }

    /**
     * 从文件恢复数据
     * <p>
     * 根据恢复设置，从指定文件恢复数据到目标数据库。
     * </p>
     *
     * @param sourceFile 源文件（如IBD文件、SQL文件等）
     * @return RestoreResult 恢复结果
     * @throws Exception 恢复过程中可能抛出的异常
     */
    RestoreResult restore(File sourceFile) throws Exception;

    /**
     * 从输入流恢复数据
     *
     * @param inputStream 输入流
     * @param fileName    文件名（确定文件类型）
     * @return RestoreResult 恢复结果
     * @throws Exception 恢复过程中可能抛出的异常
     */
    RestoreResult restore(InputStream inputStream, String fileName) throws Exception;

    /**
     * 仅恢复表结构（DDL）
     *
     * @param sourceFile 源文件
     * @return RestoreResult 恢复结果
     * @throws Exception 恢复过程中可能抛出的异常
     */
    RestoreResult restoreStructure(File sourceFile) throws Exception;

    /**
     * 仅恢复数据（不包含表结构）
     * <p>
     * 注意：目标表必须已存在
     * </p>
     *
     * @param sourceFile 源文件
     * @return RestoreResult 恢复结果
     * @throws Exception 恢复过程中可能抛出的异常
     */
    RestoreResult restoreData(File sourceFile) throws Exception;

    /**
     * 恢复数据到指定表
     *
     * @param sourceFile  源文件
     * @param targetTable 目标表名
     * @return RestoreResult 恢复结果
     * @throws Exception 恢复过程中可能抛出的异常
     */
    RestoreResult restoreToTable(File sourceFile, String targetTable) throws Exception;

    /**
     * 恢复数据到指定库表
     *
     * @param sourceFile   源文件
     * @param targetSchema 目标库名
     * @param targetTable  目标表名
     * @return RestoreResult 恢复结果
     * @throws Exception 恢复过程中可能抛出的异常
     */
    RestoreResult restoreToTable(File sourceFile, String targetSchema, String targetTable) throws Exception;

    /**
     * 升级恢复设置
     *
     * @param restoreSetting 新的恢复设置
     */
    void upgrade(RestoreSetting restoreSetting);

    /**
     * 关闭恢复服务，释放资源
     */
    @Override
    void close() throws Exception;
}
