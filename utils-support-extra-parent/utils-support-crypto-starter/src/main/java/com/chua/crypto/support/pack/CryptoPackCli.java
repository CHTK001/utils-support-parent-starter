package com.chua.crypto.support.pack;

import com.chua.crypto.support.Crypto;
import com.chua.crypto.support.KeyPolicy;

import java.util.HashMap;
import java.util.Map;

/**
* 程序包加密命令行工具
*
* <p>用法：
* <pre>
* java -cp &lt;classpath&gt; com.chua.crypto.support.pack.CryptoPackCli
*      --source app.jar                  必填：待加密的可执行 jar
*      --output app-secure.jar           必填：输出路径
*      --policy SERVER_BOUND             密钥策略：SERVER_BOUND(默认) / CUSTOM
*      --pin xxx                         口令：CUSTOM 必填；SERVER_BOUND 可作 pepper
*      --server-id node1                 固定服务器指纹（可选）
*      --encrypt-config                  配置文件随包加密（默认开启）
*      --no-encrypt-config               关闭配置文件加密
*      --obfuscate                       剥离调试信息（默认开启）
*      --rename-privates                 私有成员重命名（默认关闭，反射框架敏感）
* </pre>
*
* <p>classpath 需包含：本模块 jar、utils-support-common-starter、asm、asm-commons、
* guava、javassist、slf4j-api。
*
* @author CH
* @since 2026-08-26
 */
public final class CryptoPackCli {

    /**
    * 私有构造
    */
    private CryptoPackCli() {
    }

    /**
    * CLI 入口
    *
    * @param args 参数
    * @throws Exception 打包失败
    */
    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parse(args);

        String source = opts.get("source");
        String output = opts.get("output");
        if (source == null || output == null) {
            System.err.println("用法: CryptoPackCli --source <app.jar> --output <secure.jar> "
                    + "[--policy SERVER_BOUND|CUSTOM] [--pin xxx] [--server-id id] "
                    + "[--encrypt-config|--no-encrypt-config] [--encrypt-libs|--no-encrypt-libs] "
                    + "[--no-embed-key] [--obfuscate] [--rename-privates]");
            System.exit(2);
            return;
        }

        KeyPolicy policy = KeyPolicy.valueOf(opts.getOrDefault("policy", "SERVER_BOUND"));
        String pin = opts.getOrDefault("pin", "");

        Crypto crypto = Crypto.create()
                .keyPolicy(policy)
                .secret(pin.toCharArray())
                .serverId(opts.get("serverId"))
                .memory()
                .build();

        try {
            JarEncryptor.create()
                    .source(source)
                    .output(output)
                    .crypto(crypto)
                    .encryptConfig(!opts.containsKey("no-encrypt-config"))
                    .encryptLibs(!opts.containsKey("no-encrypt-libs"))
                    .obfuscate(!opts.containsKey("no-obfuscate"))
                    .renamePrivates(opts.containsKey("rename-privates"))
                    .embedKeyBlob(!opts.containsKey("no-embed-key"))
                    .execute();
            System.out.println("[chua-crypto] 加密完成: " + output);
            System.out.println("[chua-crypto] 运行方式: java -jar " + output
                    + ("CUSTOM" == policy.name() ? "  (-Dchua.crypto.pin=xxx)" : ""));
        } finally {
            crypto.close();
        }
    }

    /**
    * 解析 "--键 值" 与布尔开关参数
    *
    * @param args 原始参数
    * @return 键值表（布尔开关值为 "true"）
    */
    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                continue;
            }
            String key = arg.substring(2);
            boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
            if (hasValue) {
                opts.put(key, args[++i]);
            } else {
                opts.put(key, "true");
            }
        }
        return opts;
    }
}
