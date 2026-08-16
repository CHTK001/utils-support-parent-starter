package com.chua.qqwry.support;

import com.chua.common.support.network.ip.IpLocation;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * QQWry 纯真 IP 数据库查询器。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class QQWryReader implements Closeable {

    private static final int MODE_1 = 0x01;
    private static final int MODE_2 = 0x02;

    /**
     * 原始数据字节数组
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
     * 总记录数
     */
    private final int totalRecords;

    /**
     * 通过文件路径构建读取器。
     *
     * @param filePath 文件路径
     * @throws IOException 当读取文件失败时抛出
     */
    public QQWryReader(String filePath) throws IOException {
        this(Files.readAllBytes(Path.of(filePath)));
    }

    /**
     * 通过字节数组构建读取器。
     *
     * @param data 数据库文件内容
     */
    public QQWryReader(byte[] data) {
        this.data = data;
        ByteBuffer header = ByteBuffer.wrap(data, 0, 8).order(ByteOrder.LITTLE_ENDIAN);
        this.indexBegin = header.getInt() & 0xFFFFFFFFL;
        this.indexEnd = header.getInt() & 0xFFFFFFFFL;
        this.totalRecords = (int) ((indexEnd - indexBegin) / 7);
    }

    /**
     * 获取总记录数。
     *
     * @return 总记录数
     */
    public int getTotalRecords() {
        return totalRecords;
    }

    /**
     * 根据 IP 地址字符串查询地理位置信息。
     *
     * @param ip IP 地址字符串，例如 "192.168.1.1"
     * @return 地理位置信息对象，若未找到则返回 null
     */
    public IpLocation query(String ip) {
        return query(ipToLong(ip));
    }

    /**
     * 根据 IP 地址长整型值查询地理位置信息。
     *
     * @param ip IP 地址的长整型表示
     * @return 地理位置信息对象，若未找到则返回 null
     */
    public IpLocation query(long ip) {
        int lo = 0;
        int hi = totalRecords - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int offset = (int) (indexBegin + mid * 7);
            long ipFrom = read3(offset);
            long ipTo = read3(offset + 3);
            if (ip >= ipFrom && ip <= ipTo) {
                return parseLocation((int) read3(offset + 4));
            } else if (ip < ipFrom) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return null;
    }

    /**
     * 解析指定偏移量处的地理位置数据。
     *
     * @param offset 数据在文件中的偏移量
     * @return 解析后的地理位置对象
     */
    private IpLocation parseLocation(int offset) {
        IpLocation loc = new IpLocation();
        int bodyOffset = offset + 4;
        int mode = data[bodyOffset] & 0xFF;
        String country;
        String area;
        if (mode == MODE_1) {
            int realOffset = (int) read3(bodyOffset + 1);
            country = readCountry(realOffset);
            area = readArea(realOffset + strLen(realOffset) + 1);
        } else if (mode == MODE_2) {
            int realOffset = (int) read3(bodyOffset + 1);
            country = readCountry(realOffset);
            area = readArea(bodyOffset + 4);
        } else {
            country = readStr(bodyOffset);
            area = readArea(bodyOffset + country.getBytes(Charset.forName("GBK")).length + 1);
        }
        loc.setCountry(clean(country));
        parseRegion(loc, clean(area));
        return loc;
    }

    /**
     * 解析区域字符串并提取省份和城市信息。
     *
     * @param loc  地理位置对象
     * @param area 区域描述字符串
     */
    private void parseRegion(IpLocation loc, String area) {
        if (area == null || area.isEmpty()) {
            return;
        }
        String[] parts = area.split("\\s+");
        if (parts.length > 0) {
            loc.setIsp(parts[0]);
        }
        String country = loc.getCountry();
        if (country == null) {
            return;
        }
        for (String kw : new String[]{"省", "市", "自治区", "特别行政区"}) {
            int idx = country.indexOf(kw);
            if (idx > 0 && idx < country.length() - 1) {
                loc.setProvince(country.substring(0, idx + 1));
                String rest = country.substring(idx + 1).trim();
                if (!rest.isEmpty() && !rest.startsWith("市")) {
                    loc.setCity(rest.replace("市", ""));
                }
                return;
            }
        }
    }

    /**
     * 读取国家信息，处理可能的重定向模式。
     *
     * @param offset 当前偏移量
     * @return 国家名称
     */
    private String readCountry(int offset) {
        int mode = data[offset] & 0xFF;
        if (mode == MODE_1) {
            return readCountry((int) read3(offset + 1));
        } else if (mode == MODE_2) {
            return readStr((int) read3(offset + 1));
        }
        return readStr(offset);
    }

    /**
     * 读取地区信息，处理可能的重定向模式。
     *
     * @param offset 当前偏移量
     * @return 地区名称
     */
    private String readArea(int offset) {
        if (offset >= data.length) {
            return "";
        }
        int mode = data[offset] & 0xFF;
        if (mode == MODE_1 || mode == MODE_2) {
            return readStr((int) read3(offset + 1));
        }
        return readStr(offset);
    }

    /**
     * 从指定偏移量读取以 null 结尾的字符串。
     *
     * @param offset 起始偏移量
     * @return 读取的字符串
     */
    private String readStr(int offset) {
        int end = offset;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, Charset.forName("GBK"));
    }

    /**
     * 计算从指定偏移量开始到 null 字符的长度。
     *
     * @param offset 起始偏移量
     * @return 字符串长度
     */
    private int strLen(int offset) {
        int end = offset;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return end - offset;
    }

    /**
     * 读取 3 个字节组成的整数（小端序）。
     *
     * @param offset 起始偏移量
     * @return 读取的长整型数值
     */
    private long read3(int offset) {
        return (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8) | ((data[offset + 2] & 0xFFL) << 16);
    }

    /**
     * 清理字符串，移除无关标记如 CZ88.NET。
     *
     * @param s 待清理的字符串
     * @return 清理后的字符串
     */
    private String clean(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("CZ88.NET", "").trim();
    }

    /**
     * 将点分十进制 IP 地址转换为长整型数值。
     *
     * @param ip 点分十进制 IP 字符串
     * @return 转换后的长整型 IP 值
     */
    public static long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        long r = 0;
        for (int i = 0; i < 4; i++) {
            r |= (Long.parseLong(parts[i]) << (24 - i * 8));
        }
        return r;
    }

    @Override
    public void close() {
        // 无需释放资源，因为数据已加载到内存
    }
}
