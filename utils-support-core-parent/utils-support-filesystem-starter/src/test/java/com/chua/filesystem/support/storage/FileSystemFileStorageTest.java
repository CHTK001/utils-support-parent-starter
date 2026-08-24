package com.chua.filesystem.support.storage;

import com.chua.common.support.storage.FileStorage;
import com.chua.common.support.storage.MultipartStorage;
import com.chua.common.support.storage.PartETag;
import com.chua.common.support.storage.request.ExistObjectRequest;
import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.ListObjectRequest;
import com.chua.common.support.storage.request.MultipartUploadPartRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.DeleteObjectResult;
import com.chua.common.support.storage.result.ExistObjectResult;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.result.ListObjectResult;
import com.chua.common.support.storage.result.MultipartPartResult;
import com.chua.common.support.storage.result.ObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileSystemFileStorage} 单元测试。
 *
 * <p>覆盖 {@link FileStorage} 契约的核心操作：
 * 上传、下载、存在性检查、列表（含分页）、删除以及分片上传全流程。
 * 全部基于本地临时目录，不依赖任何外部服务。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class FileSystemFileStorageTest {

    /**
     * 创建指向临时目录的存储实例。
     *
     * @param root 存储根目录
     * @return 文件存储实例
     */
    private FileSystemFileStorage newStorage(Path root) {
        return new FileSystemFileStorage(BucketSetting.builder()
                .bucket(root.toString())
                .build());
    }

    @Test
    @DisplayName("SPI 工厂应能通过名称 filesystem 创建本地文件存储")
    void shouldCreateStorageViaSpi(@TempDir Path root) {
        FileStorage storage = FileStorage.createStorage("filesystem", BucketSetting.builder()
                .bucket(root.resolve("spi").toString())
                .build());

        assertNotNull(storage, "SPI 未注册 FileSystemFileStorage，请补充 META-INF/extensions 配置");
        assertDoesNotThrow(storage::close);
    }

    @Test
    @DisplayName("上传后下载内容应保持一致")
    void shouldPutThenGetObject(@TempDir Path root) {
        try (FileSystemFileStorage storage = newStorage(root)) {
            byte[] data = "hello filestorage".getBytes();

            PutObjectResult put = storage.putObject(PutObjectRequest.builder()
                    .fileName("hello.txt")
                    .content(data)
                    .build());

            assertEquals(ObjectResult.ResultCode.SUCCESS, put.getResultCode(), "上传应成功");
            assertEquals("hello.txt", put.getKey(), "无子目录时 Key 应为纯文件名");

            GetObjectResult get = storage.getObject("hello.txt");
            assertEquals(ObjectResult.ResultCode.SUCCESS, get.getResultCode(), "下载应成功");
            assertNotNull(get.getInputStream(), "下载结果应包含输入流");

            byte[] actual = get.getInputStream().readAllBytes();
            assertArrayEquals(data, actual, "下载内容应与上传内容一致");
            assertNotNull(get.getMetadata(), "下载结果应包含元数据");
            assertEquals(data.length, get.getMetadata().getSize(), "元数据大小应一致");
        } catch (Exception e) {
            throw new IllegalStateException("测试执行失败", e);
        }
    }

    @Test
    @DisplayName("带子目录上传后应可通过路径 Key 下载")
    void shouldSupportSubDirectory(@TempDir Path root) {
        try (FileSystemFileStorage storage = newStorage(root)) {
            byte[] data = "nested content".getBytes();

            PutObjectResult put = storage.putObject(PutObjectRequest.builder()
                    .filePath("docs/2026")
                    .fileName("report.txt")
                    .content(data)
                    .build());

            assertEquals(ObjectResult.ResultCode.SUCCESS, put.getResultCode());
            assertEquals("docs/2026/report.txt", put.getKey(), "Key 应为 路径/文件名 格式");
            assertTrue(Files.exists(root.resolve("docs").resolve("2026").resolve("report.txt")),
                    "物理文件应写入对应子目录");

            GetObjectResult byKey = storage.getObject("docs/2026/report.txt");
            assertEquals(ObjectResult.ResultCode.SUCCESS, byKey.getResultCode());
            assertArrayEquals(data, byKey.getInputStream().readAllBytes());

            GetObjectResult byRequest = storage.getObject(GetObjectRequest.builder()
                    .filePath("docs/2026")
                    .fileName("report.txt")
                    .build());
            assertEquals(ObjectResult.ResultCode.SUCCESS, byRequest.getResultCode());
            assertArrayEquals(data, byRequest.getInputStream().readAllBytes());
        } catch (Exception e) {
            throw new IllegalStateException("测试执行失败", e);
        }
    }

    @Test
    @DisplayName("支持从本地文件与输入流两种方式上传")
    void shouldPutFromFileAndInputStream(@TempDir Path root) throws Exception {
        try (FileSystemFileStorage storage = newStorage(root)) {
            Path sourceFile = Files.createTempFile("fst-", ".bin");
            byte[] data = new byte[]{1, 2, 3, 4, 5};
            Files.write(sourceFile, data);

            PutObjectResult fromFile = storage.putObject(PutObjectRequest.builder()
                    .file(sourceFile.toFile())
                    .build());
            assertEquals(ObjectResult.ResultCode.SUCCESS, fromFile.getResultCode(), "File 方式上传应成功");

            PutObjectResult fromStream = storage.putObject(PutObjectRequest.builder()
                    .fileName("stream.bin")
                    .inputStream(new ByteArrayInputStream(data))
                    .build());
            assertEquals(ObjectResult.ResultCode.SUCCESS, fromStream.getResultCode(), "InputStream 方式上传应成功");

            assertArrayEquals(data,
                    storage.getObject("stream.bin").getInputStream().readAllBytes());
        }
    }

    @Test
    @DisplayName("存在性检查应正确反映文件状态")
    void shouldCheckExistence(@TempDir Path root) {
        try (FileSystemFileStorage storage = newStorage(root)) {
            ExistObjectResult before = storage.existObject(ExistObjectRequest.builder()
                    .fileName("check.txt")
                    .build());
            assertFalse(before.isExists(), "上传前文件不应存在");

            storage.putObject(PutObjectRequest.builder()
                    .fileName("check.txt")
                    .content("exist".getBytes())
                    .build());

            ExistObjectResult after = storage.existObject(ExistObjectRequest.builder()
                    .fileName("check.txt")
                    .build());
            assertTrue(after.isExists(), "上传后文件应存在");
        } catch (Exception e) {
            throw new IllegalStateException("测试执行失败", e);
        }
    }

    @Test
    @DisplayName("列表应返回目录内文件并支持分页")
    void shouldListObjectsWithPaging(@TempDir Path root) {
        try (FileSystemFileStorage storage = newStorage(root)) {
            for (int i = 1; i <= 5; i++) {
                storage.putObject(PutObjectRequest.builder()
                        .fileName("file-" + i + ".txt")
                        .content(("content-" + i).getBytes())
                        .build());
            }

            ListObjectResult page1 = storage.listObject(ListObjectRequest.builder().limit(3).build());
            assertEquals(ObjectResult.ResultCode.SUCCESS, page1.getResultCode());
            assertEquals(3, page1.getMetadata().size(), "第一页数量应为 limit 值");
            assertNotNull(page1.getMarker(), "还有剩余数据时应返回下一页标记");
            assertFalse(page1.getMetadata().get(0).isDirectory(),
                    "普通文件不应标记为目录");

            ListObjectResult page2 = storage.listObject(ListObjectRequest.builder()
                    .limit(10)
                    .marker(page1.getMarker())
                    .build());
            assertEquals(ObjectResult.ResultCode.SUCCESS, page2.getResultCode());
            assertEquals(2, page2.getMetadata().size(), "第二页应为剩余 2 个文件");
            assertNull(page2.getMarker(), "全部取完后不应再有分页标记");

            List<String> names = new ArrayList<>();
            page2.getMetadata().forEach(metadata -> names.add(metadata.getName()));
            assertTrue(names.contains("file-4.txt"), "第二页应包含第 4 个文件");
            assertTrue(names.contains("file-5.txt"), "第二页应包含第 5 个文件");
        } catch (Exception e) {
            throw new IllegalStateException("测试执行失败", e);
        }
    }

    @Test
    @DisplayName("删除后文件应不存在且可重复删除")
    void shouldDeleteObject(@TempDir Path root) {
        try (FileSystemFileStorage storage = newStorage(root)) {
            storage.putObject(PutObjectRequest.builder()
                    .fileName("gone.txt")
                    .content("delete me".getBytes())
                    .build());

            DeleteObjectResult first = storage.deleteObject("gone.txt");
            assertEquals(ObjectResult.ResultCode.SUCCESS, first.getResultCode(), "首次删除应成功");
            assertFalse(storage.existObject(ExistObjectRequest.builder().fileName("gone.txt").build()).isExists(),
                    "删除后文件不应存在");

            DeleteObjectResult second = storage.deleteObject("gone.txt");
            assertEquals(ObjectResult.ResultCode.SUCCESS, second.getResultCode(), "重复删除也应成功");
        } catch (Exception e) {
            throw new IllegalStateException("测试执行失败", e);
        }
    }

    @Test
    @DisplayName("下载不存在的文件应返回 FAILURE 且携带提示信息")
    void shouldFailWhenGetNotExistObject(@TempDir Path root) {
        try (FileSystemFileStorage storage = newStorage(root)) {
            GetObjectResult result = storage.getObject("no-such-file.txt");

            assertEquals(ObjectResult.ResultCode.FAILURE, result.getResultCode(), "下载不存在的文件应失败");
            assertNotNull(result.getMessage(), "失败结果应携带提示信息");
        } catch (Exception e) {
            throw new IllegalStateException("测试执行失败", e);
        }
    }

    @Test
    @DisplayName("分片上传完成后应合并为完整文件")
    void shouldCompleteMultipartUpload(@TempDir Path root) {
        try (FileSystemFileStorage storage = newStorage(root)) {
            MultipartStorage multipart = storage.createMultipartStorage();

            MultipartPartResult init = multipart.initiate(PutObjectRequest.builder()
                    .fileName("merged.bin")
                    .build());
            assertEquals(ObjectResult.ResultCode.SUCCESS, init.getResultCode(), "初始化应成功");
            assertNotNull(init.getUploadId(), "初始化应返回 uploadId");

            List<PartETag> parts = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                byte[] chunk = new byte[]{(byte) i, (byte) i, (byte) i};
                MultipartPartResult partResult = multipart.uploadPart(MultipartUploadPartRequest.builder()
                        .uploadId(init.getUploadId())
                        .partNumber(i)
                        .content(chunk)
                        .isLastPart(i == 3)
                        .build());
                assertEquals(ObjectResult.ResultCode.SUCCESS, partResult.getResultCode(),
                        "第 " + i + " 片上传应成功");
                assertEquals(i, partResult.getPartNumber());
                parts.add(new PartETag(i, "etag-" + i));
            }

            PutObjectResult complete = multipart.complete(init.getUploadId(), parts);
            assertEquals(ObjectResult.ResultCode.SUCCESS, complete.getResultCode(), "合并完成应成功");

            byte[] expected = new byte[]{1, 1, 1, 2, 2, 2, 3, 3, 3};
            byte[] actual = storage.getObject("merged.bin").getInputStream().readAllBytes();
            assertArrayEquals(expected, actual, "合并后的内容应为各分片按序拼接");
        } catch (Exception e) {
            throw new IllegalStateException("测试执行失败", e);
        }
    }

    @Test
    @DisplayName("取消不存在的分片任务也应返回成功")
    void shouldAbortMultipartUploadGracefully(@TempDir Path root) {
        try (FileSystemFileStorage storage = newStorage(root)) {
            MultipartStorage multipart = storage.createMultipartStorage();

            DeleteObjectResult abortUnknown = multipart.abort("not-exist-upload-id");
            assertEquals(ObjectResult.ResultCode.SUCCESS, abortUnknown.getResultCode(),
                    "取消未知任务应幂等成功");
        } catch (Exception e) {
            throw new IllegalStateException("测试执行失败", e);
        }
    }
}
