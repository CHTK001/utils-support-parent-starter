import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;

public class TiffVisualCompare {
    public static void main(String[] args) throws Exception {
        // Create a visually distinctive test image
        BufferedImage src = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        
        // Gradient background
        g.setPaint(new GradientPaint(0, 0, Color.RED, 100, 100, Color.BLUE));
        g.fillRect(0, 0, 100, 100);
        
        // White circle
        g.setColor(Color.WHITE);
        g.fillOval(30, 30, 40, 40);
        
        // Green text-like rectangle
        g.setColor(Color.GREEN);
        g.fillRect(10, 10, 20, 20);
        g.setColor(Color.YELLOW);
        g.fillRect(70, 70, 20, 20);
        
        g.dispose();
        
        File jpg = new File(System.getProperty("java.io.tmpdir") + "/src.jpg");
        File tiff = new File(System.getProperty("java.io.tmpdir") + "/src.tiff");
        File heic = new File(System.getProperty("java.io.tmpdir") + "/src.heic");
        
        ImageIO.write(src, "jpg", jpg);
        ImageIO.write(src, "tiff", tiff);
        
        BufferedImage jpgImg = ImageIO.read(jpg);
        BufferedImage tiffImg = ImageIO.read(tiff);
        
        System.out.println("=== 源图 ===");
        printPixelInfo(src, "  ");
        
        System.out.println("\n=== JPG 读回 ===");
        System.out.println("  type=" + jpgImg.getType() + " colorModel=" + jpgImg.getColorModel().getClass().getSimpleName());
        printPixelInfo(jpgImg, "  ");
        
        System.out.println("\n=== TIFF 读回 ===");
        System.out.println("  type=" + tiffImg.getType() + " colorModel=" + tiffImg.getColorModel().getClass().getSimpleName());
        printPixelInfo(tiffImg, "  ");
        
        // Compare pixels
        int jpgDiff = comparePixels(src, jpgImg);
        int tiffDiff = comparePixels(src, tiffImg);
        System.out.println("\n像素差异: JPG=" + jpgDiff + "  TIFF=" + tiffDiff + " (总像素数=" + (100*100) + ")");
        
        // Check if the HEIC path works
        ImageIO.write(tiffImg, "heic", heic);
        BufferedImage heicImg = ImageIO.read(heic);
        System.out.println("\nTIFF→HEIC→读回: type=" + heicImg.getType());
        int heicDiff = comparePixels(src, heicImg);
        System.out.println("  像素差异: " + heicDiff);
        
        // Save outputs for visual inspection
        ImageIO.write(jpgImg, "jpg", new File(System.getProperty("java.io.tmpdir") + "/compare_jpg.jpg"));
        ImageIO.write(tiffImg, "jpg", new File(System.getProperty("java.io.tmpdir") + "/compare_tiff_as_jpg.jpg"));
        System.out.println("\n已保存到临时目录用于肉眼对比");
    }
    
    static void printPixelInfo(BufferedImage img, String prefix) {
        int[] samples = {0, 25, 50, 75, 99};
        for (int y : samples) {
            for (int x : samples) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                System.out.printf("%s  [%d,%d]=%d,%d,%d (0x%06x)%n", prefix, x, y, r, g, b, (r<<16)|(g<<8)|b);
            }
        }
    }
    
    static int comparePixels(BufferedImage a, BufferedImage b) {
        int diff = 0;
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (a.getRGB(x, y) != b.getRGB(x, y)) diff++;
        return diff;
    }
}
