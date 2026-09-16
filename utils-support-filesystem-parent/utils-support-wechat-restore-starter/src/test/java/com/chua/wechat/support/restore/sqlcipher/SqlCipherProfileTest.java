package com.chua.wechat.support.restore.sqlcipher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlCipherProfile} 单元测试。
 *
 * @author CH
 * @since 4.0.0.42
 */
class SqlCipherProfileTest {

    @Test
    void candidatesShouldStartWithWechat4() {
        SqlCipherProfile first = SqlCipherProfile.candidates().getFirst();
        assertEquals("wechat-4", first.name());
        assertEquals(4096, first.pageSize());
        assertEquals(256000, first.kdfIterations());
        assertEquals("HmacSHA512", first.hmacAlgorithm());
        assertEquals(64, first.hmacSize());
    }

    @Test
    void wechat4LayoutShouldMatchSqlCipherReserve() {
        SqlCipherProfile profile = SqlCipherProfile.candidates().getFirst();
        assertEquals(80, profile.reserveSize());
        assertEquals(4016, profile.cipherSize());
        assertEquals(4000, profile.cipherSize(true));
        assertTrue(profile.hmacEnabled());
    }

    @Test
    void everyCandidateShouldHaveAlignedCipherSize() {
        for (SqlCipherProfile profile : SqlCipherProfile.candidates()) {
            assertEquals(0, profile.cipherSize() % SqlCipherProfile.AES_BLOCK_SIZE,
                    profile.name() + " 密文长度未按 AES 分组对齐");
            assertEquals(0, profile.cipherSize(true) % SqlCipherProfile.AES_BLOCK_SIZE,
                    profile.name() + " 首页密文长度未按 AES 分组对齐");
        }
    }

    @Test
    void tooSmallPageSizeShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCipherProfile("bad", 100, "PBKDF2WithHmacSHA512", 1, "HmacSHA512", 64));
    }

    @Test
    void misalignedCipherSizeShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCipherProfile("bad", 4096, "PBKDF2WithHmacSHA512", 1, "HmacSHA512", 63));
    }

    @Test
    void oversizedHmacShouldThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> new SqlCipherProfile("bad", 4096, "PBKDF2WithHmacSHA512", 1, "HmacSHA512", 128));
    }
}
