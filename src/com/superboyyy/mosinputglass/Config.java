package com.superboyyy.mosinputglass;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置项定义：设置界面（模块 App）和 hook 端（微信输入法进程）共用。
 * 所有值都以字符串存进 SharedPreferences "settings"，hook 端用 XSharedPreferences 读取。
 * adb 的 debug.wglass.<key> 属性优先级最高，方便调试。
 */
final class Config {
    static final String PREFS = "settings";
    static final String CFG_VER_KEY = "cfgver";
    static final String CFG_VERSION = "12";      // 默认值含义变化时加一，设置界面打开时会把旧参数换成新默认值
    static final String MODULE_PKG = "com.superboyyy.mosinputglass";

    // ---- key ----
    static final String ENABLE = "enable";          // 总开关
    static final String SKIP = "skip";              // 去掉微信输入法自绘的底板
    static final String BG_ALPHA = "bgalpha";       // 背景底色不透明度 0..1（越大越实、越好读）
    static final String BG_DARK = "bgdark";         // 深色模式底色
    static final String BG_LIGHT = "bglight";       // 浅色模式底色
    static final String KEY_ALPHA = "keyalpha";     // 按键不透明度 0..1
    static final String BLUR = "blur";              // 模糊半径
    static final String SAT = "sat";                // 饱和度
    static final String RADIUS = "radius";          // 顶部圆角 dp
    static final String REFRACTION = "refraction";
    static final String THICKNESS = "thickness";
    static final String DEPTH = "depth";
    static final String DISPERSION = "dispersion";
    static final String EDGE = "edge";              // 边缘高光
    static final String KEY_STYLE = "keystyle";     // ios = iOS 按键配色；wechat = 微信原色
    static final String KEY_RADIUS = "keyradius";   // 按键圆角 dp，0 = 微信原样（约 6dp）
    static final String SEARCH_FILL = "searchfill"; // 桌面搜索里：与搜索框面板同色铺满、不要圆角
    static final String COMPOSE_DY = "composedy";   // 左上角拼音（输入中字符）行往下移多少 dp（微信）
    static final String COMPOSE_DY_DOUBAO = "composedy_db";   // 同上（豆包），可以是负数 = 往上
    static final String KEY_DARK = "keydark";       // 深色模式按键：白色叠加的不透明度 0..1（iOS 的 #454545 键）
    static final String KEY_LIGHT = "keylight";     // 浅色模式白键的不透明度 0..1（1 = iOS 的纯白，越小越透）
    static final String IME_WECHAT = "ime_wechat";  // 对微信输入法生效
    static final String IME_DOUBAO = "ime_doubao";  // 对豆包输入法生效

    static final String PKG_WECHAT = "com.tencent.wetype";
    static final String PKG_DOUBAO = "com.bytedance.android.doubaoime";
    static final String VOICE = "voice";            // 导航栏右侧语音按钮（按住/点一下 = 按住空格说话）
    /** 小米 / Redmi / POCO 机型：左上角 logo 换成小米标识而不是荣耀圆环。 */
    /** 键盘用哪种玻璃：和 Main 里的判断一致（荣耀液态玻璃 > HyperOS 液态玻璃 > HyperOS 背景模糊 > 安卓区域模糊）。 */
    static final int GLASS_HONOR = 0, GLASS_HYPER = 1, GLASS_HYPER_BLUR = 2, GLASS_REGION = 3;
    private static Integer glassKind;

    static int glassKind() {
        if (glassKind != null) return glassKind;
        int k = GLASS_REGION;
        try {
            Class.forName("com.hihonor.android.graphics.material.HnMaterialManager");
            k = GLASS_HONOR;
        } catch (Throwable t) {
            try {
                android.view.View.class.getMethod("setMiBackgroundBlurMode", int.class);
                k = GLASS_HYPER_BLUR;
                android.view.View.class.getMethod("setMiGlass", float[].class);
                if (new java.io.File("/system_ext/framework/miuix.jar").exists()) k = GLASS_HYPER;
            } catch (Throwable ignored) {
            }
        }
        glassKind = k;
        return k;
    }

    /** OPPO 系手机（OPPO、一加、realme，ColorOS / OxygenOS / realme UI）：左上角显示 OPPO 标识。 */
    static boolean isOppo() {
        String b = (android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER).toLowerCase(java.util.Locale.ROOT);
        return b.contains("oppo") || b.contains("oneplus") || b.contains("realme");
    }

    static boolean isXiaomi() {
        String b = (android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER).toLowerCase(java.util.Locale.ROOT);
        return b.contains("xiaomi") || b.contains("redmi") || b.contains("poco");
    }

    /**
     * 荣耀手机：系统里有荣耀的振动接口 com.hihonor.android.os.VibratorEx（MagicOS 自带，第三方应用只要振动权限就能调）。
     * 用来决定「荣耀键盘振感」开关显不显示、hook 端要不要接管按键振动。
     */
    static boolean isHonor() {
        String b = (android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER).toLowerCase(java.util.Locale.ROOT);
        if (!b.contains("honor")) return false;
        try {
            Class.forName("com.hihonor.android.os.VibratorEx");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // 荣耀键盘振感：按键振动不用输入法自己的（系统通用的「点击」），改用荣耀系统为键盘单独调的 haptic.common.click_keyboard
    static final String HONOR_HAPTIC = "honor_haptic";
    static final String HAPTIC_KEYBOARD = "haptic.common.click_keyboard";

    static final String LOGO = "logo";              // honor = 左上角换成荣耀 logo；wechat = 微信原样
    static final String LOGO_PNG = "logo_png";      // 荣耀圆环标识的 PNG（Base64，单色透明底），由设置界面读出来
    static final String LOGO_SRC_KEY = "logo_src";
    static final String LOGO_SRC_VER = "builtin_honor_ring_1";   // 换图案时改这个值，设置界面会重新生成

    // 设置页「重启输入法」：只写进远程配置（值是时间戳），输入法进程收到变化就结束自己，系统会马上重新拉起
    static final String RESTART_IME = "restart_ime";
    static final String WE_FONT = "we_font";        // 微信输入法自带的「WE」字体（Base64），由设置界面从已安装的微信输入法里读出来
    static final String WE_FONT_SRC = "we_font_src"; // 读的是哪个版本的微信输入法（版本变了就重新读）
    static final String WE_FONT_ASSET = "fonts/WE-Regular.ttf";
    static final String DOUBAO_WE_FONT = "db_wefont"; // 豆包的按键字母/数字、候选字、拼音行改用 WE 字体

    static final String SEARCH_PKG = "com.hihonor.search";
    // 桌面搜索：键盘底色 = 搜索框外面那圈灰边（面板）的颜色，截图取色、不透明。
    // 深色按提亮曲线预先压暗（Main.unlift）；浅色实测没有提亮（画 #DCDDE1 显示也是 #DCDDE1），直接用截图取的色
    static final int SEARCH_DARK = 0xFF333333;
    static final int SEARCH_LIGHT = 0xFFDADBDF;

    /**
     * 默认 = 「iOS 风格」，对照 iOS 26 实机截图：
     * 底色颜色取自 iOS（浅色按 iOS 26 键盘截图里的 #E1E2E7 实测反推成 #CDCFD8；深色按 iOS 深色键盘截图里的 #212121 底、#454545 键实测反推成 #191919、按键白 16%），不透明度 60%；
     * 键盘顶部圆角 ≈ 22dp，按键圆角 ≈ 按键高度的 18% ≈ 8dp，顶部有玻璃高光。
     */
    static final Map<String, String> DEFAULTS = map(
            ENABLE, "1",
            IME_WECHAT, "1",
            IME_DOUBAO, "1",
            SKIP, "1",
            BG_ALPHA, "0.6",
            BG_DARK, "#191919",
            BG_LIGHT, "#CDCFD8",
            KEY_ALPHA, "1",
            KEY_STYLE, "ios",
            KEY_RADIUS, "8",
            SEARCH_FILL, "1",
            COMPOSE_DY, "2",
            COMPOSE_DY_DOUBAO, "0",
            KEY_LIGHT, "1",
            KEY_DARK, "0.16",
            VOICE, "1",
            HONOR_HAPTIC, "1",
            LOGO, "honor",
            DOUBAO_WE_FONT, "0",
            BLUR, "60",
            SAT, "1.4",
            RADIUS, "22",
            REFRACTION, "0.3",
            THICKNESS, "0.6",
            DEPTH, "0.5",
            DISPERSION, "0.2",
            EDGE, "1");

    /** 预设：名字 -> 覆盖的参数（没写的取默认值）。 */
    static final Map<String, Map<String, String>> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("iOS 风格（默认）", map());
        PRESETS.put("高可读", map(BG_ALPHA, "0.78", BLUR, "90", SAT, "1.2", REFRACTION, "0.15"));
        PRESETS.put("更通透", map(BG_ALPHA, "0.25", KEY_ALPHA, "0.75", BLUR, "40", SAT, "1.3"));
    }

    static String def(String key) {
        String v = DEFAULTS.get(key);
        return v == null ? "" : v;
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private Config() {}
}
