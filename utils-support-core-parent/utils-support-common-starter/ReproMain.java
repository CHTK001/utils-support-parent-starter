import com.chua.common.support.objects.creator.Quick;

public class ReproMain {
    public static void main(String[] args) {
        Quick quick1 = Quick.create();
        Quick quick2 = Quick.create();
        quick1.variable("name", "zhang");
        Object v1 = quick1.get("name");
        Object v2 = quick2.get("name");
        System.out.println("quick1.get(name)=" + v1 + " (class=" + (v1 == null ? "null" : v1.getClass().getName()) + ")");
        System.out.println("quick2.get(name)=" + v2 + " (class=" + (v2 == null ? "null" : v2.getClass().getName()) + ")");
        System.out.println("containsBean quick2=" + quick2.context().containsBean("name"));
        System.out.println("containsBean quick1=" + quick1.context().containsBean("name"));
        quick1.close();
        quick2.close();
    }
}