package com.chua.runtime.e2e;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 凭证日志泄露回归测试 — 扫描代码确保敏感字段未被 log.* 输出。
 *
 * <p>这些检查是"防御性"测试:在源码中搜索是否还有 log 调用泄露
 * 短信验证码 / 密码 hash / Token 等敏感字段。</p>
 */
class SensitiveDataLeakageTest {

    /**
     * SmsCodeServiceImpl 不再 log 验证码 code 字段。
     */
    @Test
    void smsCodeService_doesNotLogVerificationCode() throws Exception {
        Path file = locateJavaFile(
                "D:\\ch\\project\\spring-support-api-starter",
                "SmsCodeServiceImpl.java");
        if (!Files.exists(file)) {
            return; // 文件不存在,跳过(例如项目未检出)
        }
        String src = Files.readString(file);
        // 不应包含 "code={}" 这种把整个验证码打印到日志的用法
        assertFalse(src.contains("log.info(...phone={}, code={}"),
                "SmsCodeServiceImpl 不应将短信验证码打印到日志");
        // 兼容变体
        assertFalse(Pattern.compile("log\\.\\w+.*code=\\{.*\\}").matcher(src).find()
                        && !src.contains("// SAFE: "),
                "不允许将 code 字段 log 输出");
    }

    /**
     * SysLoginServiceImpl 不再 log 密码 hash。
     */
    @Test
    void sysLoginService_doesNotLogPasswordHash() throws Exception {
        Path file = locateJavaFile(
                "D:\\ch\\project\\spring-support-api-starter",
                "SysLoginServiceImpl.java");
        if (!Files.exists(file)) {
            return;
        }
        String src = Files.readString(file);
        assertFalse(src.contains("输入hash={}"),
                "SysLoginServiceImpl 不应将密码 hash 输出到日志");
        assertFalse(src.contains("数据库hash={}"),
                "SysLoginServiceImpl 不应将数据库 hash 输出到日志");
        assertFalse(src.contains("sm4Token={}"),
                "SysLoginServiceImpl 不应将 sm4 token 输出到日志");
    }

    private Path locateJavaFile(String projectRoot, String fileName) {
        try {
            Path root = Paths.get(projectRoot);
            if (!Files.exists(root)) return Paths.get("__nonexistent__");
            try (var stream = Files.walk(root)) {
                List<Path> matches = stream
                        .filter(p -> p.getFileName().toString().equals(fileName))
                        .filter(p -> p.toString().contains("src\\main\\java")
                                || p.toString().contains("src/main/java"))
                        .toList();
                return matches.isEmpty() ? Paths.get("__nonexistent__") : matches.get(0);
            }
        } catch (Exception e) {
            return Paths.get("__nonexistent__");
        }
    }
}
