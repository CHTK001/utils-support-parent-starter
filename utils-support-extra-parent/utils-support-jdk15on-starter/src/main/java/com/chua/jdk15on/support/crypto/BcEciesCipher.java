package com.chua.jdk15on.support.crypto;

import com.chua.common.support.lang.algorithm.cipher.EciesCipher;
import com.chua.common.support.spi.annotations.Spi;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.generators.KDF2BytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.*;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.PublicKeyFactory;

/**
   * 基于 bouncycastle 的 弹性容器实例 椭圆曲线集成加密方案实现
 *
 * <p>通过 SPI 机制以 "bc" 名称注册，实现密钥封装与对称加密结合的混合加密方案。
 * 使用 P-256 (secp256r1) 曲线，ECDH 密钥协商，SHA-256 KDF，AES/CBC 数据加密。
 *
 * @author CH
 * @since 2026/07/16
 */
@Spi({"bc", "bouncycastle"})
public class BcEciesCipher implements EciesCipher {

    /** 提供者 */
    private static final String PROVIDER = "BC";
    /** Curve */
    private static final String CURVE = "secp256r1";
    /** 键_algorithm */
    private static final String KEY_ALGORITHM = "EC";

    static {
        if (Security.getProvider(PROVIDER) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Override
    /** generate键pair */
    public KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance(KEY_ALGORITHM, PROVIDER);
            gen.initialize(new ECGenParameterSpec(CURVE), new SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("ECIES 密钥对生成失败", e);
        }
    }

    @Override
    /** Encrypt */
    public byte[] encrypt(byte[] publicKey, byte[] data) {
        try {
            KeyPair ephemeral = generateKeyPair();
            byte[] ephemeralPub = ephemeral.getPublic().getEncoded();

            ECDHBasicAgreement agreement = new ECDHBasicAgreement();
            agreement.init(PrivateKeyFactory.createKey(ephemeral.getPrivate().getEncoded()));
            byte[] shared = agreement.calculateAgreement(
                    PublicKeyFactory.createKey(publicKey)).toByteArray();

            KDF2BytesGenerator kdf = new KDF2BytesGenerator(new SHA256Digest());
            kdf.init(new KDFParameters(shared, new byte[0]));
            byte[] aesKey = new byte[32];
            kdf.generateBytes(aesKey, 0, aesKey.length);

            CBCBlockCipher cbc = new CBCBlockCipher(new AESEngine());
            PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(cbc, new PKCS7Padding());
            byte[] iv = new byte[16];
            SecureRandom random = new SecureRandom();
            random.nextBytes(iv);
            cipher.init(true, new ParametersWithIV(new KeyParameter(aesKey, 0, aesKey.length), iv));
            byte[] enc = new byte[cipher.getOutputSize(data.length)];
            int len = cipher.processBytes(data, 0, data.length, enc, 0);
            len += cipher.doFinal(enc, len);

            byte[] result = new byte[4 + ephemeralPub.length + 4 + 16 + 4 + len];
            int off = 0;
            int eLen = ephemeralPub.length;
            result[off++] = (byte)(eLen >> 24);
            result[off++] = (byte)(eLen >> 16);
            result[off++] = (byte)(eLen >> 8);
            result[off++] = (byte)(eLen);
            System.arraycopy(ephemeralPub, 0, result, off, eLen);
            off += eLen;
            result[off++] = 0; result[off++] = 0; result[off++] = 0; result[off++] = 16;
            System.arraycopy(iv, 0, result, off, 16);
            off += 16;
            result[off++] = (byte)(len >> 24);
            result[off++] = (byte)(len >> 16);
            result[off++] = (byte)(len >> 8);
            result[off++] = (byte)(len);
            System.arraycopy(enc, 0, result, off, len);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("ECIES 加密失败", e);
        }
    }

    @Override
    /** Decrypt */
    public byte[] decrypt(byte[] privateKey, byte[] ciphertext) {
        try {
            int off = 0;
            int eLen = ((ciphertext[off++] & 0xff) << 24)
                     | ((ciphertext[off++] & 0xff) << 16)
                     | ((ciphertext[off++] & 0xff) << 8)
                     |  (ciphertext[off++] & 0xff);
            byte[] ephemeralPub = new byte[eLen];
            System.arraycopy(ciphertext, off, ephemeralPub, 0, eLen);
            off += eLen;
            off += 4;
            byte[] iv = new byte[16];
            System.arraycopy(ciphertext, off, iv, 0, 16);
            off += 16;
            int ctLen = ((ciphertext[off++] & 0xff) << 24)
                      | ((ciphertext[off++] & 0xff) << 16)
                      | ((ciphertext[off++] & 0xff) << 8)
                      |  (ciphertext[off++] & 0xff);
            byte[] enc = new byte[ctLen];
            System.arraycopy(ciphertext, off, enc, 0, ctLen);

            ECDHBasicAgreement agreement = new ECDHBasicAgreement();
            agreement.init(PrivateKeyFactory.createKey(privateKey));
            byte[] shared = agreement.calculateAgreement(
                    PublicKeyFactory.createKey(ephemeralPub)).toByteArray();

            KDF2BytesGenerator kdf = new KDF2BytesGenerator(new SHA256Digest());
            kdf.init(new KDFParameters(shared, new byte[0]));
            byte[] aesKey = new byte[32];
            kdf.generateBytes(aesKey, 0, aesKey.length);

            CBCBlockCipher cbc = new CBCBlockCipher(new AESEngine());
            PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(cbc, new PKCS7Padding());
            cipher.init(false, new ParametersWithIV(new KeyParameter(aesKey, 0, aesKey.length), iv));
            byte[] dec = new byte[cipher.getOutputSize(ctLen)];
            int len = cipher.processBytes(enc, 0, ctLen, dec, 0);
            len += cipher.doFinal(dec, len);
            byte[] result = new byte[len];
            System.arraycopy(dec, 0, result, 0, len);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("ECIES 解密失败", e);
        }
    }
}