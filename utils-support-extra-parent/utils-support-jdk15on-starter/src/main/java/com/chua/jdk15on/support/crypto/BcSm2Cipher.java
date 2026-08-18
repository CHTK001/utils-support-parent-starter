package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.Sm2Cipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.*;
import java.security.spec.ECGenParameterSpec;

/**
 * 基于 BouncyCastle 的 SM2 非对称加解密实现
 *
 * <p>通过 SPI 机制以 "bc" 名称注册，使用 BouncyCastle 提供者
 * 实现 SM2 密钥生成、加密、解密、签名和验签。
 *
 * @author CH
 * @since 2026/07/15
 */
@Spi({"bc", "bouncycastle"})
public class BcSm2Cipher implements Sm2Cipher {

    /** 提供者 */
    private static final String PROVIDER = "BC";
    /** Ec_algorithm */
    private static final String EC_ALGORITHM = "EC";
    /** Sm2_id */
    private static final byte[] SM2_ID = "1234567812345678".getBytes();

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(EC_ALGORITHM, PROVIDER);
            gen.initialize(new ECGenParameterSpec("sm2p256v1"), new SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("SM2 密钥对生成失败", e);
        }
    }

    @Override
    public byte[] encrypt(byte[] publicKey, byte[] data) {
        try {
            AsymmetricKeyParameter pubKey = PublicKeyFactory.createKey(publicKey);
            SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
            engine.init(true, new ParametersWithRandom(pubKey, new SecureRandom()));
            return engine.processBlock(data, 0, data.length);
        } catch (Exception e) {
            throw new RuntimeException("SM2 加密失败", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] privateKey, byte[] ciphertext) {
        try {
            AsymmetricKeyParameter privKey = PrivateKeyFactory.createKey(privateKey);
            SM2Engine engine = new SM2Engine(SM2Engine.Mode.C1C3C2);
            engine.init(false, privKey);
            return engine.processBlock(ciphertext, 0, ciphertext.length);
        } catch (Exception e) {
            throw new RuntimeException("SM2 解密失败", e);
        }
    }

    @Override
    public byte[] sign(byte[] privateKey, byte[] data) {
        try {
            AsymmetricKeyParameter privKey = PrivateKeyFactory.createKey(privateKey);
            SM2Signer signer = new SM2Signer();
            signer.init(true, new ParametersWithRandom(privKey, new SecureRandom()));
            signer.update(data, 0, data.length);
            return signer.generateSignature();
        } catch (Exception e) {
            throw new RuntimeException("SM2 签名失败", e);
        }
    }

    @Override
    public boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
        try {
            AsymmetricKeyParameter pubKey = PublicKeyFactory.createKey(publicKey);
            SM2Signer signer = new SM2Signer();
            signer.init(false, pubKey);
            signer.update(data, 0, data.length);
            return signer.verifySignature(signature);
        } catch (Exception e) {
            throw new RuntimeException("SM2 验签失败", e);
        }
    }
}
