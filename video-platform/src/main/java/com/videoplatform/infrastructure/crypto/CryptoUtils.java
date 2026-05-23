package com.videoplatform.infrastructure.crypto;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * 加密压缩工具类
 * 使用 AES-256-GCM 加密 + Gzip 压缩
 */
@Slf4j
public class CryptoUtils {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12; // 96 bits
    private static final int GCM_TAG_LENGTH = 16; // 128 bits
    private static final int KEY_LENGTH = 32; // 256 bits

    private final byte[] key;

    public CryptoUtils(String secretKey) {
        this.key = deriveKey(secretKey);
    }

    /**
     * 从密码派生密钥
     */
    private byte[] deriveKey(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive key", e);
        }
    }

    /**
     * 加密并压缩数据
     * 流程: 原始数据 → Gzip压缩 → AES加密 → Base64编码
     */
    public String encryptAndCompress(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }

        try {
            // 1. Gzip 压缩
            byte[] compressed = gzipCompress(plaintext.getBytes(StandardCharsets.UTF_8));

            // 2. 如果压缩后更大，使用原始数据
            byte[] dataToEncrypt = compressed.length < plaintext.length() * 0.9
                    ? compressed
                    : plaintext.getBytes(StandardCharsets.UTF_8);

            // 3. AES-GCM 加密
            byte[] encrypted = encrypt(dataToEncrypt);

            // 4. Base64 编码
            return Base64.getEncoder().encodeToString(encrypted);

        } catch (Exception e) {
            log.warn("Encryption failed, returning plaintext: {}", e.getMessage());
            return plaintext;
        }
    }

    /**
     * 解密并解压数据
     * 流程: Base64解码 → AES解密 → Gzip解压 → 原始数据
     */
    public String decryptAndDecompress(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }

        // 如果不是 Base64 格式，直接返回（可能是未加密的老数据）
        if (!isBase64(ciphertext)) {
            return ciphertext;
        }

        try {
            // 1. Base64 解码
            byte[] encrypted = Base64.getDecoder().decode(ciphertext);

            // 2. AES-GCM 解密
            byte[] decrypted = decrypt(encrypted);

            // 3. 尝试 Gzip 解压（如果不是压缩数据，直接返回）
            try {
                byte[] decompressed = gzipDecompress(decrypted);
                return new String(decompressed, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 不是压缩数据，直接返回解密后的字符串
                return new String(decrypted, StandardCharsets.UTF_8);
            }

        } catch (Exception e) {
            log.warn("Decryption failed, returning ciphertext: {}", e.getMessage());
            return ciphertext;
        }
    }

    /**
     * AES-GCM 加密
     */
    private byte[] encrypt(byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);

        // 生成随机 IV
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, parameterSpec);
        byte[] encrypted = cipher.doFinal(plaintext);

        // 组合 IV + 密文
        ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encrypted.length);
        byteBuffer.put(iv);
        byteBuffer.put(encrypted);

        return byteBuffer.array();
    }

    /**
     * AES-GCM 解密
     */
    private byte[] decrypt(byte[] encrypted) throws Exception {
        // 提取 IV 和密文
        ByteBuffer byteBuffer = ByteBuffer.wrap(encrypted);
        byte[] iv = new byte[GCM_IV_LENGTH];
        byte[] ciphertext = new byte[encrypted.length - GCM_IV_LENGTH];

        byteBuffer.get(iv);
        byteBuffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, iv);
        SecretKeySpec keySpec = new SecretKeySpec(key, ALGORITHM);

        cipher.init(Cipher.DECRYPT_MODE, keySpec, parameterSpec);
        return cipher.doFinal(ciphertext);
    }

    /**
     * Gzip 压缩
     */
    private byte[] gzipCompress(byte[] data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(data);
        }
        return baos.toByteArray();
    }

    /**
     * Gzip 解压
     */
    private byte[] gzipDecompress(byte[] compressed) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzipIn = new GZIPInputStream(bais)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = gzipIn.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }
        return baos.toByteArray();
    }

    /**
     * 检查是否为 Base64 编码
     */
    private boolean isBase64(String str) {
        if (str == null || str.isEmpty()) return false;
        try {
            Base64.getDecoder().decode(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 计算压缩率
     */
    public static double calculateCompressionRatio(long original, long compressed) {
        if (original == 0) return 0;
        return (1.0 - (double) compressed / original) * 100;
    }
}
