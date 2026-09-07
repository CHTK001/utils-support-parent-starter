package com.chua.remote.agent;

import com.sun.jna.platform.unix.X11;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinGDI;
import lombok.extern.slf4j.Slf4j;

import java.awt.Rectangle;

/**
 * 操作系统原生屏幕采集（替代 AWT Robot，不经 BufferedImage）。
 *
 * <p>约束：自研 agent 必须使用操作系统采集方式，不允许 {@link java.awt.Robot}。
 * 采集直接产出原始 RGB 像素（{@link NativeFrame}——显式 {@code byte[]} 拷贝），
 * 编码链路直接消费原始帧，不经 {@link java.awt.image.BufferedImage} 转换。
 * Windows 走 GDI（{@code CreateCompatibleDC + BitBlt + GetDIBits}），
 * Linux 走 X11（{@code XOpenDisplay + XGetImage}），macOS 走 CoreGraphics（实现中）。</p>
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
     * @return 原始 RGB 像素帧（不经 BufferedImage）
     */
    public static NativeFrame capture(Rectangle region) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return captureWindows(region);
        }
        if (os.contains("linux")) {
            return captureX11(region);
        }
        if (os.contains("mac")) {
            return captureMac(region);
        }
        throw new UnsupportedOperationException("[NativeScreenCapture] 不支持的操作系统: " + os);
    }

    /**
     * XImage C 结构映射（Xlib 字段顺序——X11$XImage 为 PointerType，字段经 Structure 读取）。
     */
    public static class XImageStruct extends com.sun.jna.Structure {

        public int width;
        public int height;
        public int xoffset;
        public int format;
        public com.sun.jna.Pointer data;
        public int byte_order;
        public int bitmap_unit;
        public int bitmap_bit_order;
        public int bitmap_pad;
        public int depth;
        public int bytes_per_line;
        public int bits_per_pixel;
    }

    /**
     * 自定义 X11 JNA（jna-platform 的 X11 接口缺 XGetImage 映射——按 Xlib 签名补充）。
     */
    interface X11Jna extends X11 {

        X11Jna INSTANCE = com.sun.jna.Native.load("X11", X11Jna.class);

        /**
         * XGetImage：抓取窗口区域图像（Xlib 签名——planeMask 为 unsigned long）。
         *
         * @param display  显示器
         * @param drawable 绘制对象
         * @param x        起始 x
         * @param y        起始 y
         * @param width    宽度
         * @param height   高度
         * @param planeMask 平面掩码（全平面 ~0L）
         * @param format   像素格式（ZPixmap）
         * @return XImage 指针
         */
        X11.XImage XGetImage(X11.Display display, X11.Window drawable,
                int x, int y, int width, int height, long planeMask, int format);
    }

    /**
     * macOS CoreGraphics 原生采集：CGDisplayCreateImage + CGDataProviderCopyData。
     *
     * <p>整屏采集路径（免 CGRect by-value）：主屏 CGImage → 数据提供者 → CFData
     * 字节指针显式拷贝 → RGBA 转 RGB（不经 BufferedImage）。</p>
     *
     * @param region 采集区域（整屏基线；区域裁剪由编码端按帧宽高处理）
     * @return 原始 RGB 像素帧
     */
    private static NativeFrame captureMac(Rectangle region) {
        CoreGraphics cg = CoreGraphics.INSTANCE;
        int displayId = cg.CGMainDisplayID();
        com.sun.jna.Pointer image = cg.CGDisplayCreateImage(displayId);
        if (image == null) {
            throw new IllegalStateException("[NativeScreenCapture] CGDisplayCreateImage 失败");
        }
        try {
            com.sun.jna.Pointer provider = cg.CGImageGetDataProvider(image);
            if (provider == null) {
                throw new IllegalStateException("[NativeScreenCapture] CGImageGetDataProvider 失败");
            }
            com.sun.jna.Pointer data = cg.CGDataProviderCopyData(provider);
            if (data == null) {
                throw new IllegalStateException("[NativeScreenCapture] CGDataProviderCopyData 失败");
            }
            try {
                long length = cg.CFDataGetLength(data);
                com.sun.jna.Pointer bytes = cg.CFDataGetBytePtr(data);
                // 显式像素拷贝：CFData 字节指针 → 独立 byte[]（不经 BufferedImage）
                byte[] rgba = bytes.getByteArray(0, (int) length);
                int width = (int) cg.CGImageGetWidth(image);
                int height = (int) cg.CGImageGetHeight(image);
                // RGBA→RGB 显式转换
                byte[] rgb = new byte[width * height * 3];
                for (int y = 0; y < height; y++) {
                    int line = y * width * 4;
                    int row = y * width * 3;
                    for (int x = 0; x < width; x++) {
                        int offset = line + x * 4;
                        int p = row + x * 3;
                        rgb[p] = rgba[offset];
                        rgb[p + 1] = rgba[offset + 1];
                        rgb[p + 2] = rgba[offset + 2];
                    }
                }
                return new NativeFrame(width, height, NativeFrame.FORMAT_RGB, rgb);
            } finally {
                cg.CFRelease(data);
            }
        } finally {
            cg.CGImageRelease(image);
        }
    }

    /**
     * macOS CoreGraphics JNA 直连（jna-platform 无 mac CoreGraphics 封装）。
     *
     * <p>整屏采集所需最小 API 集。编译级验证；macOS 运行时未实测（如实记录）。</p>
     */
    interface CoreGraphics extends com.sun.jna.Library {

        CoreGraphics INSTANCE = com.sun.jna.Native.load("CoreGraphics", CoreGraphics.class);

        /** 主显示器 id */
        int CGMainDisplayID();

        /** 整屏图像（CGImageRef） */
        com.sun.jna.Pointer CGDisplayCreateImage(int displayId);

        /** 图像数据提供者（CGDataProviderRef） */
        com.sun.jna.Pointer CGImageGetDataProvider(com.sun.jna.Pointer image);

        /** 提供者数据拷贝（CFDataRef） */
        com.sun.jna.Pointer CGDataProviderCopyData(com.sun.jna.Pointer provider);

        /** 数据长度 */
        long CFDataGetLength(com.sun.jna.Pointer data);

        /** 数据字节指针 */
        com.sun.jna.Pointer CFDataGetBytePtr(com.sun.jna.Pointer data);

        /** 图像宽 */
        long CGImageGetWidth(com.sun.jna.Pointer image);

        /** 图像高 */
        long CGImageGetHeight(com.sun.jna.Pointer image);

        /** 释放图像 */
        void CGImageRelease(com.sun.jna.Pointer image);

        /** 释放 CF 数据 */
        void CFRelease(com.sun.jna.Pointer data);
    }

    /**
     * Linux X11 原生采集：XOpenDisplay + XGetImage。
     *
     * <p>像素数据经 XImage 显式 {@code byte[]} 拷贝并转换为 RGB（不经 BufferedImage）。
     * 无可用 X 服务（无头环境未配 xvfb）时明确报错。</p>
     *
     * @param region 采集区域
     * @return 原始 RGB 像素帧
     */
    private static NativeFrame captureX11(Rectangle region) {
        X11 x11 = X11.INSTANCE;
        X11.Display display = x11.XOpenDisplay(null);
        if (display == null) {
            throw new IllegalStateException("[NativeScreenCapture] XOpenDisplay 失败（无可用 X 服务，"
                    + "无头环境请配置 xvfb 或 X 服务）");
        }
        try {
            int screen = x11.XDefaultScreen(display);
            X11.Window root = x11.XRootWindow(display, screen);
            X11.XImage image = X11Jna.INSTANCE.XGetImage(display, root, region.x, region.y,
                    region.width, region.height, ~0L, X11.ZPixmap);
            if (image == null) {
                throw new IllegalStateException("[NativeScreenCapture] XGetImage 失败");
            }
            try {
                // XImage 为 C 结构指针（X11$XImage extends PointerType）——经 jna Structure 映射读取字段
                XImageStruct xis = com.sun.jna.Structure.newInstance(XImageStruct.class, image.getPointer());
                int width = xis.width;
                int height = xis.height;
                int bytesPerPixel = xis.bits_per_pixel / 8;
                int bytesPerLine = xis.bytes_per_line;
                byte[] data = xis.data.getByteArray(0, height * bytesPerLine);
                // BGR→RGB 显式转换（原始像素拷贝——不经 BufferedImage）
                byte[] rgb = new byte[width * height * 3];
                for (int y = 0; y < height; y++) {
                    int line = y * bytesPerLine;
                    int row = y * width * 3;
                    for (int x = 0; x < width; x++) {
                        int offset = line + x * bytesPerPixel;
                        int p = row + x * 3;
                        rgb[p] = data[offset + 2];
                        rgb[p + 1] = data[offset + 1];
                        rgb[p + 2] = data[offset];
                    }
                }
                return new NativeFrame(width, height, NativeFrame.FORMAT_RGB, rgb);
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
     * <p>像素数据经 GetDIBits 显式拷贝到独立 {@code byte[]} 缓冲并转换为 RGB
     * （不经 BufferedImage，不使用零拷贝共享缓冲）。</p>
     *
     * @param region 采集区域
     * @return 原始 RGB 像素帧
     */
    private static NativeFrame captureWindows(Rectangle region) {
        int width = region.width;
        int height = region.height;
        WinDef.HDC screenDc = User32.INSTANCE.GetDC(null);
        WinDef.HDC memoryDc = GDI32.INSTANCE.CreateCompatibleDC(screenDc);
        WinDef.HBITMAP bitmap = GDI32.INSTANCE.CreateCompatibleBitmap(screenDc, width, height);
        // GDI32.SelectObject 返回/入参为 WinNT.HANDLE（非 WinDef.HANDLE）
        com.sun.jna.platform.win32.WinNT.HANDLE oldBitmap = GDI32.INSTANCE.SelectObject(memoryDc, bitmap);
        try {
            if (!GDI32.INSTANCE.BitBlt(memoryDc, 0, 0, width, height, screenDc,
                    region.x, region.y, 0x00CC0020)) {
                throw new IllegalStateException("[NativeScreenCapture] GDI BitBlt 失败");
            }
            // 显式像素拷贝：GetDIBits 到独立 Memory 缓冲（BGRA 32bpp），随后读回 byte[]
            byte[] bgra = new byte[width * height * 4];
            com.sun.jna.Memory dibitsBuffer = new com.sun.jna.Memory(bgra.length);
            WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
            bmi.bmiHeader.biSize = bmi.bmiHeader.size();
            bmi.bmiHeader.biWidth = width;
            bmi.bmiHeader.biHeight = -height;
            bmi.bmiHeader.biPlanes = 1;
            bmi.bmiHeader.biBitCount = 32;
            bmi.bmiHeader.biCompression = WinGDI.BI_RGB;
            int lines = GDI32.INSTANCE.GetDIBits(memoryDc, bitmap, 0, height, dibitsBuffer, bmi, WinGDI.DIB_RGB_COLORS);
            if (lines == 0) {
                throw new IllegalStateException("[NativeScreenCapture] GetDIBits 失败");
            }
            // 显式读回：GetDIBits 写入 Memory → 显式拷贝到 byte[]（不经零拷贝共享缓冲）
            dibitsBuffer.read(0, bgra, 0, bgra.length);
            // BGRA→RGB 显式转换（原始像素拷贝——不经 BufferedImage）
            byte[] rgb = new byte[width * height * 3];
            for (int y = 0; y < height; y++) {
                int line = y * width * 4;
                int row = y * width * 3;
                for (int x = 0; x < width; x++) {
                    int offset = line + x * 4;
                    int p = row + x * 3;
                    rgb[p] = bgra[offset + 2];
                    rgb[p + 1] = bgra[offset + 1];
                    rgb[p + 2] = bgra[offset];
                }
            }
            return new NativeFrame(width, height, NativeFrame.FORMAT_RGB, rgb);
        } finally {
            GDI32.INSTANCE.SelectObject(memoryDc, oldBitmap);
            GDI32.INSTANCE.DeleteObject(bitmap);
            GDI32.INSTANCE.DeleteDC(memoryDc);
            User32.INSTANCE.ReleaseDC(null, screenDc);
        }
    }
}
