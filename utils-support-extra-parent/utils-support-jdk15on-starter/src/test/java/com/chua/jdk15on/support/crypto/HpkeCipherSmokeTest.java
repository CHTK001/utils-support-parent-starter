package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.AesCipher;
import com.chua.common.support.lang.algorithm.cipher.Cipher;
import com.chua.common.support.lang.algorithm.cipher.CipherFlow;
import com.chua.common.support.lang.algorithm.cipher.HpkeCipher;
import com.chua.common.support.lang.algorithm.cipher.HpkeFlow;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * HPKE / cipher流 / hpke流 冒烟测试。
 *
 * <p>遵循项目约定使用 {@code main} 方法直接运行（本模块无 JUnit 依赖）：</p>
 * <pre>
 * 运行方式：{@code java com.chua.jdk15on.support.crypto.HpkeCipherSmokeTest}
 * 任一校验失败输出 FAIL 并以退出码 1 结束，全部通过输出 PASS。
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class HpkeCipherSmokeTest {

    /**
     * 失败计数
    */
    private static int failureCount = 0;

    /**
     * 成功计数
    */
    private static int passCount = 0;

    /**
     * main。
     * @param args 参数
     */
    public static void main(String[] args) {
        testKeyPair();
        testPrimitivesRoundTrip();
        testNonceFreshness();
        testFlowChain();
        testIkM();
        testTamperDetection();
        testKdfDeterminism();
        testCipherFlowEntry();
        testAesBuiltIn();

        System.out.println("========================================");
        System.out.println("HpkeCipherSmokeTest 结果: PASS=" + passCount + ", FAIL=" + failureCount);
        if (failureCount > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    /**
     * 密钥对长度
    */
    static void testKeyPair() {
        byte[][] keys = HpkeCipher.create("bc").generateKeyPair();
        check(keys.length == 2, "generateKeyPair 返回 2 段");
        check(keys[0].length == 32, "公钥 32 字节");
        check(keys[1].length == 32, "私钥 32 字节");
    }

    /**
     * 原语 round-trip
    */
    static void testPrimitivesRoundTrip() {
        HpkeCipher hpke = HpkeCipher.create("bc");
        byte[][] keys = hpke.generateKeyPair();
        byte[] plain = "机密数据-roundtrip".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "aad".getBytes(StandardCharsets.UTF_8);

        byte[][] encap = hpke.encap(keys[0], null);
        byte[] ekSender = encap[1];
        byte[] ct = hpke.seal(ekSender, aad, plain);
        byte[] ekReceiver = hpke.recoverKey(keys[1], encap[0], null);
        byte[] out = hpke.open(ekReceiver, aad, ct);
        check(Arrays.equals(plain, out), "原语 round-trip 一致");
        check(Arrays.equals(ekSender, ekReceiver), "收发派生的 ek 一致");
        check(ct.length == plain.length + 12 + 16, "密文信封为 nonce(12) + 密文体 + 标签(16)");
    }

    /**
     * nonce 新鲜度：同一封装密钥重复 seal 不得复用密钥流
    */
    static void testNonceFreshness() {
        HpkeCipher hpke = HpkeCipher.create("bc");
        byte[][] keys = hpke.generateKeyPair();
        byte[] ek = hpke.encap(keys[0], null)[1];
        byte[] plain = "amount=1".getBytes(StandardCharsets.UTF_8);
        byte[] other = "amount=9".getBytes(StandardCharsets.UTF_8);

        byte[] first = hpke.seal(ek, null, plain);
        byte[] again = hpke.seal(ek, null, plain);
        check(!Arrays.equals(first, again), "同一 ek 两次 seal 密文不同（nonce 随消息取随机）");
        check(Arrays.equals(plain, hpke.open(ek, null, first)), "首次密文可正常解开");
        check(Arrays.equals(plain, hpke.open(ek, null, again)), "重复 seal 的密文可独立解开");

        byte[] second = hpke.seal(ek, null, other);
        // 偏移 12 = 信封 nonce 头长度（同组信封长度断言已锁定该布局），比较的是密文体而非标签区
        boolean keystreamReused = true;
        for (int i = 0; i < plain.length && i < other.length; i++) {
            int ctXor = (first[i + 12] ^ second[i + 12]) & 0xff;
            int ptXor = (plain[i] ^ other[i]) & 0xff;
            if (ctXor != ptXor) {
                keystreamReused = false;
                break;
            }
        }
        check(!keystreamReused, "两段密文的异或不等于两段明文的异或（密钥流未被复用）");
    }

    /**
     * hpke流 链式
    */
    static void testFlowChain() {
        byte[][] keys = HpkeFlow.of().keys();
        byte[] plain = "flow".getBytes(StandardCharsets.UTF_8);
        byte[] aad = "aad".getBytes(StandardCharsets.UTF_8);

        HpkeFlow.SealedMessage msg = HpkeFlow.of().receiverPk(keys[0]).aad(aad).seal(plain);
        byte[] out = HpkeFlow.of().secretKey(keys[1]).enc(msg.enc()).aad(aad).open(msg.ciphertext());
        check(Arrays.equals(plain, out), "HpkeFlow 链式收发一致");
        check(msg.enc().length == 32, "SealedMessage.enc 为 32 字节封装密钥");
    }

    /**
     * ikm 参与派生
    */
    static void testIkM() {
        HpkeFlow flow = HpkeFlow.of();
        byte[][] keys = flow.keys();
        byte[] ikm = "psk".getBytes(StandardCharsets.UTF_8);
        byte[] plain = "ikm-data".getBytes(StandardCharsets.UTF_8);

        HpkeFlow.SealedMessage msg = flow.receiverPk(keys[0]).ikm(ikm).seal(plain);
        byte[] out = flow.secretKey(keys[1]).enc(msg.enc()).ikm(ikm).open(msg.ciphertext());
        check(Arrays.equals(plain, out), "ikm 一致时解密成功");

        // ikm 不一致必须失败
        boolean threw = false;
        try {
            flow.secretKey(keys[1]).enc(msg.enc()).ikm("other-psk".getBytes()).open(msg.ciphertext());
        } catch (Exception e) {
            threw = true;
        }
        check(threw, "ikm 不一致时解密失败（GCM 校验）");
    }

    /**
     * 篡改检测
    */
    static void testTamperDetection() {
        HpkeFlow flow = HpkeFlow.of();
        byte[][] keys = flow.keys();
        byte[] aad = "aad".getBytes(StandardCharsets.UTF_8);
        byte[] plain = "tamper".getBytes(StandardCharsets.UTF_8);
        HpkeFlow.SealedMessage msg = flow.receiverPk(keys[0]).aad(aad).seal(plain);

        // 篡改 aad
        boolean threwAad = false;
        try {
            flow.secretKey(keys[1]).enc(msg.enc()).aad("wrong".getBytes()).open(msg.ciphertext());
        } catch (Exception e) {
            threwAad = true;
        }
        check(threwAad, "篡改 aad 被检测到");

        // 篡改密文
        byte[] corrupted = msg.ciphertext().clone();
        corrupted[0] ^= 0xFF;
        boolean threwCt = false;
        try {
            flow.secretKey(keys[1]).enc(msg.enc()).aad(aad).open(corrupted);
        } catch (Exception e) {
            threwCt = true;
        }
        check(threwCt, "篡改密文被检测到");
    }

    /**
     * KDF 幂等性
    */
    static void testKdfDeterminism() {
        HpkeCipher hpke = HpkeCipher.create("bc");
        byte[][] keys = hpke.generateKeyPair();
        byte[] ikm = "ikm".getBytes(StandardCharsets.UTF_8);
        byte[][] e1 = hpke.encap(keys[0], ikm);
        byte[] ekA = hpke.recoverKey(keys[1], e1[0], ikm);
        byte[] ekB = hpke.recoverKey(keys[1], e1[0], ikm);
        check(Arrays.equals(ekA, ekB), "相同输入 recoverKey 幂等");
        check(ekA.length == 32, "对称密钥 ek 为 32 字节（nonce 由每次 seal 现取，不参与派生）");
    }

    /**
     * cipher流 统一入口
    */
    static void testCipherFlowEntry() {
        HpkeFlow hf = CipherFlow.of("hpke").hpkeFlow();
        byte[][] keys = hf.keys();
        byte[] plain = "cipherflow".getBytes(StandardCharsets.UTF_8);
        HpkeFlow.SealedMessage msg = hf.receiverPk(keys[0]).seal(plain);
        byte[] out = hf.secretKey(keys[1]).enc(msg.enc()).open(msg.ciphertext());
        check(Arrays.equals(plain, out), "CipherFlow.hpkeFlow 收发一致");

        Cipher aes = CipherFlow.of("aes").resolve();
        check(aes instanceof AesCipher, "resolve(\"aes\") 返回 AesCipher");
    }

    /**
     * AES 内置
    */
    static void testAesBuiltIn() {
        AesCipher aes = CipherFlow.of("aes").aes();
        byte[] key = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        byte[] data = "aes-data".getBytes(StandardCharsets.UTF_8);
        byte[] ct = aes.encrypt(key, data);
        byte[] pt = aes.decrypt(key, ct);
        check(Arrays.equals(data, pt), "CipherFlow.aes() round-trip 一致");
    }

    /**
     * 校验并计数
     *
     * @param condition 条件
     * @param message 消息
     */
    private static void check(boolean condition, String message) {
        if (condition) {
            passCount++;
            System.out.println("[PASS] " + message);
        } else {
            failureCount++;
            System.out.println("[FAIL] " + message);
        }
    }
}
