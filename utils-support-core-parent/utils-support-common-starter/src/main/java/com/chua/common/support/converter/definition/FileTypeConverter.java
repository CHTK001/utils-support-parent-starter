package com.chua.common.support.converter.definition;

import com.chua.common.support.utils.ArrayUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static com.chua.common.support.constant.CommonConstant.FILE_PROTOCOL;


/**
 * File 类型转换器。
 * <p>将各种类型的值转换为 {@link File}，支持以下输入类型：</p>
 * <ul>
 *   <li>{@link File} / {@link Path} — 直接返回或转换</li>
 *   <li>{@link String} — 支持 HTTP URL、file:/ 协议、Base64 Data URL、classpath:、classpath*:、用户目录、临时目录等多种路径解析</li>
 *   <li>{@link URL} (file://协议) / {@link java.net.URI} — 转为 File</li>
 *   <li>数组类型 — 遍历取第一个 File 元素</li>
 * </ul>
 *
 * @author CH
 * @version 1.0.0
 * @since 2021/5/24
 */
public class FileTypeConverter implements TypeConverter<File> {

    /** 操作系统默认临时目录 */
    /** Temp */
    private static final String[] TEMP = new String[]{"Documents", "Downloads", "Desktop"};
    /**
     * 数据
     */
    private static final String DATA = "data:";
    /** base64 数据前缀 */
    /** Base64 */
    private static final CharSequence BASE64 = "base64,";
    /** HTTP 协议前缀 */
    /** Http_prefix */
    private static final String HTTP_PREFIX = "http";
    /** classpath 资源路径前缀 */
    /** Classpath_url_prefix */
    private static final String CLASSPATH_URL_PREFIX = "classpath:";
    /** classpath 通配资源路径前缀 */
    /** Classpath_url_all_prefix */
    private static final String CLASSPATH_URL_ALL_PREFIX = "classpath*:";

    /**
     * 将给定值转换为 File。
     *
     * @param value 源值
     * @return File 值，如果无法转换则返回 null
     */
    @Override
    public File convert(Object value) {
        switch (value) {
            case null -> {
                return null;
            }
            case File file -> {
                return file;
            }
            case Path path -> {
                return path.toFile();
            }
            case String s -> {
                return stringToFile(value.toString());
            }
            case URL url when FILE_PROTOCOL.equalsIgnoreCase(url.getProtocol()) -> {
                return new File(((URL) value).getFile());
            }
            case URI uri -> {
                try {
                    return new File(uri.toURL().getFile());
                } catch (MalformedURLException ignored) {
                }
            }
            default -> {
            }
        }

        if (ArrayUtils.isArray(value)) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                Object o = Array.get(value, i);
                if(null != o && o instanceof File) {
                    return (File) o;
                }
            }
            value = Array.get(value, 0);
        }

        return null;
    }

    /**
     * 将字符串转换为 File。
     * <p>支持的字符串格式（按优先级降序）：</p>
     * <ol>
     *   <li>http/https URL — 转为 URI 后取路径</li>
     *   <li>file: 协议 URL</li>
     *   <li>Base64 Data URL (data:...;base64,...) — 解码为临时文件</li>
     *   <li>classpath: / classpath*: 前缀 — 从类路径查找</li>
     *   <li>用户目录 ({@code user_home}) 下查找</li>
     *   <li>Documents/Downloads/Desktop 子目录下查找</li>
     *   <li>直接作为文件路径</li>
     * </ol>
     */
    private File stringToFile(String str) {
        if (str.startsWith(HTTP_PREFIX)) {
            try {
                return new File(new URI(str).toURL().getFile());
            } catch (Exception ignored) {
            }
        }
        if (str.startsWith(FILE_PROTOCOL)) {
            try {
                return new File(new URI(str).toURL().getFile());
            } catch (Exception ignored) {
            }
        }

        if (str.startsWith(DATA) && str.contains(BASE64)) {
            try {
                byte[] b = Base64.getDecoder().decode(str.substring(str.indexOf("base64,") + 7));
                for (int i = 0; i < b.length; ++i) {
                    if (b[i] < 0) {
                        b[i] += (byte) 256;
                    }
                }
                File tempFile = Files.createTempFile("converter", UUID.randomUUID().toString() + ".jpg").toFile();
                try (OutputStream out = new FileOutputStream(tempFile)) {
                    out.write(b);
                    out.flush();
                    return tempFile;
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            } catch (Exception ignore) {
            }
        }

        File temp = null;
        if(str.startsWith(CLASSPATH_URL_PREFIX) || str.startsWith(CLASSPATH_URL_ALL_PREFIX)) {
            URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(
                str.startsWith(CLASSPATH_URL_PREFIX) ? str.substring(CLASSPATH_URL_PREFIX.length()) : str.substring(CLASSPATH_URL_ALL_PREFIX.length())
            );
            if (resourceUrl != null && FILE_PROTOCOL.equals(resourceUrl.getProtocol())) {
                temp = new File(resourceUrl.getFile());
            }
        }

        if (null != temp && temp.exists()) {
            return temp;
        }

        if(str.startsWith(CLASSPATH_URL_PREFIX)) {
            temp = new File("src/main/resources", str.substring(CLASSPATH_URL_PREFIX.length()));
        } else {
            temp = new File("src/main/resources", str);
        }

        if (temp.exists()) {
            return temp;
        }

        URL resource = null;
        if(str.startsWith(CLASSPATH_URL_PREFIX)) {
            resource = ClassLoader.getSystemClassLoader().getResource(str.substring(CLASSPATH_URL_PREFIX.length()));
        } else {
            resource = ClassLoader.getSystemClassLoader().getResource(str);
        }

        if (null != resource && FILE_PROTOCOL.equals(resource.getProtocol())) {
            temp = new File(resource.getFile());
            if(temp.exists()) {
                return temp;
            }
        }

        String userHome = System.getProperty("user_home");
        temp = new File(userHome, str);
        if (temp.exists()) {
            return temp;
        }

        for (String s : TEMP) {
            temp = new File(userHome + "/" + s, str);
            if (temp.exists()) {
                return temp;
            }
        }

        try {
            File file = new File(str);
            if(file.exists()) {
                return file;
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * 获取当前转换器支持的目标类型。
     *
     * @return File.class
     */
    @Override
    public Class<File> getType() {
        return File.class;
    }
}
