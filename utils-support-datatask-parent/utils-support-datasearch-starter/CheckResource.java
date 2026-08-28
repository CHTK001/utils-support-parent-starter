import java.io.*;
public class CheckResource {
    public static void main(String[] a) throws Exception {
        InputStream is = CheckResource.class.getClassLoader().getResourceAsStream("blocked-providers.json");
        System.out.println("IS: " + (is != null ? "found" : "null"));
        if (is != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(is));
            String line = r.readLine();
            System.out.println(line);
        }
    }
}
