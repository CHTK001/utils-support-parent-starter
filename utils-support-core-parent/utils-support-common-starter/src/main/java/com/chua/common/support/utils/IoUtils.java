package com.chua.common.support.utils;

import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.chua.common.support.constant.CommonConstant.INDEX_NOT_FOUND;
import static com.chua.common.support.constant.NumberConstant.DEFAULT_BUFFER_SIZE;

/**
 * IO 工具类
 *
 * @author CH
 * @since 4.0.0.42
 */
public class IoUtils {

    /** AsBytes */
    public static byte[] asBytes(final InputStreamReader input, final Charset charset) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output, charset);
            return output.toByteArray();
        }
    }

    /** AsBytes */
    public static byte[] asBytes(final InputStreamReader input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output, Charset.defaultCharset());
            return output.toByteArray();
        }
    }
    /** AsBytes */
    public static byte[] asBytes(final InputStream input) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return output.toByteArray();
        }
    }

    /** ToByteArray */
    public static byte[] toByteArray(final InputStream input) throws IOException {
        return asBytes(input);
    }

    /** ToByteArray */
    public static byte[] toByteArray(final InputStream input, final Charset charset) throws IOException {
        return asBytes(input, charset);
    }

    /** AsBytes */
    public static byte[] asBytes(final InputStream input, final Charset charset) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(new InputStreamReader(input, charset), output, charset);
            return output.toByteArray();
        }
    }


    /** AsBytes */
    public static byte[] asBytes(final URL url) {
        try {
            return asBytes(openStream(url));
        } catch (IOException e) {
            return null;
        }
    }

    /** AsBytes */
    public static byte[] asBytes(final File file) {
        try {
            return asBytes(openStream(file));
        } catch (IOException e) {
            return null;
        }
    }

    /** AsBytes */
    public static byte[] asBytes(final Path path) {
        try {
            return asBytes(openStream(path));
        } catch (IOException e) {
            return null;
        }
    }

    /** 打开Buffer */
    public static ByteBuffer openBuffer(final byte[] bytes) {
        return ByteBuffer.wrap(bytes);
    }


    /** 打开Stream */
    public static InputStream openStream(final URL url) throws IOException {
        return null != url ? url.openStream() : null;
    }

    /** 打开Stream */
    public static InputStream openStream(final File file) throws IOException {
        if (null == file) {
            throw new FileNotFoundException();
        }

        if (!file.exists()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }

        if (!file.canRead()) {
            throw new IOException("File cannot be read: " + file.getAbsolutePath());
        }
        return Files.newInputStream(file.toPath());
    }

    /** 打开Stream */
    public static InputStream openStream(final Path path) throws IOException {
        if (null == path) {
            throw new FileNotFoundException();
        }
        return openStream(path.toFile());
    }

    /** 复制 */
    public static InputStream copy(final InputStream input) throws IOException {
        try (ByteArrayOutputStream baas = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int len;
            while ((len = input.read(buffer)) > -1) {
                baas.write(buffer, 0, len);
            }
            baas.flush();
            return new ByteArrayInputStream(baas.toByteArray());
        } catch (IOException e) {
            throw e;
        }
    }

    /** 复制 */
    public static void copy(final Reader input, final OutputStream output, final Charset charset)
            throws IOException {
        final OutputStreamWriter out = new OutputStreamWriter(output, charset);
        copy(input, out);
        out.flush();
    }

    /** 复制 */
    public static void copy(final InputStream input, final Writer output, final Charset charset) throws IOException {
        final InputStreamReader in = new InputStreamReader(input, charset);
        copy(in, output);
    }

    /** 复制 */
    public static long copy(final InputStream input, final OutputStream output, final int bufferSize) throws IOException {
        return copyLarge(input, output, new byte[bufferSize]);
    }

    /** 复制 */
    public static int copy(final Reader input, final Writer output) throws IOException {
        final long count = copyLarge(input, output);
        if (count > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) count;
    }

    /** 复制 */
    public static int copy(final InputStream input, final OutputStream output) throws IOException {
        final long count = copyLarge(input, output);
        if (count > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) count;
    }

    /** 复制Large */
    public static long copyLarge(final Reader input, final Writer output) throws IOException {
        return copyLarge(input, output, new char[DEFAULT_BUFFER_SIZE]);
    }

    /** 复制Large */
    public static long copyLarge(final Reader input, final Writer output, final char[] buffer) throws IOException {
        long count = 0;
        int n;
        while (INDEX_NOT_FOUND != (n = input.read(buffer))) {
            output.write(buffer, 0, n);
            count += n;
        }
        return count;
    }

    /** 复制Large */
    public static long copyLarge(final InputStream input, final OutputStream output, final byte[] buffer) throws IOException {
        try (InputStream is = input;
             OutputStream os = output
        ) {
            long count = 0;
            int n;
            while (INDEX_NOT_FOUND != (n = is.read(buffer))) {
                os.write(buffer, 0, n);
                count += n;
            }
            return count;
        }
    }

    /** 复制Large */
    public static long copyLarge(final InputStream input, final OutputStream output) throws IOException {
        return copy(input, output, DEFAULT_BUFFER_SIZE);
    }


    /** 关闭Quietly */
    public static void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /** 关闭Quietly */
    public static void closeQuietly(final URLConnection conn) {
        if (conn instanceof HttpURLConnection) {
            ((HttpURLConnection) conn).disconnect();
        }
    }

    /** 关闭Quietly */
    public static void closeQuietly(Graphics2D graphics2d) {
        if (null == graphics2d) {
            return;
        }
        graphics2d.dispose();
    }

    /** 关闭Quietly */
    public static void closeQuietly(Process process) {
        if (null == process) {
            return;
        }
        process.destroy();
    }

    /** AsString */
    public static String asString(InputStreamReader inputStreamReader) {
        try (inputStreamReader){
            return new String(asBytes(inputStreamReader));
        } catch (IOException e) {
            return null;
        }
    }

    /** AsString */
    public static String asString(InputStreamReader inputStreamReader, Charset charset) {
        try (inputStreamReader){
            return new String(asBytes(inputStreamReader, charset));
        } catch (IOException e) {
            return null;
        }
    }

    /** AsString */
    public static String asString(InputStream input) {
        try (input){
            return new String(asBytes(input));
        } catch (IOException e) {
            return null;
        }
    }

    /** AsString */
    public static String asString(InputStream input, Charset charset) {
        try (input){
            return new String(asBytes(input, charset));
        } catch (IOException e) {
            return null;
        }
    }
}
