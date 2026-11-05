package com.chua.datasource.support.engine;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

public class ProbeTest {

    static class User {
        private Integer id;
        private Integer age;

        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public Integer getAge() { return age; }
        public void setAge(Integer age) { this.age = age; }

        User(Integer id, Integer age) { this.id = id; this.age = age; }
    }

    @Test
    void probeWrappers() throws Exception {
        InMemoryEngine engine = new InMemoryEngine();
        engine.store("user", List.of(new User(1, 1)));

        Object del = engine.delete(User.class).eq(User::getId, 1);
// [P3C E1 豁免] 场景化输出，非业务日志
        System.out.println("DELETE runtime class: " + del.getClass().getName());
        for (Method m : del.getClass().getMethods()) {
            if (m.getName().equals("remove")) {
// [P3C E1 豁免] 场景化输出，非业务日志
                System.out.println("  remove() declared in " + m.getDeclaringClass().getName());
            }
        }

        Object upd = engine.update(User.class).eq(User::getId, 1).set(User::getAge, 5);
// [P3C E1 豁免] 场景化输出，非业务日志
        System.out.println("UPDATE runtime class: " + upd.getClass().getName());
        for (Method m : upd.getClass().getMethods()) {
            if (m.getName().equals("update")) {
// [P3C E1 豁免] 场景化输出，非业务日志
                System.out.println("  update() declared in " + m.getDeclaringClass().getName());
            }
        }

        try {
            int r = engine.delete(User.class).eq(User::getId, 1).remove();
// [P3C E1 豁免] 场景化输出，非业务日志
            System.out.println("remove() returned: " + r);
        } catch (Throwable t) {
// [P3C E1 豁免] 场景化输出，非业务日志
            System.out.println("remove() threw: " + t);
        }

        try {
            int u = engine.update(User.class).eq(User::getId, 1).set(User::getAge, 7).update();
// [P3C E1 豁免] 场景化输出，非业务日志
            System.out.println("update() returned: " + u);
        } catch (Throwable t) {
// [P3C E1 豁免] 场景化输出，非业务日志
            System.out.println("update() threw: " + t);
        }
    }
}