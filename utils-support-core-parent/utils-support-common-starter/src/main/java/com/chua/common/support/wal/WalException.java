package com.chua.common.support.wal;


/**
 * WAL 操作异常。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class WalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 使用消息构造。
     */
    public WalException(String message) {
        super(message);
    }

    /**
     * 使用消息和原因构造。
     */
    public WalException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 使用原因构造。
     */
    public WalException(Throwable cause) {
        super(cause);
    }

    /**
     * WAL 文件已损坏异常。
     */
    public static WalException corrupted(String path) {
        return new WalException("WAL 文件已损坏或不兼容: " + path);
    }

    /**
     * WAL CRC 校验失败异常。
     */
    public static WalException crcMismatch(long lsn) {
        return new WalException("WAL 记录 CRC 校验失败: lsn=" + lsn);
    }
}
