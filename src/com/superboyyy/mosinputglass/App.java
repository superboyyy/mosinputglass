package com.superboyyy.mosinputglass;

import android.app.Application;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** 接收 LSPosed 通过 XposedProvider 发来的服务；设置界面靠它写远程配置。 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {
    static volatile XposedService service;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    static void addListener(Runnable r) {
        listeners.add(r);
    }

    static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService s) {
        service = s;
        for (Runnable r : listeners) r.run();
    }

    @Override
    public void onServiceDied(XposedService s) {
        if (service == s) service = null;
        for (Runnable r : listeners) r.run();
    }
}
