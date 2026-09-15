package com.chua.common.support.task.restore;

import com.chua.common.support.spi.ServiceProvider;

import java.io.File;
import java.util.Objects;

/**
* 数据还原接口。
*
* <p>定义"从数据源文件还原为可读数据文件"的标准契约，各模块通过 SPI 机制注册实现，例如：</p>
* <ul>
*   <li>idb-starter — 解析 MySQL IBD 表空间文件，还原为 CSV / SQL / Excel</li>
*   <li>wechat-starter — 解析本机微信数据库，还原聊天记录为 CSV / SQL / Excel</li>
* </ul>
*
* <p>契约默认以「单个文件」为源（{@link AbstractDataRestore} 会校验 {@code source.isFile()}），
* 但<b>数据天然是目录</b>的实现可以重写 {@code restore(File, DataRestoreConfig)} 接受目录，
* 例如微信的数据分散在 {@code db_storage} 下的二十来个库里，传目录比逐个传文件更贴近真实用法：</p>
*
* <pre>{@code
* // 按类型创建还原器，输出默认 CSV
* DataRestore restore = DataRestore.create("idb");
* DataRestoreResult result = restore.restore(new File("user.ibd"));
*
* // 指定输出格式与目录
* DataRestoreConfig config = DataRestoreConfig.builder()
*         .format(ExportFormat.EXCEL)
*         .outputDir(new File("out"))
*         .build();
* DataRestore restore = DataRestore.create("wechat", config);
* DataRestoreResult result = restore.restore(new File("EnMicroMsg.db"));
*
* // 目录源同样一键：微信实现接受账号目录 / db_storage / xwechat_files
* DataRestoreResult result = DataRestore.create("wechat")
*         .restore(new File("E:/微信/xwechat_files"));
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public interface DataRestore {

    /**
    * 根据 SPI 类型创建还原器实例（使用默认配置）。
    *
    * @param type 数据源类型 SPI 名称（如 "idb"、"wechat"）
    * @return DataRestore 实例
    * @throws IllegalArgumentException 找不到对应实现时抛出
     */
    static DataRestore create(String type) {
        Objects.requireNonNull(type, "数据源类型不能为空");
        DataRestore restore = ServiceProvider.of(DataRestore.class).getExtension(type);
        if (restore == null) {
            throw new IllegalArgumentException("未找到 DataRestore SPI 实现: " + type);
        }
        return restore;
    }

    /**
    * 根据 SPI 类型创建还原器实例（携带配置）。
    *
    * @param type   数据源类型 SPI 名称
    * @param config 还原配置
    * @return DataRestore 实例
    * @throws IllegalArgumentException 找不到对应实现时抛出
     */
    static DataRestore create(String type, DataRestoreConfig config) {
        Objects.requireNonNull(type, "数据源类型不能为空");
        DataRestore restore = ServiceProvider.of(DataRestore.class).getNewExtension(type, config);
        if (restore == null) {
            throw new IllegalArgumentException("未找到 DataRestore SPI 实现: " + type);
        }
        return restore;
    }

    /**
    * 获取当前还原器支持的数据源类型名称（如 "idb"、"wechat"）。
    *
    * @return 类型名称
     */
    String type();

    /**
    * 还原数据源文件为数据文件（使用默认配置）。
    *
    * @param source 数据源文件（实现支持时也可传目录，见 {@link #restore(File, DataRestoreConfig)}）
    * @return 还原结果
    * @throws Exception 还原过程中可能抛出的异常
     */
    DataRestoreResult restore(File source) throws Exception;

    /**
    * 按指定配置还原数据源文件为数据文件。
    *
    * <p>默认契约要求 {@code source} 是单个文件；数据天然是目录的实现
    * （如微信）可以重写本方法接受目录，语义与文件源一致。</p>
    *
    * @param source 数据源文件或目录
    * @param config 还原配置
    * @return 还原结果
    * @throws Exception 还原过程中可能抛出的异常
     */
    DataRestoreResult restore(File source, DataRestoreConfig config) throws Exception;
}