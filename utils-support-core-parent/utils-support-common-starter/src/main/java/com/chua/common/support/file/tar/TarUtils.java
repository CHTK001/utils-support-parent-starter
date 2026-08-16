package com.chua.common.support.file.tar;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/**
 * Tar 文件工具类，用于计算 TAR 归档的大小。
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TarUtils {

	/**
	 * 计算给定文件或文件夹路径对应的 TAR 文件大小。
	 *
	 * @param path 要计算大小的文件或文件夹路径
	 * @return TAR 文件的总大小（包含文件头、数据块及结束标记）
	 */
	public static long calculateTarSize(File path) {
		return tarSize(path) + TarConstants.EOF_BLOCK;
	}

	/**
	 * 递归计算文件或目录在 TAR 格式中的大小。
	 *
	 * @param dir 待计算的文件或目录
	 * @return 该文件或目录在 TAR 格式下的大小
	 */
	private static long tarSize(File dir) {
		long size = 0L;

		if (dir.isFile()) {
			return entrySize(dir.length());
		} else {
			File[] subFiles = dir.listFiles();

			if (subFiles != null && subFiles.length > 0) {
				for (File file : subFiles) {
					if (file.isFile()) {
						size += entrySize(file.length());
					} else {
						size += tarSize(file);
					}
				}
			} else {
				// 空目录需要添加目录头信息
				return TarConstants.HEADER_BLOCK;
			}
		}

		return size;
	}

	/**
	 * 计算单个文件条目在 TAR 格式中的大小。
	 *
	 * @param fileSize 原始文件的大小
	 * @return 包含文件头、数据内容及填充字节后的总大小
	 */
	private static long entrySize(long fileSize) {
		long size = 0L;
		// 添加文件头大小
		size += TarConstants.HEADER_BLOCK;
		// 添加文件实际内容大小
		size += fileSize;

		long extra = size % TarConstants.DATA_BLOCK;

		if (extra > 0) {
			// 补齐到 512 字节对齐
			size += (TarConstants.DATA_BLOCK - extra);
		}

		return size;
	}

	/**
	 * 移除字符串首尾指定的字符。
	 *
	 * @param s  原始字符串
	 * @param c  需要移除的字符
	 * @return 去除首尾指定字符后的新字符串
	 */
	public static String trim(String s, char c) {
		if (s == null || s.isEmpty()) {
			return s;
		}

		StringBuilder tmp = new StringBuilder(s);

		int start = 0;
		while (start < tmp.length() && tmp.charAt(start) == c) {
			start++;
		}

		int end = tmp.length() - 1;
		while (end >= start && tmp.charAt(end) == c) {
			end--;
		}

		return tmp.substring(start, end + 1);
	}
}
