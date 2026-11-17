package com.chua.common.support.network.download.extractor;

import com.chua.common.support.spi.annotations.Spi;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.FileHeader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * ZIP 压缩文件提取器（支持密码）
 *
 * <p>本类是 {@link Extractor} SPI 的 <b>唯一</b> {@code zip} 实现（原先 common-starter 与
 * filesystem-starter 各有一份 {@code @Spi("zip")}，SPI 按 "zip" 查找会命中歧义，属 S0 重复实现，
 * 已按 R4 下沉收敛到本模块）。使用 zip4j 实现，支持带密码保护的 ZIP 文件解压；
 * 解压前对所有条目做 Zip Slip 路径逃逸校验，任一条目越界则整体拒绝解压。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
@Spi("zip")
public class ZipExtractor implements Extractor {

    /**
     * 单个 ZIP 允许的最大条目数（防 zip 炸弹；与监控侧既有阈值一致）。
     */
    private static final int MAX_ENTRIES = 5000;

    /**
     * 获取支持的扩展名列表
     *
     * @return 支持的扩展名数组，仅包含 ".zip"
     */
    @Override
    public String[] supportedExtensions() {
        return new String[]{".zip"};
    }

    /**
     * 从源 ZIP 文件中提取内容到目标目录（无密码）
     *
     * @param sourceFile 源 ZIP 文件对象
     * @param targetDir  目标解压目录对象
     * @return 如果解压成功返回 true，否则返回 false
     */
    @Override
    public boolean extract(File sourceFile, File targetDir) {
        return extract(sourceFile, targetDir, null);
    }

    /**
     * 将 ZIP 文件提取到目标目录
     *
     * <p>先校验全部条目路径均落在目标目录内（防 Zip Slip），再执行解压；
     * 解压失败时不会留下部分解压结果之外的安全问题（越界条目在解压前即被拒绝）。</p>
     *
     * @param sourceFile 源 ZIP 文件
     * @param targetDir  目标目录
     * @param password   密码（可选，{@code null} 表示无密码）
     * @return 提取是否成功
     */
    public boolean extract(File sourceFile, File targetDir, String password) {
        if (sourceFile == null || !sourceFile.exists()) {
            log.error("源文件不存在：{}", sourceFile);
            return false;
        }

        Path targetPath = Paths.get(targetDir.getAbsolutePath());
        try {
            Files.createDirectories(targetPath);
        } catch (IOException e) {
            log.error("创建目标目录失败：{}", targetDir, e);
            return false;
        }

        try (ZipFile zipFile = new ZipFile(sourceFile)) {
            if (zipFile.isEncrypted() && password == null) {
                log.error("ZIP 文件已加密但未提供密码：{}", sourceFile.getName());
                return false;
            }
            if (password != null) {
                zipFile.setPassword(password.toCharArray());
            }

            List<FileHeader> fileHeaders = zipFile.getFileHeaders();
            // 条目数上限校验（防 zip 炸弹，解压前拦截）
            if (fileHeaders.size() > MAX_ENTRIES) {
                log.error("ZIP 条目数超过上限 {}，拒绝解压：{}", MAX_ENTRIES, sourceFile.getName());
                return false;
            }
            // Zip Slip 校验：任一条目解析路径越出目标目录，整体拒绝（解压前拦截）
            for (FileHeader header : fileHeaders) {
                if (header.isDirectory()) {
                    continue;
                }
                Path entryPath = targetPath.resolve(header.getFileName()).normalize();
                if (!entryPath.startsWith(targetPath.normalize())) {
                    log.error("ZIP 条目路径越出目标目录，拒绝解压：{} -> {}", header.getFileName(), entryPath);
                    return false;
                }
            }

            zipFile.extractAll(targetDir.getAbsolutePath());
            log.info("ZIP 解压完成：{} -> {}", sourceFile.getName(), targetDir.getAbsolutePath());
            return true;
        } catch (IOException e) {
            log.error("ZIP 解压失败：{}", sourceFile.getName(), e);
            return false;
        }
    }
}
