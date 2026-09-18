package com.chua.common.support.lang.algorithm.cipher;


/**
* 加解密算法统一门面，支持链式调用。
*
* <p>遵循项目 {@code XxxFlow} 家风，作为<b>所有加解密算法的统一入口</b>：按算法名绑定，
* 再解析出对应实现（JDK 内置直接实例化；其余走 SPI 按提供者加载，当前内置 BouncyCastle "bc"）。</p>
*
* <h2>使用示例</h2>
* <pre>{@code
* // 按算法名 + 提供者统一入口
* byte[] enc = CipherFlow.of("sm2").provider("bc").sm2().encrypt(publicKey, data);
* byte[] dec = CipherFlow.of("aes").aes().encrypt(key, data);
*
* // 具体算法直接链式（按算法名解析出对应类型）
* byte[] rsaCt = CipherFlow.of("rsa").rsa().encrypt(publicKey, data);
*
* // HPKE 混合加密（委托 HpkeFlow 门面）
* HpkeFlow hpke = CipherFlow.of("hpke").hpkeFlow();
* HpkeFlow.SealedMessage msg = hpke.receiverPk(receiverPub).aad(aad).seal(plain);
* byte[] out = hpke.secretKey(receiverPriv).enc(msg.enc()).aad(aad).open(msg.ciphertext());
* }</pre>
*
* @author CH
* @since 4.0.0.42
 */
public final class CipherFlow {

    /** 默认提供者（BouncyCastle） */
    private static final String DEFAULT_PROVIDER = "bc";

    /** 算法名 */
    private String algorithm;
    /** 提供者名称，默认 "bc" */
    private String provider = DEFAULT_PROVIDER;

    /**
     * 构造函数
     * @param algorithm 方法入参 algorithm
     */
    private CipherFlow(String algorithm) {
        this.algorithm = algorithm;
    }

    // ==================== 工厂 ====================

    /**
    * 按算法名创建门面（默认提供者 "bc"）。
    *
    * @param algorithm 算法名，如 "sm2"、"sm4"、"aes"、"hpke"
    * @return 门面实例
    */
    public static CipherFlow of(String algorithm) {
        return new CipherFlow(algorithm);
    }

    /**
    * 按算法名 + 提供者创建门面。
    *
    * @param algorithm 算法名
    * @param provider  提供者名称，如 "bc"
    * @return 门面实例
    */
    public static CipherFlow of(String algorithm, String provider) {
        CipherFlow flow = new CipherFlow(algorithm);
        if (provider != null && !provider.isEmpty()) {
            flow.provider = provider;
        }
        return flow;
    }

    // ==================== 配置（链式，返回 this） ====================

    /**
    * 切换算法名。
    *
    * @param algorithm 算法名
    * @return 当前门面
    */
    public CipherFlow algorithm(String algorithm) {
        this.algorithm = algorithm;
        return this;
    }

    /**
    * 切换提供者。
    *
    * @param provider 提供者名称，如 "bc"
    * @return 当前门面
    */
    public CipherFlow provider(String provider) {
        this.provider = provider;
        return this;
    }

    /**
    * 获取当前算法名。
    *
    * @return 算法名
    */
    public String algorithm() {
        return algorithm;
    }

    /**
    * 获取当前提供者。
    *
    * @return 提供者名称
    */
    public String provider() {
        return provider;
    }

    // ==================== 解析具体算法 ====================

    /**
    * 按绑定的算法名解析出对应 {@link Cipher} 实现（统一入口）。
    *
    * @return 对应的 Cipher 实现
    * @throws IllegalArgumentException 未知算法名时
    */
    public Cipher resolve() {
        String name = algorithm == null ? "" : algorithm.toLowerCase();
        return switch (name) {
            case "aes" -> aes();
            case "des" -> des();
            case "rsa" -> rsa();
            case "sm2" -> sm2();
            case "sm4" -> sm4();
            case "ecies" -> ecies();
            case "hpke" -> hpke();
            case "twofish" -> twofish();
            case "noekeon" -> noekeon();
            case "desede", "3des" -> desede();
            default -> throw new IllegalArgumentException("未知算法: " + algorithm);
        };
    }

    /**
     * AES（JDK 内置）
     * @return AesCipher 对象
     */
    public AesCipher aes() {
        return new AesCipher();
    }

    /**
     * DES（JDK 内置）
     * @return DesCipher 对象
     */
    public DesCipher des() {
        return new DesCipher();
    }

    /**
     * RSA 非对称加解密 / 签名验签
     * @return RsaCipher 对象
     */
    public RsaCipher rsa() {
        return RsaCipher.create(provider);
    }

    /**
     * SM2 非对称加解密 / 签名验签
     * @return Sm2Cipher 对象
     */
    public Sm2Cipher sm2() {
        return Sm2Cipher.create(provider);
    }

    /**
     * SM4 对称加解密
     * @return Sm4Cipher 对象
     */
    public Sm4Cipher sm4() {
        return Sm4Cipher.create(provider);
    }

    /**
     * ECIES 椭圆曲线集成加密
     * @return EciesCipher 对象
     */
    public EciesCipher ecies() {
        return EciesCipher.create(provider);
    }

    /**
     * HPKE（RFC 9180）混合公钥加密
     * @return HpkeCipher 对象
     */
    public HpkeCipher hpke() {
        return HpkeCipher.create(provider);
    }

    /**
     * Twofish 对称加解密
     * @return TwofishCipher 对象
     */
    public TwofishCipher twofish() {
        return TwofishCipher.create(provider);
    }

    /**
     * Noekeon 对称加解密
     * @return NoekeonCipher 对象
     */
    public NoekeonCipher noekeon() {
        return NoekeonCipher.create(provider);
    }

    /**
     * 3DES 对称加解密
     * @return DesedeCipher 对象
     */
    public DesedeCipher desede() {
        return DesedeCipher.create(provider);
    }

    /**
     * HPKE 链式门面（委托 {@link HpkeFlow}）
     * @return HpkeFlow 对象
     */
    public HpkeFlow hpkeFlow() {
        return HpkeFlow.of(provider);
    }
}
