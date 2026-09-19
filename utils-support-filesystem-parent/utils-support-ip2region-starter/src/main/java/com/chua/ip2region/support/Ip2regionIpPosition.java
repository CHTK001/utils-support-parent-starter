package com.chua.ip2region.support;

import com.chua.common.support.network.ip.GeoSetting;
import com.chua.common.support.network.ip.IpLocation;
import com.chua.common.support.network.ip.IpPosition;
import com.chua.common.support.spi.annotations.Spi;
import com.chua.common.support.utils.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
    private static final String DEFAULT_DB = "classpath:ip2region.xdb";

    /**
     * 类路径 资源前缀
     */
    private static final String CLASSPATH_PREFIX = "classpath:";

    /**
     * xdb 文件后缀
     */
    private static final String XDB_SUFFIX = ".xdb";

    /**
     * 区域字段缺失占位值
     */
    private static final String UNKNOWN_PLACEHOLDER = "0";

    /**
     * 保留地址（内网/回环）占位值
     */
    private static final String RESERVED_REGION = "Reserved";

    /**
     * 索引区块长度：起始 IP(4) + 结束 IP(4) + 数据长度(2) + 数据偏移(4)
     */
    private static final int INDEX_BLOCK_LENGTH = 14;

    /**
     * xdb 文件完整数据
     */
    private final byte[] data;

    /**
     * 索引区起始位置
     */
    private final long indexBegin;

    /**
     * 索引区结束位置（不含）
     */
    private final long indexEnd;

    /**
     * 使用默认路径构造。
     */
    public Ip2regionIpPosition() {
        this(DEFAULT_DB);
    }

    /**
     * 使用 SPI 配置构造。
     *
     * @param geoSetting 地理数据库配置，为空或未填路径时使用默认类路径资源
     */
    public Ip2regionIpPosition(GeoSetting geoSetting) {
        this(resolvePath(null == geoSetting ? null : geoSetting.getDatabaseFile()));
    }

    /**
     * 过滤配置中的数据库路径。
     *
     * <p>{@code plugin.ip.database-file} 在多个 IP 实现之间共用，默认值指向其它数据库，
     * 因此只接受 xdb 文件，其余情况回落到内置资源。</p>
     *
     * @param databaseFile 配置的数据库路径，可能为空
     * @return 可用的数据库路径
     */
    private static String resolvePath(String databaseFile) {
        return null != databaseFile && databaseFile.endsWith(XDB_SUFFIX) ? databaseFile : DEFAULT_DB;
    }

    /**
     * 指定数据库文件路径构造。
     *
     * @param dbPath xdb 数据库文件路径，支持 {@code classpath:} 前缀
     */
    public Ip2regionIpPosition(String dbPath) {
        try {
            byte[] buf = readDatabase(StringUtils.defaultString(dbPath, DEFAULT_DB));
            ByteBuffer header = ByteBuffer.wrap(buf, 0, 16).order(ByteOrder.LITTLE_ENDIAN);
            long begin = header.getInt(8) & 0xFFFFFFFFL;
            long end = header.getInt(12) & 0xFFFFFFFFL;
            if (begin <= 0 || end <= begin || (end - begin) % INDEX_BLOCK_LENGTH != 0 || end > buf.length) {
                throw new IllegalStateException("非法的 ip2region xdb 索引区: " + begin + " - " + end + ", 文件 " + buf.length);
            }
            this.data = buf;
            this.indexBegin = begin;
            this.indexEnd = end;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load ip2region database: " + dbPath, e);
        }
    }

    /**
     * 读取 xdb 数据库字节。
     *
     * <p>xdb 打包在资源 jar 内，因此 {@code classpath:} 前缀走类加载器流读取；无前缀时按本地文件读取。</p>
     *
     * @param location 数据库位置
     * @return 数据库字节
     * @throws IOException 读取失败
     */
    private static byte[] readDatabase(String location) throws IOException {
        if (!location.startsWith(CLASSPATH_PREFIX)) {
            return Files.readAllBytes(Path.of(location));
        }
        String resource = location.substring(CLASSPATH_PREFIX.length());
        while (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        try (InputStream in = Ip2regionIpPosition.class.getClassLoader().getResourceAsStream(resource)) {
            if (null == in) {
                throw new IOException("类路径未找到 " + resource + "，请添加 utils-support-resource-ip2region 依赖");
            }
            return in.readAllBytes();
        }
    }

    @Override
    /**
     * 查询
    */
    public IpLocation query(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        long ipVal = ipToLong(ip);
        if (ipVal < 0) {
            return null;
        }
        int low = 0;
        int high = (int) ((indexEnd - indexBegin) / INDEX_BLOCK_LENGTH) - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int offset = (int) (indexBegin + (long) mid * INDEX_BLOCK_LENGTH);
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

            int dataLength = readShort(offset + 8);
            long dataPtr = readInt(offset + 10);
            if (dataLength <= 0 || dataPtr + dataLength > data.length) {
                return null;
            }
            return parseRegion(new String(data, (int) dataPtr, dataLength, StandardCharsets.UTF_8));
        }
        return null;
    }

    /**
     * 解析区域字符串。
     *
     * <p>格式为 {@code 国家|省份|城市|ISP|国家码}，{@code 0} 表示该级未知。</p>
     *
     * @param region 区域字符串
     * @return 地理位置，字段缺失时对应项为空串
     */
    private static IpLocation parseRegion(String region) {
        String[] parts = region.split("\\|");
        IpLocation location = new IpLocation();
        location.setCountry(part(parts, 0));
        location.setProvince(part(parts, 1));
        location.setCity(part(parts, 2));
        location.setIsp(part(parts, 3));
        return location;
    }

    /**
     * 取区域片段，未知值归一化为空串。
     *
     * @param parts 区域片段
     * @param index 下标
     * @return 片段值
     */
    private static String part(String[] parts, int index) {
        if (index >= parts.length) {
            return "";
        }
        String value = parts[index];
        return StringUtils.isBlank(value) || UNKNOWN_PLACEHOLDER.equals(value) || RESERVED_REGION.equals(value) ? "" : value;
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
     * 读取 2 字节无符号整数（小端序）。
     * @param offset 偏移量
     * @return 读取short的结果
     */
    private int readShort(int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    /**
     * 将 IPv4 地址转换为长整型。
     *
     * @param ip ip
     * @return ip 转为 long 的结果，非 IPv4 或格式非法返回 -1
     */
    private static long ipToLong(String ip) {
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return -1L;
        }
        long result = 0L;
        for (int i = 0; i < 4; i++) {
            int segment;
            try {
                segment = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return -1L;
            }
            if (segment < 0 || segment > 255) {
                return -1L;
            }
            result |= ((long) segment) << (24 - i * 8);
        }
        return result;
    }
}
