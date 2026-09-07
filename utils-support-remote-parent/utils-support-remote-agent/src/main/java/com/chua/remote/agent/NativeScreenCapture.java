package com.chua.remote.agent;

import com.sun.jna.platform.win32.Gdi32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.awt.Rectangle;

/**
 * 操作系统原生屏幕采集（替代 AWT Robot）。
 *
 * <p>约束：自研 agent 必须使用操作系统采集方式，不允许 {@link java.awt.Robot}。
 * Windows 走 GDI（{@code CreateCompatibleDC + BitBlt + GetDIBits}），像素数据经
 * 显式 {@code byte[]} 拷贝回填 {@link BufferedImage}（不引入零拷贝共享缓冲）。
 * 平台分发：Windows 原生 GDI；Linux/macOS 的 X11/CoreGraphics 原生路径为结构占位
 * （同 JNA 方式，按平台实现）。</p>
 *
 * @author CH
 * @since 4.0.0.42
 */
@Slf4j
public final class NativeScreenCapture {

    private NativeScreenCapture() {
    }

    /**
     * 按操作系统原生方式采集屏幕区域。
     *
     * @param region 采集区域（虚拟桌面坐标）
     * @return 采集图像（显式像素拷贝）
     */
    public static BufferedImage capture(Rectangle region) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return captureWindows(region);
        }
        if (os.contains("linux")) {
            return captureX11(region);
        }
        if (os.contains("mac")) {
            throw new UnsupportedOperationException(
                    "[NativeScreenCapture] macOS 的 CoreGraphics 原生采集路径实现中（同 Windows GDI 方式）");
        }
        throw new UnsupportedOperationException("[NativeScreenCapture] 不支持的操作系统: " + os);
    }

    /**
     * Linux X11 原生采集：XOpenDisplay + XGetImage。
     *
     * <p>像素数据经 XImage 显式 {@code byte[]} 拷贝回填 {@link BufferedImage}
     * （不引入零拷贝共享缓冲）。无可用 X 服务（无头环境未配 xvfb）时明确报错。</p>
     *
     * @param region 采集区域
     * @return 采集图像
     */
    private static BufferedImage captureX11(Rectangle region) {
        X11 x11 = X11.INSTANCE;
        X11.Display display = x11.XOpenDisplay(null);
        if (display == null) {
            throw new IllegalStateException("[NativeScreenCapture] XOpenDisplay 失败（无可用 X 服务，"
                    + "无头环境请配置 xvfb 或 X 服务）");
        }
        try {
            int screen = x11.XDefaultScreen(display);
            X11.Window root = x11.XRootWindow(display, screen);
            X11.XImage image = x11.XGetImage(display, root, region.x, region.y,
                    region.width, region.height, X11.AllPlanes, X11.ZPixmap);
            if (image == null) {
                throw new IllegalStateException("[NativeScreenCapture] XGetImage 失败");
            }
            try {
                int bytesPerPixel = image.getBitsPerPixel() / 8;
                int bytesPerLine = image.getBytesPerLine();
                byte[] data = image.getData().getByteArray(0, region.height * bytesPerLine);
                BufferedImage result = new BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_RGB);
                // 直接写 raster 数据缓冲（int[] 一次性填充——避免逐像素 setRGB 的方法调用开销，30fps 采集必需）
                int[] pixels = ((java.awt.image.DataBufferInt) result.getRaster().getDataBuffer()).getData();
                for (int y = 0; y < region.height; y++) {
                    int row = y * region.width;
                    int line = y * bytesPerLine;
                    for (int x = 0; x < region.width; x++) {
                        int offset = line + x * bytesPerPixel;
                        pixels[row + x] = ((data[offset + 2] & 0xFF) << 16)
                                | ((data[offset + 1] & 0xFF) << 8)
                                | (data[offset] & 0xFF);
                    }
                }
                return result;
            } finally {
                x11.XDestroyImage(image);
            }
        } finally {
            x11.XCloseDisplay(display);
        }
    }

    /**
     * Windows GDI 采集：CreateCompatibleDC + BitBlt + GetDIBits。
     *
     * <p>像素数据经 GetDIBits 显式拷贝到独立 {@code byte[]} 缓冲，再回填
     * {@link BufferedImage}——数据路径全程显式复制，不使用零拷贝共享缓冲。</p>
     *
     * @param region 采集区域
     * @return 采集图像
     */
    private static BufferedImage captureWindows(Rectangle region) {
        int width = region.width;
        int height = region.height;
        WinDef.HDC screenDc = User32.INSTANCE.GetDC(null);
        WinDef.HDC memoryDc = Gdi32.INSTANCE.CreateCompatibleDC(screenDc);
        WinDef.HBITMAP bitmap = Gdi32.INSTANCE.CreateCompatibleBitmap(screenDc, width, height);
        WinDef.HANDLE oldBitmap = Gdi32.INSTANCE.SelectObject(memoryDc, bitmap);
        try {
            if (!Gdi32.INSTANCE.BitBlt(memoryDc, 0, 0, width, height, screenDc,
                    region.x, region.y, WinGDI.SRCCOPY)) {
                throw new IllegalStateException("[NativeScreenCapture] GDI BitBlt 失败");
            }
            // 显式像素拷贝：GetDIBits 到独立 byte[] 缓冲
            byte[] pixels = new byte[width * height * 4];
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biSize = bmi.bmiHeader.size();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height;
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            int lines = Gdi32.INSTANCE.GetDIBits(memoryDc, bitmap, 0, height, pixels, bmi, WinGDI.DIB_RGB_COLORS);
            if (lines == 0) {
                throw new IllegalStateException("[NativeScreenCapture] GetDIBits 失败");
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            // 直接写 raster 数据缓冲（int[] 一次性填充——避免逐像素 setRGB 的方法调用开销，30fps 采集必需）
            int[] target = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
            // 像素回填（显式复制——BI_RGB 32bpp 为 B/G/R/A 顺序，源为 GetDIBits 的 byte[] 缓冲）
            for (int y = 0; y < height; y++) {
                int row = y * width;
                int line = y * width * 4;
                for (int x = 0; x < width; x++) {
                    int offset = line + x * 4;
                    target[row + x] = ((pixels[offset + 2] & 0xFF) << 16)
                            | ((pixels[offset + 1] & 0xFF) << 8)
                            | (pixels[offset] & 0xFF);
                }
            }
            return image;
        } finally {
            Gdi32.INSTANCE.SelectObject(memoryDc, oldBitmap);
            Gdi32.INSTANCE.DeleteObject(bitmap);
            Gdi32.INSTANCE.DeleteDC(memoryDc);
            User32.INSTANCE.ReleaseDC(null, screenDc);
        }
    }
}
