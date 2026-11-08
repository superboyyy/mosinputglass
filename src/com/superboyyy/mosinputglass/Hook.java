package com.superboyyy.mosinputglass;

import java.lang.reflect.Executable;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/**
 * before/after 风格的 hook，转成 libxposed 的 intercept(chain)。
 * before 里 setResult 就不调原方法；args 改了会原样传给原方法；after 里能看到/改返回值。
 * before/after 自己抛异常只记日志，不影响原方法。
 */
abstract class Hook {
    protected void before(P p) throws Throwable {}

    protected void after(P p) throws Throwable {}

    static final class P {
        Object thisObject;
        Object[] args;
        Executable method;
        private Object result;
        private boolean returnEarly;
        private Map<String, Object> extras;

        void setResult(Object r) {
            result = r;
            returnEarly = true;
        }

        Object getResult() {
            return result;
        }

        void setObjectExtra(String k, Object v) {
            if (extras == null) extras = new HashMap<>(4);
            extras.put(k, v);
        }

        Object getObjectExtra(String k) {
            return extras == null ? null : extras.get(k);
        }
    }

    final XposedInterface.Hooker hooker() {
        return chain -> {
            P p = new P();
            p.thisObject = chain.getThisObject();
            p.args = chain.getArgs().toArray();
            p.method = chain.getExecutable();
            try {
                before(p);
            } catch (Throwable t) {
                Main.log("hook before " + p.method.getName() + " failed: " + t);
            }
            if (!p.returnEarly) p.result = chain.proceed(p.args);
            try {
                after(p);
            } catch (Throwable t) {
                Main.log("hook after " + p.method.getName() + " failed: " + t);
            }
            return p.result;
        };
    }
}
