package com.chua.common.support.file.tar;

import java.io.File;

/**
 * Tar 文件头结构定义。
 * <p>
 * 定义了 TAR 文件格式的头部字段布局，包括文件名、权限、用户 ID、分组 ID、
 * 文件大小、修改时间、校验和、链接标识等。支持 UStar 扩展格式。
 * </p>
 * <pre>
 * Offset  Size     Field
 * 0       100      File name
 * 100     8        File mode
 * 108     8        Owner's numeric user ID
 * 116     8        Group's numeric user ID
 * 124     12       File size in bytes
 * 136     12       Last modification time in numeric Unix time format
 * 148     8        Checksum for header block
 * 156     1        Link indicator (file type)
 * 157     100      Name of linked file
 * </pre>
 * <p>
 * File Types:
 * '0' - Normal file
 * '1' - Hard link
 * '2' - Symbolic link
 * '3' - Character special
 * '4' - Block special
 * '5' - Directory
 * '6' - FIFO
 * '7' - Contiguous
 * </p>
 * <pre>
 * Ustar header:
 * Offset  Size    Field
 * 257     6       UStar indicator "ustar"
 * 263     2       UStar version "00"
 * 265     32      Owner user name
 * 297     32      Owner group name
 * 329     8       Device major number
 * 337     8       Device minor number
 * 345     155     Filename prefix
 * </pre>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TarHeader {

    /** Header 区域字段长度：文件名 */
    public static final int NAMELEN = 100;

    /** Header 区域字段长度：文件模式 */
    public static final int MODELEN = 8;

    /** Header 区域字段长度：用户 ID */
    public static final int UIDLEN = 8;

    /** Header 区域字段长度：分组 ID */
    public static final int GIDLEN = 8;

    /** Header 区域字段长度：文件大小 */
    public static final int SIZELEN = 12;

    /** Header 区域字段长度：修改时间 */
    public static final int MODTIMELEN = 12;

    /** Header 区域字段长度：校验和 */
    public static final int CHKSUMLEN = 8;

    /** 文件类型：旧式普通文件 */
    public static final byte LF_OLDNORM = 0;

    /** 文件类型：普通文件 */
    public static final byte LF_NORMAL = (byte) '0';

    /** 文件类型：硬链接 */
    public static final byte LF_LINK = (byte) '1';

    /** 文件类型：符号链接 */
    public static final byte LF_SYMLINK = (byte) '2';

    /** 文件类型：字符设备 */
    public static final byte LF_CHR = (byte) '3';

    /** 文件类型：块设备 */
    public static final byte LF_BLK = (byte) '4';

    /** 文件类型：目录 */
    public static final byte LF_DIR = (byte) '5';

    /** 文件类型：FIFO 管道 */
    public static final byte LF_FIFO = (byte) '6';

    /** 文件类型：连续文件 */
    public static final byte LF_CONTIG = (byte) '7';

    /** UStar 魔数标识 */
    public static final String USTAR_MAGIC = "ustar";

    /** UStar 魔数字段长度 */
    public static final int USTAR_MAGICLEN = 8;

    /** UStar 用户名字段长度 */
    public static final int USTAR_USER_NAMELEN = 32;

    /** UStar 分组名字段长度 */
    public static final int USTAR_GROUP_NAMELEN = 32;

    /** UStar 设备字段长度 */
    public static final int USTAR_DEVLEN = 8;

    /** UStar 文件名前缀字段长度 */
    public static final int USTAR_FILENAME_PREFIX = 155;

    /** 文件名 */
    public StringBuffer name;

    /** 文件权限模式 */
    public int mode;

    /** 用户 ID */
    public int userId;

    /** 分组 ID */
    public int groupId;

    /** 文件大小（字节） */
    public long size;

    /** 最后修改时间（Unix 时间戳） */
    public long modTime;

    /** 校验和 */
    public int checkSum;

    /** 链接标识（文件类型） */
    public byte linkFlag;

    /** 链接文件名 */
    public StringBuffer linkName;

    /** UStar 魔数（indicator and version） */
    public StringBuffer magic;

    /** 用户名 */
    public StringBuffer userName;

    /** 分组名 */
    public StringBuffer groupName;

    /** 主设备号 */
    public int devMajor;

    /** 次设备号 */
    public int devMinor;

    /** 文件名前缀 */
    public StringBuffer namePrefix;

    /**
    * 构造 TarHeader，初始化 UStar 魔数、用户名等默认值。
    */
    public TarHeader() {
        this.magic = new StringBuffer(TarHeader.USTAR_MAGIC);
        this.name = new StringBuffer();
        this.linkName = new StringBuffer();

        String user = System.getProperty("user.name", "");

        if (user.length() > 31) {
            user = user.substring(0, 31);
        }

        this.userId = 0;
        this.groupId = 0;
        this.userName = new StringBuffer(user);
        this.groupName = new StringBuffer("");
        this.namePrefix = new StringBuffer();
    }

    /**
     * 从头部缓冲区解析条目名称。
     *
     * @param header 头部缓冲区
     * @param offset 解析起始偏移量
     * @param length 待解析的字节数
     * @return 解析出的条目名称
     */
    public static StringBuffer parseName(byte[] header, int offset, int length) {
        StringBuffer result = new StringBuffer(length);

        int end = offset + length;
        for (int i = offset; i < end; ++i) {
            if (header[i] == 0) {
                break;
            }
            result.append((char) header[i]);
        }

        return result;
    }

    /**
     * 将条目名称写入头部缓冲区。
     *
     * @param name   待写入的名称
     * @param buf    头部缓冲区
     * @param offset 写入起始偏移量
     * @param length 写入的字节数
     * @return 写入后的偏移量
     */
    public static int getNameBytes(StringBuffer name, byte[] buf, int offset, int length) {
        int i;

        for (i = 0; i < length && i < name.length(); ++i) {
            buf[offset + i] = (byte) name.charAt(i);
        }

        for (; i < length; ++i) {
            buf[offset + i] = 0;
        }

        return offset + length;
    }

    /**
     * 为文件/目录条目创建新头部。
     *
     * @param entryName   文件名
     * @param size        文件大小（字节）
     * @param modTime     最后修改时间（Unix 时间戳）
     * @param dir         是否为目录
     * @param permissions 文件权限
     * @return TarHeader 实例
     */
    public static TarHeader createHeader(String entryName, long size, long modTime, boolean dir, int permissions) {
        String name = entryName;
        name = TarUtils.trim(name.replace(File.separatorChar, '/'), '/');

        TarHeader header = new TarHeader();
        header.linkName = new StringBuffer("");
        header.mode = permissions;

        if (name.length() > 100) {
            header.namePrefix = new StringBuffer(name.substring(0, name.lastIndexOf('/')));
            header.name = new StringBuffer(name.substring(name.lastIndexOf('/') + 1));
        } else {
            header.name = new StringBuffer(name);
        }

        if (dir) {
            header.linkFlag = TarHeader.LF_DIR;
            if (header.name.charAt(header.name.length() - 1) != '/') {
                header.name.append("/");
            }
            header.size = 0;
        } else {
            header.linkFlag = TarHeader.LF_NORMAL;
            header.size = size;
        }

        header.modTime = modTime;
        header.checkSum = 0;
        header.devMajor = 0;
        header.devMinor = 0;

        return header;
    }
}
