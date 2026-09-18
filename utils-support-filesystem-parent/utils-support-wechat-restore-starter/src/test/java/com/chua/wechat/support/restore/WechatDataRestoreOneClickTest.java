package com.chua.wechat.support.restore;

import com.chua.common.support.task.restore.DataRestore;
import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.DataRestoreResult;
import com.chua.common.support.task.restore.ExportFormat;
import com.chua.wechat.support.restore.sqlcipher.SqlCipherDecryptor;
import com.chua.wechat.support.restore.sqlcipher.SqlCipherProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一键还原（SPI 入口）端到端测试。
 *
 * <p>其它还原器用例都直接 {@code new WechatDataRestore()}，绕开了真实调用形态。
 * 而对外承诺的写法是 {@code DataRestore.create("wechat").restore(source)} ——
 * 主名注册在 {@code META-INF/extensions} 里、别名 {@code wechat-export} 只写在
 * {@code @Spi} 注解上，这两点只要散掉一条，调用方拿到的就是
 * {@code IllegalArgumentException}，而所有既有测试仍然全绿。本用例把
 * 「链式创建 + 一键导出」这条入口钉住，数据源用本地合成的 SQLCipher 库，
 * 不依赖微信进程。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class WechatDataRestoreOneClickTest {

    /**
     * 正确的 32 字节密钥（hex）
     */
    private static final String KEY_HEX =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    @TempDir
    Path tempDir;

    /**
     * spi应当把加密库一键导出为Excel工作簿。
     *
     * <p>别名只存在于 {@code @Spi} 注解，因此这里刻意用 {@code wechat-export} 取实例。</p>
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void spiShouldExportEncryptedDatabaseToExcelInOneCall() throws Exception {
        File encrypted = encryptFixture(newDir("data"));
        File out = newDir("out");

        DataRestoreResult result = DataRestore.create("wechat-export", config(out, ExportFormat.EXCEL))
                .restore(encrypted);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        File xlsx = new File(out, "session__MSG.xlsx");
        assertTrue(xlsx.isFile(), "缺少 Excel 导出文件: " + xlsx.getAbsolutePath());
        byte[] head = Files.readAllBytes(xlsx.toPath());
        assertTrue(head.length > 2 && head[0] == 'P' && head[1] == 'K', "xlsx 应为 ZIP 容器");
    }

    /**
     * spi应当接受目录源并使用缺省输出目录。
     *
     * @throws Exception 当执行过程不满足前置条件时
     */
    @Test
    void spiShouldAcceptDirectorySourceWithDefaultOutputDir() throws Exception {
        File dataDir = newDir("data");
        encryptFixture(dataDir);

        DataRestoreResult result = DataRestore.create("wechat", config(null, ExportFormat.CSV)).restore(dataDir);

        assertTrue(result.isSuccess(), result.getErrorMessage());
        File msgCsv = new File(dataDir, "wechat-restore-out/session__MSG.csv");
        assertTrue(msgCsv.isFile(), "缺省输出目录下缺少 CSV: " + msgCsv.getAbsolutePath());
        String csv = Files.readString(msgCsv.toPath(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("hello wechat"), "加密库没有被真正解开: " + csv);
    }

    /**
     * 未注册的类型名应当被明确拒绝。
     *
     * <p>注册名是 {@code ibd}，而 {@code DataRestore} 的 javadoc 示例一度写成 {@code idb}。
     * 那种笔误编译期无感、只在运行时炸，这里把「未知名必须显式失败」钉住。</p>
     */
    @Test
    void unknownTypeShouldBeRejected() {
        assertThrows(IllegalArgumentException.class, () -> DataRestore.create("idb"));
    }

    /**
     * 在数据目录下生成加密的 {@code session.db}。
     *
     * @param dataDir 数据目录
     * @return 加密库文件
     * @throws Exception 生成失败
     */
    private File encryptFixture(File dataDir) throws Exception {
        File plain = WechatRestoreTestSupport.createReservedLayoutSqliteDatabase(new File(dataDir, "seed.db"));
        File encrypted = new File(dataDir, "session.db");
        WechatRestoreTestSupport.encrypt(plain, encrypted,
                SqlCipherDecryptor.parseHexKey(KEY_HEX), SqlCipherProfile.candidates().getFirst());
        Files.deleteIfExists(plain.toPath());
        return encrypted;
    }

    /**
     * 构造 sqlcipher 模式配置。
     *
     * @param outputDir 输出目录，null 表示走缺省值
     * @param format    输出格式
     * @return 配置
     */
    private DataRestoreConfig config(File outputDir, ExportFormat format) {
        Map<String, Object> options = new HashMap<>(4);
        options.put(WechatDataRestore.OPTION_MODE, WechatDataRestore.MODE_SQLCIPHER);
        options.put(WechatDataRestore.OPTION_KEY, KEY_HEX);
        return DataRestoreConfig.builder()
                .format(format)
                .outputDir(outputDir)
                .charset("UTF-8")
                .includeStructure(true)
                .options(options)
                .build();
    }

    /**
     * 创建目录。
     *
     * @param name 目录名
     * @return 目录
     */
    private File newDir(String name) {
        File dir = tempDir.resolve(name).toFile();
        assertTrue(dir.mkdirs() || dir.isDirectory());
        return dir;
    }
}
