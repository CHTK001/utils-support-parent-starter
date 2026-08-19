/**
 * 简易 classloader 自检工具，用于排查 native 库加载路径问题。
 *
 * @author CH
 * @since 4.0.0
 */
public class TestClassLoader {
    /** Main */
    public static void main(String[] args) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        System.out.println("ContextClassLoader: " + cl);
        System.out.println("Context resource: " + cl.getResource("native/windows-x86_64/"));
        System.out.println("Context resource (slash): " + cl.getResource("/native/windows-x86_64/"));

        ClassLoader myCl = TestClassLoader.class.getClassLoader();
        System.out.println("MyClassLoader: " + myCl);
        System.out.println("My resource: " + myCl.getResource("native/windows-x86_64/"));
        System.out.println("My resource (slash): " + myCl.getResource("/native/windows-x86_64/"));

        java.net.URL url = myCl.getResource("native/windows-x86_64/libarcsoft_face.dll");
        System.out.println("File resource: " + url);
    }
}
