package com.chua.common.support.file.tar;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tar 文件输入流，用于读取 TAR 归档文件。
 * <p>
 * 该流支持逐条读取 TAR 条目，并在读取完当前条目内容后自动跳过填充字节。
 * 如果未完全读取当前条目即进入下一条，将自动跳过剩余数据。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TarInputStream extends FilterInputStream {

	/**
	 * 跳过缓冲区的大小，默认为 2048 字节。
	 */
	private static final int SKIP_BUFFER_SIZE = 2048;

	/**
	 * 当前正在处理的 TAR 条目。
	 */
	private TarEntry currentEntry;

	/**
	 * 当前条目已读取的字节数。
	 */
	private long currentFileSize;

	/**
	 * 从流开始读取至今的总字节数。
	 */
	private long bytesRead;

	/**
	 * 是否使用父类的 skip 方法。默认值为 false，表示手动实现跳过逻辑以精确控制字节计数。
	 */
	private boolean defaultSkip = false;

	/**
	 * 构造一个新的 TarInputStream。
	 *
	 * @param in 底层的输入流
	 */
	public TarInputStream(InputStream in) {
		super(in);
		this.currentFileSize = 0L;
		this.bytesRead = 0L;
	}

	/**
	 * 标记功能不支持。
	 *
	 * @return false
	 */
	@Override
	public boolean markSupported() {
		return false;
	}

	/**
	 * 标记功能不被支持，直接返回。
	 *
	 * @param readlimit 读取限制（未使用）
	 */
	@Override
	public synchronized void mark(int readlimit) {
	}

	/**
	 * 重置功能不被支持，抛出异常。
	 *
	 * @throws IOException 总是抛出此异常
	 */
	@Override
	public synchronized void reset() throws IOException {
		throw new IOException("mark/reset not supported");
	}

	/**
	 * 读取单个字节。
	 *
	 * @return 读取到的字节值 (0-255)，如果到达文件末尾则返回 -1
	 * @throws IOException 发生 I/O 错误时抛出
	 */
	@Override
	public int read() throws IOException {
		byte[] buf = new byte[1];
		int res = this.read(buf, 0, 1);

		if (res != -1) {
			return 0xFF & buf[0];
		}

		return res;
	}

	/**
	 * 读取字节数组。
	 * <p>
	 * 检查读取的字节数是否超过当前条目大小，如果是则调整长度。更新字节计数器。
	 * </p>
	 *
	 * @param b 目标缓冲区
	 * @param off 起始偏移量
	 * @param len 请求读取的长度
	 * @return 实际读取的字节数，如果到达文件末尾则返回 -1
	 * @throws IOException 发生 I/O 错误时抛出
	 */
	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		if (currentEntry != null) {
			if (currentFileSize == currentEntry.getSize()) {
				return -1;
			} else if ((currentEntry.getSize() - currentFileSize) < len) {
				len = (int) (currentEntry.getSize() - currentFileSize);
			}
		}

		int br = super.read(b, off, len);

		if (br != -1) {
			if (currentEntry != null) {
				currentFileSize += br;
			}

			bytesRead += br;
		}

		return br;
	}

	/**
	 * 获取 TAR 文件中的下一个条目。
	 *
	 * @return 下一个 TAR 条目，如果没有更多条目则返回 null
	 * @throws IOException 发生 I/O 错误或文件损坏时抛出
	 */
	public TarEntry getNextEntry() throws IOException {
		closeCurrentEntry();

		byte[] header = new byte[TarConstants.HEADER_BLOCK];
		byte[] theader = new byte[TarConstants.HEADER_BLOCK];
		int tr = 0;

		while (tr < TarConstants.HEADER_BLOCK) {
			int res = read(theader, 0, TarConstants.HEADER_BLOCK - tr);

			if (res < 0) {
				break;
			}

			System.arraycopy(theader, 0, header, tr, res);
			tr += res;
		}

		boolean eof = true;
		for (byte b : header) {
			if (b != 0) {
				eof = false;
				break;
			}
		}

		if (!eof) {
			currentEntry = new TarEntry(header);
		}

		return currentEntry;
	}

	/**
	 * 获取当前流的偏移量（字节数）。
	 * <p>
	 * 这可用于确定 TAR 文件中某个条目内容开始的位置。
	 * </p>
	 *
	 * @return 当前偏移量
	 */
	public long getCurrentOffset() {
		return bytesRead;
	}

	/**
	 * 关闭当前的 TAR 条目。
	 * <p>
	 * 如果当前条目未完全读取，将跳过剩余字节并跳过条目后的填充块。
	 * </p>
	 *
	 * @throws IOException 发生 I/O 错误或检测到文件损坏时抛出
	 */
	protected void closeCurrentEntry() throws IOException {
		if (currentEntry != null) {
			if (currentEntry.getSize() > currentFileSize) {
				long bs = 0;
				while (bs < currentEntry.getSize() - currentFileSize) {
					long res = skip(currentEntry.getSize() - currentFileSize - bs);

					if (res == 0 && currentEntry.getSize() - currentFileSize > 0) {
						throw new IOException("Possible tar file corruption");
					}

					bs += res;
				}
			}

			currentEntry = null;
			currentFileSize = 0L;
			skipPad();
		}
	}

	/**
	 * 跳过每个 TAR 条目文件内容末尾的填充块。
	 * <p>
	 * TAR 文件要求每条记录必须是 512 字节的倍数，不足部分用零填充。
	 * </p>
	 *
	 * @throws IOException 发生 I/O 错误时抛出
	 */
	protected void skipPad() throws IOException {
		if (bytesRead > 0) {
			int extra = (int) (bytesRead % TarConstants.DATA_BLOCK);

			if (extra > 0) {
				long bs = 0;
				while (bs < TarConstants.DATA_BLOCK - extra) {
					long res = skip(TarConstants.DATA_BLOCK - extra - bs);
					bs += res;
				}
			}
		}
	}

	/**
	 * 跳过指定的字节数。
	 * <p>
	 * 重写父类的 skip 方法，如果启用了 defaultSkip 模式，则委托给父类；
	 * 否则通过循环读取来精确跳过字节并更新 bytesRead 计数器。
	 * </p>
	 *
	 * @param n 要跳过的字节数
	 * @return 实际跳过的字节数
	 * @throws IOException 发生 I/O 错误时抛出
	 */
	@Override
	public long skip(long n) throws IOException {
		if (defaultSkip) {
			long bs = super.skip(n);
			bytesRead += bs;

			return bs;
		}

		if (n <= 0) {
			return 0;
		}

		long left = n;
		byte[] sBuff = new byte[SKIP_BUFFER_SIZE];

		while (left > 0) {
			int res = read(sBuff, 0, (int) (left < SKIP_BUFFER_SIZE ? left : SKIP_BUFFER_SIZE));
			if (res < 0) {
				break;
			}
			left -= res;
		}

		return n - left;
	}

	/**
	 * 获取是否使用父类 skip 方法的标志。
	 *
	 * @return true 表示使用父类 skip 方法，false 表示手动实现
	 */
	public boolean isDefaultSkip() {
		return defaultSkip;
	}

	/**
	 * 设置是否使用父类 skip 方法。
	 *
	 * @param defaultSkip true 表示使用父类 skip 方法，false 表示手动实现
	 */
	public void setDefaultSkip(boolean defaultSkip) {
		this.defaultSkip = defaultSkip;
	}
}
