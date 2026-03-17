import java.lang.reflect.Method;
public class CheckDedaleApi {
    public static void main(String[] args) throws Exception {
        Class<?> clazz = Class.forName("eu.su.mas.dedale.mas.AbstractDedaleAgent");
        for (Method m : clazz.getMethods()) {
            if (m.getName().toLowerCase().contains("load") || m.getName().toLowerCase().contains("entity")) {
                System.out.println(m.getName() + " - " + m.getReturnType().getName());
                for(Class<?> p : m.getParameterTypes()) {
                    System.out.println("  Param: " + p.getName());
                }
            }
        }
    }
}
