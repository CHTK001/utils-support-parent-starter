package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.RsaCipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
   * 基于 bouncycastle 的 RSA 非对称加解密实现
 *
 * <p>通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者
 * 实现 RSA 密钥生成、加密、解密、签名和验签。
   * 支持 1024/2048/4096 位密钥长度，签名算法采用 SHA256withrsa。
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi({"bc", "bouncycastle"})
public class BcRsaCipher implements RsaCipher {

    /** 提供者 */
    private static final String PROVIDER = "BC";
    /** 键_algorithm */
    private static final String KEY_ALGORITHM = "RSA";
    /** Cipher_algorithm */
    private static final String CIPHER_ALGORITHM = "RSA/ECB/PKCS1Padding";
    /** 签名_algorithm */
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    /** generate键pair */
    public KeyPair generateKeyPair(int keySize) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(KEY_ALGORITHM, PROVIDER);
            gen.initialize(keySize, new SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("RSA 密钥对生成失败", e);
        }
    }

    @Override
    /** Encrypt */
    public byte[] encrypt(byte[] publicKey, byte[] data) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM, PROVIDER);
            PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKey));
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(CIPHER_ALGORITHM, PROVIDER);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, pubKey);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("RSA 加密失败", e);
        }
    }

    @Override
    /** Decrypt */
    public byte[] decrypt(byte[] privateKey, byte[] ciphertext) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM, PROVIDER);
            PrivateKey privKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKey));
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(CIPHER_ALGORITHM, PROVIDER);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, privKey);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("RSA 解密失败", e);
        }
    }

    @Override
    /** 标志 */
    public byte[] sign(byte[] privateKey, byte[] data) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM, PROVIDER);
            PrivateKey privKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privateKey));
            Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM, PROVIDER);
            signature.initSign(privKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new RuntimeException("RSA 签名失败", e);
        }
    }

    @Override
    /** 验证 */
    public boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM, PROVIDER);
            PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(publicKey));
            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM, PROVIDER);
            sig.initVerify(pubKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            throw new RuntimeException("RSA 验签失败", e);
        }
    }
}
