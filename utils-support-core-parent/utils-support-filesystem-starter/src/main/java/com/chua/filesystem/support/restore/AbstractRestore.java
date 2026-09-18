package com.chua.filesystem.support.restore;

import com.chua.filesystem.support.data.datasource.jdbc.option.DataSourceOptions;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
* 抽象恢复基类
* <p>
* 提供恢复接口的基础实现，子类需要实现具体的恢复逻辑。
* </p>
*
* @版本 1.0.0
* @author CH
* @since 4.0.0.42
 */
@Slf4j
public abstract class AbstractRestore implements Restore {

    /**
    * 数据库配置选项
    */
    protected final DataSourceOptions databaseOptions;

    /**
    * 恢复设置
    */
    protected RestoreSetting restoreSetting;

    /**
    * 构造函数
    *
    * @param databaseOptions 数据库配置选项
    * @param restoreSetting  恢复设置
    */
    public AbstractRestore(DataSourceOptions databaseOptions, RestoreSetting restoreSetting) {
        this.databaseOptions = databaseOptions;
        this.restoreSetting = restoreSetting;
        log.info("[filesystem-restore] 初始化数据恢复实例");
    }

    @Override
    /** Restore */
    public RestoreResult restore(InputStream inputStream, String fileName) throws Exception {
        // 创建临时文件
        String suffix = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            suffix = fileName.substring(dotIndex);
        }

        Path tempFile = Files.createTempFile("restore_", suffix);
        try {
            Files.copy(inputStream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return restore(tempFile.toFile());
        }
 finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Override
    /** restore结构 */
    public RestoreResult restoreStructure(File sourceFile) throws Exception {
        RestoreSetting originalSetting = this.restoreSetting;
        try {
            this.restoreSetting = RestoreSetting.builder()
                    .restoreType(RestoreSetting.RestoreType.STRUCTURE)
                    .targetSchema(originalSetting.getTargetSchema())
                    .targetTable(originalSetting.getTargetTable())
                    .dropIfExists(originalSetting.isDropIfExists())
                    .mysql5(originalSetting.isMysql5())
                    .keyringFile(originalSetting.getKeyringFile())
                    .charset(originalSetting.getCharset())
                    .build();
            return restore(sourceFile);
        }
 finally {
            this.restoreSetting = originalSetting;
        }
    }

    @Override
    /** restore数据 */
    public RestoreResult restoreData(File sourceFile) throws Exception {
        RestoreSetting originalSetting = this.restoreSetting;
        try {
            this.restoreSetting = RestoreSetting.builder()
                    .restoreType(RestoreSetting.RestoreType.DATA)
                    .targetSchema(originalSetting.getTargetSchema())
                    .targetTable(originalSetting.getTargetTable())
                    .batchSize(originalSetting.getBatchSize())
                    .useTransaction(originalSetting.isUseTransaction())
                    .useLoadData(originalSetting.isUseLoadData())
                    .truncateBeforeRestore(originalSetting.isTruncateBeforeRestore())
                    .mysql5(originalSetting.isMysql5())
                    .keyringFile(originalSetting.getKeyringFile())
                    .charset(originalSetting.getCharset())
                    .build();
            return restore(sourceFile);
        }
 finally {
            this.restoreSetting = originalSetting;
        }
    }

    @Override
    /** restore转为table */
    public RestoreResult restoreToTable(File sourceFile, String targetTable) throws Exception {
        return restoreToTable(sourceFile, null, targetTable);
    }

    @Override
    /** restore转为table */
    public RestoreResult restoreToTable(File sourceFile, String targetSchema, String targetTable) throws Exception {
        RestoreSetting originalSetting = this.restoreSetting;
        try {
            this.restoreSetting = RestoreSetting.builder()
                    .restoreType(originalSetting.getRestoreType())
                    .targetSchema(targetSchema != null ? targetSchema : originalSetting.getTargetSchema())
                    .targetTable(targetTable)
                    .dropIfExists(originalSetting.isDropIfExists())
                    .truncateBeforeRestore(originalSetting.isTruncateBeforeRestore())
                    .batchSize(originalSetting.getBatchSize())
                    .useTransaction(originalSetting.isUseTransaction())
                    .useLoadData(originalSetting.isUseLoadData())
                    .mysql5(originalSetting.isMysql5())
                    .keyringFile(originalSetting.getKeyringFile())
                    .charset(originalSetting.getCharset())
                    .build();
            return restore(sourceFile);
        }
 finally {
            this.restoreSetting = originalSetting;
        }
    }

    @Override
    /** Upgrade */
    public void upgrade(RestoreSetting restoreSetting) {
        this.restoreSetting = restoreSetting;
        log.info("[filesystem-restore] 升级恢复设置");
    }

    /**
    * 获取文件扩展名
    * @param file 文件
    * @return 获取文件延伸的结果
    */
    protected String getFileExtension(File file) {
        String name = file.getName();
        int dotIndex = name.lastIndexOf('.');
        return dotIndex > 0 ? name.substring(dotIndex + 1).toLowerCase() : "";
    }
}
