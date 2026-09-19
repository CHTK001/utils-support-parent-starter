package com.chua.ip2region.support;

import com.chua.common.support.network.ip.IpLocation;
import com.chua.common.support.network.ip.IpPosition;
import com.chua.common.support.spi.annotations.Spi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ip2region IP 地理位置查询 SPI 实现。
 *
 * <p>基于 ip2region xdb 数据库文件实现毫秒级 IP 定位。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Spi("ip2region")
public class Ip2regionIpPosition implements IpPosition {

    /**
     * 默认数据库文件路径
     */
    private static final String DEFAULT_DB = "ip2region.xdb";

    /**
     * xdb 文件完整数据
     */
    private final byte[] data;

    /**
     * 索引区起始位置
     */
    private final long indexBegin;

    /**
     * 索引区结束位置
     */
    private final long indexEnd;

    /**
     * 使用默认路径构造。
     */
    public Ip2regionIpPosition() {
        this(DEFAULT_DB);
    }

    /**
     * 指定数据库文件路径构造。
     *
     * @param dbPath xdb 数据库文件路径
     */
    public Ip2regionIpPosition(String dbPath) {
        try {
            byte[] buf = Files.readAllBytes(Path.of(dbPath));
            ByteBuffer header = ByteBuffer.wrap(buf, 0, 16).order(ByteOrder.LITTLE_ENDIAN);
            this.data = buf;
            this.indexBegin = header.getInt() & 0xFFFFFFFFL;
            this.indexEnd = header.getInt(8) & 0xFFFFFFFFL;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ip2region database: " + dbPath, e);
        }
    }

    @Override
    /** 查询 */
    public IpLocation query(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        long ipVal = ipToLong(ip);
        int low = 0;
        int high = (int) ((indexEnd - indexBegin) / 13);

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int offset = (int) (indexBegin + mid * 13);
            long startIp = readInt(offset);
            long endIp = readInt(offset + 4);

            if (ipVal < startIp) {
                high = mid - 1;
                continue;
            }
            if (ipVal > endIp) {
                low = mid + 1;
                continue;
            }

            int ptr = (int) readInt(offset + 8);
            if (ptr <= 0) {
                return null;
            }

            int flag = data[ptr] & 0xFF;
            int strPtr;
            if (flag == 1) {
                strPtr = (int) readInt(ptr + 1);
            } else {
                strPtr = ptr;
            }

            String region = readString(strPtr, data);
            String[] parts = region.split("\\|");
            IpLocation location = new IpLocation();
            location.setCountry(parts.length > 0 ? parts[0] : "");
            location.setProvince(parts.length > 2 ? parts[2] : "");
            location.setCity(parts.length > 3 ? parts[3] : "");
            location.setIsp(parts.length > 4 ? parts[4] : "");
            return location;
        }
        return null;
    }

    /**
     * 读取 4 字节无符号整数（小端序）。
     * @param offset 偏移量
     * @return 读取int的结果
     */
    private long readInt(int offset) {
        return (data[offset] & 0xFFL)
             | ((data[offset + 1] & 0xFFL) << 8)
             | ((data[offset + 2] & 0xFFL) << 16)
             | ((data[offset + 3] & 0xFFL) << 24);
    }

    /**
     * 读取以 \0 结尾的字符串（GBK 编码）。
     * @param offset 偏移量
     * @param buffer 缓冲
     * @return 读取字符串的结果
     */
    private String readString(int offset, byte[] buffer) {
        int end = offset;
        while (end < buffer.length && buffer[end] != 0) {
            end++;
        }
        return new String(buffer, offset, end - offset);
    }

    /**
     * 将 IP 地址转换为长整型。
     * @param ip ip
     * @return ip转为long的结果
     */
    private static long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long result = 0;
        for (int i = 0; i < 4; i++) {
            result |= (Long.parseLong(parts[i]) << (24 - i * 8));
        }
        return result;
    }
}
