package com.chua.common.support.file.tar;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * 用于写入 TAR 格式归档文件的输出流。
 * <p>
 * 该类扩展了 {@link OutputStream}，提供了将文件内容以 TAR 格式写入流或文件的功能。
 * 它支持在写入条目前关闭当前条目，自动填充数据块边界，并在流结束时写入 EOF 记录。
 * </p>
 *
 * @author CH
 * @since 4.0.0.42
 */
public class TarOutputStream extends OutputStream {

    /**
     * 底层的输出流。
     */
    private final OutputStream out;

    /**
     * 已写入的总字节数。
     */
    private long bytesWritten;

    /**
     * 当前条目的已写入字节数。
     */
    private long currentFileSize;

    /**
     * 当前正在处理的 TAR 条目。
     */
    private TarEntry currentEntry;

    /**
     * 使用指定的输出流创建新的 TAR 输出流。
     *
     * @param out 目标输出流
     */
    public TarOutputStream(OutputStream out) {
        this.out = out;
        this.bytesWritten = 0;
        this.currentFileSize = 0;
    }

    /**
     * 使用指定的文件创建新的 TAR 输出流。
     *
     * @param fout 目标文件
     * @throws FileNotFoundException 如果文件不存在且无法打开进行写入
     */
    public TarOutputStream(final File fout) throws FileNotFoundException {
        this.out = new BufferedOutputStream(new FileOutputStream(fout));
        this.bytesWritten = 0;
        this.currentFileSize = 0;
    }

    /**
     * 使用指定的文件创建新的 TAR 输出流，并可选择是否追加模式。
     * 如果处于追加模式且文件大小超过 EOF 块大小，则定位到文件末尾之前的 EOF 块位置。
     *
     * @param fout   目标文件
     * @param append 如果为 true 则启用追加模式
     * @throws IOException 如果发生 I/O 错误
     */
    public TarOutputStream(final File fout, final boolean append) throws IOException {
        @SuppressWarnings("resource")
        RandomAccessFile raf = new RandomAccessFile(fout, "rw");
        final long fileSize = fout.length();
        if (append && fileSize > TarConstants.EOF_BLOCK) {
            raf.seek(fileSize - TarConstants.EOF_BLOCK);
        }
        this.out = new BufferedOutputStream(new FileOutputStream(raf.getFD()));
    }

    /**
     * 追加 EOF 记录并关闭流。
     *
     * @see java.io.FilterOutputStream#close()
     */
    @Override
    public void close() throws IOException {
        closeCurrentEntry();
        write(new byte[TarConstants.EOF_BLOCK]);
        out.close();
    }

    /**
     * 向流中写入一个字节并更新字节计数器。
     *
     * @param b 要写入的字节
     * @see java.io.FilterOutputStream#write(int)
     */
    @Override
    public void write(int b) throws IOException {
        out.write(b);
        bytesWritten += 1;

        if (currentEntry != null) {
            currentFileSize += 1;
        }
    }

    /**
     * 检查写入的字节是否超过当前条目的大小限制。
     *
     * @param b 包含数据的字节数组
     * @param off 起始偏移量
     * @param len 要写入的字节数
     * @see java.io.FilterOutputStream#write(byte[], int, int)
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (currentEntry != null && !currentEntry.isDirectory()) {
            if (currentEntry.getSize() < currentFileSize + len) {
                throw new IOException(
                        "The current entry[" + currentEntry.getName() + "] size["
                                + currentEntry.getSize() + "] is smaller than the bytes["
                                + (currentFileSize + len) + "] being written."
                );
            }
        }

        out.write(b, off, len);
        bytesWritten += len;

        if (currentEntry != null) {
            currentFileSize += len;
        }
    }

    /**
     * 在流中写入下一个 TAR 条目的头部。
     *
     * @param entry 要写入的 TAR 条目
     * @throws IOException 如果发生 I/O 错误
     */
    public void putNextEntry(TarEntry entry) throws IOException {
        closeCurrentEntry();

        byte[] header = new byte[TarConstants.HEADER_BLOCK];
        entry.writeEntryHeader(header);

        write(header);
        currentEntry = entry;
    }

    /**
     * 关闭当前的 TAR 条目。
     * 如果条目未完全写入，将抛出异常。
     *
     * @throws IOException 如果发生 I/O 错误
     */
    protected void closeCurrentEntry() throws IOException {
        if (currentEntry != null) {
            if (currentEntry.getSize() > currentFileSize) {
                throw new IOException(
                        "The current entry[" + currentEntry.getName() + "] of size["
                                + currentEntry.getSize() + "] has not been fully written."
                );
            }

            currentEntry = null;
            currentFileSize = 0;

            pad();
        }
    }

    /**
     * 填充最后一个内容块，使其对齐到 TAR 块边界（通常为 512 字节）。
     *
     * @throws IOException 如果发生 I/O 错误
     */
    protected void pad() throws IOException {
        if (bytesWritten > 0) {
            int extra = (int) (bytesWritten % TarConstants.DATA_BLOCK);

            if (extra > 0) {
                write(new byte[TarConstants.DATA_BLOCK - extra]);
            }
        }
    }
}
