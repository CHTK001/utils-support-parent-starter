package com.chua.filesystem.support.storage;

import com.chua.common.support.storage.request.GetObjectRequest;
import com.chua.common.support.storage.request.PutObjectRequest;
import com.chua.common.support.storage.result.GetObjectResult;
import com.chua.common.support.storage.result.ObjectResult;
import com.chua.common.support.storage.result.PutObjectResult;
import com.chua.common.support.storage.setting.BucketSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 存储根目录的路径穿越防护。
 *
 * <p>这是真实漏洞的回归测试，不是假想。{@code FileSystemFileStorage.resolvePath}
 * 原本只做 {@code basePath.resolve(key).normalize()}，没有根目录包含校验，而
 * {@code normalize()} <b>不会消除开头的 {@code ..}</b>：{@code ../../../../etc/passwd}
 * 归一后依然指向根目录之外。put / get / delete / list 四个调用方都走这一个方法，
 * 因此这是一条<b>任意文件读</b>（写、删同理）的通道，而 key 来自 HTTP 请求路径。</p>
 *
 * <p>同仓的 {@code LocalFileStorage} 一直有 {@code resolved.startsWith(rootPath)} 校验，
 * 本类却没有 —— 同一件事两处实现不一致，正是这类漏洞的典型来源。</p>
 *
 * <p>用 JUnit 临时目录而不是 mock：漏洞本质是文件系统路径解析，只有真实文件系统
 * 才能证伪。断言同时覆盖「越界必须失败」与「正常 key 必须照常工作」，因为加安全校验
 * 最常见的事故就是把合法路径一起拒掉。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
class FileSystemFileStorageTraversalTest {

    /**
     * 按指定根目录构造存储实例。
     *
     * <p>只填 endpoint、不填 bucket：{@code FileSystemFileStorage} 取根目录时
     * <b>bucket 优先</b>，而 bucket 在这里是当作目录路径用的。若填了 bucket，
     * 根目录会变成一个相对目录名，临时目录就完全没参与，测试会静默失去意义。</p>
     *
     * @param root 存储根目录
     * @return 存储实例
     */
    private static FileSystemFileStorage storageAt(Path root) {
        return new FileSystemFileStorage(BucketSetting.builder().endpoint(root.toString()).build());
    }

    /**
     * 在 base 下建一个存储根，并返回该实例。
     *
     * @param base 存储根的上级目录
     * @return 存储实例
     */
    private static FileSystemFileStorage storageUnder(Path base) {
        return storageAt(base.resolve("root"));
    }

    /**
     * 以纯文件名（不带 filePath）构造读取请求。
     *
     * <p>刻意只填 fileName：{@code buildKey} 在 filePath 为空时直接返回 fileName，
     * 这样穿越片段才会原样进入 key。若顺手填了 filePath，会被拼成
     * {@code filePath + "/" + fileName}，测的就不是同一条路径了。</p>
     *
     * @param key 文件名（可含穿越片段）
     * @return 读取请求
     */
    private static GetObjectRequest get(String key) {
        return GetObjectRequest.builder().fileName(key).build();
    }

    /**
     * 以纯文件名（不带 filePath）构造写入请求。
     *
     * @param key     文件名（可含穿越片段）
     * @param content 内容
     * @return 写入请求
     */
    private static PutObjectRequest put(String key, String content) {
        return PutObjectRequest.builder()
                .fileName(key)
                .content(content.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 断言读取失败且没有可读流。
     *
     * @param storage 存储实例
     * @param key     文件名
     */
    private static void assertReadDenied(FileSystemFileStorage storage, String key) {
        GetObjectResult result = storage.getObject(get(key));
        assertEquals(ObjectResult.ResultCode.FAILURE, result.getResultCode(),
                "key=" + key + " 不该被放行");
        assertNull(result.getInputStream(),
                "key=" + key + " 失败时不得同时给出可读流，否则上层照样会把内容发出去");
    }

    /**
     * 正常读出内容并断言文本一致。
     *
     * @param storage 存储实例
     * @param key     文件名
     * @param expect  期望内容
     * @throws Exception 读取失败
     */
    private static void assertReads(FileSystemFileStorage storage, String key, String expect) throws Exception {
        GetObjectResult result = storage.getObject(get(key));
        assertEquals(ObjectResult.ResultCode.SUCCESS, result.getResultCode(),
                "合法路径必须可读，实际=" + result.getMessage());
        try (InputStream in = result.getInputStream()) {
            assertNotNull(in);
            assertEquals(expect, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("越界的 key 必须被拒绝，且不得读到根目录之外的文件")
    void traversalOutsideRootIsRejected(@TempDir Path base) throws Exception {
        // 在根目录之外放一个「敏感文件」，穿越成功就能读到它
        Path secret = base.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET", StandardCharsets.UTF_8);

        assertReadDenied(storageUnder(base), "../secret.txt");
    }

    @Test
    @DisplayName("多层穿越与混合写法同样必须被拒绝")
    void deepTraversalIsRejected(@TempDir Path base) throws Exception {
        Files.writeString(base.resolve("secret.txt"), "TOP-SECRET", StandardCharsets.UTF_8);
        FileSystemFileStorage storage = storageUnder(base);

        for (String attack : new String[]{"../secret.txt", "../../secret.txt", "../../../secret.txt",
                "a/../../secret.txt", "./../../secret.txt", "../root/../secret.txt"}) {
            assertReadDenied(storage, attack);
        }
    }

    @Test
    @DisplayName("反斜杠形式的穿越也必须被拒绝（只挡正斜杠，Windows 上换个分隔符就绕过了）")
    void backslashTraversalIsRejected(@TempDir Path base) throws Exception {
        Files.writeString(base.resolve("secret.txt"), "TOP-SECRET", StandardCharsets.UTF_8);

        assertReadDenied(storageUnder(base), "..\\secret.txt");
    }

    @Test
    @DisplayName("写入也不得越界，否则等价于往任意路径落文件")
    void traversalOnWriteIsRejected(@TempDir Path base) throws Exception {
        Path secret = base.resolve("secret.txt");
        Files.writeString(secret, "ORIGINAL", StandardCharsets.UTF_8);

        PutObjectResult result = storageUnder(base).putObject(put("../secret.txt", "OVERWRITTEN"));

        assertEquals(ObjectResult.ResultCode.FAILURE, result.getResultCode(), "越界写入必须失败");
        assertEquals("ORIGINAL", Files.readString(secret, StandardCharsets.UTF_8),
                "根目录外的文件绝不能被改写");
    }

    @Test
    @DisplayName("正常读写必须照常工作（防止把安全校验写成把所有路径都拒掉）")
    void normalOperationStillWorks(@TempDir Path base) throws Exception {
        FileSystemFileStorage storage = storageUnder(base);

        assertEquals(ObjectResult.ResultCode.SUCCESS,
                storage.putObject(put("sub/dir/a.txt", "hello")).getResultCode(), "正常写入应成功");
        assertReads(storage, "sub/dir/a.txt", "hello");

        assertEquals(ObjectResult.ResultCode.SUCCESS,
                storage.putObject(put("b.txt", "bee")).getResultCode(), "根目录直属写入应成功");
        assertReads(storage, "b.txt", "bee");

        // 名字里带 ".." 但并未越界的片段是合法文件名，不能被误伤
        assertEquals(ObjectResult.ResultCode.SUCCESS,
                storage.putObject(put("x..y/c.txt", "cee")).getResultCode(),
                "文件名里的 .. 属于合法字符，不能当成穿越");
        assertReads(storage, "x..y/c.txt", "cee");

        // "a/../b" 归一后仍在根内，属于合法访问
        assertEquals(ObjectResult.ResultCode.SUCCESS,
                storage.putObject(put("a/../d.txt", "dee")).getResultCode(),
                "归一后仍在根内的路径应放行");
        assertReads(storage, "d.txt", "dee");
    }

    @Test
    @DisplayName("存储根本身含 .. 时，正常 key 仍必须可读（包含校验不能误伤）")
    void rootContainingDotDotStillResolves(@TempDir Path base) throws Exception {
        // 构造 base/a/../root 这种形态的根路径。basePath 若不 normalize，
        // 它会保持 "/a/../root" 的字面形态，而 resolvePath 返回的是已归一的
        // "/root"，两者做 startsWith 字符串比较必然不成立 —— 结果是合法 key 全被拒。
        // 这正是加安全校验时最容易顺手弄坏的地方，所以要单独钉一条。
        Path real = base.resolve("root");
        Files.createDirectories(real);
        Path odd = base.resolve("a").resolve("..").resolve("root");

        FileSystemFileStorage storage = storageAt(odd);

        PutObjectResult putResult = storage.putObject(put("ok.txt", "fine"));
        assertEquals(ObjectResult.ResultCode.SUCCESS, putResult.getResultCode(),
                "根路径含 .. 不该导致合法写入失败：" + putResult.getMessage());
        assertTrue(Files.exists(real.resolve("ok.txt")), "文件应落在真实根目录下");
        assertReads(storage, "ok.txt", "fine");
    }

    @Test
    @DisplayName("缺失文件仍报「不存在」而不是「越界」，两类失败必须可区分")
    void missingFileIsNotReportedAsTraversal(@TempDir Path base) {
        GetObjectResult result = storageUnder(base).getObject(get("no/such/file.txt"));

        String message = String.valueOf(result.getMessage());
        assertEquals(ObjectResult.ResultCode.FAILURE, result.getResultCode());
        assertFalse(message.contains("越出"),
                "缺失文件不该被报成越界，否则排查时会被误导到安全方向：" + message);
        assertTrue(message.contains("不存在"),
                "应保留原有的「文件不存在」语义，实际=" + message);
    }
}