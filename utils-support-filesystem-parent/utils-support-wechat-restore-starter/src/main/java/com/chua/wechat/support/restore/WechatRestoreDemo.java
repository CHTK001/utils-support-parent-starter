package com.chua.wechat.support.restore;

import com.chua.common.support.task.restore.DataRestoreConfig;
import com.chua.common.support.task.restore.ExportFormat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 微信聊天记录还原端到端演示.
 *
 * <p>使用模拟数据演示完整还原管道，输出 CSV / SQL / Excel 文件。</p>
 */
public class WechatRestoreDemo {

    /**
     * 程序入口，运行示例自检。
     *
     * @param args 参数，不允许为 null
     * @throws Exception 当执行过程不满足前置条件时
     */
    public static void main(String[] args) throws Exception {
        // 模拟会话数据
        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> msg1 = new HashMap<>();
        msg1.put("id", 1L);
        msg1.put("sender_username", "wxid_xxx");
        msg1.put("message_content", "你好，在吗？");
        msg1.put("create_time", 1700000000L);
        messages.add(msg1);

        Map<String, Object> msg2 = new HashMap<>();
        msg2.put("id", 2L);
        msg2.put("sender_username", "wxid_yyy");
        msg2.put("message_content", "在的，有什么可以帮你的？");
        msg2.put("create_time", 1700000001L);
        messages.add(msg2);

        Map<String, Object> msg3 = new HashMap<>();
        msg3.put("id", 3L);
        msg3.put("sender_username", "wxid_xxx");
        msg3.put("message_content", "帮我看看这个文件");
        msg3.put("create_time", 1700000002L);
        msg3.put("compress_content", "FD2FB52800000001" + "deadbeef".repeat(10));
        messages.add(msg3);

        // 输出目录
        File outputDir = new File("G:/work/wechat-restore-output");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        System.out.println("=== 微信聊天记录还原演示 ===");
        System.out.println("消息总数: " + messages.size());

        // 1. 导出 CSV
        File csvFile = new File(outputDir, "张三.csv");
        WechatExportUtils.writeCsv(csvFile,
                List.of("id", "sender_username", "message_content", "create_time"),
                messages, StandardCharsets.UTF_8);
        System.out.println("CSV 输出: " + csvFile.getAbsolutePath() + " (" + csvFile.length() + " bytes)");

        // 2. 导出 SQL
        String sql = WechatExportUtils.buildSqlScript(messages, "wechat_message", "wechat", true);
        File sqlFile = new File(outputDir, "wechat_message.sql");
        Files.writeString(sqlFile.toPath(), sql, StandardCharsets.UTF_8);
        System.out.println("SQL 输出: " + sqlFile.getAbsolutePath() + " (" + sqlFile.length() + " bytes)");

        // 3. 验证 CSV 内容
        System.out.println("\n--- CSV 内容预览 ---");
        Files.readAllLines(csvFile.toPath()).forEach(System.out::println);

        // 4. 验证 SQL 内容预览
        System.out.println("\n--- SQL 内容预览 ---");
        String[] sqlLines = sql.split("\n");
        for (int i = 0; i < Math.min(10, sqlLines.length); i++) {
            System.out.println(sqlLines[i]);
        }
        System.out.println("... (共 " + sqlLines.length + " 行)");

        // 5. 列出输出目录
        System.out.println("\n--- 输出目录文件 ---");
        File[] files = outputDir.listFiles();
        if (files != null) {
            for (File f : files) {
                System.out.println("  " + f.getName() + " (" + f.length() + " bytes)");
            }
        }

        System.out.println("\n还原完成！文件位于: " + outputDir.getAbsolutePath());
    }
}
