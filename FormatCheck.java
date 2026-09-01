import javax.imageio.*;
import java.util.*;
public class FormatCheck {
    public static void main(String[] args) {
        System.out.println("=== Readers ===");
        Iterator<ImageReader> ri = ImageIO.getImageReadersByFormatName("png");
        System.out.println("png reader: " + (ri.hasNext() ? ri.next().getClass().getSimpleName() : "none"));
        ri = ImageIO.getImageReadersByFormatName("jpg");
        System.out.println("jpg reader: " + (ri.hasNext() ? ri.next().getClass().getSimpleName() : "none"));
        ri = ImageIO.getImageReadersByFormatName("heic");
        System.out.println("heic reader: " + (ri.hasNext() ? ri.next().getClass().getSimpleName() : "none"));
        ri = ImageIO.getImageReadersByFormatName("heif");
        System.out.println("heif reader: " + (ri.hasNext() ? ri.next().getClass().getSimpleName() : "none"));
        ri = ImageIO.getImageReadersByFormatName("webp");
        System.out.println("webp reader: " + (ri.hasNext() ? ri.next().getClass().getSimpleName() : "none"));
        ri = ImageIO.getImageReadersByFormatName("bmp");
        System.out.println("bmp reader: " + (ri.hasNext() ? ri.next().getClass().getSimpleName() : "none"));
        ri = ImageIO.getImageReadersByFormatName("tiff");
        System.out.println("tiff reader: " + (ri.hasNext() ? ri.next().getClass().getSimpleName() : "none"));
        
        System.out.println("\n=== Writers ===");
        String[] fmts = {"png","jpg","heic","heif","webp","bmp","gif","tiff"};
        for (String f : fmts) {
            Iterator<ImageWriter> w = ImageIO.getImageWritersByFormatName(f);
            System.out.println(f + " writer: " + (w.hasNext() ? w.next().getClass().getSimpleName() : "none"));
        }
        
        System.out.println("\n=== All reader formats ===");
        System.out.println(Arrays.toString(ImageIO.getReaderFormatNames()));
        System.out.println("\n=== All writer formats ===");
        System.out.println(Arrays.toString(ImageIO.getWriterFormatNames()));
    }
}
