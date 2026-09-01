package com.chua.common.support.file;

import com.chua.common.support.file.impl.TarFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TarFileSystem 分卷压缩功能单元测试。
 */
class TarFileSystemSplitTest {

    @TempDir
    Path tempDir;

    // ==================== 分卷写入测试 ====================

    @Test
    void testSplitWriteSmallFile() throws IOException {
        // 小于 splitSize 的文件应该直接复制为单个文件
        File outputFile = tempDir.resolve("output.tar.gz").toFile();
        File testFile = createTestFile("test.txt", "Hello World");

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(1024 * 1024) // 1MB
            .addFile("test.txt", testFile)
            .finish();

        assertTrue(outputFile.exists(), "Output file should exist");
        assertTrue(outputFile.length() > 0, "Output file should not be empty");
    }

    @Test
    void testSplitWriteMultipleEntries() throws IOException {
        File outputFile = tempDir.resolve("output.tar.gz").toFile();
        File file1 = createTestFile("file1.txt", "Content 1");
        File file2 = createTestFile("file2.txt", "Content 2");
        byte[] bytes3 = "Content 3".getBytes(StandardCharsets.UTF_8);

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(1024)
            .addFile("dir/file1.txt", file1)
            .addFile("dir/file2.txt", file2)
            .addBytes("dir/file3.txt", bytes3)
            .finish();

        assertTrue(outputFile.exists());
    }

    @Test
    void testSplitWriteWithGzip() throws IOException {
        File outputFile = tempDir.resolve("output.tar.gz").toFile();
        File testFile = createTestFile("test.txt", "GZIP compressed content");

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .gz(Deflater.BEST_COMPRESSION)
            .splitSize(1024)
            .addFile("test.txt", testFile)
            .finish();

        assertTrue(outputFile.exists());
    }

    // ==================== 分卷读取测试 ====================

    @Test
    void testSplitReadRoundTrip() throws IOException {
        // 创建测试数据
        File sourceDir = tempDir.resolve("source").toDir();
        sourceDir.mkdirs();
        createTestFileInDir(sourceDir, "a.txt", "File A content");
        createTestFileInDir(sourceDir, "b.txt", "File B content");
        createTestFileInDir(sourceDir, "c.txt", "File C content");

        // 创建分卷
        File outputFile = tempDir.resolve("archive.tar.gz").toFile();
        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(200) // 小分卷大小以触发分卷
            .addFile("a.txt", new File(sourceDir, "a.txt"))
            .addFile("b.txt", new File(sourceDir, "b.txt"))
            .addFile("c.txt", new File(sourceDir, "c.txt"))
            .finish();

        // 验证分卷文件被创建
        File[] splitFiles = tempDir.toFile().listFiles((dir, name) -> name.startsWith("archive.tar.gz."));
        assertNotNull(splitFiles);
        assertTrue(splitFiles.length > 0, "Should have split files");
        System.out.println("Split files created: " + splitFiles.length);

        // 读取分卷
        File extractDir = tempDir.resolve("extracted").toDir();
        tar.read(outputFile)
            .gz()
            .split()
            .extractAll(extractDir);

        // 验证提取结果 - 包括文件存在性和内容完整性
        assertTrue(new File(extractDir, "a.txt").exists());
        assertTrue(new File(extractDir, "b.txt").exists());
        assertTrue(new File(extractDir, "c.txt").exists());
        assertEquals("File A content", Files.readString(new File(extractDir, "a.txt").toPath()));
        assertEquals("File B content", Files.readString(new File(extractDir, "b.txt").toPath()));
        assertEquals("File C content", Files.readString(new File(extractDir, "c.txt").toPath()));
    }

    @Test
    void testSplitReadListEntries() throws IOException {
        File outputFile = tempDir.resolve("archive.tar.gz").toFile();
        File testFile = createTestFile("test.txt", "content");

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(100)
            .addFile("dir/file1.txt", testFile)
            .addBytes("dir/file2.txt", "data".getBytes())
            .finish();

        List<String> entries = tar.read(outputFile)
            .gz()
            .split()
            .listEntries();

        assertEquals(2, entries.size());
        assertTrue(entries.contains("dir/file1.txt"));
        assertTrue(entries.contains("dir/file2.txt"));
    }

    @Test
    void testSplitReadExtractSpecificEntry() throws IOException {
        File outputFile = tempDir.resolve("archive.tar.gz").toFile();
        File testFile = createTestFile("test.txt", "specific content");

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(100)
            .addFile("target.txt", testFile)
            .addFile("other.txt", createTestFile("other.txt", "other"))
            .finish();

        File extractDir = tempDir.resolve("extract-specific").toDir();
        tar.read(outputFile)
            .gz()
            .split()
            .extract("target.txt", extractDir);

        assertTrue(new File(extractDir, "target.txt").exists());
        assertFalse(new File(extractDir, "other.txt").exists());
        assertEquals("specific content", Files.readString(new File(extractDir, "target.txt").toPath()));
    }

    @Test
    void testSplitReadExtractMultipleEntries() throws IOException {
        // 测试使用 varargs 提取多个指定条目
        File outputFile = tempDir.resolve("archive.tar.gz").toFile();
        File testFile1 = createTestFile("file1.txt", "Entry 1 content");
        File testFile2 = createTestFile("file2.txt", "Entry 2 content");
        File testFile3 = createTestFile("file3.txt", "Entry 3 content");

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(100)
            .addFile("file1.txt", testFile1)
            .addFile("file2.txt", testFile2)
            .addFile("file3.txt", testFile3)
            .finish();

        File extractDir = tempDir.resolve("extract-multi").toDir();
        tar.read(outputFile)
            .gz()
            .split()
            .extract(extractDir, "file1.txt", "file3.txt");

        // 只有指定的文件被提取
        assertTrue(new File(extractDir, "file1.txt").exists());
        assertFalse(new File(extractDir, "file2.txt").exists());
        assertTrue(new File(extractDir, "file3.txt").exists());
        assertEquals("Entry 1 content", Files.readString(new File(extractDir, "file1.txt").toPath()));
        assertEquals("Entry 3 content", Files.readString(new File(extractDir, "file3.txt").toPath()));
    }

    @Test
    void testSplitReadEntryAsString() throws IOException {
        File outputFile = tempDir.resolve("archive.tar.gz").toFile();
        String content = "Read entry content test";
        File testFile = createTestFile("test.txt", content);

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(100)
            .addFile("test.txt", testFile)
            .finish();

        String result = tar.read(outputFile)
            .gz()
            .split()
            .readEntry("test.txt");

        assertEquals(content, result);
    }

    @Test
    void testSplitReadDataIntegrity() throws IOException {
        // 测试大数据量的完整性验证 - 确保分卷不会丢失数据
        // 使用无 GZIP 模式避免压缩影响分卷触发
        File outputFile = tempDir.resolve("archive.tar").toFile();
        
        // 创建包含丰富内容的数据以确保不会被压缩后过小
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("Line ").append(String.format("%04d", i)).append(": ")
              .append("The quick brown fox jumps over the lazy dog. ")
              .append("Pack my box with five dozen liquor jugs.\n");
        }
        String originalContent = sb.toString();
        File testFile = createTestFile("large.txt", originalContent);

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .splitSize(1000) // 使用合理分卷大小
            .addFile("large.txt", testFile)
            .finish();

        // 验证分卷文件数量
        File[] splitFiles = tempDir.toFile().listFiles((dir, name) -> name.startsWith("archive.tar."));
        assertNotNull(splitFiles);
        System.out.println("Split files for large data: " + splitFiles.length);

        // 计算分卷总大小
        long totalSplitSize = 0;
        for (File f : splitFiles) {
            totalSplitSize += f.length();
        }
        System.out.println("Total split size: " + totalSplitSize + " bytes");

        // 读取并验证内容完整性（无 GZIP）
        File extractDir = tempDir.resolve("integrity-test").toDir();
        tar.read(outputFile)
            .split()
            .extractAll(extractDir);

        File extractedFile = new File(extractDir, "large.txt");
        assertTrue(extractedFile.exists());
        String extractedContent = Files.readString(extractedFile.toPath());
        assertEquals(originalContent.length(), extractedContent.length(), "Content length should match");
        assertEquals(originalContent, extractedContent, "Content should be identical");
    }

    // ==================== 边界情况测试 ====================

    @Test
    void testSplitWriteExactSplitSize() throws IOException {
        // 文件大小恰好等于 splitSize
        File outputFile = tempDir.resolve("output.tar.gz").toFile();
        byte[] data = new byte[1024];
        Arrays.fill(data, (byte) 'A');
        File testFile = createTestFile("test.bin", new String(data, StandardCharsets.ISO_8859_1));

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(1024)
            .addFile("test.bin", testFile)
            .finish();

        assertTrue(outputFile.exists());
    }

    @Test
    void testSplitWriteLargerThanSplitSize() throws IOException {
        // 文件大小大于 splitSize，应该产生多个分卷
        File outputFile = tempDir.resolve("output.tar.gz").toFile();
        byte[] data = new byte[2048];
        Arrays.fill(data, (byte) 'B');
        File testFile = createTestFile("large.bin", new String(data, StandardCharsets.ISO_8859_1));

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .splitSize(500) // 小于文件大小
            .addFile("large.bin", testFile)
            .finish();

        // 验证分卷文件被创建
        File[] splitFiles = tempDir.toFile().listFiles((dir, name) -> name.startsWith("output.tar.gz.") && !name.equals("output.tar.gz"));
        assertNotNull(splitFiles);
        assertTrue(splitFiles.length > 0, "Should have split files when content exceeds split size");
    }

    @Test
    void testSplitReadNoSplitFiles() throws IOException {
        // 测试没有分卷文件时的读取（只有主文件）
        // 这种情况下 findSplitFiles() 返回只有主文件的列表，
        // 因此会使用普通的 FileInputStream 而不是 MergedInputStream
        File outputFile = tempDir.resolve("single.tar.gz").toFile();
        File testFile = createTestFile("test.txt", "single file content");

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .gz()
            .addFile("test.txt", testFile)
            .finish();

        // 使用 split() 模式读取，但没有分卷文件 - 应该回退到普通读取
        List<String> entries = tar.read(outputFile)
            .gz()
            .split()
            .listEntries();

        assertEquals(1, entries.size());
        assertEquals("test.txt", entries.get(0));
    }

    @Test
    void testSplitReadNonExistentMainFile() throws IOException {
        // 测试主文件不存在的情况
        File nonExistent = tempDir.resolve("nonexistent.tar.gz").toFile();
        FileSystem tar = FileSystem.create("tar");

        // 应该抛出异常
        assertThrows(Exception.class, () -> {
            tar.read(nonExistent).gz().split().listEntries();
        });
    }

    // ==================== 无 GZIP 分卷测试 ====================

    @Test
    void testSplitWriteWithoutGzip() throws IOException {
        File outputFile = tempDir.resolve("output.tar").toFile();
        File testFile = createTestFile("test.txt", "No GZIP content");

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .splitSize(100)
            .addFile("test.txt", testFile)
            .finish();

        assertTrue(outputFile.exists());
    }

    @Test
    void testSplitReadWithoutGzip() throws IOException {
        File outputFile = tempDir.resolve("archive.tar").toFile();
        File testFile = createTestFile("test.txt", "No GZIP content");

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .splitSize(100)
            .addFile("test.txt", testFile)
            .finish();

        List<String> entries = tar.read(outputFile)
            .split()
            .listEntries();

        assertEquals(1, entries.size());
        assertEquals("test.txt", entries.get(0));
    }

    @Test
    void testSplitReadWithoutGzipRoundTrip() throws IOException {
        // 无 GZIP 模式的完整往返测试
        File outputFile = tempDir.resolve("archive.tar").toFile();
        String content = "Round trip without GZIP";
        File testFile = createTestFile("test.txt", content);

        FileSystem tar = FileSystem.create("tar");
        tar.write(outputFile)
            .splitSize(100)
            .addFile("test.txt", testFile)
            .finish();

        File extractDir = tempDir.resolve("extract-no-gzip").toDir();
        tar.read(outputFile)
            .split()
            .extractAll(extractDir);

        assertTrue(new File(extractDir, "test.txt").exists());
        assertEquals(content, Files.readString(new File(extractDir, "test.txt").toPath()));
    }

    // ==================== 辅助方法 ====================

    private File createTestFile(String name, String content) throws IOException {
        File file = tempDir.resolve(name).toFile();
        Files.writeString(file.toPath(), content);
        return file;
    }

    private void createTestFileInDir(File dir, String name, String content) throws IOException {
        File file = new File(dir, name);
        Files.writeString(file.toPath(), content);
    }
}
