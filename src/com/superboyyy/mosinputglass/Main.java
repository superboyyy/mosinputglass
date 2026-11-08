package com.superboyyy.mosinputglass;

import android.app.Dialog;
import android.content.res.Configuration;
import android.graphics.BlendMode;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import android.content.SharedPreferences;

import io.github.libxposed.api.XposedModule;

/**
 * 给微信输入法（com.tencent.wetype）和豆包输入法（com.bytedance.android.doubaoime）的键盘套上 MagicOS 系统液态玻璃。
 *
 * 1. 系统液态玻璃（/system/framework/framework-magic.jar）：
 *    HnLiquidGlassMaterial + Blur/Glass/EdgeLight ParamEx，
 *    通过 HnMaterialManager.setWindowLiquidGlassEnable 设到输入法窗口上，区域限定为键盘矩形，
 *    由 SurfaceFlinger 取样窗口背后的应用内容。
 * 2. 玻璃之上加一层圆角底色（不透明度可调），保证可读性。
 * 3. 拦截输入法自绘的整块皮肤底板 / 按键阴影，按键改为 iOS 配色。
 * 两个输入法的差别（哪些视图算输入法自己的、整块底板怎么判断、语音键、logo）见 isWechat 分支。
 *
 * 用的是 libxposed API 101/102（LSPosed 2.x 的新接口）：入口见 resources/META-INF/xposed/java_init.list。
 * 参数来源（优先级从高到低）：adb 属性 debug.wglass.<key> > 设置界面（LSPosed 远程配置 getRemotePreferences）> Config.DEFAULTS。
 * 设置界面一改，远程配置的监听器就会触发，键盘显示着的话立刻重新应用。
 *
 * 只能用 adb 设置的诊断属性：
 *   debug.wglass.mode     window|view|both   玻璃挂在窗口上（默认）还是视图上
 *   debug.wglass.bright   float              亮度，默认 浅色 0.08 / 深色 0
 *   debug.wglass.mask     #AARRGGBB          玻璃蒙版色
 *   debug.wglass.trace    1/0                把跳过/保留的大块绘制指令打到 logcat
 *   debug.wglass.dump     1/0                把键盘视图层级打到 logcat（tag WGlass）
 */
public class Main extends XposedModule {
    static final String TAG = "WGlass";
    static Main self;
    /** 当前进程是哪个输入法；微信专属的功能（语音面板、logo）只在微信里开。 */
    static boolean isWechat;
    /** 输入法自己的视图类名前缀：微信是 selfdraw 键盘 / 候选栏，豆包是普通 View。 */
    static String ownerPrefix;
    static final String M = "com.hihonor.android.graphics.material.";
    static final String A = M + "atomiceffect.";

    private final WeakHashMap<View, String> applied = new WeakHashMap<>();
    private final WeakHashMap<View, Boolean> layoutHooked = new WeakHashMap<>();
    private final List<WeakReference<View>> hiddenSkins = new ArrayList<>();
    private final List<WeakReference<View>> insetViews = new ArrayList<>();
    private final WeakHashMap<View, android.view.ViewTreeObserver.OnGlobalLayoutListener> cornerListeners = new WeakHashMap<>();
    private Map<String, String> props = new HashMap<>();
    private SharedPreferences prefs;
    /** 远程配置的监听器；SharedPreferences 只弱引用监听器，所以要自己拿着。 */
    private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
    private WeakReference<InputMethodService> imsRef;
    private WeakReference<View> rootRef;
    private final Runnable prefsApply = () -> {
        View root = rootRef == null ? null : rootRef.get();
        InputMethodService ims = imsRef == null ? null : imsRef.get();
        if (root != null && ims != null && root.isShown()) safeApply(ims, root);
    };
    private WeakReference<View> glassRef;
    private boolean active;              // 当前效果是否已经套上

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        self = this;
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam lp) {
        if (!lp.isFirstPackage()) return;
        if (Config.PKG_WECHAT.equals(lp.getPackageName())) {
            isWechat = true;
            ownerPrefix = "com.tencent.wetype.plugin.hld";
        } else if (Config.PKG_DOUBAO.equals(lp.getPackageName())) {
            isWechat = false;
            ownerPrefix = "com.bytedance.";
        } else {
            return;
        }
        self = this;
        try {
            prefs = getRemotePreferences(Config.PREFS);
            // 设置界面一次会改很多个键（打开时整份同步）：合并成一次，并且只在键盘显示着的时候应用
            // （键盘隐藏后窗口的 Surface 已经释放，下次显示时 onWindowShown 会重新应用）
            prefsListener = (sp, key) -> {
                View root = rootRef == null ? null : rootRef.get();
                InputMethodService ims = imsRef == null ? null : imsRef.get();
                if (Config.RESTART_IME.equals(key)) {
                    // 只结束跑着输入法服务的那个进程；系统会把默认输入法重新拉起来（不是强行停止，默认输入法不会被换掉）
                    if (ims == null) return;
                    log("restart requested, killing " + android.os.Process.myPid());
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 150);
                    return;
                }
                if (root == null || ims == null) return;
                root.removeCallbacks(prefsApply);
                root.postDelayed(prefsApply, 50);
            };
            prefs.registerOnSharedPreferenceChangeListener(prefsListener);
            log("loaded in " + lp.getPackageName() + " (" + getFrameworkName() + " " + getFrameworkVersion()
                    + ", API " + getApiVersion() + "), settings=" + prefs.getAll().size() + " keys");
        } catch (Throwable t) {
            log("loaded in " + lp.getPackageName() + ", remote preferences unavailable: " + t);
        }

        Hook apply = new Hook() {
            @Override protected void after(Hook.P p) {
                InputMethodService ims = (InputMethodService) p.thisObject;
                View root = imeRoot(ims);
                if (root != null) root.post(() -> safeApply(ims, root));
            }
        };
        hookAll(InputMethodService.class, "setInputView", apply);
        hookAll(InputMethodService.class, "onStartInputView", apply);
        hookAll(InputMethodService.class, "onWindowShown", new Hook() {
            @Override protected void after(Hook.P p) {
                InputMethodService ims = (InputMethodService) p.thisObject;
                View root = imeRoot(ims);
                if (root == null) return;
                imsRef = new WeakReference<>(ims);
                rootRef = new WeakReference<>(root);
                root.post(() -> safeApply(ims, root));
            }
        });
        hookAll(InputMethodService.class, "onWindowHidden", new Hook() {
            @Override protected void after(Hook.P p) {
                voiceTouch.cancel();
            }
        });
        hookPanelSkip();
        try {
            // 荣耀系统只给白名单输入法显示导航栏语音按钮；在微信/豆包输入法里让这个隐藏逻辑不生效
            Class<?> nbc = Class.forName("android.inputmethodservice.NavigationBarController");
            hookAll(nbc, "hideImeVoiceButtonIfNotDefault", new Hook() {
                @Override protected void before(Hook.P p) {
                    if (voiceOn) p.setResult(null);
                }
            });
        } catch (Throwable t) {
            log("hook NavigationBarController failed: " + t);
        }
        hookLogo();
        if (Config.isHonor()) hookHaptic();
    }

    // ---------------------------------------------------------------------------------------------
    // 荣耀键盘振感
    // 微信输入法和豆包只认华为旧的 com.huawei.android.os.VibratorEx（MagicOS 11 上已经没有了），所以按键时走的是
    // 普通的 View.performHapticFeedback，系统播的是通用的「点击」（haptic.common.click_medium）。
    // 荣耀系统给键盘单独调了一套振感（/odm/etc/vibrator 里的 haptic.common.click_keyboard*），
    // 通过 com.hihonor.android.os.VibratorEx.setHwVibrator(类型) 就能播，第三方应用只要振动权限。
    // 这里把按键的那两种（KEYBOARD_TAP、VIRTUAL_KEY）换成它，其他的（长按等）照旧。
    // ---------------------------------------------------------------------------------------------
    static volatile boolean hapticOn = false;
    private static Object vibEx;                 // com.hihonor.android.os.VibratorEx
    private static java.lang.reflect.Method vibExPlay, vibExSupport;
    /** 每种振感系统支不支持（isSupportHwVibrator 的结果），只问一次。 */
    private static final Map<String, Boolean> effectSupport = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean vibExFailed;

    static volatile boolean hapDiag = false;     // adb 属性 hapdiag=1：每次按键振动把按到的键位打到 logcat
    // 手指最近一次按下的位置（屏幕坐标）和次数；按住不放时输入法重复触发的振动（长按删除）不会带新的按下
    private static float downRawX, downRawY;
    private static long downTime;
    private static int downSeq, lastHapticSeq = -1;
    private static String lastHapticKind;

    private void hookHaptic() {
        // 窗口根视图（DecorView）是 ViewGroup，它的 dispatchTouchEvent 是 ViewGroup 自己的实现，不走 View 的
        hookAll(ViewGroup.class, "dispatchTouchEvent", new Hook() {
            @Override protected void before(Hook.P p) {
                if (!hapticOn || !(p.args[0] instanceof android.view.MotionEvent)) return;
                View v = (View) p.thisObject;
                if (v.getParent() instanceof View) return;           // 只在窗口根视图记一次
                android.view.MotionEvent e = (android.view.MotionEvent) p.args[0];
                int a = e.getActionMasked();
                if (a != android.view.MotionEvent.ACTION_DOWN && a != android.view.MotionEvent.ACTION_POINTER_DOWN) return;
                int i = e.getActionIndex();
                downRawX = e.getRawX(i);
                downRawY = e.getRawY(i);
                downTime = android.os.SystemClock.uptimeMillis();
                downSeq++;
            }
        });
        hookAll(View.class, "performHapticFeedback", new Hook() {
            @Override protected void before(Hook.P p) {
                if (!hapticOn || p.args.length < 1 || !(p.args[0] instanceof Integer)) return;
                int c = (Integer) p.args[0];
                if (c != android.view.HapticFeedbackConstants.KEYBOARD_TAP
                        && c != android.view.HapticFeedbackConstants.VIRTUAL_KEY) return;
                View v = (View) p.thisObject;
                // 振动开关都按系统原来的规矩：视图自己关了、系统「触感反馈」或「键盘振动」关了，就交给原方法（它也不会振）
                if (!v.isHapticFeedbackEnabled() || !systemHapticOn(v.getContext())) return;
                String kind = keyKind();
                if (playKeyboardHaptic(v.getContext(), effectFor(kind))) p.setResult(Boolean.TRUE);
            }
        });
    }

    /** 这次振动按的是哪种键：space / delete / delete_repeat（按住删除连删）/ key。 */
    private static String keyKind() {
        boolean repeat = downSeq == lastHapticSeq && android.os.SystemClock.uptimeMillis() - downTime > 250;
        if (repeat && lastHapticKind != null && lastHapticKind.startsWith("delete")) {
            lastHapticKind = "delete_repeat";
            return lastHapticKind;
        }
        String kind = "key";
        try {
            kind = classifyKey(repeat);
        } catch (Throwable t) {
            if (hapDiag) log("hapdiag classify failed: " + t);
        }
        lastHapticSeq = downSeq;
        lastHapticKind = kind;
        return kind;
    }

    private static String effectFor(String kind) {
        switch (kind) {
            case "space": return Config.HAPTIC_KEYBOARD + "_space";
            case "delete": return Config.HAPTIC_KEYBOARD + "_delet";
            case "delete_repeat": return Config.HAPTIC_KEYBOARD + "_longpressdelet";
            default: return hapKeyOverride.isEmpty() ? Config.HAPTIC_KEYBOARD : "haptic.common." + hapKeyOverride;
        }
    }

    /** adb 属性 hapkey：普通键临时改播别的荣耀振感（如 click_keyboard_future），用来试手感；空 = 默认 click_keyboard。 */
    static volatile String hapKeyOverride = "";

    // 每个键盘视图上画过的键面（视图内坐标）。按键时只重画按下的那个键，所以一直累积；
    // 新键面和已有的重叠但大小位置不同 = 键盘布局换了（9 键 / 26 键 / 符号），把重叠的旧键面去掉
    private static final WeakHashMap<View, ArrayList<RectF>> faces = new WeakHashMap<>();

    private static void trackFace(String op, Object[] args) {
        if (!hapticOn || owner == null) return;
        RectF r = opRect(op, args);
        if (r == null || r.width() < 20 || r.height() < 20) return;
        ArrayList<RectF> l = faces.get(owner);
        if (l == null) { l = new ArrayList<>(); faces.put(owner, l); }
        for (int i = l.size() - 1; i >= 0; i--) {
            RectF o = l.get(i);
            if (sameRect(o, r)) return;
            if (RectF.intersects(o, r)) l.remove(i);
        }
        l.add(new RectF(r));
    }

    private static String classifyKey(boolean repeat) {
        View best = null;
        float lx = 0, ly = 0;
        int[] loc = new int[2];
        for (Map.Entry<View, ArrayList<RectF>> en : faces.entrySet()) {
            View v = en.getKey();
            if (v == null || !v.isShown() || en.getValue().isEmpty()) continue;
            v.getLocationOnScreen(loc);
            float x = downRawX - loc[0], y = downRawY - loc[1];
            if (x < 0 || y < 0 || x > v.getWidth() || y > v.getHeight()) continue;
            if (best == null || v.getWidth() * v.getHeight() > best.getWidth() * best.getHeight()) {
                best = v; lx = x; ly = y;
            }
        }
        if (best == null) {
            if (hapDiag) log("hapdiag no keyboard view at " + downRawX + "," + downRawY + " views=" + faces.size());
            return "key";
        }
        ArrayList<RectF> all = new ArrayList<>(faces.get(best));
        float[] ws = new float[all.size()], hs = new float[all.size()];
        for (int i = 0; i < ws.length; i++) { ws[i] = all.get(i).width(); hs[i] = all.get(i).height(); }
        java.util.Arrays.sort(ws);
        java.util.Arrays.sort(hs);
        float medW = ws[ws.length / 2], medH = hs[hs.length / 2];
        // 按到的键面；手指落在两个键之间的缝里时取最近的那个（缝只有十几像素）
        RectF hit = null;
        float hitD = medH * 0.2f;
        for (RectF r : all) {
            float dx = Math.max(Math.max(r.left - lx, lx - r.right), 0), dy = Math.max(Math.max(r.top - ly, ly - r.bottom), 0);
            float d = (float) Math.hypot(dx, dy);
            if (d <= hitD) { hit = r; hitD = d; if (d == 0) break; }
        }
        // 两个输入法实测（荣耀 1320 宽）：
        //   空格：最下面一排最宽的键（26 键约 475 宽，9 键约 430~470 宽，都超过普通键的 1.5 倍）
        //   删除：26 键是第三排最右边那个键；9 键是最右边一列最上面那个键（下面是重输/0 和高的换行键）
        String kind = "key";
        if (hit != null && hit.height() <= medH * 1.5f) {
            float maxBottom = 0, maxRight = 0, widestBottom = 0;
            for (RectF r : all) {
                if (r.height() > medH * 1.5f) continue;              // 9 键左边的符号列、高的换行键不算
                maxBottom = Math.max(maxBottom, r.bottom);
                maxRight = Math.max(maxRight, r.right);
            }
            for (RectF r : all) {
                if (r.height() <= medH * 1.5f && r.bottom >= maxBottom - medH * 0.5f) widestBottom = Math.max(widestBottom, r.width());
            }
            boolean bottomRow = hit.bottom >= maxBottom - medH * 0.5f;
            boolean nine = medW > best.getWidth() * 0.15f;
            if (bottomRow && hit.width() >= widestBottom && hit.width() >= medW * 1.5f) {
                kind = "space";
            } else if (!bottomRow && hit.right >= maxRight - 8) {
                if (nine) {
                    float minTop = Float.MAX_VALUE;
                    for (RectF r : all) if (r.right >= maxRight - 8) minTop = Math.min(minTop, r.top);
                    if (hit.top <= minTop + 8) kind = "delete";
                } else {
                    // 26 键：从上往下数，按中心高度分出几排；删除在倒数第二排（字母第三排）
                    ArrayList<Float> bands = new ArrayList<>();
                    for (RectF r : all) {
                        boolean found = false;
                        for (float b : bands) if (Math.abs(b - r.centerY()) < medH * 0.4f) { found = true; break; }
                        if (!found) bands.add(r.centerY());
                    }
                    java.util.Collections.sort(bands);
                    int idx = -1;
                    for (int i = 0; i < bands.size(); i++) if (Math.abs(bands.get(i) - hit.centerY()) < medH * 0.4f) idx = i;
                    if (bands.size() >= 4 && idx == bands.size() - 2) kind = "delete";
                }
            }
        }
        if (hapDiag) {
            log("hapdiag " + best.getClass().getSimpleName() + " " + best.getWidth() + "x" + best.getHeight()
                    + " touch=" + Math.round(lx) + "," + Math.round(ly) + " repeat=" + repeat + " faces=" + all.size()
                    + " hit=" + (hit == null ? "none" : hit.toShortString()) + " medW=" + Math.round(medW) + " -> " + kind);
        }
        return kind;
    }

    private static boolean systemHapticOn(android.content.Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            return android.provider.Settings.System.getInt(cr, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
                    && android.provider.Settings.System.getInt(cr, "keyboard_vibration_enabled", 1) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 播一次荣耀的键盘振感；接口不可用时返回 false，调用方就退回输入法原来的振动。 */
    private static boolean playKeyboardHaptic(android.content.Context ctx, String effect) {
        if (vibExFailed) return false;
        try {
            if (vibEx == null) {
                Class<?> c = Class.forName("com.hihonor.android.os.VibratorEx");
                Object o = c.getConstructor(android.content.Context.class).newInstance(ctx.getApplicationContext());
                Object ok = c.getMethod("isSupportHwVibrator", String.class).invoke(o, Config.HAPTIC_KEYBOARD);
                if (!Boolean.TRUE.equals(ok)) {
                    vibExFailed = true;
                    log("honor haptic: " + Config.HAPTIC_KEYBOARD + " not supported");
                    return false;
                }
                vibExPlay = c.getMethod("setHwVibrator", String.class);
                vibExSupport = c.getMethod("isSupportHwVibrator", String.class);
                vibEx = o;
                effectSupport.put(Config.HAPTIC_KEYBOARD, Boolean.TRUE);
            }
            // 空格、删除这些专用振感不一定每台荣耀都有（振感表在 /odm，跟机型走）：
            // 系统没有的振感 setHwVibrator 不会报错、只是不振，所以第一次用之前先问一下，没有就换回普通键盘振感
            Boolean ok = effectSupport.get(effect);
            if (ok == null) {
                ok = Boolean.TRUE.equals(vibExSupport.invoke(vibEx, effect));
                effectSupport.put(effect, ok);
                if (!ok) log("honor haptic: " + effect + " not supported, using " + Config.HAPTIC_KEYBOARD);
            }
            vibExPlay.invoke(vibEx, ok ? effect : Config.HAPTIC_KEYBOARD);
            return true;
        } catch (Throwable t) {
            vibExFailed = true;
            log("honor haptic unavailable: " + t);
            return false;
        }
    }

    /** 按名字 hook 一个类里声明的所有同名方法（等同于旧 API 的 XposedBridge.hookAllMethods）。 */
    static int hookAll(Class<?> c, String name, Hook h) {
        int n = 0;
        for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name) || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
            try {
                self.hook(m).intercept(h.hooker());
                n++;
            } catch (Throwable t) {
                log("hook " + c.getSimpleName() + "." + name + " failed: " + t);
            }
        }
        return n;
    }

    // ---------------------------------------------------------------------------------------------
    // 跳过微信输入法自绘的整块底板，按键改半透明
    // 微信输入法的 selfdraw 键盘 / 候选栏在 onDraw 里先画一整块皮肤底板再画按键。
    // 在它们 draw 期间拦截 Canvas 绘制指令：覆盖整个视图的那一笔（底板）跳过，按键大小的灰色块降低不透明度。
    // ---------------------------------------------------------------------------------------------
    static volatile boolean skipEnabled = false;
    static volatile float keyAlpha = 1f;
    static volatile boolean trace = false;
    static volatile boolean observe = false;   // 诊断：只记录绘制指令，不做任何修改
    static int obsLog;
    static volatile int obsMax = 600;   // observe 最多打多少条（adb 的 obsmax）
    private static View owner;       // 当前正在 draw 的微信输入法视图（只在 UI 线程访问）
    private static int ownerW, ownerH;
    static int keyLog;

    private void hookPanelSkip() {
        hookAll(View.class, "draw", new Hook() {
            @Override protected void before(Hook.P p) {
                if (!skipEnabled || p.args.length != 1) return;
                View v = (View) p.thisObject;
                p.setObjectExtra("prevCur", cur);
                cur = v;                                   // 任意类：用来算底色在当前画布里的位置
                if (v.getClass().getName().startsWith(ownerPrefix)) {
                    p.setObjectExtra("prev", owner);
                    p.setObjectExtra("owned", Boolean.TRUE);
                    owner = v; ownerW = v.getWidth(); ownerH = v.getHeight();
                }
            }
            @Override protected void after(Hook.P p) {
                if (p.args.length != 1) return;
                if (cur == p.thisObject) cur = (View) p.getObjectExtra("prevCur");
                if (p.getObjectExtra("owned") == null || owner != p.thisObject) return;
                View prev = (View) p.getObjectExtra("prev");
                owner = prev;
                if (prev != null) { ownerW = prev.getWidth(); ownerH = prev.getHeight(); }
            }
        });

        if (!isWechat) {
            // 豆包候选字左右的内边距（约 9dp）比较大，候选之间空得太开：测量前按比例缩小（adb 属性 candpad，默认 0.55）。
            // 单字候选的宽度由最小宽度（约 36dp，候选本身和外面包的那层都设了）撑着，内边距缩了也不变窄，所以最小宽度也按同一比例缩。
            // 候选之间的外间距另外按 candgap 放大（默认 1.6）；第一个候选的位置不动。记住原值，豆包重设时重新按原值算
            hookAll(android.widget.TextView.class, "onMeasure", new Hook() {
                @Override protected void before(Hook.P p) {
                    Class<?> cc = candClass;
                    if (cc == null || p.thisObject.getClass() != cc) return;
                    android.widget.TextView v = (android.widget.TextView) p.thisObject;
                    float f = skipEnabled && keyIos ? candPadF : 1f;
                    // e: 原左、原右、设的左、设的右、原最小宽、设的最小宽
                    int[] e = candPads.get(v);
                    if (e == null) { e = new int[6]; e[2] = e[3] = e[5] = -1; candPads.put(v, e); }
                    int l = v.getPaddingLeft(), r = v.getPaddingRight(), mw = v.getMinWidth();
                    if (l != e[2] || r != e[3]) { e[0] = l; e[1] = r; }
                    if (mw != e[5]) e[4] = mw;
                    int nl = Math.round(e[0] * f), nr = Math.round(e[1] * f);
                    int nmw = e[4] > 0 && e[4] < 100000 ? Math.round(e[4] * f) : e[4];
                    e[2] = nl; e[3] = nr; e[5] = nmw;
                    if (nl != l || nr != r) v.setPadding(nl, v.getPaddingTop(), nr, v.getPaddingBottom());
                    if (nmw != mw) v.setMinWidth(nmw);
                    // 外面那层 FrameLayout 的最小宽度（测完子 View 后才用，所以这里改了本次测量就生效）
                    if (v.getParent() instanceof View) {
                        View pv = (View) v.getParent();
                        // pe: 原最小宽、设的最小宽、原左外边距、设的左外边距
                        int[] pe = candPads.get(pv);
                        if (pe == null) { pe = new int[]{0, -1, 0, -1}; candPads.put(pv, pe); }
                        int pm = pv.getMinimumWidth();
                        if (pm != pe[1]) pe[0] = pm;
                        int npm = Math.round(pe[0] * f);
                        pe[1] = npm;
                        if (npm != pm) pv.setMinimumWidth(npm);
                        // 候选之间的外间距（外面那层的左外边距）按 candgap 放大；第一个候选不动，保持和拼音行左对齐。
                        // 在测量途中直接改字段，不触发重新布局；RecyclerView 摆放子 View 时才读外边距
                        if (pv.getLayoutParams() instanceof ViewGroup.MarginLayoutParams && pv.getParent() instanceof View) {
                            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) pv.getLayoutParams();
                            if (lp.leftMargin != pe[3]) pe[2] = lp.leftMargin;
                            float g = skipEnabled && keyIos && adapterPos((View) pv.getParent(), pv) > 0 ? candGapF : 1f;
                            int nm = Math.round(pe[2] * g);
                            pe[3] = nm;
                            lp.leftMargin = nm;
                        }
                    }
                }
            });

            // 豆包按键上的字比微信细：iOS 配色时把键盘（KeyboardView）里画的字临时换成中粗（500），画完换回去
            // 豆包的按键文字（KeyboardView）和候选字（CandidateItemView）比微信大一点、候选字是 85% 黑，看起来发虚：
            // iOS 配色时按微信的大小和粗细临时改画笔（字号缩放、候选字改成实黑、可选的细描边加粗），画完换回去
            Hook text = new Hook() {
                @Override protected void before(Hook.P p) {
                    if (trace && (owner == null || !owner.getClass().getSimpleName().equals("KeyboardView")) && textLog++ < 120) {
                        Paint tp = lastPaint(p.args);
                        Object t0 = p.args.length > 0 ? p.args[0] : null;
                        if (t0 instanceof char[]) t0 = new String((char[]) t0);
                        // 诊断日志不记文字内容（候选字可能是用户正在输入的东西），只记长度
                        Log.i(TAG, "text " + p.method.getName() + "/" + p.args.length + " len=" + text(t0).length() + " owner="
                                + (owner == null ? null : owner.getClass().getName()) + " cur=" + (cur == null ? null : cur.getClass().getName())
                                + (tp == null ? "" : String.format(" c=#%08X size=%.1f tfw=%d align=%s", tp.getColor(), tp.getTextSize(),
                                tp.getTypeface() == null ? -1 : tp.getTypeface().getWeight(), tp.getTextAlign())));
                    }
                    if (!skipEnabled || !keyIos || selfDraw || owner == null) return;
                    String on = owner.getClass().getSimpleName();
                    boolean key = on.equals("KeyboardView"), cand = on.equals("CandidateItemView");
                    // 左上角的拼音行：候选栏里 HorizontalScrollView 里的 TextView
                    boolean pinyin = !key && !cand && on.equals("CandidateToolbarHostLayout") && cur instanceof android.widget.TextView
                            && cur.getParent() instanceof android.widget.HorizontalScrollView;
                    if (!key && !cand && !pinyin) return;
                    Paint pt = lastPaint(p.args);
                    if (pt == null || pt.getStyle() != Paint.Style.FILL || isIconText(p.args[0])) return;
                    android.graphics.Typeface tf = pt.getTypeface();
                    boolean medium = tf != null && tf.getWeight() >= 500;      // 豆包高亮的第一个候选：保持它自己的中粗
                    boolean latin = hasAscii(p.args[0]);
                    // 换成微信的 WE 字体（只有拉丁字母/数字/符号，中文自动回落到系统字体）：字形一样了，粗细也就一样，不用再描边
                    android.graphics.Typeface we = latin && !medium && dbWeFont ? weTypeface() : null;
                    float scale, bold, spacing = -1f;
                    if (pinyin) {
                        scale = 1f; bold = 0f;
                    } else if (we != null && cand) {
                        scale = candScaleWe; bold = candBoldWe;
                    } else if (we != null) {
                        // 和微信量出来的：9 键字母一样大但字间距宽一点；26 键字母小 8%；「123」小 12%；「@#」小 10%
                        CharSequence t = text(p.args[0]);
                        boolean letters = hasLetter(t);
                        scale = isDigits(t) ? digitScaleWe : letters ? (layout26 ? lblScale26We : lblScale9We) : symScaleWe;
                        if (!layout26 && letters && t.length() > 1) spacing = lblSpacing9We;
                        bold = 0f;
                        if (trace && textLog++ < 200) Log.i(TAG, "we label len=" + t.length() + " layout26=" + layout26 + " scale=" + scale
                                + " spacing=" + spacing + " size=" + pt.getTextSize() + " ls=" + pt.getLetterSpacing() + " op=" + p.method.getName() + "/" + p.args.length);
                    } else if (key) {
                        scale = !layout26 ? lblScale9 : isLetter(p.args[0]) ? lblScale26 : 1f;
                        bold = latin || !dbWeFont ? lblBold : 0f;      // 中文本来就不细，用 WE 字体时中文不再加粗
                    } else {
                        scale = candScale;
                        bold = latin || !dbWeFont ? candBold : 0f;
                    }
                    if (medium) bold = 0f;
                    int c = pt.getColor();
                    boolean black = cand && candBlack && Color.alpha(c) >= 0x80 && Color.alpha(c) < 255
                            && Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c))) < 0x30;
                    if (we == null && scale == 1f && bold <= 0f && !black) return;
                    float size = pt.getTextSize();
                    p.setObjectExtra("txt", new float[]{Float.intBitsToFloat(c), size, pt.getStrokeWidth()});
                    if (we != null) p.setObjectExtra("tf", tf == null ? (Object) Boolean.FALSE : tf);
                    if (spacing >= 0f) {
                        p.setObjectExtra("ls", pt.getLetterSpacing());
                        pt.setLetterSpacing(spacing);
                    }
                    if (black) pt.setColor(c | 0xFF000000);
                    int xi = p.method.getName().equals("drawText") ? (p.args.length == 4 ? 1 : p.args.length == 6 ? 3 : -1) : -1;
                    boolean canMove = xi > 0 && p.args[xi] instanceof Float && p.args[xi + 1] instanceof Float;
                    float w0 = canMove ? measure(pt, p.args) : 0f;
                    if (we != null) pt.setTypeface(we);
                    float ns = size * scale;
                    if (scale != 1f) pt.setTextSize(ns);
                    if (canMove && (we != null || scale != 1f)) {
                        // 文字宽度变了：按对齐方式补偿 x，让字还在原来的中间；字号变了再按大写字母高（≈ 0.7 字号）补偿 y
                        float w1 = measure(pt, p.args);
                        float dx = pt.getTextAlign() == Paint.Align.LEFT ? (w0 - w1) / 2f
                                : pt.getTextAlign() == Paint.Align.RIGHT ? -(w0 - w1) / 2f : 0f;
                        if (pinyin) dx = 0f;                                   // 拼音行是左对齐的一行字，不居中
                        p.args[xi] = (Float) p.args[xi] + dx;
                        p.args[xi + 1] = (Float) p.args[xi + 1] - (size - ns) * 0.35f;
                    }
                    if (bold > 0f) {
                        pt.setStyle(Paint.Style.FILL_AND_STROKE);
                        pt.setStrokeWidth(pt.getTextSize() * bold);
                    }
                }
                @Override protected void after(Hook.P p) {
                    Object o = p.getObjectExtra("txt");
                    if (o == null) return;
                    Paint pt = lastPaint(p.args);
                    if (pt == null) return;
                    float[] v = (float[]) o;
                    pt.setColor(Float.floatToRawIntBits(v[0]));
                    pt.setTextSize(v[1]);
                    pt.setStyle(Paint.Style.FILL);
                    pt.setStrokeWidth(v[2]);
                    Object tf = p.getObjectExtra("tf");
                    if (tf != null) pt.setTypeface(tf instanceof android.graphics.Typeface ? (android.graphics.Typeface) tf : null);
                    Object ls = p.getObjectExtra("ls");
                    if (ls instanceof Float) pt.setLetterSpacing((Float) ls);
                }
            };
            int n = 0;
            for (String cls : new String[]{"android.graphics.Canvas", "android.graphics.BaseRecordingCanvas"}) {
                try {
                    Class<?> c = Class.forName(cls);
                    n += hookAll(c, "drawText", text) + hookAll(c, "drawTextRun", text);
                } catch (Throwable t) {
                    log("hook text " + cls + " failed: " + t);
                }
            }
            log("hooked " + n + " text ops");

            // 记录正在画背景的视图：View.draw 的第一步 drawBackground，这时它的子视图还没画
            // ColorOS 上纯色 Drawable（ColorDrawable）的画法绕过了画布层的拦截：豆包工具栏、面板标题栏等整块纯色背景
            // 在画布层抓不到，盖在模糊上是一整条不透明灰。在 ColorDrawable.draw 这一层再拦一次（只拦面板背景，规则同画布层）
            hookAll(ColorDrawable.class, "draw", new Hook() {
                @Override protected void before(Hook.P p) {
                    if (!skipEnabled || isWechat || p.args.length != 1 || !(p.args[0] instanceof android.graphics.Canvas)) return;
                    View bv = bgView;
                    if (bv == null || bv.getBackground() != p.thisObject || glassW <= 0 || bv.getWidth() < glassW * 0.97f) return;
                    // 区域模糊（ColorOS）下：键盘里铺满整宽的不透明纯色背景（表情页底、面板底等）都去掉，露出毛玻璃
                    boolean opaqueFull = regionBlurOn && Color.alpha(((ColorDrawable) p.thisObject).getColor()) == 255
                            && bv.getHeight() >= 40 && isInsideGlass(bv);
                    if (!(opaqueFull || PANEL_BG_IDS.contains(idName(bv)) || hyperGlassOn && insideOverlay(bv))) return;
                    android.graphics.Canvas cv = (android.graphics.Canvas) p.args[0];
                    if (!hyperGlassOn) glassPatch(cv, new RectF(((Drawable) p.thisObject).getBounds()));
                    p.setResult(null);
                }
            });

            hookAll(View.class, "drawBackground", new Hook() {
                @Override protected void before(Hook.P p) {
                    if (!skipEnabled) return;
                    p.setObjectExtra("prevBg", bgView);
                    bgView = (View) p.thisObject;
                }
                @Override protected void after(Hook.P p) {
                    if (bgView == p.thisObject) bgView = (View) p.getObjectExtra("prevBg");
                }
            });
        }

        Hook skip = new Hook() {
            @Override protected void before(Hook.P p) {
                if (selfDraw || owner == null || ownerW <= 0 || ownerH <= 0) return;
                String op = p.method.getName();
                // 豆包按住说话：面板下半截「松手结束」那块是一层半透明黑的大椭圆（AsrEllipseView），
                // 它只铺到按键区底边，在键盘底栏上沿戛然而止。记下这个椭圆，在键盘（玻璃视图）的叠加层里把底栏那一段补画上（ArcExt）
                if (!isWechat && keyIos && op.equals("drawOval") && cur != null
                        && cur.getClass().getName().endsWith(".AsrEllipseView")) {
                    Paint pp = lastPaint(p.args);
                    RectF oval = ovalRect(p.args);
                    if (pp != null && oval != null && pp.getStyle() != Paint.Style.STROKE && pp.getShader() == null) {
                        ArcExt.update(cur, oval, pp.getColor());
                    }
                }
                float[] wh = opSize(op, p.args);
                if (wh == null && observe && "1".equals(String.valueOf(props.get("obsall"))) && obsLog++ < obsMax) {
                    Paint paint = lastPaint(p.args);
                    Log.i(TAG, "obs " + op + " ?" + (paint == null ? "" : String.format(" c=#%08X %s", paint.getColor(), paint.getStyle()))
                            + " owner=" + owner.getClass().getSimpleName());
                }
                if (wh == null) return;
                boolean fillOp = op.equals("drawRoundRect") || op.equals("drawRect") || op.equals("drawPath")
                        || op.equals("drawPatch") || op.equals("drawDoubleRoundRect");
                boolean keyMode = keyAlpha < 1f || keyIos || keyRadiusPx > 0;
                // 整块底板的宽度门槛：微信的 owner 就是整个键盘；豆包每个按键、每一栏都是单独的 View，
                // 所以按玻璃区域（整个键盘）的宽度算，否则按键自己的背景会被当成底板擦掉
                float fullW = isWechat || glassW <= 0 ? ownerW * 0.9f : glassW * 0.9f;
                if (observe) {
                    boolean all = "1".equals(String.valueOf(props.get("obsall")));
                    if (obsLog++ < obsMax && (all || (wh[0] >= 40 && wh[1] >= 40))) {
                        Paint paint = lastPaint(p.args);
                        android.graphics.Canvas cv = (android.graphics.Canvas) p.thisObject;
                        Log.i(TAG, "obs " + op + " " + (int) wh[0] + "x" + (int) wh[1]
                                + (paint == null ? "" : String.format(" c=#%08X %s xf=%s sh=%s", paint.getColor(), paint.getStyle(),
                                        paint.getBlendMode(), paint.getShader() == null ? "-" : paint.getShader().getClass().getSimpleName())
                                )
                                + " cv=" + cv.getClass().getSimpleName() + (cv.isHardwareAccelerated() ? "/hw" : "/sw")
                                + " owner=" + owner.getClass().getSimpleName() + " cur=" + (cur == null ? null : cur.getClass().getSimpleName()));
                    }
                    return;
                }
                // 豆包每个键下面有一条和键同宽、25px 高的 30% 黑色路径（#4C000000），键面盖住上面大半，
                // 露出来的 4px 就是键底下那条深灰「立体底边」。iOS 配色没有这条边：直接不画
                if (!isWechat && keyIos && op.equals("drawPath") && wh[0] >= 40 && wh[1] < 40 && wh[0] > wh[1] * 3
                        && owner.getClass().getSimpleName().equals("KeyboardView")) {
                    Paint paint = lastPaint(p.args);
                    int c = paint == null ? 0 : paint.getColor();
                    if (paint != null && paint.getStyle() != Paint.Style.STROKE && paint.getShader() == null
                            && Color.alpha(c) < 255 && Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c))) < 0x20) {
                        p.setResult(null);
                        return;
                    }
                }
                // 微信（小米上实测）每个键先画一条和键同宽、36px 高的 55% 黑色圆角条（#8C000000）当立体底边，再画键面（9 键、符号页都有）。
                // 键面变半透明后这条会从键面下半截透出来（键底一截发暗、下面多一道黑线）。iOS 配色没有这条边：直接不画
                if (isWechat && (keyIos || keyAlpha < 1f) && op.equals("drawRoundRect") && wh[0] >= 40 && wh[1] < 40
                        && wh[0] > wh[1] * 2) {   // 符号页的键窄（约 104×36），宽高比不到 3
                    Paint paint = lastPaint(p.args);
                    int c = paint == null ? 0 : paint.getColor();
                    if (paint != null && paint.getStyle() != Paint.Style.STROKE && paint.getShader() == null
                            && Color.alpha(c) < 255 && Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c))) < 0x20) {
                        p.setResult(null);
                        return;
                    }
                }
                View bv = bgView;
                // 豆包表情页底部的分类栏（emoji_tab、颜文字页的 kaomoji_tab）：栏底（深 #2F2F2F）和每个分类按钮的底（同色）连成一整条，
                // 按钮底还会被当成按键改成按键色，在键盘上很突兀；选中那一格是贴边的直角方块。
                // 第一次画到它时把这些背景直接换掉（styleEmojiTabs）；按钮切换选中时只重画自己，画的时候拦不到，所以不靠拦截
                if (!isWechat && bv != null && bv == cur && wh[0] >= bv.getWidth() * 0.9f && wh[1] >= bv.getHeight() * 0.9f
                        && isEmojiTabBar(bv)) {
                    final View bar = bv;
                    bar.post(() -> styleEmojiTabs(bar));   // 画的过程中不改背景，等这一帧画完
                    if (!hyperGlassOn) glassPatch((android.graphics.Canvas) p.thisObject, opRect(op, p.args));
                    p.setResult(null);
                    return;
                }
                // 豆包打开面板（左上角功能菜单、翻译等）时，顶上那条工具栏（native_candidate_bar）和面板的标题栏、内容区
                // 各自有一块铺满整宽的不透明底色（深 #222222、浅 #E0E2E6），和键盘底色对不上。面板里窄的卡片、按钮的底色不动。
                // HyperOS 玻璃模式：不画，工具栏露出下面的玻璃，面板里的露出面板底下我们铺的玻璃色渐变（OverlayBg）；
                // MagicOS：和整块底板一样擦透明再补键盘底色（glassPatch），面板底下的按键也一起盖住
                if (!isWechat && bv != null && bv == cur && glassW > 0 && bv.getWidth() >= glassW * 0.97f
                        && wh[0] >= bv.getWidth() * 0.9f && wh[1] >= bv.getHeight() * 0.9f
                        && (PANEL_BG_IDS.contains(idName(bv)) || hyperGlassOn && insideOverlay(bv))) {
                    Paint paint = lastPaint(p.args);
                    if (paint == null || paint.getShader() == null && paint.getBlendMode() == null) {
                        if (!hyperGlassOn) glassPatch((android.graphics.Canvas) p.thisObject, opRect(op, p.args));
                        p.setResult(null);
                        return;
                    }
                }
                if (keyMode && fillOp && wh[0] < fullW && wh[0] >= 40 && wh[1] >= 40) {
                    Paint paint = lastPaint(p.args);
                    if (paint == null || paint.getShader() != null) return;
                    int c = paint.getColor();
                    if (trace && (keyLog++ < 80 || wh[0] > 600 && wh[1] < 200 || wh[0] < 140 && wh[1] < 140)) {
                        StringBuilder a = new StringBuilder();
                        for (Object o : p.args) a.append(o instanceof Paint ? "paint" : o instanceof RectF ? ((RectF) o).toShortString() : String.valueOf(o)).append(' ');
                        Log.i(TAG, "key " + op + String.format(" c=#%08X ", c) + paint.getStyle() + " args=" + a
                                + " in " + owner.getClass().getSimpleName() + "#" + idName(owner)
                                + " cur=" + (cur == null ? null : cur.getClass().getSimpleName() + "#" + cur.getId())
                                + (((android.graphics.Canvas) p.thisObject).isHardwareAccelerated() ? " hw" : " sw"));
                    }
                    boolean isFill = paint.getStyle() != Paint.Style.STROKE;
                    boolean translucent = keyIos || keyAlpha < 1f;
                    // 豆包 26 键气泡（T 形路径，比一个键高一倍多）先画一层同形状的淡阴影（#26000000）再画气泡：
                    // 这层要留着，不然气泡和后面同色的按键连成一片、看不出边
                    boolean bubbleShadow = !isWechat && op.equals("drawPath") && wh[1] > ownerH * 0.25f;
                    if (translucent && isFill && Color.alpha(c) < 255 && !bubbleShadow
                            && Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c))) < 0x20) {
                        p.setResult(null);   // 按键底部的半透明黑色阴影：按键变半透明后会露出来，直接不画
                        return;
                    }
                    // iOS 按键没有描边：去掉微信按键外圈的细描边（深色白边 #1AFFFFFF / #0DFFFFFF，浅色黑边 #1A000000 / #0D000000）
                    // 豆包：只去和刚画的键面一样大的描边（按键外圈）；9 键左边符号列的分隔线（深色 #19FFFFFF、浅色 #26000000）
                    // 和描边同色，但比键面宽（伸出列外），要留着
                    // 豆包翻译栏（translate_view）：输入框外圈的描边比输入框本身小一圈，也去掉
                    boolean translateBar = !isWechat && "translate_view".equals(idName(owner));
                    if (keyIos && !isFill && Color.alpha(c) < 0x40 && (isWechat || translateBar || sameRect(opRect(op, p.args), lastFace))
                            && (Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c))) > 0xE0
                            // 黑色细描边只去微信的；豆包 9 键左边符号列的分隔线也是 #26000000 描边，要留着
                            || (isWechat || translateBar) && Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c))) < 0x20)) {
                        p.setResult(null);
                        return;
                    }
                    // 按键圆角：只改原本圆角较小的按键（候选栏的圆形按钮不动），键面和描边一起改
                    if (keyRadiusPx > 0 && op.equals("drawRoundRect")) setKeyRadius(p.args, wh);
                    // 微信的键面是不透明灰；豆包的键面是半透明白（字母键 #47FFFFFF、功能键 #14FFFFFF）
                    boolean keyFace = isWechat ? Color.alpha(c) == 255 : Color.alpha(c) > 0;
                    if (isFill && keyFace && isGray(c)) {
                        keyOwners.put(owner, Boolean.TRUE);
                        trackSpaceKey(op, p.args);
                        trackFace(op, p.args);
                        if (!isWechat) lastFace = opRect(op, p.args);
                        // 豆包 9 键 / 26 键用同一个 KeyboardView：窄键（< 10% 宽）只有 26 键有，高键（左边那一列、换行/确定）只有 9 键有
                        if (!isWechat && owner.getClass().getSimpleName().equals("KeyboardView")) {
                            if (wh[0] < ownerW * 0.1f) layout26 = true;
                            else if (wh[1] > ownerH * 0.3f) layout26 = false;
                        }
                        // 灰/白色按键面 -> iOS 配色 / 半透明；彩色强调键（微信绿）、描边、文字不动
                        int target = keyIos ? iosKeyColor(c) : c;
                        int a = Math.round(Color.alpha(target) * keyAlpha);
                        if (target != c || a != 255) {
                            p.setObjectExtra("color", c);
                            paint.setColor((target & 0x00FFFFFF) | (a << 24));
                        }
                        // 豆包的按键先画进一张位图；按下/松开时只重画那一小块，而且不先擦掉旧的。
                        // 原版键面不透明所以没事，换成半透明白以后会一层层叠上去，松开后那几个键变成纯白：先把这个键的区域擦透明
                        android.graphics.Canvas cv = (android.graphics.Canvas) p.thisObject;
                        // 翻译栏也画在位图里，但输入框左边的「中A」图标底是叠在输入框上面画的，擦了会把输入框挖出一个方块，不擦
                        if (!isWechat && a < 255 && !cv.isHardwareAccelerated() && !translateBar) {
                            // 26 键按下时弹出的气泡是一个 T 形路径（上面宽的头 + 下面连着按键的一截），外框比形状大得多：
                            // 按外框擦会把上一排、左右两边的键一起擦掉。路径只擦它自己的形状
                            if (op.equals("drawPath") && p.args.length > 0 && p.args[0] instanceof android.graphics.Path) {
                                clearPath(cv, (android.graphics.Path) p.args[0]);
                            } else {
                                RectF r = opRect(op, p.args);
                                if (r != null) clearRect(cv, r);
                            }
                        }
                    }
                    return;
                }
                if (!isWechat && wh[0] >= fullW && wh[1] >= ownerH * 0.6f) {
                    // 豆包：按键先画到位图里再整张贴上来（drawBitmap），不能当底板；带混合模式的是圆角遮罩，也不动。
                    // 其余整块纯色（皮肤层、候选栏、InputView 的底）直接不画；不擦成透明，
                    // 因为不确定它们是在子视图之前还是之后画的，擦了可能把按键一起擦掉
                    // 例外：键盘背景图（input_view_image_bg，皮肤图）也是整张位图。豆包按住说话时会给它换上一张发蓝的光晕图，
                    // 在玻璃上露出来（荣耀上半截一块、底部导航那一行一条；小米底部一条），和皮肤图一样不画
                    if (op.equals("drawBitmap") && !(cur != null && "input_view_image_bg".equals(idName(cur)))) return;
                    Paint pp = lastPaint(p.args);
                    if (pp != null && pp.getBlendMode() != null) return;
                    // 我们自己设在玻璃区域（豆包的 InputView）上的底色也会走到这里，不能跳过
                    if (pp != null && (pp.getColor() & 0xFFFFFF) == (tintColor & 0xFFFFFF)) return;
                    // 正在画自己背景的视图（子视图还没画）或者没有子视图的视图：可以擦成透明再补底色，
                    // 这样表情面板这类盖在键盘上的面板能把下面的工具栏/按键盖住
                    View c = cur;
                    boolean safeClear = c != null && (c == bgView
                            || !(c instanceof ViewGroup) || ((ViewGroup) c).getChildCount() == 0);
                    // 豆包的皮肤图（ShapeableImageView）是在自己的离屏层里画的（后面有 DST_OUT 的形状遮罩），
                    // 在里面擦透明擦不到下面 InputView 的底色，再补一层就叠成两层（按键区域比候选栏暗一档）
                    if (c != null && c.getClass().getSimpleName().equals("ShapeableImageView")) safeClear = false;
                    android.graphics.Canvas cv = (android.graphics.Canvas) p.thisObject;
                    if (safeClear && !cv.isHardwareAccelerated()) {
                        // 软件画布 = 豆包的按键位图（贴在 InputView 的底色之上）：只擦透明，不能再补底色。
                        // 输入拼音时「确定」键重画前会先画一笔裁剪到这个键范围的整块黑色，补了底色就叠成一块灰
                        clearRect(cv, opRect(op, p.args));
                    } else if (safeClear) {
                        if (hyperGlassOn && cv.isHardwareAccelerated()) overlayGlass(c);
                        glassPatch(cv, opRect(op, p.args));
                    }
                    p.setResult(null);
                    if (trace) Log.i(TAG, "skip " + op + " " + (int) wh[0] + "x" + (int) wh[1]
                            + " in " + owner.getClass().getSimpleName() + " cur=" + (c == null ? null : c.getClass().getName())
                            + " childs=" + (c instanceof ViewGroup ? ((ViewGroup) c).getChildCount() : -1) + (safeClear ? " (clear)" : ""));
                    return;
                }
                // 只有接近整个键盘宽的视图才算底板：面板里的卡片、按钮自己的背景也「占满自己」，但不能去掉
                //（微信首次使用的协议卡片 1112 宽、同意/不同意按钮 333 宽）
                boolean fullWidthOwner = glassW <= 0 || ownerW >= glassW * 0.97f;
                if (fullWidthOwner && wh[0] >= fullW && wh[1] >= ownerH * 0.6f) {
                    // 整块底板：不是简单跳过，而是把这块区域擦成透明（露出窗口背后的系统玻璃）再补画底色。
                    // 这样盖在键盘上面的面板（表情、菜单）也会把下面的键盘一起盖住，不会叠在一起。
                    android.graphics.Canvas cv2 = (android.graphics.Canvas) p.thisObject;
                    if (hyperGlassOn && cv2.isHardwareAccelerated() && cur != null) overlayGlass(cur);
                    // 微信只在「正在画自己的背景」或「没有子视图」时擦透明：已经有子视图的容器在 onDraw 里画的整块底色
                    //（符号页的 ImeSymbolPagerView、S5SymbolKeyboard 等）一擦，荣耀上符号页的符号键会整页消失。只跳过不擦
                    View cw = cur;
                    boolean clearOk = !isWechat || cw == null || cw == bgView || !(cw instanceof ViewGroup)
                            || ((ViewGroup) cw).getChildCount() == 0;
                    if (clearOk) glassPatch(cv2, opRect(op, p.args));
                    p.setResult(null);
                    if (trace) Log.i(TAG, "skip " + op + " " + (int) wh[0] + "x" + (int) wh[1]
                            + " in " + owner.getClass().getSimpleName() + " cur=" + (cur == null ? null : cur.getClass().getName()));
                } else if (trace && wh[0] >= ownerW * 0.5f) {
                    Paint kp = lastPaint(p.args);
                    Log.i(TAG, "keep " + op + " " + (int) wh[0] + "x" + (int) wh[1]
                            + " in " + owner.getClass().getSimpleName()
                            + " cur=" + (cur == null ? null : cur.getClass().getSimpleName() + "#" + idName(cur))
                            + " bg=" + (bgView == null ? null : bgView.getClass().getSimpleName() + "#" + idName(bgView))
                            + (kp == null ? "" : String.format(" c=#%08X sh=%s bm=%s", kp.getColor(), kp.getShader(), kp.getBlendMode())));
                }
            }
            @Override protected void after(Hook.P p) {
                Object old = p.getObjectExtra("color");
                if (old instanceof Integer) {
                    Paint paint = lastPaint(p.args);
                    if (paint != null) paint.setColor((Integer) old);
                }
            }
        };
        String[] ops = {"drawColor", "drawPaint", "drawARGB", "drawRGB", "drawRect", "drawRoundRect",
                "drawBitmap", "drawPath", "drawPatch", "drawDoubleRoundRect",
                "drawLine", "drawLines", "drawOval", "drawCircle", "drawArc"};
        for (String cls : new String[]{"android.graphics.BaseRecordingCanvas", "android.graphics.Canvas"}) {
            try {
                Class<?> c = Class.forName(cls);
                int n = 0;
                for (String op : ops) n += hookAll(c, op, skip);
                log("hooked " + n + " draw ops on " + cls);
            } catch (Throwable t) {
                log("hook " + cls + " failed: " + t);
            }
        }
    }

    private static View cur;                 // 当前正在 draw 的视图（任意类）
    private static View bgView;              // 当前正在 drawBackground 的视图
    static boolean selfDraw;                 // 我们自己发出的绘制指令，不要再被拦截
    static volatile int tintColor = 0;       // 玻璃之上那层底色（含不透明度）
    static volatile int glassW = 0;          // 玻璃区域（整个键盘）宽度
    static volatile float tintRadius = 0f;
    static WeakReference<View> glassStatic;
    static WeakReference<View> edgeHostRef;   // 画玻璃边缘的视图（微信 = 玻璃视图本身，豆包 = 外面一层）
    private static final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 只把 r 区域擦成透明（r == null 表示整个画布）。 */
    private static void clearRect(android.graphics.Canvas c, RectF r) {
        selfDraw = true;
        try {
            c.save();
            if (r != null) c.clipRect(r);
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            c.restore();
        } catch (Throwable t) {
            if (trace) Log.i(TAG, "clearRect failed: " + t);
        } finally {
            selfDraw = false;
        }
    }

    /** 只把路径 path 围住的区域擦成透明。 */
    private static void clearPath(android.graphics.Canvas c, android.graphics.Path path) {
        selfDraw = true;
        try {
            c.save();
            c.clipPath(path);
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            c.restore();
        } catch (Throwable t) {
            if (trace) Log.i(TAG, "clearPath failed: " + t);
        } finally {
            selfDraw = false;
        }
    }

    /** 把 r 区域擦成透明，再按玻璃区域（圆角）补画底色。r == null 表示整个画布。 */
    private static void glassPatch(android.graphics.Canvas c, RectF r) {
        // HyperOS 玻璃是画在 InputView 自己的 RenderNode 里的，擦透明会把玻璃一起擦掉：只跳过原来的底板
        if (hyperGlassOn && c.isHardwareAccelerated()) return;
        selfDraw = true;
        try {
            c.save();
            if (r != null) c.clipRect(r);
            c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
            View g = glassStatic == null ? null : glassStatic.get();
            View v = cur;
            if (g != null && v != null && Color.alpha(tintColor) > 0) {
                int[] a = new int[2], b = new int[2];
                v.getLocationInWindow(a);
                g.getLocationInWindow(b);
                float dx = b[0] - a[0] + v.getScrollX(), dy = b[1] - a[1] + v.getScrollY();
                float rr = tintRadius;
                android.graphics.Path path = new android.graphics.Path();
                path.addRoundRect(new RectF(dx, dy, dx + g.getWidth(), dy + g.getHeight()),
                        new float[]{rr, rr, rr, rr, 0, 0, 0, 0}, android.graphics.Path.Direction.CW);
                tintPaint.setColor(tintColor);
                c.drawPath(path, tintPaint);
            }
            c.restore();
        } catch (Throwable t) {
            if (trace) Log.i(TAG, "glassPatch failed: " + t);
        } finally {
            selfDraw = false;
        }
    }

    /** 一次绘制指令的目标区域（画布坐标）；drawColor/drawPaint 返回 null（整个画布）。 */
    private static RectF opRect(String op, Object[] a) {
        try {
            switch (op) {
                case "drawColor": case "drawPaint": case "drawARGB": case "drawRGB":
                    return null;
                case "drawPath":
                    if (a.length > 0 && a[0] instanceof android.graphics.Path) {
                        RectF r = new RectF();
                        ((android.graphics.Path) a[0]).computeBounds(r, true);
                        return r;
                    }
                    return null;
                default:
                    for (int i = a.length - 1; i >= 0; i--) {
                        if (a[i] instanceof RectF) return new RectF((RectF) a[i]);
                        if (a[i] instanceof android.graphics.Rect) return new RectF((android.graphics.Rect) a[i]);
                    }
                    if (op.equals("drawBitmap") && a.length >= 3 && a[0] instanceof android.graphics.Bitmap
                            && a[1] instanceof Float) {
                        android.graphics.Bitmap bm = (android.graphics.Bitmap) a[0];
                        float l = (Float) a[1], t = (Float) a[2];
                        return new RectF(l, t, l + bm.getWidth(), t + bm.getHeight());
                    }
                    if (a.length >= 5 && a[0] instanceof Float && a[2] instanceof Float) {
                        return new RectF((Float) a[0], (Float) a[1], (Float) a[2], (Float) a[3]);
                    }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // 空格键（最下面一排最宽的灰键），给导航栏语音按钮转发「按住空格说话」用
    static WeakReference<View> spaceView;
    static RectF spaceRect;

    private static void trackSpaceKey(String op, Object[] args) {
        if (!owner.getClass().getName().contains(".keyboard.")) return;
        RectF r = opRect(op, args);
        if (r == null || r.bottom < ownerH * 0.7f) return;
        View prev = spaceView == null ? null : spaceView.get();
        if (prev != owner || spaceRect == null || r.width() > spaceRect.width() * 1.02f
                || (Math.abs(r.centerX() - spaceRect.centerX()) < 4 && Math.abs(r.centerY() - spaceRect.centerY()) < 4)) {
            if (prev != owner || spaceRect == null || r.width() >= spaceRect.width()) {
                spaceView = new WeakReference<>(owner);
                spaceRect = r;
            }
        }
    }

    static volatile boolean keyIos = true;
    static volatile boolean nightNow = true;
    static volatile float keyRadiusPx = 0f;

    /**
     * 微信输入法按键色 -> iOS 26 键盘配色。
     * 微信：深色 字母键 #5F5F5F / 功能键 #414141；浅色 字母键 #FCFCFC / 功能键 #B7BCC4（ime_color_12/13）。
     * iOS 26（实机截图取色）：字母键、功能键、空格全部同色、无描边无阴影。
     *   浅色：键 #FFFFFF，底 #E7E8EB；深色：键 #696969，底 #454545（键比底亮一档 ≈ 白色 20% 叠加）。
     */
    private static int iosKeyColor(int c) {
        return (Math.round((nightNow ? keyDark : keyLight) * 255) << 24) | 0xFFFFFF;
    }

    static volatile float keyDark = 0.16f;   // 深色模式按键 = 白色这么多不透明度叠在底色上（iOS 是 #212121 底上的 #454545 键）
    static volatile float keyLight = 1f;     // 浅色模式白键不透明度（1 = iOS 的纯白）
    // 豆包文字对齐微信：字号缩放、描边加粗（字号的比例）、候选字改实黑。默认值按截图量出来的（见 README）
    // 按键文字字号：9 键 ×0.95（豆包比微信大 5%），26 键的单个字母 ×1.04（比微信小 4%），26 键其余文字本来就一样大
    static volatile float lblScale9 = 0.95f, lblScale26 = 1.04f, lblBold = 0.008f, candScale = 0.96f, candBold = 0.006f;
    static RectF lastFace;               // 豆包最近画的一个键面（区分按键描边和分隔线）

    private static boolean sameRect(RectF a, RectF b) {
        return a != null && b != null && Math.abs(a.left - b.left) < 3 && Math.abs(a.top - b.top) < 3
                && Math.abs(a.right - b.right) < 3 && Math.abs(a.bottom - b.bottom) < 3;
    }

    static volatile boolean layout26;    // 豆包当前是不是 26 键（按画出来的键形判断）
    // 用 WE 字体时含拉丁字母/数字的文字的字号缩放（9 键按键、26 键按键、候选字）；中文还用上面的 lblScale9 / 1 / candScale
    static volatile float lblScale9We = 1f, lblScale26We = 1.08f, digitScaleWe = 1.12f, symScaleWe = 1.03f, lblSpacing9We = 0.09f,
            candScaleWe = 1f, candBoldWe = 0.006f;
    static volatile boolean dbWeFont = true;
    private static volatile String weSrc;
    private static volatile android.graphics.Typeface weTf;
    private static volatile String weB64;

    /** 微信输入法的 WE 字体（设置界面读出来的 Base64），中文等没有的字回落到系统 sans-serif；没有就返回 null。 */
    private static android.graphics.Typeface weTypeface() {
        String b = weB64;
        if (b == null || b.isEmpty()) return null;
        if (b.equals(weSrc)) return weTf;
        weSrc = b;
        weTf = null;
        try {
            byte[] data = android.util.Base64.decode(b, android.util.Base64.DEFAULT);
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(data.length);
            buf.put(data).flip();
            android.graphics.fonts.Font font = new android.graphics.fonts.Font.Builder(buf).build();
            android.graphics.fonts.FontFamily fam = new android.graphics.fonts.FontFamily.Builder(font).build();
            weTf = new android.graphics.Typeface.CustomFallbackBuilder(fam).setSystemFallback("sans-serif").build();
            log("WE font loaded: " + data.length + " bytes");
        } catch (Throwable t) {
            log("WE font failed: " + t);
        }
        return weTf;
    }

    private static CharSequence text(Object t) {
        if (t instanceof char[]) return new String((char[]) t);
        return t instanceof CharSequence ? (CharSequence) t : "";
    }

    /** 含半角字符（字母、数字、ASCII 符号）：这些用 WE 字体画；全角标点和中文留给系统字体。 */
    private static boolean hasAscii(Object t) {
        CharSequence cs = text(t);
        for (int i = 0; i < cs.length(); i++) if (cs.charAt(i) > 0x20 && cs.charAt(i) < 0x7F) return true;
        return false;
    }

    private static boolean hasLetter(CharSequence cs) {
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') return true;
        }
        return false;
    }

    private static boolean isDigits(CharSequence cs) {
        if (cs.length() == 0) return false;
        for (int i = 0; i < cs.length(); i++) if (cs.charAt(i) < '0' || cs.charAt(i) > '9') return false;
        return true;
    }

    /** 单个拉丁字母（26 键的字母键）。 */
    private static boolean isLetter(Object t) {
        if (t instanceof char[]) t = new String((char[]) t);
        if (!(t instanceof CharSequence) || ((CharSequence) t).length() != 1) return false;
        char c = ((CharSequence) t).charAt(0);
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }
    static volatile boolean candBlack = true;
    static int textLog;

    /** drawText(String, x, y, p) / (String|CharSequence, start, end, x, y, p) / (char[], index, count, x, y, p) 的文字宽度。 */
    private static float measure(Paint pt, Object[] a) {
        Object t = a[0];
        if (a.length == 4 && t instanceof String) return pt.measureText((String) t);
        if (a.length == 6 && a[1] instanceof Integer && a[2] instanceof Integer) {
            int x = (Integer) a[1], y = (Integer) a[2];
            if (t instanceof char[]) return pt.measureText((char[]) t, x, y);
            if (t instanceof String) return pt.measureText((String) t, x, y);
            if (t instanceof CharSequence) return pt.measureText((CharSequence) t, x, y);
        }
        return 0f;
    }

    /** 豆包的删除/回车等图标是图标字体里私用区（U+E000–U+F8FF）的字，不加粗（加粗后删除键里的 × 会糊）。 */
    private static boolean isIconText(Object t) {
        if (t instanceof char[]) t = new String((char[]) t);
        if (!(t instanceof CharSequence)) return false;
        CharSequence cs = (CharSequence) t;
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (c >= 0xE000 && c <= 0xF8FF || Character.isSurrogate(c)) return true;
        }
        return false;
    }

    /** drawRoundRect(RectF, rx, ry, paint) 或 (l, t, r, b, rx, ry, paint)：把较小的圆角放大到设置值。 */
    private static void setKeyRadius(Object[] a, float[] wh) {
        int ix = a.length >= 4 && a[0] instanceof RectF ? 1 : a.length >= 7 ? 4 : -1;
        if (ix < 0 || !(a[ix] instanceof Float) || !(a[ix + 1] instanceof Float)) return;
        float rx = (Float) a[ix];
        if (rx >= 40f) return;                                  // 原本就很圆（候选栏圆形按钮等）
        float r = Math.min(keyRadiusPx, Math.min(wh[0], wh[1]) / 2f);
        a[ix] = r;
        a[ix + 1] = r;
    }

    private static Paint lastPaint(Object[] a) {
        for (int i = a.length - 1; i >= 0; i--) if (a[i] instanceof Paint) return (Paint) a[i];
        return null;
    }

    /** 低饱和度（灰/白/黑）颜色。 */
    private static boolean isGray(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 24;
    }

    /** 估算一次绘制指令的覆盖尺寸 [w, h]；算不出来返回 null。 */
    private static float[] opSize(String op, Object[] a) {
        try {
            switch (op) {
                case "drawColor": case "drawPaint": case "drawARGB": case "drawRGB":
                    return new float[]{ownerW, ownerH};
                case "drawRect": case "drawRoundRect": case "drawDoubleRoundRect": case "drawPatch":
                case "drawBitmap": {
                    for (int i = a.length - 1; i >= 0; i--) {   // 最后一个 Rect/RectF = 目标区域
                        if (a[i] instanceof RectF) return new float[]{((RectF) a[i]).width(), ((RectF) a[i]).height()};
                        if (a[i] instanceof android.graphics.Rect) {
                            android.graphics.Rect r = (android.graphics.Rect) a[i];
                            return new float[]{r.width(), r.height()};
                        }
                    }
                    if (op.equals("drawBitmap") && a.length >= 3 && a[0] instanceof android.graphics.Bitmap
                            && a[1] instanceof Float) {
                        android.graphics.Bitmap b = (android.graphics.Bitmap) a[0];
                        return new float[]{b.getWidth(), b.getHeight()};
                    }
                    if (a.length >= 5 && a[0] instanceof Float && a[2] instanceof Float) {
                        return new float[]{(Float) a[2] - (Float) a[0], (Float) a[3] - (Float) a[1]};
                    }
                    return null;
                }
                case "drawPath":
                    if (a.length > 0 && a[0] instanceof android.graphics.Path) {
                        RectF r = new RectF();
                        ((android.graphics.Path) a[0]).computeBounds(r, true);
                        return new float[]{r.width(), r.height()};
                    }
                    return null;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // 套玻璃
    // ---------------------------------------------------------------------------------------------
    private static View imeRoot(InputMethodService ims) {
        try {
            Dialog d = ims.getWindow();
            Window w = d == null ? null : d.getWindow();
            return w == null ? null : w.getDecorView();
        } catch (Throwable t) {
            return null;
        }
    }

    private void safeApply(InputMethodService ims, View decor) {
        try {
            props = readProps();
            trace = "1".equals(prop("trace", "0"));
            observe = "1".equals(prop("observe", "0"));
            obsMax = Math.round(f("obsmax", 600f));
            if ("1".equals(prop("dump", "0"))) dump(decor, 0);
            if ("2".equals(prop("dump", "0"))) {
                final View dv = decor;
                dv.postDelayed(() -> dump(dv, 0), 8000);   // 调试：等打完字、候选出来后再转一次
            }
            boolean enable = "1".equals(prop(Config.ENABLE))
                    && "1".equals(prop(isWechat ? Config.IME_WECHAT : Config.IME_DOUBAO));
            if (!enable) {
                hapticOn = false;
                disable(ims, decor);
                return;
            }
            hapticOn = "1".equals(prop(Config.HONOR_HAPTIC));
            hapDiag = "1".equals(prop("hapdiag", "0"));
            hapKeyOverride = prop("hapkey", "").replaceAll("[^a-z0-9_]", "");
            skipEnabled = "1".equals(prop(Config.SKIP));
            keyAlpha = clamp(f(Config.KEY_ALPHA), 0f, 1f);
            keyIos = "ios".equals(prop(Config.KEY_STYLE));
            keyLight = clamp(f(Config.KEY_LIGHT), 0.1f, 1f);
            keyDark = clamp(f(Config.KEY_DARK), 0.02f, 1f);
            dbWeFont = "1".equals(prop(Config.DOUBAO_WE_FONT));
            weB64 = prop(Config.WE_FONT, "");
            lblScale9We = clamp(f("lblscale9we", 1f), 0.5f, 1.5f);
            lblScale26We = clamp(f("lblscale26we", 1.08f), 0.5f, 1.5f);
            digitScaleWe = clamp(f("digitscalewe", 1.12f), 0.5f, 1.5f);
            symScaleWe = clamp(f("symscalewe", 1.03f), 0.5f, 1.5f);
            lblSpacing9We = clamp(f("lblspacing9we", 0.09f), 0f, 0.3f);
            candScaleWe = clamp(f("candscalewe", 1f), 0.5f, 1.5f);
            candBoldWe = clamp(f("candboldwe", 0.006f), 0f, 0.05f);
            lblScale9 = clamp(f("lblscale9", 0.95f), 0.5f, 1.5f);
            lblScale26 = clamp(f("lblscale26", 1.04f), 0.5f, 1.5f);
            lblBold = clamp(f("lblbold", 0.008f), 0f, 0.05f);
            candScale = clamp(f("candscale", 0.96f), 0.5f, 1.5f);
            candBold = clamp(f("candbold", 0.006f), 0f, 0.05f);
            candBlack = !"0".equals(prop("candblack", "1"));
            nightNow = isNight(ims);
            keyRadiusPx = Math.max(0f, f(Config.KEY_RADIUS)) * ims.getResources().getDisplayMetrics().density;
            apply(ims, decor);
            invalidateAll(decor);
        } catch (Throwable t) {
            log("apply failed: " + Log.getStackTraceString(t));
        }
    }

    /** 关闭：撤掉玻璃、底色，恢复被隐藏的皮肤层和按键。 */
    private void disable(InputMethodService ims, View decor) throws Throwable {
        skipEnabled = false;
        keyAlpha = 1f;
        keyIos = false;
        tintColor = 0;
        logoOn = false;
        keyRadiusPx = 0f;
        if (!active) return;
        active = false;
        View glass = glassRef == null ? null : glassRef.get();
        if (glass != null) {
            glass.setBackground(null);
            glass.setForeground(null);
            View eh = edgeHostRef == null ? null : edgeHostRef.get();
            if (eh != null) eh.setForeground(null);
            if (!isWechat) {
                glass.setClipToOutline(false);
                glass.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            }
            applied.remove(glass);
            clearHyperBlur(glass);
            Object sc = null;
            Class<?> mgr = null;
            if (hasHonorGlass()) {
                mgr = Class.forName(M + "HnMaterialManager");
                sc = mgr.getMethod("getSurfaceControl", View.class).invoke(null, glass);
            }
            if (sc != null) {
                mgr.getMethod("setWindowLiquidGlassEnable", Class.forName("android.view.SurfaceControl"), boolean.class,
                        Class.forName(M + "HnLiquidGlassMaterial")).invoke(null, sc, false,
                        buildMaterial(isNight(ims), new RectF(), 0));
            }
        }
        for (WeakReference<View> r : insetViews) {
            View v = r.get();
            if (v != null) { v.setTranslationX(0f); v.setTranslationY(0f); }
        }
        insetViews.clear();
        for (WeakReference<View> r : hiddenSkins) {
            View v = r.get();
            if (v != null) v.setVisibility(View.VISIBLE);
        }
        hiddenSkins.clear();
        invalidateAll(decor);
        log("disabled");
    }

    private void apply(InputMethodService ims, View decor) throws Throwable {
        View inputArea = decor.findViewById(android.R.id.inputArea);
        View inputView = (inputArea instanceof ViewGroup && ((ViewGroup) inputArea).getChildCount() > 0)
                ? ((ViewGroup) inputArea).getChildAt(0) : null;
        View glassView = inputArea != null ? inputArea : decor;
        // 豆包的 inputArea 铺满整个屏幕，键盘是里面贴底的子视图：这时用子视图当玻璃区域
        if (inputView != null && inputView.getHeight() > 0 && glassView.getHeight() > inputView.getHeight() * 1.2f) {
            glassView = inputView;
        }
        if (glassView.getWidth() == 0 || glassView.getHeight() == 0) return;

        // 1) 去掉不透明的皮肤背景，让玻璃露出来
        for (View v = inputView; v != null && v != decor; ) {
            if (v.getBackground() != null) v.setBackground(null);
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        if ("1".equals(prop(Config.SKIP)) && inputView != null) {
            hideSkin(inputView, (int) (glassView.getWidth() * 0.9f), 0);
        }

        boolean night = isNight(ims);
        lastNight = night;
        if (!isWechat) {
            candPadF = clamp(f("candpad", 0.55f), 0.2f, 1f);
            candGapF = clamp(f("candgap", 1.6f), 0.5f, 4f);
            if (candClass == null) {
                try {
                    candClass = Class.forName("com.bytedance.common_biz.tool_bar.view.widgets.CandidateItemView",
                            false, ims.getClass().getClassLoader());
                } catch (Throwable t) { log("CandidateItemView not found: " + t); }
            }
        }
        float density = ims.getResources().getDisplayMetrics().density;
        float radiusPx = Math.max(0f, f(Config.RADIUS)) * density;
        String pkg = targetPackage(ims);
        // 桌面下拉搜索：键盘紧接在搜索框面板下面，改成和面板同色（不透明）、铺满、不要圆角，看起来是一整块
        boolean searchFill = Config.SEARCH_PKG.equals(pkg) && "1".equals(prop(Config.SEARCH_FILL));
        if (searchFill) radiusPx = 0f;

        // 拼音行（候选栏左上角的 HorizontalScrollView）紧贴键盘左上角，圆角后会露在外面 -> 按圆角往里收
        final float insetR = radiusPx, insetDy = Math.round(clamp(f(isWechat ? Config.COMPOSE_DY : Config.COMPOSE_DY_DOUBAO), -8f, 24f) * density);
        insetCornerRows(inputView, glassView, insetR, insetDy, density);
        // 拼音行可能在键盘大小不变的情况下才出现（豆包：候选栏里从隐藏变成显示），所以每次布局后都再检查一次
        if (inputView != null) {
            final View iv = inputView, gv = glassView;
            final float d = density;
            android.view.ViewTreeObserver.OnGlobalLayoutListener old = cornerListeners.get(iv);
            if (old != null) iv.getViewTreeObserver().removeOnGlobalLayoutListener(old);
            android.view.ViewTreeObserver.OnGlobalLayoutListener l = () -> {
                if (active) insetCornerRows(iv, gv, insetR, insetDy, d);
            };
            cornerListeners.put(iv, l);
            iv.getViewTreeObserver().addOnGlobalLayoutListener(l);
        }

        // 2) 玻璃之上的圆角底色：控制背景不透明度（可读性）
        int base = searchFill ? (night ? unlift(Config.SEARCH_DARK) : Config.SEARCH_LIGHT)
                : color(night ? Config.BG_DARK : Config.BG_LIGHT);
        int alpha = searchFill ? 255 : Math.round(clamp(f(Config.BG_ALPHA), 0f, 1f) * 255);
        GradientDrawable tint = new GradientDrawable();
        tint.setColor((base & 0x00FFFFFF) | (alpha << 24));
        tint.setCornerRadii(new float[]{radiusPx, radiusPx, radiusPx, radiusPx, 0, 0, 0, 0});
        glassView.setBackground(tint);
        tintColor = (base & 0x00FFFFFF) | (alpha << 24);
        tintRadius = radiusPx;
        glassW = glassView.getWidth();
        glassStatic = new WeakReference<>(glassView);
        // iOS 的玻璃顶边：一条顺着圆角的亮线，下面一层约 20px 往下淡出的光（截图取色反推的强度）
        // 豆包的 InputView 自己画完前景后还会在最上面几像素再画一层（把亮线和暗轮廓盖掉），
        // 所以豆包画在外面一层（铺满屏幕的 inputArea）的前景上，按 InputView 的位置画
        View edgeHost = !isWechat && glassView.getParent() instanceof android.widget.FrameLayout
                ? (View) glassView.getParent() : glassView;
        View oldHost = edgeHostRef == null ? null : edgeHostRef.get();
        if (oldHost != null && oldHost != edgeHost) oldHost.setForeground(null);
        if (edgeHost != glassView) glassView.setForeground(null);
        edgeHost.setForeground(!searchFill && "1".equals(prop(Config.EDGE)) ? new EdgeGlow(glassView, edgeHost, radiusPx, night, density) : null);
        edgeHostRef = new WeakReference<>(edgeHost);
        // 豆包的语音面板等自带直角背景，会盖出键盘的圆角：把整个键盘区域按顶部圆角裁剪
        if (!isWechat) {
            final float rr = radiusPx;
            if (rr > 0) {
                glassView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                    @Override public void getOutline(View v, android.graphics.Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight() + Math.round(rr), rr);
                    }
                });
                glassView.setClipToOutline(true);
            } else {
                glassView.setClipToOutline(false);
            }
            glassView.invalidateOutline();
        }

        applyVoiceButton(ims, decor);
        applyLogo(ims, inputView, glassView, night);

        // 3) 系统液态玻璃。不是 MagicOS（没有 HnMaterialManager）时，有 HyperOS 的模糊接口就用它（实验）
        if (!hasHonorGlass()) {
            if (hasHyperBlur()) {
                if (!applyHyperGlass(glassView, night)) applyHyperBlur(glassView, density);
            } else if (!searchFill) {
                // 其他系统（ColorOS 等）：没有液态玻璃，用安卓 12 起的区域背景模糊（只模糊键盘这一块），底色叠在模糊上
                applyRegionBlur(glassView, tintColor, radiusPx, density);
            }
            applied.put(glassView, "hyper");
            glassRef = new WeakReference<>(glassView);
            active = true;
            return;
        }
        int[] loc = new int[2];
        glassView.getLocationInWindow(loc);
        RectF rect = new RectF(loc[0], loc[1], loc[0] + glassView.getWidth(), loc[1] + glassView.getHeight());
        String mode = prop("mode", "window");
        String sig = props.toString() + allPrefs() + night + rect + pkg;
        boolean same = sig.equals(applied.get(glassView));

        Class<?> mgr = Class.forName(M + "HnMaterialManager");
        if (mode.equals("window") || mode.equals("both")) {
            // 输入法窗口每次隐藏都会销毁 Surface，所以每次都重新下发（只是一个 Surface 事务，开销很小）
            Object sc = mgr.getMethod("getSurfaceControl", View.class).invoke(null, glassView);
            if (sc == null) throw new IllegalStateException("no SurfaceControl yet");
            mgr.getMethod("setWindowLiquidGlassEnable", Class.forName("android.view.SurfaceControl"), boolean.class,
                    Class.forName(M + "HnLiquidGlassMaterial")).invoke(null, sc, true,
                    buildMaterial(night, rect, radiusPx));
        }
        if ((mode.equals("view") || mode.equals("both")) && !same) {
            Object opt = Class.forName(M + "HnMaterialOpt").getConstructor().newInstance();
            call(opt, "setClearBackground", new Class[]{boolean.class}, true);
            call(opt, "setLayerBackdrop", new Class[]{boolean.class}, true);
            mgr.getMethod("setHnMaterial", View.class, Class.forName(M + "HnMaterial"), opt.getClass())
                    .invoke(null, glassView, buildMaterial(night, null, radiusPx), opt);
        }
        applied.put(glassView, sig);
        glassRef = new WeakReference<>(glassView);
        active = true;
        if (!layoutHooked.containsKey(glassView)) {
            layoutHooked.put(glassView, Boolean.TRUE);
            // 键盘高度/位置变化（候选展开、切换布局、横竖屏）时重新设置玻璃区域
            glassView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (l != ol || t != ot || r != or || b != ob) v.post(() -> safeApply(ims, decor));
            });
        }
        if (same && !trace) return;
        log("glass(" + mode + ") pkg=" + pkg + " searchFill=" + searchFill + " rect=" + rect.toShortString() + " night=" + night + " settings=" + allPrefs()
                + " props=" + props);
    }

    private static Boolean honorGlass, hyperBlur;

    static boolean hasHonorGlass() {
        if (honorGlass == null) {
            try { Class.forName(M + "HnMaterialManager"); honorGlass = true; } catch (Throwable t) { honorGlass = false; }
        }
        return honorGlass;
    }

    /**
     * 安卓 12 起的「区域背景模糊」：ViewRootImpl.createBackgroundBlurDrawable() 拿到的 Drawable 只模糊自己所在那块区域背后的内容，
     * 可以设模糊半径、圆角和叠在上面的颜色。ColorOS（一加 / OPPO）上实测可用，系统关了窗口模糊（省电模式等）时不生效，保留原来的底色。
     * 模糊半径取设置里的「模糊」（px，和 MagicOS 的玻璃模糊同一个设置）。
     */
    private void applyRegionBlur(View v, int tint, float radiusPx, float density) {
        try {
            android.view.WindowManager wm = (android.view.WindowManager) v.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm != null && !wm.isCrossWindowBlurEnabled()) { log("region blur: cross-window blur disabled"); return; }
            Object vri = View.class.getMethod("getViewRootImpl").invoke(v);
            if (vri == null) { log("region blur: no ViewRootImpl yet"); return; }
            Drawable bd = (Drawable) vri.getClass().getMethod("createBackgroundBlurDrawable").invoke(vri);
            int blur = Math.round(clamp(f(Config.BLUR), 0f, 400f));
            bd.getClass().getMethod("setBlurRadius", int.class).invoke(bd, blur);
            bd.getClass().getMethod("setCornerRadius", float.class, float.class, float.class, float.class)
                    .invoke(bd, radiusPx, radiusPx, 0f, 0f);
            bd.getClass().getMethod("setColor", int.class).invoke(bd, tint);
            v.setBackground(bd);
            regionBlurOn = true;
            // 豆包的键盘背景图（input_view_image_bg）在 ColorOS 上是一张纯色图（深 #222222），画法绕过了去底板的拦截，
            // 会把模糊整块盖住（微信是半透明毛玻璃，豆包是一整块不透明灰）。这张图只是皮肤底图，直接设成全透明
            if (!isWechat) {
                View bgImg = findByIdName(v, "input_view_image_bg", 0);
                if (bgImg != null && bgImg.getAlpha() != 0f) {
                    bgImg.setAlpha(0f);
                    log("region blur: hid input_view_image_bg");
                }
            }
            log("region blur on " + v.getClass().getSimpleName() + " radius=" + blur + String.format(" tint=#%08X", tint));
        } catch (Throwable t) {
            log("region blur failed: " + t);
        }
    }

    static volatile boolean regionBlurOn;

    /** v 在玻璃视图（键盘区域）里面。 */
    private static boolean isInsideGlass(View v) {
        View g = glassStatic == null ? null : glassStatic.get();
        if (g == null) return false;
        for (android.view.ViewParent q = v.getParent(); q instanceof View; q = q.getParent()) if (q == g) return true;
        return false;
    }

    private static View findByIdName(View v, String name, int depth) {
        if (name.equals(idName(v))) return v;
        if (depth > 8 || !(v instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View r = findByIdName(g.getChildAt(i), name, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    static boolean hasHyperBlur() {
        if (hyperBlur == null) {
            try { View.class.getMethod("setMiBackgroundBlurMode", int.class); hyperBlur = true; } catch (Throwable t) { hyperBlur = false; }
        }
        return hyperBlur;
    }

    /**
     * HyperOS（小米）的背景模糊：View.setMiBackgroundBlurMode / setMiBackgroundBlurRadius / setMiViewBlurMode
     * / addMiBackgroundBlendColor（系统隐藏接口，miuix 的 MiuiBlurUtils 也是这样反射调用的）。
     * 参数先用 adb 属性试：hbmode（背景模糊模式，默认 1）、hbradius（半径，默认按「模糊强度」）、hvmode（视图模糊模式，默认 -1 = 不设）、
     * hblend（混色模式，默认 -1 = 不混色）、hblendcolor（混色颜色，默认用底色）。
     */
    private void applyHyperBlur(View v, float density) {
        try {
            int mode = Math.round(f("hbmode", 1f));
            int radius = Math.round(f("hbradius", Math.max(0f, f(Config.BLUR))));
            int vmode = Math.round(f("hvmode", -1f));
            int blend = Math.round(f("hblend", -1f));
            View.class.getMethod("setMiBackgroundBlurMode", int.class).invoke(v, mode);
            View.class.getMethod("setMiBackgroundBlurRadius", int.class).invoke(v, Math.min(400, radius));
            if (vmode >= 0) View.class.getMethod("setMiViewBlurMode", int.class).invoke(v, vmode);
            try { View.class.getMethod("clearMiBackgroundBlendColor").invoke(v); } catch (Throwable ignored) {}
            if (blend >= 0) {
                int color = tintColor;
                String bc = prop("hblendcolor", "");
                if (!bc.isEmpty()) color = Color.parseColor(bc);
                View.class.getMethod("addMiBackgroundBlendColor", int.class, int.class).invoke(v, color, blend);
            }
            if (prop("hnobg", "0").equals("1")) v.setBackground(null);   // 试：不画我们自己的底色，只靠系统模糊和混色
            log("hyper blur mode=" + mode + " radius=" + radius + " vmode=" + vmode + " blend=" + blend);
        } catch (Throwable t) {
            log("hyper blur failed: " + t);
        }
    }

    private static ClassLoader miuixLoader;
    private static volatile boolean hyperGlassOn, lastNight;
    static volatile Class<?> candClass;
    static volatile float candPadF = 0.55f;
    static volatile float candGapF = 1.6f;
    /** 豆包面板里铺满整宽的纯色底（工具栏、功能菜单的标题栏和内容区）。 */
    private static final java.util.Set<String> PANEL_BG_IDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "native_candidate_bar", "toolbox_header_container", "toolbox_content_container"));
    private static final WeakHashMap<Drawable, Boolean> ourTabBgs = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> styledTabBars = new WeakHashMap<>();

    /**
     * 豆包表情分类栏：栏底去掉（露出键盘底色）；每个分类按钮的底换成「选中时一个内缩的圆角胶囊（按键色），没选中时透明」。
     * 豆包重建按钮时（布局变化）再换一遍。只在 iOS 配色下做。
     */
    private static void styleEmojiTabs(View bar) {
        if (!keyIos) return;
        bar.setBackground(null);
        restyleTabs(bar, 0);
        if (styledTabBars.containsKey(bar)) return;
        styledTabBars.put(bar, Boolean.TRUE);
        bar.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (keyIos && skipEnabled) restyleTabs(bar, 0);
        });
    }

    private static void restyleTabs(View v, int depth) {
        if (!(v instanceof ViewGroup) || depth > 3) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            Drawable d = c.getBackground();
            if (d != null && !ourTabBgs.containsKey(d)) {
                if (c.getClass().getName().endsWith("TabLayout$TabView")) c.setBackground(null);
                else if (!c.getClass().getName().endsWith("TabLayout$SlidingTabIndicator")) c.setBackground(tabPill());
            }
            restyleTabs(c, depth + 1);
        }
        // 分类栏左边单独的「最近使用」按钮（btn_history），选中时一样是贴边方块
        if (depth == 0 && v.getParent() instanceof ViewGroup) {
            ViewGroup pg = (ViewGroup) v.getParent();
            for (int i = 0; i < pg.getChildCount(); i++) {
                View c = pg.getChildAt(i);
                Drawable d = c.getBackground();
                if (!"btn_history".equals(idName(c))) continue;
                if (d != null && !ourTabBgs.containsKey(d)) c.setBackground(tabPill());
                // 它固定在分类栏左端、盖在分类栏上面；原来靠不透明底挡住滑过来的分类，换成透明底以后会叠在一起。
                // 把分类栏左边被它盖住的那段裁掉，分类滑到它旁边就消失
                int edge = c.getVisibility() == View.VISIBLE ? c.getRight() - v.getLeft() : 0;
                android.graphics.Rect cb = v.getClipBounds();
                if (edge > 0 && edge < v.getWidth() && c.getTop() < v.getBottom() && c.getBottom() > v.getTop()) {
                    if (cb == null || cb.left != edge || cb.right != v.getWidth() || cb.bottom != v.getHeight())
                        v.setClipBounds(new android.graphics.Rect(edge, 0, v.getWidth(), v.getHeight()));
                } else if (cb != null) {
                    v.setClipBounds(null);
                }
            }
        }
    }

    /** 分类按钮的底：选中时内缩的圆角胶囊（按键色），没选中时透明。 */
    private static Drawable tabPill() {
        GradientDrawable pill = new GradientDrawable();
        int t = iosKeyColor(0);
        pill.setColor((t & 0x00FFFFFF) | (Math.round(Color.alpha(t) * keyAlpha) << 24));
        pill.setCornerRadius(10000f);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_selected},
                new android.graphics.drawable.InsetDrawable(pill, 0.1f, 0.14f, 0.1f, 0.14f));
        sl.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        ourTabBgs.put(sl, Boolean.TRUE);
        return sl;
    }

    private static RectF ovalRect(Object[] a) {
        if (a.length >= 1 && a[0] instanceof RectF) return new RectF((RectF) a[0]);
        if (a.length >= 4 && a[0] instanceof Float) return new RectF((Float) a[0], (Float) a[1], (Float) a[2], (Float) a[3]);
        return null;
    }

    /**
     * 豆包按住说话「松手结束」那块深色椭圆在键盘底栏里的延续：挂在玻璃视图（豆包的 InputView）的叠加层上，
     * 只画在椭圆视图底边以下、玻璃视图以内的那一段，颜色和不透明度跟椭圆一样（含椭圆视图及其外层的透明度）。
     * 椭圆视图不显示时不画；每帧绘制前检查一次位置、显示状态有没有变，变了才重画。
     */
    static final class ArcExt extends Drawable {
        private static WeakReference<View> ellipse = new WeakReference<>(null);
        private static final RectF oval = new RectF();
        private static int color;
        private static ArcExt inst;
        private static WeakReference<View> host = new WeakReference<>(null);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] a = new int[2], b = new int[2];
        private String lastSig = "";

        static void update(View ev, RectF o, int c) {
            boolean changed = ellipse.get() != ev || !oval.equals(o) || color != c;
            ellipse = new WeakReference<>(ev);
            oval.set(o);
            color = c;
            View g = glassStatic == null ? null : glassStatic.get();
            if (g == null) return;
            if (inst == null || host.get() != g) {
                if (inst != null && host.get() != null) host.get().getOverlay().remove(inst);
                inst = new ArcExt();
                host = new WeakReference<>(g);
                g.getOverlay().add(inst);
                final ArcExt me = inst;
                g.getViewTreeObserver().addOnPreDrawListener(() -> {
                    if (inst == me) me.checkChanged();
                    return true;
                });
                changed = true;
            }
            inst.setBounds(0, 0, g.getWidth(), g.getHeight());
            if (changed) inst.invalidateSelf();
        }

        private String signature() {
            View ev = ellipse.get(), g = host.get();
            if (ev == null || g == null || !ev.isShown() || !skipEnabled) return "hidden";
            ev.getLocationInWindow(a);
            g.getLocationInWindow(b);
            return (a[0] - b[0]) + "," + (a[1] - b[1]) + "," + ev.getHeight() + "," + g.getWidth() + "x" + g.getHeight() + "," + chainAlpha(ev, g);
        }

        void checkChanged() {
            String sig = signature();
            if (!sig.equals(lastSig)) invalidateSelf();
        }

        private static float chainAlpha(View v, View stop) {
            float al = 1f;
            for (View x = v; x != null && x != stop; x = x.getParent() instanceof View ? (View) x.getParent() : null) al *= x.getAlpha();
            return al;
        }

        @Override public void draw(android.graphics.Canvas c) {
            lastSig = signature();
            if ("hidden".equals(lastSig)) return;
            View ev = ellipse.get(), g = host.get();
            if (ev == null || g == null) return;
            setBounds(0, 0, g.getWidth(), g.getHeight());
            float dx = a[0] - b[0], dy = a[1] - b[1];
            float top = dy + ev.getHeight();
            if (top >= g.getHeight()) return;
            int al = Math.round(Color.alpha(color) * chainAlpha(ev, g));
            paint.setColor((color & 0x00FFFFFF) | (al << 24));
            boolean old = selfDraw;
            selfDraw = true;
            c.save();
            try {
                c.clipRect(0, top, g.getWidth(), g.getHeight());
                c.drawOval(oval.left + dx, oval.top + dy, oval.right + dx, oval.bottom + dy, paint);
            } finally {
                c.restore();
                selfDraw = old;
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** 豆包表情页底部的分类栏：Emoji 页 emoji_tab、颜文字页 kaomoji_tab 等，都是名字以 _tab 结尾的 TabLayout。 */
    private static boolean isEmojiTabBar(View v) {
        return v.getClass().getName().endsWith("tabs.TabLayout") && idName(v).endsWith("_tab");
    }

    /** v 往上 depth 层以内有没有资源名是 id 的视图。 */
    private static boolean insideId(View v, String id, int depth) {
        android.view.ViewParent q = v.getParent();
        for (int i = 0; i < depth && q instanceof View; i++, q = q.getParent()) {
            if (id.equals(idName((View) q))) return true;
        }
        return false;
    }

    /** 视图 id 的资源名（缓存；豆包的资源包名和应用包名不同，getIdentifier 查不到，只能反查）。 */
    private static final HashMap<Integer, String> idNames = new HashMap<>();
    static String idName(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return "";
        String n = idNames.get(id);
        if (n == null) {
            try { n = v.getResources().getResourceEntryName(id); } catch (Throwable t) { n = ""; }
            idNames.put(id, n);
        }
        return n;
    }
    private static java.lang.reflect.Method adapterPosM;

    /** RecyclerView 里子 View 的位置（RecyclerView.getChildAdapterPosition）；不是 RecyclerView 或取不到时返回 -1。 */
    private static int adapterPos(View rv, View child) {
        try {
            java.lang.reflect.Method m = adapterPosM;
            if (m == null || m.getDeclaringClass() != rv.getClass() && !m.getDeclaringClass().isInstance(rv)) {
                m = rv.getClass().getMethod("getChildAdapterPosition", View.class);
                adapterPosM = m;
            }
            return (Integer) m.invoke(rv, child);
        } catch (Throwable t) {
            return -1;
        }
    }
    private static final WeakHashMap<View, int[]> candPads = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> overlayGlassed = new WeakHashMap<>();
    /** 画过键面的视图（键盘本体），不是盖在键盘上的面板。 */
    private static final WeakHashMap<View, Boolean> keyOwners = new WeakHashMap<>();

    /**
     * HyperOS 玻璃模式下，盖在键盘上的面板（豆包点左上角出来的功能菜单、按住说话、翻译等）原来的整块底色被跳过了，下面的按键会透出来。
     * 给这种面板铺一层不透明的底色，颜色按面板在键盘里的位置取键盘玻璃实测的上下渐变（OverlayBg），看起来和周围的玻璃是同一块。
     * （以前给面板自己再挂一层玻璃 + 85% 底色：面板玻璃自己的明暗和键盘那块对不上，下面的按键也会透一点出来）
     * 只铺「盖住键盘一半以上高度」的最外层面板；候选栏这种不盖按键的条、已经铺过的面板里面的子视图都不铺。
     */
    /** c 是否画在别的内容之上：从 c 往上到 root，某一层前面有可见的、和它重叠的兄弟视图。 */
    private static boolean drawnOverOthers(View c, View root) {
        android.graphics.Rect rc = new android.graphics.Rect(), rs = new android.graphics.Rect();
        for (View v = c; v != null && v != root; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (!(v.getParent() instanceof ViewGroup)) break;
            ViewGroup pg = (ViewGroup) v.getParent();
            rc.set(v.getLeft(), v.getTop(), v.getRight(), v.getBottom());
            for (int i = 0; i < pg.getChildCount(); i++) {
                View sib = pg.getChildAt(i);
                if (sib == v) break;
                if (sib.getVisibility() != View.VISIBLE || sib.getWidth() == 0 || sib.getHeight() == 0) continue;
                rs.set(sib.getLeft(), sib.getTop(), sib.getRight(), sib.getBottom());
                if (android.graphics.Rect.intersects(rc, rs) && rs.height() > rc.height() * 0.3f) {
                    if (trace) Log.i(TAG, "overlay check " + c.getClass().getName() + ": level " + v.getClass().getName()
                            + " over " + sib.getClass().getName() + " " + rs + " childs="
                            + (sib instanceof ViewGroup ? ((ViewGroup) sib).getChildCount() : -1));
                    return true;
                }
            }
        }
        return false;
    }

    private void overlayGlass(View c) {
        View g = glassRef == null ? null : glassRef.get();
        if (g == null || c == g || overlayGlassed.containsKey(c)) return;
        for (android.view.ViewParent q = c.getParent(); q instanceof View; q = q.getParent()) {
            if (overlayGlassed.containsKey(q)) return;          // 外层已经铺过
        }
        for (android.view.ViewParent q = g.getParent(); q instanceof View; q = q.getParent()) {
            if (q == c) return;                                   // c 是玻璃视图的外层容器
        }
        if (c.getHeight() < g.getHeight() * 0.5f) return;
        if (!drawnOverOthers(c, g)) return;                       // 键盘自己的底板（在按键下面）不铺
        overlayGlassed.put(c, Boolean.TRUE);
        final boolean night = lastNight;
        c.post(() -> {
            // 这一帧画完了：自己画过按键的是键盘本体（微信的 9 键/26 键也放在叠在上层的容器里），不铺
            if (keyOwners.containsKey(c)) { log("overlay skip (keyboard) " + c.getClass().getName()); return; }
            c.setBackground(new OverlayBg(c, g, night));
            invalidateTree(c);   // 面板里的标题栏等第一帧已经画过（那时还不算在面板里），让它们按新规则重画
            log("overlay bg on " + c.getClass().getName() + " " + c.getWidth() + "x" + c.getHeight());
        });
    }

    private static void invalidateTree(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateTree(g.getChildAt(i));
        }
    }

    /** v 是不是铺了 OverlayBg 的面板里面的视图（不含面板本身）。 */
    private static boolean insideOverlay(View v) {
        for (android.view.ViewParent q = v.getParent(); q instanceof View; q = q.getParent()) {
            if (overlayGlassed.containsKey(q)) return ((View) q).getBackground() instanceof OverlayBg;
        }
        return false;
    }

    /**
     * 盖在键盘上的面板的底色：不透明，按面板相对玻璃区域的位置画键盘玻璃的上下渐变。
     * 渐变是在小米 15（HyperOS 4.0）上对着纯黑 / 纯白背景截图取色的（玻璃上半截基本不变，下半截逐渐变亮）。
     * 自己画的这笔不能被底板跳过逻辑拦掉，所以画的时候置 selfDraw。
     */
    static final class OverlayBg extends Drawable {
        private static final float[] POS_DARK = {0f, 0.35f, 0.62f, 0.78f, 1f};
        private static final int[] COL_DARK = {0xFF181818, 0xFF191919, 0xFF1C1C1C, 0xFF1E1E1E, 0xFF202020};
        private static final float[] POS_LIGHT = {0f, 0.31f, 0.52f, 0.72f, 0.93f, 1f};
        private static final int[] COL_LIGHT = {0xFFE1E0E2, 0xFFE2E0E2, 0xFFE5E4E6, 0xFFEBEAEB, 0xFFEFEFF0, 0xFFEFEFF0};
        private final WeakReference<View> panel, glass;
        private final boolean night;
        private final Paint paint = new Paint();
        private final int[] a = new int[2], b = new int[2];
        private int lastOff = Integer.MIN_VALUE, lastH = -1;

        OverlayBg(View panel, View glass, boolean night) {
            this.panel = new WeakReference<>(panel);
            this.glass = new WeakReference<>(glass);
            this.night = night;
        }

        @Override public void draw(android.graphics.Canvas c) {
            View p = panel.get(), g = glass.get();
            int off = 0, h = getBounds().height();
            if (p != null && g != null && g.getHeight() > 0) {
                p.getLocationInWindow(b);
                g.getLocationInWindow(a);
                off = b[1] - a[1];
                h = g.getHeight();
            }
            if (off != lastOff || h != lastH || paint.getShader() == null) {
                lastOff = off; lastH = h;
                paint.setShader(new android.graphics.LinearGradient(0, -off, 0, -off + Math.max(1, h),
                        night ? COL_DARK : COL_LIGHT, night ? POS_DARK : POS_LIGHT, android.graphics.Shader.TileMode.CLAMP));
            }
            boolean old = selfDraw;
            selfDraw = true;
            try {
                c.drawRect(getBounds(), paint);
            } finally {
                selfDraw = old;
            }
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    /**
     * HyperOS 的液态玻璃（实验）：和 miuix 的 HyperMaterialUtils.applyContainerWithGlass 一样——
     * 背景模糊模式 1、视图模糊模式 1、材质类型 1、玻璃模糊半径（小/大）、setMiGlass(着色器参数)。
     * 着色器参数直接用系统自带 miuix.jar 里的预设（GlassToken.Glass_xxx.toShaderParams()），不用自己猜数值。
     * 预设名用 adb 属性 hglass 换（默认 Glass_Common_Medium_Thin_High，none = 不用玻璃，退回普通模糊）。
     * 玻璃要折射窗口背后的画面，得开「窗口模糊透传」（setPassWindowBlurEnabled），系统只给白名单应用开，
     * 所以在输入法进程里把 ViewRootImpl 的白名单过滤关掉；背景底色保留形状、透明度设 0（玻璃按它的形状画）。
     */
    private boolean applyHyperGlass(View v, boolean night) {
        // 浅色、深色用不同的系统预设（浅色玻璃放在深色模式里会整块发白）
        String name = night ? prop("hglassdark", "Glass_Common_Medium_Regular_Dark")
                : prop("hglass", "Glass_Common_Medium_Thin_High");
        if ("none".equals(name)) return false;
        try {
            if (miuixLoader == null) {
                miuixLoader = new dalvik.system.PathClassLoader("/system_ext/framework/miuix.jar", View.class.getClassLoader());
            }
            Class<?> gt = Class.forName("miuix.theme.token.GlassToken", true, miuixLoader);
            Object token = gt.getField(name).get(null);
            float[] params = (float[]) gt.getMethod("toShaderParams").invoke(token);
            Object br = gt.getField("blurRadius").get(token);
            int small = br.getClass().getField("smallBlurRadius").getInt(br);
            int big = br.getClass().getField("bigBlurRadius").getInt(br);
            if (!prop("hpass", "1").equals("0")) {
                // 系统只给白名单里的应用开「把窗口背后的画面传给视图」（玻璃要靠它才有东西可折射）。
                // 白名单检查在本进程的 ViewRootImpl 里，只在输入法自己的进程里关掉这个过滤
                try {
                    java.lang.reflect.Field ff = Class.forName("android.view.ViewRootImpl").getDeclaredField("sIsEnabledPassWindowBlurFilter");
                    ff.setAccessible(true);
                    ff.setBoolean(null, false);
                } catch (Throwable t) { log("pass filter: " + t); }
                Object ok = View.class.getMethod("setPassWindowBlurEnabled", boolean.class).invoke(v, true);
                log("hyper pass window blur -> " + ok);
            }
            View.class.getMethod("setMiBackgroundBlurMode", int.class).invoke(v, 1);
            int btype = Math.round(f("hbtype", -1f));
            if (btype >= 0) View.class.getMethod("setMiBackgroundBlurType", int.class).invoke(v, btype);
            int stype = Math.round(f("hstype", -1f));
            if (stype >= 0) View.class.getMethod("setMiSelfBlurType", int.class).invoke(v, stype);
            int bgr = Math.round(f("hbradius", -1f));
            if (bgr >= 0) View.class.getMethod("setMiBackgroundBlurRadius", int.class).invoke(v, Math.min(400, bgr));
            View.class.getMethod("setMiViewBlurMode", int.class).invoke(v, Math.round(f("hvmode", 1f)));
            View.class.getMethod("setMiViewMaterialType", int.class).invoke(v, Math.round(f("hmat", 1f)));
            View.class.getMethod("setMiGlassBlurRadius", int.class, int.class).invoke(v, small, big);
            View.class.getMethod("setMiGlass", float[].class).invoke(v, (Object) params);
            int win = Math.round(f("hwintype", 1f));
            if (win >= 0) {
                Object ok = View.class.getMethod("setMiBlurWinType", int.class).invoke(v, win);
                log("hyper win type " + win + " -> " + ok);
            }
            String nobg = prop("hnobg", "2");
            if (nobg.equals("1")) v.setBackground(null);
            else if (nobg.equals("2") && v.getBackground() != null) v.getBackground().mutate().setAlpha(0);
            hyperGlassOn = true;
            log("hyper glass " + name + " params=" + params.length + " blur=" + small + "/" + big);
            return true;
        } catch (Throwable t) {
            log("hyper glass failed: " + t);
            return false;
        }
    }

    private static void clearHyperBlur(View v) {
        hyperGlassOn = false;
        if (!hasHyperBlur()) return;
        try {
            View.class.getMethod("setMiBackgroundBlurMode", int.class).invoke(v, 0);
            try { View.class.getMethod("clearMiBackgroundBlendColor").invoke(v); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
    }

    private Object buildMaterial(boolean night, RectF rect, float radiusPx) throws Throwable {
        Object mat = Class.forName(M + "HnLiquidGlassMaterial").getConstructor().newInstance();

        // 模糊 + 蒙版
        Class<?> blurCls = Class.forName(A + "BlurParamEx");
        Object blur = blurCls.getConstructor().newInstance();
        call(blur, "setRadius", new Class[]{float.class}, Math.max(0f, f(Config.BLUR)));
        call(blur, "setSaturation", new Class[]{float.class}, Math.max(0f, f(Config.SAT)));
        float b = f("bright", night ? 0f : 0.08f);
        call(blur, "setBrightnessParams", new Class[]{float.class, float.class, float.class, float.class}, b, b, b, b);
        int mask = color("mask", night ? 0x26B3B3B3 : 0x26FFFFFF);
        call(blur, "setMultiMaskColors", new Class[]{int[].class, BlendMode[].class},
                new int[]{mask, 0x00FFFFFF}, new BlendMode[]{BlendMode.LUMINOSITY, BlendMode.PLUS});
        call(mat, "setBlurParamEx", new Class[]{blurCls}, blur);

        // 玻璃（折射/厚度/深度/色散）
        Class<?> glassCls = Class.forName(A + "GlassParamEx");
        Object glass = glassCls.getConstructor().newInstance();
        call(glass, "setRefraction", new Class[]{float.class}, f(Config.REFRACTION));
        call(glass, "setThickness", new Class[]{float.class}, f(Config.THICKNESS));
        call(glass, "setDepth", new Class[]{float.class}, f(Config.DEPTH));
        call(glass, "setDispersion", new Class[]{float.class}, f(Config.DISPERSION));
        call(mat, "setGlassParamEx", new Class[]{glassCls}, glass);

        // 边缘高光
        if ("1".equals(prop(Config.EDGE))) {
            Class<?> edgeCls = Class.forName(A + "EdgeLightParamEx");
            Object edge = edgeCls.getConstructor().newInstance();
            call(edge, "setAngle", new Class[]{int.class}, 250);
            call(edge, "setThickness", new Class[]{float.class}, 6f);
            call(edge, "setColor", new Class[]{int.class}, night ? 0x4DFFFFFF : 0x33FFFFFF);
            call(mat, "setEdgeLightParamEx", new Class[]{edgeCls}, edge);
        }

        // 形状：键盘矩形，圆角
        Class<?> shapeCls = Class.forName(A + "ShapeParamEx");
        Class<?> shapeEnum = Class.forName(A + "ShapeParamEx$Shape");
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object type = Enum.valueOf((Class) shapeEnum, radiusPx > 0 ? "RRect" : "Rect");
        Object shape = rect == null
                ? shapeCls.getConstructor(shapeEnum, float.class).newInstance(type, radiusPx)
                : shapeCls.getConstructor(shapeEnum, float.class, RectF.class).newInstance(type, radiusPx, rect);
        call(mat, "setShapeParamEx", new Class[]{shapeCls}, shape);
        return mat;
    }

    /** 隐藏微信输入法整宽、自绘、没有子视图的纯背景层（只针对 android.view.View 本类）。 */
    private void hideSkin(View v, int minWidth, int depth) {
        if (depth > 8) return;
        if (v.getClass() == View.class && v.getVisibility() == View.VISIBLE
                && !v.willNotDraw() && v.getWidth() >= minWidth) {
            v.setVisibility(View.INVISIBLE);
            hiddenSkins.add(new WeakReference<>(v));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) hideSkin(g.getChildAt(i), minWidth, depth + 1);
        }
    }

    /**
     * 把落在左上角圆角范围内的横向滚动行（拼音/编码行）往右平移，避免文字跑到圆角外面。
     * 整行往下移 dy（默认 2dp）后，文字顶部离边约 35px，圆弧在这个高度的水平侵入量约为半径的 0.15 倍，
     * 行内自带约 18px 左边距，所以平移半径的 0.35 倍（22dp 圆角 ≈ 26px）就够，再多看起来太靠右。
     * 桌面搜索里没有圆角，只下移。
     */
    private void insetCornerRows(View inputView, View glassView, float radiusPx, float dy, float density) {
        if (inputView == null) return;
        List<View> rows = new ArrayList<>();
        // 判定范围：左边在圆角内（至少 16dp），顶边在 16dp 内（候选栏在拼音行下面约 22dp，不会被选中）
        float zone = 16f * density;
        findCornerRows(inputView, glassView, Math.max(radiusPx, zone), zone, rows, 0);
        float dx = Math.round(radiusPx * 0.35f);
        for (View v : rows) {
            if (v.getTranslationX() != dx) v.setTranslationX(dx);
            if (v.getTranslationY() != dy) v.setTranslationY(dy);
            boolean tracked = false;
            for (WeakReference<View> r : insetViews) if (r.get() == v) { tracked = true; break; }
            if (!tracked) insetViews.add(new WeakReference<>(v));
        }
        if (trace) log("corner rows inset " + dx + "," + dy + "px: " + rows.size());
    }

    private static void findCornerRows(View v, View glassView, float maxLeft, float maxTop, List<View> out, int depth) {
        if (depth > 20 || v.getVisibility() == View.GONE) return;   // 豆包的拼音行在 InputView 下第 13 层
        if (v instanceof android.widget.HorizontalScrollView) {
            int[] a = new int[2], g = new int[2];
            v.getLocationInWindow(a);
            glassView.getLocationInWindow(g);
            // 位置要减去已有的平移量，否则第二次调用时会因为已经右移而判断不到
            float left = a[0] - g[0] - v.getTranslationX();
            float top = a[1] - g[1] - v.getTranslationY();
            if (left < maxLeft && top < maxTop) out.add(v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup gr = (ViewGroup) v;
            for (int i = 0; i < gr.getChildCount(); i++) findCornerRows(gr.getChildAt(i), glassView, maxLeft, maxTop, out, depth + 1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 导航栏语音按钮（荣耀系统自带，但只对白名单输入法显示）：显示出来，转发成「按住空格说话」
    //   点一下：开始说话，再点一下结束（像 iOS 的听写）；按住：按住说话、松开结束。
    // ---------------------------------------------------------------------------------------------
    static volatile boolean voiceOn = true;
    private final VoiceTouch voiceTouch = new VoiceTouch();

    private void applyVoiceButton(InputMethodService ims, View decor) {
        voiceOn = "1".equals(prop(Config.VOICE));
        View btn = findByEntryName(decor, "input_method_nav_ime_voice", 0);
        if (btn == null) {
            if (trace) log("voice button not found");
            return;
        }
        if (!voiceOn) {
            btn.setVisibility(View.INVISIBLE);
            btn.setOnTouchListener(null);
            return;
        }
        if (!voiceTouch.listening) voiceTouch.setIcon(btn, false);   // 普通图标（系统着色）
        btn.setVisibility(View.VISIBLE);
        btn.setOnTouchListener(voiceTouch);
        if (trace) log("voice button " + btn.getClass().getName() + " " + btn.getWidth() + "x" + btn.getHeight()
                + " clickable=" + btn.isClickable() + " enabled=" + btn.isEnabled());
    }

    /**
     * 输入法自己的听写界面是否正显示着：
     * 微信是 com.tencent.wetype.plugin.hld.voice.* 里铺满键盘的那个视图；
     * 豆包是盖在键盘上的 AsrLongPressView（里面的 #asr_root_ll 也会从 0 高变成 202 高）。
     */
    static boolean voicePanelShown(View v, int depth) {
        if (depth > 24 || v.getVisibility() != View.VISIBLE) return false;
        if (isWechat) {
            if (v.getClass().getName().startsWith("com.tencent.wetype.plugin.hld.voice.") && v.getHeight() > 400
                    && v.isShown()) return true;
        } else if (v.getHeight() > 100 && v.isShown()
                && (v.getClass().getName().equals("com.bytedance.android.input.speech.view.AsrLongPressView")
                    || "asr_root_ll".equals(entryName(v)))) {
            return true;                                   // 豆包：按住空格说话时盖在键盘上的 AsrLongPressView
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) if (voicePanelShown(g.getChildAt(i), depth + 1)) return true;
        }
        return false;
    }

    static String entryName(View v) {
        try {
            return v.getId() == View.NO_ID ? null : v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按资源名（不依赖 R.id 数值）在视图树里找控件。 */
    private static View findByEntryName(View v, String name, int depth) {
        if (depth > 24) return null;
        try {
            if (v.getId() != View.NO_ID && name.equals(v.getResources().getResourceEntryName(v.getId()))) return v;
        } catch (Throwable ignored) {
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findByEntryName(g.getChildAt(i), name, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    static final class VoiceTouch implements View.OnTouchListener {
        boolean listening, endPress;
        long downTime, pressStart;

        @Override public boolean onTouch(View v, android.view.MotionEvent e) {
            long now = android.os.SystemClock.uptimeMillis();
            if (trace) Log.i(TAG, "voice touch " + android.view.MotionEvent.actionToString(e.getActionMasked())
                    + " listening=" + listening + " space=" + (spaceView == null ? null : spaceView.get()) + " rect=" + spaceRect);
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    View kb = spaceView == null ? null : spaceView.get();
                    boolean kbShown = kb != null && kb.isShown();
                    boolean panel = kb != null && voicePanelShown(kb.getRootView(), 0);
                    if (trace) Log.i(TAG, "voice down: listening=" + listening + " kbShown=" + kbShown + " panel=" + panel);
                    // 听写时微信会在键盘上盖一层语音面板（plugin.hld.voice.*）；面板没了说明微信那边已经结束（超时、取消等）
                    if (listening && !panel) { listening = false; setIcon(v, false); }
                    if (!listening && (panel || !kbShown)) { endPress = true; return true; }   // 不是我们发起的听写 / 键盘没显示：不处理
                    if (!listening) {
                        downTime = now;
                        if (!send(android.view.MotionEvent.ACTION_DOWN)) return true;
                        if (trace && kb != null) {                       // 诊断：开始听写 1 秒后的视图层级
                            View r = kb.getRootView();
                            r.postDelayed(() -> { Log.i(TAG, "voice panel dump:"); dump(r, 0); }, 1000);
                        }
                        listening = true;
                        pressStart = now;
                        endPress = false;
                        setIcon(v, true);
                    } else {
                        send(android.view.MotionEvent.ACTION_UP);
                        listening = false;
                        endPress = true;
                        setIcon(v, false);
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (endPress) { endPress = false; return true; }
                    if (listening && now - pressStart >= 450) {          // 按住说话：松开结束
                        send(android.view.MotionEvent.ACTION_UP);
                        listening = false;
                        setIcon(v, false);
                    }                                                     // 点一下：保持说话，等下一次点击结束
                    return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    if (listening) {
                        send(android.view.MotionEvent.ACTION_CANCEL);
                        listening = false;
                        setIcon(v, false);
                    }
                    return true;
            }
            return true;
        }

        boolean send(int action) {
            View kb = spaceView == null ? null : spaceView.get();
            RectF r = spaceRect;
            if (kb == null || r == null || !kb.isAttachedToWindow()) {
                if (trace) Log.i(TAG, "voice: no space key (kb=" + kb + " rect=" + r + ")");
                return false;
            }
            android.view.MotionEvent ev = android.view.MotionEvent.obtain(downTime,
                    android.os.SystemClock.uptimeMillis(), action, r.centerX() - kb.getScrollX(),
                    r.centerY() - kb.getScrollY(), 0);
            ev.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            boolean ok = kb.dispatchTouchEvent(ev);
            ev.recycle();
            if (trace) Log.i(TAG, "voice " + android.view.MotionEvent.actionToString(action) + " -> " + kb.getClass().getSimpleName()
                    + " at " + r.toShortString() + " handled=" + ok);
            return true;
        }

        /**
         * 切换语音图标（普通/激活）。用导航栏自己的 NavigationBarView.updateImeVoiceButtonIconStatus：
         * 0 = 普通，1 = 激活，-1 = 隐藏。它用的是按导航栏深浅色着色过的 KeyButtonDrawable；
         * 不能自己从资源里取图换上去，那样没有着色，深色模式下会变成黑色图标。
         */
        void setIcon(View v, boolean active) {
            for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
                if (p.getClass().getName().equals("android.inputmethodservice.navigationbar.NavigationBarView")) {
                    try {
                        p.getClass().getMethod("updateImeVoiceButtonIconStatus", int.class).invoke(p, active ? 1 : 0);
                    } catch (Throwable t) {
                        if (trace) Log.i(TAG, "updateImeVoiceButtonIconStatus failed: " + t);
                    }
                    return;
                }
            }
        }

        void cancel() {
            if (!listening) return;
            send(android.view.MotionEvent.ACTION_CANCEL);
            listening = false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 左上角 logo：微信输入法 logo -> 荣耀 logo（蓝底白字 HONOR，取自荣耀帐号应用 hnid_ic_hornorid_logo）
    // 微信输入法看不到荣耀帐号应用（应用可见性限制），所以由模块设置界面读出来、转成 PNG 存进配置（logo_png）。
    // 在 ImageView 绘制前检查：只要是左上角 logo 那个位置，就换成荣耀 logo（微信怎么重设图片都会被换回来）。
    // ---------------------------------------------------------------------------------------------
    static volatile boolean logoOn = false;
    static volatile Drawable logoDrawable;
    private static final WeakHashMap<android.widget.ImageView, Boolean> logoSpots = new WeakHashMap<>();
    private static final int TAG_ORIG = 0x7f0a7a7a;
    private String logoPngCache;

    private void applyLogo(InputMethodService ims, View inputView, View glassView, boolean night) {
        logoOn = "honor".equals(prop(Config.LOGO));
        if (!logoOn) return;
        if (Config.isXiaomi() || Config.isOppo()) {
            // 小米 / OPPO 系机型：换成对应品牌的标识（代码里直接画，不需要设置页先生成图片）
            boolean mi = Config.isXiaomi();
            String key = (mi ? "xiaomi:" : "oppo:") + night;
            if (!key.equals(logoPngCache) || logoDrawable == null) {
                android.graphics.Bitmap bm = mi ? xiaomiLogo(192) : oppoLogo(192);
                logoDrawable = mi ? (isWechat ? new CircleLogo(bm, night, true, 0.52f) : new CircleLogo(bm, night, false, 0.74f))
                        : (isWechat ? new CircleLogo(bm, night, true, 0.70f) : new CircleLogo(bm, night, false, 0.90f));
                logoPngCache = key;
            }
            if (inputView != null) inputView.invalidate();
            return;
        }
        String png = prop(Config.LOGO_PNG, "");
        if (png.isEmpty()) {
            if (trace) log("logo: no logo_png in settings (open the module settings once)");
            logoOn = false;
            return;
        }
        String key = png.length() + ":" + png.hashCode() + ":" + night;
        if (!key.equals(logoPngCache) || logoDrawable == null) {
            byte[] bytes = android.util.Base64.decode(png, android.util.Base64.DEFAULT);
            android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bm == null) { logoOn = false; return; }
            // 微信的 logo 位置是一张整图，要自己画圆形底；豆包的按钮自带圆形底，只换中间的图标
            logoDrawable = isWechat ? new CircleLogo(bm, night, true, 0.48f) : new CircleLogo(bm, night, false, 0.72f);
            logoPngCache = key;
        }
        if (inputView != null) inputView.invalidate();
    }

    /**
     * 小米标识（单色）：超椭圆（n=3）的「方圆」底，中间镂空 mi 字母。只用来做透明度遮罩，颜色由 CircleLogo 决定。
     * 坐标按 100×100 设计：字高 36、笔画 8，m 的右上角是圆角，中间一竖不连顶，i 是一整条竖。
     */
    /**
     * OPPO 标识（单色）：小写字标 oppo。o 是横向略宽的椭圆环，p 是椭圆环加左边一竖（从字母顶部一直往下，伸出一小截）。
     * 照官方字标的比例：字宽约为字高的 1.18 倍、笔画约为字高的 0.17、字距很紧。只用来做透明度遮罩，颜色由 CircleLogo 决定。
     * 坐标按 100×100 设计，整体在画布里上下居中。
     */
    static android.graphics.Bitmap oppoLogo(int size) {
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        c.scale(size / 100f, size / 100f);
        float w = 23f, h = 19.5f, sw = 3.3f, gap = 2.2f, desc = 8.5f;
        float total = 4 * w + 3 * gap;
        float x0 = (100 - total) / 2f;
        float top = 50f - (h + desc) / 2f;     // 字母顶部；连同 p 的下伸部分一起上下居中
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(sw);
        ring.setColor(Color.BLACK);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.BLACK);
        for (int i = 0; i < 4; i++) {
            float left = x0 + i * (w + gap);
            c.drawOval(new RectF(left + sw / 2f, top + sw / 2f, left + w - sw / 2f, top + h - sw / 2f), ring);
            if (i == 1 || i == 2) {             // 两个 p：左边一竖，从字母顶部往下伸出 desc
                c.drawRect(new RectF(left, top + h / 2f, left + sw, top + h + desc), fill);
                c.drawRect(new RectF(left, top + sw, left + sw, top + h / 2f), fill);
            }
        }
        return bm;
    }

    static android.graphics.Bitmap xiaomiLogo(int size) {
        android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bm);
        c.scale(size / 100f, size / 100f);
        android.graphics.Path sq = new android.graphics.Path();
        for (int i = 0; i <= 360; i++) {
            double t = Math.toRadians(i), ct = Math.cos(t), st = Math.sin(t);
            float x = (float) (50 + 50 * Math.signum(ct) * Math.pow(Math.abs(ct), 2.0 / 3.0));
            float y = (float) (50 + 50 * Math.signum(st) * Math.pow(Math.abs(st), 2.0 / 3.0));
            if (i == 0) sq.moveTo(x, y); else sq.lineTo(x, y);
        }
        sq.close();
        android.graphics.Path mi = new android.graphics.Path();
        float o = 1f;   // 整体右移 1，让 mi 居中
        mi.moveTo(22 + o, 68);
        mi.lineTo(22 + o, 32);
        mi.lineTo(46 + o, 32);
        mi.arcTo(new RectF(32 + o, 32, 60 + o, 60), -90, 90);
        mi.lineTo(60 + o, 68);
        mi.lineTo(52 + o, 68);
        mi.lineTo(52 + o, 46);
        mi.arcTo(new RectF(40 + o, 40, 52 + o, 52), 0, -90);
        mi.lineTo(30 + o, 40);
        mi.lineTo(30 + o, 68);
        mi.close();
        mi.addRect(37 + o, 48, 45 + o, 68, android.graphics.Path.Direction.CW);
        mi.addRect(68 + o, 32, 76 + o, 68, android.graphics.Path.Direction.CW);
        sq.op(mi, android.graphics.Path.Op.DIFFERENCE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        c.drawPath(sq, p);
        return bm;
    }

    /** 和工具栏其他按钮一样的圆形底（可选），中间是单色的荣耀圆环标识（深色模式白、浅色模式黑），占 frac 大小。 */
    /**
     * 键盘顶边的玻璃边缘（对照 iOS 26 截图逐像素取色，iOS 是 3 倍图）。iOS 的边缘像一块有厚度的玻璃被上方的光照着：
     * - 亮线：顶边最亮（浅 #FFFFFF / #FBFCFF，深 #464646 / #3D3D3D），绕到圆角上很快变淡，到侧边就没了；
     * - 暗轮廓：顶边很淡（浅 #D2D3D3，深几乎没有），越往圆角侧面越深（浅约亮度 165，深约 9，比页面还暗）；
     * - 亮线下面一层约 20px 往下淡出的光（浅 #F3F4F8 → #E6E7EC → 底色，深 #343434 → #272727 → 底色）。
     * 只画一条均匀的亮线会像「一条线」；顶亮侧暗才有玻璃的厚度感。画在玻璃视图的前景上。
     */
    static final class EdgeGlow extends Drawable {
        // 前景默认画在 padding 里面（FrameLayout 的 foregroundInsidePadding）：豆包的 InputView 顶上有几像素 padding，
        // 边缘会往下错开、最上面几像素画不出来。所以不用 getBounds()，按视图自己的完整大小画
        private final WeakReference<View> view, host;
        private int cw = -1, ch = -1, cx = -1, cy = -1;
        private final float radius, lineW, rimW, glowH;
        private final int lineTop, lineSide, rim;
        private final int[] glow;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), lineP = new Paint(Paint.ANTI_ALIAS_FLAG),
                rimP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path outer = new android.graphics.Path(), inner = new android.graphics.Path();

        EdgeGlow(View v, View host, float radius, boolean night, float density) {
            this.view = new WeakReference<>(v);
            this.host = new WeakReference<>(host);
            this.radius = radius;
            float px = density / 3f;
            lineW = 1.5f * px;
            rimW = 2f * px;
            glowH = 22f * px;
            // 暗轮廓（黑）：顶边 → 圆角侧面
            lineTop = night ? 0x00000000 : 0x12000000;
            lineSide = night ? 0x99000000 : 0x48000000;
            rim = night ? 0x26FFFFFF : 0xEEFFFFFF;
            glow = night ? new int[]{0x17FFFFFF, 0x08FFFFFF, 0x00FFFFFF} : new int[]{0x99FFFFFF, 0x2BFFFFFF, 0x00FFFFFF};
            lineP.setStyle(Paint.Style.STROKE);
            rimP.setStyle(Paint.Style.STROKE);
        }

        private static android.graphics.Path round(android.graphics.Path p, float l, float t, float r, float b, float rad) {
            p.reset();
            p.addRoundRect(new RectF(l, t, r, b), new float[]{rad, rad, rad, rad, 0, 0, 0, 0}, android.graphics.Path.Direction.CW);
            return p;
        }

        private void layout(android.graphics.Rect b) {
            round(outer, b.left, b.top, b.right, b.bottom, radius);
            round(inner, b.left + lineW, b.top + lineW, b.right - lineW, b.bottom, Math.max(0f, radius - lineW));
            float t = b.top, r = Math.max(radius, 4f * rimW);
            int clear = lineSide & 0x00FFFFFF;
            // 暗轮廓：顶边淡 → 圆角下半段最深 → 圆角结束后几像素内消失
            lineP.setStrokeWidth(lineW * 2f);                 // 一半在外面被裁掉，里面正好 lineW
            lineP.setShader(new android.graphics.LinearGradient(0, t, 0, t + r + 6f * lineW,
                    new int[]{lineTop, lineTop, lineSide, lineSide, clear},
                    new float[]{0f, 0.05f, 0.55f, 0.85f, 1f}, android.graphics.Shader.TileMode.CLAMP));
            // 亮线：顶边最亮，绕到圆角上半段就淡掉
            rimP.setStrokeWidth(rimW * 2f);
            rimP.setShader(new android.graphics.LinearGradient(0, t + lineW, 0, t + lineW + r * 0.45f,
                    new int[]{rim, rim, rim & 0x00FFFFFF}, new float[]{0f, Math.min(0.9f, rimW * 2f / (r * 0.45f)), 1f},
                    android.graphics.Shader.TileMode.CLAMP));
            float g0 = t + lineW + rimW;
            fill.setShader(new android.graphics.LinearGradient(0, g0, 0, g0 + glowH, glow,
                    new float[]{0f, 1.5f / 22f, 1f}, android.graphics.Shader.TileMode.CLAMP));
        }

        @Override public void draw(android.graphics.Canvas c) {
            View v = view.get(), h = host.get();
            if (v == null || h == null || v.getWidth() <= 0 || v.getHeight() <= 0) return;
            // 玻璃视图在宿主坐标里的位置（宿主就是它自己时是 0,0）
            int x = 0, y = 0;
            for (View p = v; p != null && p != h; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
                x += p.getLeft() + Math.round(p.getTranslationX());
                y += p.getTop() + Math.round(p.getTranslationY());
            }
            android.graphics.Rect b = new android.graphics.Rect(x, y, x + v.getWidth(), y + v.getHeight());
            if (b.width() != cw || b.height() != ch || x != cx || y != cy) {
                cw = b.width(); ch = b.height(); cx = x; cy = y;
                layout(b);
            }
            c.save();
            c.clipPath(outer);
            c.drawPath(outer, lineP);
            c.clipPath(inner);
            c.drawRect(b.left, b.top + lineW + rimW, b.right, b.top + lineW + rimW + glowH, fill);
            c.drawPath(inner, rimP);
            c.restore();
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter f) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    static final class CircleLogo extends Drawable {
        private final android.graphics.Bitmap bm;
        private final boolean night, withBg;
        private final float frac;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        CircleLogo(android.graphics.Bitmap bm, boolean night, boolean withBg, float frac) {
            this.bm = bm;
            this.night = night;
            this.withBg = withBg;
            this.frac = frac;
            bg.setColor(night ? 0x21FFFFFF : 0x14000000);
            p.setColorFilter(new android.graphics.PorterDuffColorFilter(night ? 0xFFFFFFFF : 0xFF000000,
                    android.graphics.PorterDuff.Mode.SRC_IN));
        }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float size = Math.min(b.width(), b.height());
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            if (withBg) c.drawCircle(cx, cy, size / 2f, bg);
            float s = size * frac / Math.max(bm.getWidth(), bm.getHeight());
            int w = Math.max(1, Math.round(bm.getWidth() * s)), h = Math.max(1, Math.round(bm.getHeight() * s));
            // 一次从 192px 缩到几十像素会跳过像素、边缘出锯齿：先对半缩到接近目标大小，再缩到正好的像素数，按 1:1 画
            if (scaled == null || scaled.getWidth() != w || scaled.getHeight() != h) scaled = downscale(bm, w, h);
            c.drawBitmap(scaled, Math.round(cx - w / 2f), Math.round(cy - h / 2f), p);
        }

        private android.graphics.Bitmap scaled;

        private static android.graphics.Bitmap downscale(android.graphics.Bitmap src, int w, int h) {
            android.graphics.Bitmap cur = src;
            while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) {
                cur = android.graphics.Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            }
            return android.graphics.Bitmap.createScaledBitmap(cur, w, h, true);
        }
        @Override public int getIntrinsicWidth() { return -1; }
        @Override public int getIntrinsicHeight() { return -1; }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); bg.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * 是不是微信 logo：微信 logo 是绿色的（同一个位置菜单打开时显示的收起箭头是白/灰色）。
     * 画到 24x24 小图上数绿色像素；每个 drawable 实例只算一次。
     */
    private static final WeakHashMap<Drawable, Boolean> greenCache = new WeakHashMap<>();

    static boolean isWechatLogo(Drawable d) {
        Boolean r = greenCache.get(d);
        if (r != null) return r;
        int green = 0;
        try {
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Rect old = new android.graphics.Rect(d.getBounds());
            d.setBounds(0, 0, 24, 24);
            d.draw(new android.graphics.Canvas(bm));
            d.setBounds(old);
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int c = bm.getPixel(x, y);
                int a = c >>> 24, rr = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                if (a > 100 && g > rr + 50 && g > b) green++;
            }
            bm.recycle();
        } catch (Throwable ignored) {
        }
        r = green >= 4;
        greenCache.put(d, r);
        return r;
    }

    private static void hookLogo() {
        hookAll(android.widget.ImageView.class, "onDraw", new Hook() {
            @Override protected void before(Hook.P p) {
                android.widget.ImageView iv = (android.widget.ImageView) p.thisObject;
                Drawable cur = iv.getDrawable();
                if (!logoOn || logoDrawable == null) {
                    // 关掉后恢复微信原 logo
                    if (cur instanceof CircleLogo && iv.getTag(TAG_ORIG) instanceof Drawable) {
                        iv.setImageDrawable((Drawable) iv.getTag(TAG_ORIG));
                    }
                    return;
                }
                if (iv.getWidth() == 0 || cur == logoDrawable) return;
                if (cur instanceof CircleLogo) { iv.setImageDrawable(logoDrawable); return; }   // 深浅色切换后换新的
                Boolean spot = logoSpots.get(iv);
                if (spot == null) {
                    spot = isLogoSpot(iv);
                    logoSpots.put(iv, spot);
                }
                if (!spot || cur == null || cur instanceof CircleLogo) return;
                if (isWechat && !isWechatLogo(cur)) return;          // 微信：收起箭头等其他图标不替换
                iv.setTag(TAG_ORIG, cur);
                iv.setImageDrawable(logoDrawable);
            }
        });
    }

    /**
     * 微信：键盘左上角（离玻璃区域左上角 260x220px 内）、父视图可点击、70~160px 的 ImageView。
     * 豆包：工具栏左上角的 #btn_logo。
     */
    private static boolean isLogoSpot(android.widget.ImageView v) {
        View g = glassStatic == null ? null : glassStatic.get();
        if (g == null || v.getRootView() != g.getRootView()) return false;
        if (!isWechat) return "btn_logo".equals(entryName(v));
        if (v.getWidth() < 70 || v.getWidth() > 160 || !(v.getParent() instanceof View)
                || !((View) v.getParent()).isClickable()) return false;
        int[] a = new int[2], b = new int[2];
        v.getLocationInWindow(a);
        g.getLocationInWindow(b);
        return a[0] - b[0] < 260 && a[1] - b[1] < 220 && a[1] - b[1] >= -20;
    }

    /**
     * 输入法窗口里画出来的颜色会被一条曲线提亮（实测：#1A→#22、#28→#35、#33→#42、#80→#92，黑白不变；
     * 同屏的应用窗口不受影响，SurfaceFlinger 里两个图层的 dataspace/colorTransform 也一样，原因在输入法窗口内部）。
     * 需要和应用里的颜色精确一致时（桌面搜索），按实测点反查，预先压暗。
     */
    private static final int[][] LIFT = {{0, 0}, {26, 34}, {40, 53}, {51, 66}, {128, 146}, {255, 255}};

    static int unlift(int argb) {
        return (argb & 0xFF000000) | (unliftCh((argb >> 16) & 0xFF) << 16)
                | (unliftCh((argb >> 8) & 0xFF) << 8) | unliftCh(argb & 0xFF);
    }

    private static int unliftCh(int out) {
        for (int i = 1; i < LIFT.length; i++) {
            int i0 = LIFT[i - 1][0], o0 = LIFT[i - 1][1], i1 = LIFT[i][0], o1 = LIFT[i][1];
            if (out <= o1) return Math.round(i0 + (out - o0) * (float) (i1 - i0) / (o1 - o0));
        }
        return 255;
    }

    private static String targetPackage(InputMethodService ims) {
        try {
            android.view.inputmethod.EditorInfo ei = ims.getCurrentInputEditorInfo();
            return ei == null ? "" : String.valueOf(ei.packageName);
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean isNight(InputMethodService ims) {
        return (ims.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static void invalidateAll(View v) {
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) invalidateAll(g.getChildAt(i));
        }
    }

    private static void dump(View v, int depth) {
        if (depth > 22) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        String id = "";
        try { if (v.getId() != View.NO_ID) id = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
        Drawable bg = v.getBackground();
        sb.append(v.getClass().getName()).append(" #").append(id)
          .append(" @").append(v.getLeft()).append(",").append(v.getTop())
          .append(" ").append(v.getWidth()).append("x").append(v.getHeight())
          .append(v instanceof android.widget.TextView ? " textLen=" + ((android.widget.TextView) v).length() + " minW=" + ((android.widget.TextView) v).getMinWidth() + " grav=" + Integer.toHexString(((android.widget.TextView) v).getGravity()) : "")   // 不记文字内容
          .append(" pad=").append(v.getPaddingLeft()).append(",").append(v.getPaddingTop()).append(",").append(v.getPaddingRight())
          .append(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                  ? " mar=" + ((ViewGroup.MarginLayoutParams) v.getLayoutParams()).leftMargin + "," + ((ViewGroup.MarginLayoutParams) v.getLayoutParams()).rightMargin : "")
          .append(" minW=").append(v.getMinimumWidth()).append(" vis=").append(v.getVisibility())
          .append(" bg=").append(bg == null ? "null" : bg.getClass().getSimpleName()
                  + (bg instanceof ColorDrawable ? String.format("(#%08X)", ((ColorDrawable) bg).getColor()) : ""))
          .append(v.willNotDraw() ? "" : " draws")
          .append(v instanceof android.widget.ImageView ? " img=" + ((android.widget.ImageView) v).getDrawable() : "")
          .append(v.hasOnClickListeners() ? " click" : "");
        Log.i(TAG, sb.toString());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dump(g.getChildAt(i), depth + 1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 参数读取：adb 属性 > 设置界面 > 默认值
    // ---------------------------------------------------------------------------------------------
    private static Object call(Object o, String name, Class<?>[] sig, Object... args) throws Throwable {
        return o.getClass().getMethod(name, sig).invoke(o, args);
    }

    /** 一次 getprop 读出所有 debug.wglass.* 属性。 */
    private static Map<String, String> readProps() {
        Map<String, String> m = new HashMap<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("[debug.wglass.")) continue;
                    int k = line.indexOf("]: [");
                    if (k < 0 || !line.endsWith("]")) continue;
                    String v = line.substring(k + 4, line.length() - 1).trim();
                    if (!v.isEmpty()) m.put(line.substring(14, k), v);
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }

    private String allPrefs() {
        try {
            if (prefs == null) return "{}";
            Map<String, Object> m = new java.util.TreeMap<>(prefs.getAll());
            if (m.containsKey(Config.LOGO_PNG)) m.put(Config.LOGO_PNG, "<" + String.valueOf(m.get(Config.LOGO_PNG)).length() + " chars>");
            if (m.containsKey(Config.WE_FONT)) m.put(Config.WE_FONT, "<" + String.valueOf(m.get(Config.WE_FONT)).length() + " chars>");
            return String.valueOf(m);
        } catch (Throwable t) {
            return "{}";
        }
    }

    private String prop(String key) {
        return prop(key, Config.def(key));
    }

    private String prop(String key, String def) {
        String v = props.get(key);
        if (v != null) return v;
        try {
            if (prefs != null) {
                v = prefs.getString(key, null);
                if (v != null && !v.isEmpty()) return v;
            }
        } catch (Throwable ignored) {
        }
        return def;
    }

    private float f(String key) {
        try { return Float.parseFloat(prop(key)); } catch (Throwable t) { return Float.parseFloat(Config.def(key)); }
    }

    private float f(String key, float def) {
        try { return Float.parseFloat(prop(key, "")); } catch (Throwable t) { return def; }
    }

    private int color(String key) {
        try { return Color.parseColor(prop(key)); } catch (Throwable t) { return Color.parseColor(Config.def(key)); }
    }

    private int color(String key, int def) {
        try { return Color.parseColor(prop(key, "")); } catch (Throwable t) { return def; }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static void log(String s) {
        Log.i(TAG, s);
        Main m = self;
        if (m != null) {
            try { m.log(Log.INFO, TAG, s); } catch (Throwable ignored) {}
        }
    }
}
