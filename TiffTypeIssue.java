import javax.imageio.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
public class TiffTypeIssue {
    public static void main(String[] args) throws Exception {
        BufferedImage src = new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.RED); g.fillRect(0,0,2,2); g.dispose();
        
        File tiff = new File(System.getProperty("java.io.tmpdir") + "/issue.tiff");
        ImageIO.write(src, "tiff", tiff);
        BufferedImage tiffImg = ImageIO.read(tiff);
        System.out.println("TIFF read type: " + tiffImg.getType());
        
        // Replicate BufferedImageUtils.brightnessImage logic
        try {
            int w = tiffImg.getWidth(), h = tiffImg.getHeight();
            BufferedImage dest = new BufferedImage(w, h, tiffImg.getType());
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    dest.setRGB(x, y, tiffImg.getRGB(x, y));
            System.out.println("Copy OK, dest type=" + dest.getType());
        } catch (Exception e) {
            System.out.println("Copy FAILED: " + e);
        }
        
        // Resize
        try {
            BufferedImage dest = new BufferedImage(8,8, tiffImg.getType());
            dest.getGraphics().drawImage(tiffImg, 0,0,8,8,null);
            System.out.println("Resize OK, dest type=" + dest.getType());
        } catch (Exception e) {
            System.out.println("Resize FAILED: " + e);
        }
    }
}
