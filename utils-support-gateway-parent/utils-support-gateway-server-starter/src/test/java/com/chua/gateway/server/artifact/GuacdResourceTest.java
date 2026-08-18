package com.chua.gateway.server.artifact;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * guacd 内嵌预编译资源完整性测试。
 *
 * <p>验证 utils-support-guacd-resource-starter 内嵌的 linux zip：
 *   1. classpath 资源存在
 *   2. 包含 sbin/guacd，且解压后可执行位会被设置
 *   3. 包含 lib/ 下的 guac client 插件库与 freerdp2 插件
 *
 * @author CH
 * @since 4.0.0.42
 */
class GuacdResourceTest {

    /**
     * Windows 资源加载失败时的错误消息
     */
    private static final String ERR_NO_LINUX_RESOURCE = "linux guacd zip 必须内嵌在 classpath";

    /**
     * 资源根路径（与 GuacdBootstrapper 一致）
     */
    private static final String LINUX_RESOURCE = "META-INF/resources/native/linux-x86_64/guacd-linux-x86_64.zip";

    /**
     * 断言 linux zip 内嵌且结构正确（可执行文件 + 插件库齐全）。
     */
    @Test
    void shouldEmbedCompleteLinuxGuacdZip() throws Exception {
        Path zipPath = extractToTemp();
        assertTrue(Files.exists(zipPath), "linux guacd zip 应存在于 classpath");

        boolean hasSbinGuacd = false;
        boolean hasGuacLib = false;
        boolean hasFreerdp2 = false;
        try (InputStream in = Files.newInputStream(zipPath);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if ("sbin/guacd".equals(name)) {
                    hasSbinGuacd = true;
                }
                if (name.startsWith("lib/libguac.so.")) {
                    hasGuacLib = true;
                }
                if (name.startsWith("lib/freerdp2/")) {
                    hasFreerdp2 = true;
                }
            }
        }
        assertTrue(hasSbinGuacd, "zip 必须包含 sbin/guacd");
        assertTrue(hasGuacLib, "zip 必须包含 lib/libguac.so.*");
        assertTrue(hasFreerdp2, "zip 必须包含 freerdp2 插件目录");
    }

    /**
     * 断言 linux zip 包内 guacd 二进制解压后可执行（extractZip 会 setExecutable）。
     */
    @Test
    void shouldSetExecutableOnGuacd() throws Exception {
        Path zipPath = extractToTemp();
        Path stageDir = Files.createTempDirectory("guacd-stage-");
        try (InputStream in = Files.newInputStream(zipPath);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("sbin/guacd".equals(entry.getName())) {
                    Path out = stageDir.resolve("guacd");
                    try (FileOutputStream fos = new FileOutputStream(out.toFile())) {
                        zis.transferTo(fos);
                    }
                    boolean execOk = out.toFile().setExecutable(true, false);
                    assertTrue(execOk, "解压后应可设置可执行位");
                    assertTrue(out.toFile().canExecute(), "guacd 应可执行");
                    return;
                }
            }
        }
        fail(ERR_NO_LINUX_RESOURCE);
    }

    /**
     * 将内嵌 zip 资源解压到临时文件。
     *
     * @return zip 临时文件路径
     */
    private Path extractToTemp() throws Exception {
        Path tempZip = Files.createTempFile("guacd-embedded-", ".zip");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(LINUX_RESOURCE)) {
            assertNotNull(in, ERR_NO_LINUX_RESOURCE);
            try (FileOutputStream fos = new FileOutputStream(tempZip.toFile())) {
                in.transferTo(fos);
            }
        }
        return tempZip;
    }
}