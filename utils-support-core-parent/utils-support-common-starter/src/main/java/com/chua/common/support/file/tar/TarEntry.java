package com.chua.common.support.file.tar;

import lombok.Getter;

import java.io.File;
import java.util.Date;

/**
 * Tar 归档条目表示，封装文件/目录及其对应的 TarHeader。
 *
 * @author CH
 * @since 4.0.0.42
 */
@Getter
public class TarEntry {

	/**
	 * 原始文件对象。
     * -- GETTER --
     *  获取关联的文件对象。
     */
	private File file;

	/**
	 * Tar 头信息对象。
     * -- GETTER --
     *  获取当前的 Tar 头信息。

     */
	private TarHeader header;

	/**
	 * 私有构造函数，用于初始化默认状态。
	 */
	private TarEntry() {
		this.file = null;
		this.header = new TarHeader();
	}

	/**
	 * 根据文件和条目名称创建新的 Tar 条目。
	 *
	 * @param file      源文件对象
	 * @param entryName 条目在归档中的名称
	 */
	public TarEntry(File file, String entryName) {
		this();
		this.file = file;
		this.extractTarHeader(entryName);
	}

	/**
	 * 根据字节缓冲区解析创建 Tar 条目。
	 *
	 * @param headerBuf 包含 Tar 头信息的字节数组
	 */
	public TarEntry(byte[] headerBuf) {
		this();
		this.parseTarHeader(headerBuf);
	}

	/**
	 * 根据现有的 TarHeader 对象创建条目。
	 * <p>
	 * 此方法适用于以编程方式添加新条目（例如，添加文件系统中不存在的文件或目录）。
	 *
	 * @param header 现有的 Tar 头对象
	 */
	public TarEntry(TarHeader header) {
		this.file = null;
		this.header = header;
	}

	@Override
	/** 判断相等 */
	public boolean equals(Object it) {
		if (!(it instanceof TarEntry)) {
			return false;
		}
		TarEntry other = (TarEntry) it;
		return this.header.name.toString().equals(other.header.name.toString());
	}

	@Override
	/** HashCode */
	public int hashCode() {
		return this.header.name.hashCode();
	}

	/**
	 * 检查当前条目是否为指定条目的后代。
	 *
	 * @param desc 待检查的后代条目
	 * @return 如果是指定条目的后代返回 true
	 */
	public boolean isDescendent(TarEntry desc) {
		return desc.header.name.toString().startsWith(this.header.name.toString());
	}

    /**
	 * 获取条目的完整名称（包括前缀）。
	 *
	 * @return 完整的条目名称字符串
	 */
	public String getName() {
		String name = this.header.name.toString();
		if (this.header.namePrefix != null && !this.header.namePrefix.toString().isEmpty()) {
			name = this.header.namePrefix.toString() + "/" + name;
		}
		return name;
	}

	/**
	 * 设置条目的名称。
	 *
	 * @param name 新的条目名称
	 */
	public void setName(String name) {
		this.header.name = new StringBuffer(name);
	}

	/**
	 * 获取用户 ID。
	 *
	 * @return 用户 ID
	 */
	public int getUserId() {
		return this.header.userId;
	}

	/**
	 * 设置用户 ID。
	 *
	 * @param userId 新的用户 ID
	 */
	public void setUserId(int userId) {
		this.header.userId = userId;
	}

	/**
	 * 获取组 ID。
	 *
	 * @return 组 ID
	 */
	public int getGroupId() {
		return this.header.groupId;
	}

	/**
	 * 设置组 ID。
	 *
	 * @param groupId 新的组 ID
	 */
	public void setGroupId(int groupId) {
		this.header.groupId = groupId;
	}

	/**
	 * 获取用户名。
	 *
	 * @return 用户名
	 */
	public String getUserName() {
		return this.header.userName.toString();
	}

	/**
	 * 设置用户名。
	 *
	 * @param userName 新的用户名
	 */
	public void setUserName(String userName) {
		this.header.userName = new StringBuffer(userName);
	}

	/**
	 * 获取组名。
	 *
	 * @return 组名
	 */
	public String getGroupName() {
		return this.header.groupName.toString();
	}

	/**
	 * 设置组名。
	 *
	 * @param groupName 新的组名
	 */
	public void setGroupName(String groupName) {
		this.header.groupName = new StringBuffer(groupName);
	}

	/**
	 * 同时设置用户 ID 和组 ID。
	 *
	 * @param userId   用户 ID
	 * @param groupId  组 ID
	 */
	public void setIds(int userId, int groupId) {
		this.setUserId(userId);
		this.setGroupId(groupId);
	}

	/**
	 * 通过毫秒时间戳设置修改时间。
	 *
	 * @param time 毫秒时间戳
	 */
	public void setModTime(long time) {
		this.header.modTime = time / 1000;
	}

	/**
	 * 通过 Date 对象设置修改时间。
	 *
	 * @param time Date 对象
	 */
	public void setModTime(Date time) {
		this.header.modTime = time.getTime() / 1000;
	}

	/**
	 * 获取修改时间。
	 *
	 * @return 修改时间的 Date 对象
	 */
	public Date getModTime() {
		return new Date(this.header.modTime * 1000L);
	}

    /**
	 * 获取条目大小。
	 *
	 * @return 文件大小（字节）
	 */
	public long getSize() {
		return this.header.size;
	}

	/**
	 * 设置条目大小。
	 *
	 * @param size 新的大小（字节）
	 */
	public void setSize(long size) {
		this.header.size = size;
	}

	/**
	 * 检查当前条目是否为目录。
	 *
	 * @return 如果是目录返回 true
	 */
	public boolean isDirectory() {
		if (this.file != null) {
			return this.file.isDirectory();
		}

		if (this.header != null) {
			if (this.header.linkFlag == TarHeader.LF_DIR) {
				return true;
			}

			if (this.header.name.toString().endsWith("/")) {
				return true;
			}
		}

		return false;
	}

	/**
	 * 从文件系统提取并填充 Tar 头信息。
	 *
	 * @param entryName 条目名称
	 */
	public void extractTarHeader(String entryName) {
		int permissions = PermissionUtils.permissions(this.file);
		this.header = TarHeader.createHeader(
				entryName,
				this.file.length(),
				this.file.lastModified() / 1000,
				this.file.isDirectory(),
				permissions
		);
	}

	/**
	 * 计算校验和。
	 *
	 * @param buf 数据缓冲区
	 * @return 校验和值
	 */
	public long computeCheckSum(byte[] buf) {
		long sum = 0;

		for (int i = 0; i < buf.length; ++i) {
			sum += 255 & buf[i];
		}

		return sum;
	}

	/**
	 * 将头信息写入字节缓冲区。
	 *
	 * @param outbuf 输出缓冲区
	 */
	public void writeEntryHeader(byte[] outbuf) {
		int offset = 0;

		offset = TarHeader.getNameBytes(this.header.name, outbuf, offset, TarHeader.NAMELEN);
		offset = Octal.getOctalBytes(this.header.mode, outbuf, offset, TarHeader.MODELEN);
		offset = Octal.getOctalBytes(this.header.userId, outbuf, offset, TarHeader.UIDLEN);
		offset = Octal.getOctalBytes(this.header.groupId, outbuf, offset, TarHeader.GIDLEN);

		long size = this.header.size;

		offset = Octal.getLongOctalBytes(size, outbuf, offset, TarHeader.SIZELEN);
		offset = Octal.getLongOctalBytes(this.header.modTime, outbuf, offset, TarHeader.MODTIMELEN);

		int csOffset = offset;
		for (int c = 0; c < TarHeader.CHKSUMLEN; ++c) {
			outbuf[offset++] = (byte) ' ';
		}

		outbuf[offset++] = this.header.linkFlag;

		offset = TarHeader.getNameBytes(this.header.linkName, outbuf, offset, TarHeader.NAMELEN);
		offset = TarHeader.getNameBytes(this.header.magic, outbuf, offset, TarHeader.USTAR_MAGICLEN);
		offset = TarHeader.getNameBytes(this.header.userName, outbuf, offset, TarHeader.USTAR_USER_NAMELEN);
		offset = TarHeader.getNameBytes(this.header.groupName, outbuf, offset, TarHeader.USTAR_GROUP_NAMELEN);
		offset = Octal.getOctalBytes(this.header.devMajor, outbuf, offset, TarHeader.USTAR_DEVLEN);
		offset = Octal.getOctalBytes(this.header.devMinor, outbuf, offset, TarHeader.USTAR_DEVLEN);
		offset = TarHeader.getNameBytes(this.header.namePrefix, outbuf, offset, TarHeader.USTAR_FILENAME_PREFIX);

		while (offset < outbuf.length) {
			outbuf[offset++] = 0;
		}

		long checkSum = this.computeCheckSum(outbuf);

		Octal.getCheckSumOctalBytes(checkSum, outbuf, csOffset, TarHeader.CHKSUMLEN);
	}

	/**
	 * 从字节缓冲区解析 Tar 头信息。
	 *
	 * @param bh 包含 Tar 头信息的字节数组
	 */
	public void parseTarHeader(byte[] bh) {
		int offset = 0;

		this.header.name = TarHeader.parseName(bh, offset, TarHeader.NAMELEN);
		offset += TarHeader.NAMELEN;

		this.header.mode = (int) Octal.parseOctal(bh, offset, TarHeader.MODELEN);
		offset += TarHeader.MODELEN;

		this.header.userId = (int) Octal.parseOctal(bh, offset, TarHeader.UIDLEN);
		offset += TarHeader.UIDLEN;

		this.header.groupId = (int) Octal.parseOctal(bh, offset, TarHeader.GIDLEN);
		offset += TarHeader.GIDLEN;

		this.header.size = Octal.parseOctal(bh, offset, TarHeader.SIZELEN);
		offset += TarHeader.SIZELEN;

		this.header.modTime = Octal.parseOctal(bh, offset, TarHeader.MODTIMELEN);
		offset += TarHeader.MODTIMELEN;

		this.header.checkSum = (int) Octal.parseOctal(bh, offset, TarHeader.CHKSUMLEN);
		offset += TarHeader.CHKSUMLEN;

		this.header.linkFlag = bh[offset++];

		this.header.linkName = TarHeader.parseName(bh, offset, TarHeader.NAMELEN);
		offset += TarHeader.NAMELEN;

		this.header.magic = TarHeader.parseName(bh, offset, TarHeader.USTAR_MAGICLEN);
		offset += TarHeader.USTAR_MAGICLEN;

		this.header.userName = TarHeader.parseName(bh, offset, TarHeader.USTAR_USER_NAMELEN);
		offset += TarHeader.USTAR_USER_NAMELEN;

		this.header.groupName = TarHeader.parseName(bh, offset, TarHeader.USTAR_GROUP_NAMELEN);
		offset += TarHeader.USTAR_GROUP_NAMELEN;

		this.header.devMajor = (int) Octal.parseOctal(bh, offset, TarHeader.USTAR_DEVLEN);
		offset += TarHeader.USTAR_DEVLEN;

		this.header.devMinor = (int) Octal.parseOctal(bh, offset, TarHeader.USTAR_DEVLEN);
		offset += TarHeader.USTAR_DEVLEN;

		this.header.namePrefix = TarHeader.parseName(bh, offset, TarHeader.USTAR_FILENAME_PREFIX);
	}
}
