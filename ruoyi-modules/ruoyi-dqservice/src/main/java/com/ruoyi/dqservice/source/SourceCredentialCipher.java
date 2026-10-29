package com.ruoyi.dqservice.source;

import com.ruoyi.common.core.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** 密钥只读取服务器环境，缺失不阻断旧本地规则启动。 */
@Component
public class SourceCredentialCipher
{
    private final Supplier<String> keyProvider;
    private final SecureRandom random = new SecureRandom();

    public SourceCredentialCipher()
    {
        this(() -> System.getenv("DQ_SOURCE_AES_KEY"));
    }

    public SourceCredentialCipher(Supplier<String> keyProvider)
    {
        this.keyProvider = keyProvider;
    }

    private SecretKeySpec key()
    {
        try
        {
            byte[] bytes = Base64.getDecoder().decode(keyProvider.get());
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        }
        catch (RuntimeException e)
        {
            throw new ServiceException("服务器未配置有效的 DQ_SOURCE_AES_KEY（Base64 编码的 32 字节密钥）");
        }
    }

    public String encrypt(String value)
    {
        SecretKeySpec secret = key();
        try
        {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret, new GCMParameterSpec(128, nonce));
            cipher.updateAAD("dq-source:v1".getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] packed = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(packed);
        }
        catch (Exception e)
        {
            throw new ServiceException("外部凭据加密失败");
        }
    }

    public String decrypt(String value)
    {
        SecretKeySpec secret = key();
        try
        {
            if (value == null || !value.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] packed = Base64.getDecoder().decode(value.substring(3));
            if (packed.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, packed, 0, 12));
            cipher.updateAAD("dq-source:v1".getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(packed, 12, packed.length - 12), StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            throw new ServiceException("外部凭据不可解密，请检查服务器密钥或重新配置凭据");
        }
    }
}
