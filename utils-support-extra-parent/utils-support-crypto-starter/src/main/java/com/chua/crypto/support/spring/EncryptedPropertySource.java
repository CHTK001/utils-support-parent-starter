package com.chua.crypto.support.spring;

import com.chua.crypto.support.Crypto;
import com.chua.crypto.support.config.ConfigFileCipher;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
* 支持 ENC(...) 透明解密的 财产源 装饰器
*
* <p>包装既有枚举型 PropertySource：读取属性时若值形如 {@code ENC(Base64密文)}，
* 则经 {@link ConfigFileCipher#decryptValue(String, Crypto)} 解密后返回，
* 业务代码无感知；非加密值原样透传（含类型）。
*
* @author CH
* @since 2026-08-26
 */
public class EncryptedPropertySource extends EnumerablePropertySource<PropertySource<?>> {

    /**
    * 加密门面
    */
    private final Crypto crypto;

    /**
    * 构造装饰器
    *
    * @param delegate 被包装的 财产源
    * @param crypto   已初始化的加密门面
    */
    public EncryptedPropertySource(PropertySource<?> delegate, Crypto crypto) {
        super(delegate.getName(), delegate);
        this.crypto = crypto;
    }

    /**
    * 返回全部属性名（透传）
    *
    * @return 属性名数组
    */
    @Override
    public String[] getPropertyNames() {
        if (getSource() instanceof EnumerablePropertySource<?> enumerable) {
            return enumerable.getPropertyNames();
        }
        return new String[0];
    }

    /**
    * 读取属性：ENC(...) 形态自动解密，其余原样返回
    *
    * @param name 属性名
    * @return 属性值
    */
    @Override
    public Object getProperty(String name) {
        Object value = getSource().getProperty(name);
        if (value instanceof String text && ConfigFileCipher.isEncryptedValue(text)) {
            return crypto.decryptValue(text);
        }
        return value;
    }
}
