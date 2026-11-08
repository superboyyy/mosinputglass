package com.superboyyy.mosinputglass;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;

/**
 * 模块设置界面。设置先存在本地（MODE_PRIVATE），LSPosed 服务连上后同步到远程配置（getRemotePreferences），
 * 输入法进程里的模块监听远程配置，一改就生效。
 */
public class SettingsActivity extends Activity {
    private SharedPreferences sp;
    /** LSPosed 远程配置；服务还没连上时为 null。 */
    private SharedPreferences remote;
    private TextView status;
    private LinearLayout scopeBox;
    private final Runnable serviceChanged = () -> runOnUiThread(this::onServiceChanged);
    private boolean night;
    /** 手机上有没有装微信输入法 / 豆包输入法；没装的相关设置置灰。 */
    private boolean hasWechat, hasDoubao;

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 置灰：变淡，里面的开关、滑块都不能点。 */
    private static void dim(View v) {
        v.setAlpha(0.4f);
        setEnabledDeep(v, false);
    }

    private static void setEnabledDeep(View v, boolean on) {
        v.setEnabled(on);
        v.setClickable(on && v.isClickable());
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), on);
        }
    }
    private int cText, cSub, cCard, cPage, cAccent, cDivider;
    /** 套用预设/恢复默认后，用它们把各控件刷新成新值（不重建页面，键盘不会被收起）。 */
    private final java.util.List<Runnable> refreshers = new java.util.ArrayList<>();

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();
        sp = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        // 第一次打开时把默认值写进去，保证远程配置里每个键都有值
        SharedPreferences.Editor init = sp.edit();
        boolean missing = false;
        String ver = sp.getString(Config.CFG_VER_KEY, "");
        if (!Config.CFG_VERSION.equals(ver)) {
            if (ver.equals("4") || ver.equals("5") || ver.equals("6") || ver.equals("7") || ver.equals("8") || ver.equals("9") || ver.equals("10") || ver.equals("11")) {
                // v4 -> v5：只把仍是旧默认值的背景不透明度（0.55）调到新默认值，用户改过的不动
                if ("0.55".equals(sp.getString(Config.BG_ALPHA, ""))) init.putString(Config.BG_ALPHA, Config.def(Config.BG_ALPHA));
                // v5 -> v6：浅色白键默认从 70% 改成 iOS 的纯白（还是旧默认值 0.7 才换）
                if ("0.7".equals(sp.getString(Config.KEY_LIGHT, "0.7"))) init.putString(Config.KEY_LIGHT, Config.def(Config.KEY_LIGHT));
                // v6 -> v7：浅色底色从 #E3E4E8 调深到 #D4D6DD（叠在玻璃上实际显示 #E1E2E7，和 iOS 26 截图取色一致）
                // v7 -> v8：背景不透明度 70% -> 60%，浅色底色跟着换成 #CDCFD8（白色界面上还是显示 #E1E2E7）
                String bl = sp.getString(Config.BG_LIGHT, "#E3E4E8");
                if ("#E3E4E8".equalsIgnoreCase(bl) || "#D4D6DD".equalsIgnoreCase(bl)) init.putString(Config.BG_LIGHT, Config.def(Config.BG_LIGHT));
                if ("0.7".equals(sp.getString(Config.BG_ALPHA, "0.7"))) init.putString(Config.BG_ALPHA, Config.def(Config.BG_ALPHA));
                // v8 -> v9：深色底色 #242426 -> #191919（iOS 深色键盘截图里底色是 #212121）
                if ("#242426".equalsIgnoreCase(sp.getString(Config.BG_DARK, "#242426"))) init.putString(Config.BG_DARK, Config.def(Config.BG_DARK));
                // v10 -> v11：模糊默认值从 300 改回 60（v10 临时改成过 300）
                if ("300".equals(sp.getString(Config.BLUR, "60"))) init.putString(Config.BLUR, Config.def(Config.BLUR));
                // v11 -> v12：「豆包用微信的英文字体」默认改成关闭
                if ("1".equals(sp.getString(Config.DOUBAO_WE_FONT, "1"))) init.putString(Config.DOUBAO_WE_FONT, Config.def(Config.DOUBAO_WE_FONT));
            } else {
                // 更早的版本默认值含义变化较大：全部换成新默认值，只保留「启用」
                for (String key : Config.DEFAULTS.keySet()) {
                    if (!key.equals(Config.ENABLE)) init.putString(key, Config.def(key));
                }
            }
            init.putString(Config.CFG_VER_KEY, Config.CFG_VERSION);
            missing = true;
        }
        for (String key : Config.DEFAULTS.keySet()) {
            if (!sp.contains(key)) { init.putString(key, Config.def(key)); missing = true; }
        }
        if (missing) init.apply();
        cacheHonorLogo();
        cacheWeFont();
        hasWechat = installed(Config.PKG_WECHAT);
        hasDoubao = installed(Config.PKG_DOUBAO);
        night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        cPage = night ? 0xFF000000 : 0xFFF2F2F7;
        cCard = night ? 0xFF1C1C1E : 0xFFFFFFFF;
        cText = night ? 0xFFFFFFFF : 0xFF000000;
        cSub = night ? 0x99EBEBF5 : 0x993C3C43;
        cAccent = night ? 0xFF0A84FF : 0xFF007AFF;
        cDivider = night ? 0x33FFFFFF : 0x1F000000;
        getWindow().setStatusBarColor(cPage);
        getWindow().setNavigationBarColor(cPage);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        build();
        App.addListener(serviceChanged);
        onServiceChanged();
    }

    @Override
    protected void onDestroy() {
        App.removeListener(serviceChanged);
        super.onDestroy();
    }

    /** LSPosed 服务连上/断开：更新状态行，连上时把本地设置整份同步过去。 */
    private void onServiceChanged() {
        io.github.libxposed.service.XposedService svc = App.service;
        remote = null;
        if (svc != null) {
            try {
                remote = svc.getRemotePreferences(Config.PREFS);
                SharedPreferences.Editor e = remote.edit();
                for (Map.Entry<String, ?> en : sp.getAll().entrySet()) e.putString(en.getKey(), String.valueOf(en.getValue()));
                e.apply();
            } catch (Throwable t) {
                android.util.Log.w("WGlass", "sync remote preferences failed", t);
                remote = null;
            }
        }
        if (status == null) return;
        scopeBox.removeAllViews();
        if (svc == null || remote == null) {
            status.setText("● 未激活 · 请在 LSPosed 里启用");
            status.setTextColor(0xFFFF3B30);
            return;
        }
        java.util.List<String> scope;
        try { scope = svc.getScope(); } catch (Throwable t) { scope = java.util.Collections.emptyList(); }
        status.setText("● 已激活 · " + svc.getFrameworkName() + " " + svc.getFrameworkVersion());
        status.setTextColor(night ? 0xFF30D158 : 0xFF248A3D);
        String[][] imes = {{Config.PKG_WECHAT, "微信输入法"}, {Config.PKG_DOUBAO, "豆包输入法"}};
        for (String[] ime : imes) {
            if (scope.contains(ime[0]) || !installed(ime[0])) continue;   // 没装的输入法不用加作用域
            Button add = button("把「" + ime[1] + "」加入作用域");
            add.setOnClickListener(v -> svc.requestScope(java.util.Collections.singletonList(ime[0]),
                    new io.github.libxposed.service.XposedService.OnScopeEventListener() {
                        @Override public void onScopeRequestApproved(java.util.List<String> approved) {
                            runOnUiThread(SettingsActivity.this::onServiceChanged);
                        }
                        @Override public void onScopeRequestFailed(String message) {
                            runOnUiThread(() -> android.widget.Toast.makeText(SettingsActivity.this,
                                    "请求失败：" + message, android.widget.Toast.LENGTH_LONG).show());
                        }
                    }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
            lp.bottomMargin = dp(12);
            scopeBox.addView(add, lp);
        }
    }

    /**
     * 让输入法进程重启：只往远程配置写一个时间戳（不写本地，否则下次同步又会触发一次），
     * 输入法里的模块收到变化后结束自己的进程，系统会马上重新拉起默认输入法。
     */
    private void restartIme() {
        if (remote == null) {
            android.widget.Toast.makeText(this, "LSPosed 还没连上，没法通知输入法", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        android.view.inputmethod.InputMethodManager imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        remote.edit().putString(Config.RESTART_IME, String.valueOf(System.currentTimeMillis())).apply();
        android.widget.Toast.makeText(this, "已重启输入法", android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 本地设置整份推到远程配置（套用预设、首次初始化之后）。 */
    private void pushAll() {
        if (remote == null) return;
        SharedPreferences.Editor e = remote.edit();
        for (Map.Entry<String, ?> en : sp.getAll().entrySet()) e.putString(en.getKey(), String.valueOf(en.getValue()));
        e.apply();
    }

    private void build() {
        refreshers.clear();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cPage);
        scroll.setFitsSystemWindows(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(16), dp(16), dp(32));
        scroll.addView(page);

        // ---- 顶部：图标 + 名字 + 状态 ----
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(8), dp(4), dp(16));
        // 图标：自己画「玻璃底板 + 键盘符号」（系统给的图标会被桌面主题处理过）
        android.widget.ImageView icon = new android.widget.ImageView(this);
        int glyph = getResources().getIdentifier("ic_launcher_art", "drawable", getPackageName());
        if (glyph != 0) icon.setImageResource(glyph);
        GradientDrawable tile = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                night ? new int[]{0xFF3A3A3C, 0xFF2C2C2E} : new int[]{0xFFFFFFFF, 0xFFE9EAEE});
        tile.setCornerRadius(dp(56) * 0.26f);
        tile.setStroke(Math.max(1, dp(0.75f)), night ? 0x33FFFFFF : 0x14000000);
        icon.setBackground(tile);
        head.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(14), 0, 0, 0);
        TextView title = text("MOS液态输入", 24, cText);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        // 名字较长：一行放不下时自动缩小字号，不换行
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(16, 24, 1, TypedValue.COMPLEX_UNIT_SP);
        titles.addView(title);
        status = text("正在连接 LSPosed…", 13, cSub);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setPadding(dp(10), dp(3), dp(10), dp(3));
        status.setBackground(pill(night ? 0x3D78788C : 0x1F78788C));
        // 「已激活 / 未激活」状态标签不显示了（status 仍然更新，只是不放进界面）
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        page.addView(head);

        // ---- 主卡片：总开关 + 试一试 ----
        LinearLayout hero = card(page);
        toggle(hero, "总开关", "仅在 MagicOS 11、HyperOS 4 和 ColorOS 17 上测试", Config.ENABLE, "1", "0", true);
        divider(hero);
        LinearLayout restart = new LinearLayout(this);
        restart.setOrientation(LinearLayout.VERTICAL);
        restart.setPadding(dp(16), dp(11), dp(16), dp(11));
        TextView rt = text("重启输入法", 16, cAccent);
        rt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        restart.addView(rt);
        TextView rs = text("改动没有生效时点这里", 13, cSub);
        rs.setPadding(0, dp(2), 0, 0);
        restart.addView(rs);
        restart.setOnClickListener(v -> restartIme());
        hero.addView(restart, new LinearLayout.LayoutParams(-1, -2));
        divider(hero);
        EditText test = new EditText(this);
        test.setHint("点这里唤出键盘，边调边看");
        test.setTextColor(cText);
        test.setHintTextColor(cSub);
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        test.setBackground(null);
        test.setPadding(dp(16), dp(14), dp(16), dp(14));
        hero.addView(test, new LinearLayout.LayoutParams(-1, -2));
        scopeBox = new LinearLayout(this);
        scopeBox.setOrientation(LinearLayout.VERTICAL);
        scopeBox.setPadding(dp(12), 0, dp(12), 0);
        hero.addView(scopeBox);

        // ---- 输入法 ----
        LinearLayout imes = section(page, "输入法", null);
        View wx = toggle(imes, "微信输入法", hasWechat ? null : "未安装", Config.IME_WECHAT, "1", "0", false);
        View db = toggle(imes, "豆包输入法", hasDoubao ? null : "未安装", Config.IME_DOUBAO, "1", "0", false);
        if (!hasWechat) dim(wx);
        if (!hasDoubao) dim(db);

        // ---- 风格 ----
        LinearLayout presets = section(page, "风格", null);
        presets.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout row = new LinearLayout(this);
        presets.addView(row);
        for (Map.Entry<String, Map<String, String>> e : Config.PRESETS.entrySet()) {
            Button btn = button(e.getKey().replace("（默认）", ""));
            btn.setOnClickListener(v -> {
                applyPreset(e.getValue());
                android.widget.Toast.makeText(this, "已切换到「" + e.getKey() + "」", android.widget.Toast.LENGTH_SHORT).show();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(btn, lp);
        }

        // ---- 常用 ----
        LinearLayout common = section(page, "常用", null);
        slider(common, "背景不透明度", Config.BG_ALPHA, 0, 1, 0.01f, "%");
        slider(common, "键盘圆角", Config.RADIUS, 0, 40, 1, "dp");
        slider(common, "按键圆角", Config.KEY_RADIUS, 0, 16, 0.5f, "dp");
        toggle(common, "iOS 按键配色", "按键同色、无描边无阴影", Config.KEY_STYLE, "ios", "wechat", false);
        // 导航栏语音键是荣耀系统导航栏上的按钮，其他手机（如小米）没有，不显示
        if (Config.isHonor()) {
            toggle(common, "导航栏语音键", "点一下开始说话，按住说话松开结束", Config.VOICE, "1", "0", false);
        }
        // 只有荣耀系统有这套键盘振感，其他手机不显示
        if (Config.isHonor()) {
            toggle(common, "荣耀键盘振感", "按键振动换成系统为键盘调的振感", Config.HONOR_HAPTIC, "1", "0", false);
        }
        toggle(common, Config.isXiaomi() ? "小米 logo" : Config.isOppo() ? "OPPO logo" : "荣耀 logo",
                Config.isXiaomi() ? "左上角换成小米标识" : Config.isOppo() ? "左上角换成 OPPO 标识" : "左上角换成荣耀圆环",
                Config.LOGO, "honor", "wechat", false);

        // ---- 高级（默认收起） ----
        LinearLayout advHead = card(page);
        ((LinearLayout.LayoutParams) advHead.getLayoutParams()).topMargin = dp(24);
        LinearLayout ah = new LinearLayout(this);
        ah.setGravity(Gravity.CENTER_VERTICAL);
        ah.setPadding(dp(16), dp(14), dp(16), dp(14));
        ah.addView(text("高级设置", 16, cText), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chev = text("›", 22, cSub);
        ah.addView(chev);
        advHead.addView(ah);
        LinearLayout adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        page.addView(adv);
        ah.setOnClickListener(v -> {
            boolean open = adv.getVisibility() != View.VISIBLE;
            adv.setVisibility(open ? View.VISIBLE : View.GONE);
            chev.animate().rotation(open ? 90 : 0).setDuration(150).start();
        });

        LinearLayout bg = section(adv, "背景", "底色的不透明度由「背景不透明度」控制，这里只填 RGB。");
        slider(bg, "模糊强度", Config.BLUR, 0, 120, 1, "");
        slider(bg, "饱和度", Config.SAT, 0.5f, 2f, 0.05f, "x");
        colorRow(bg, "浅色底色", Config.BG_LIGHT);
        colorRow(bg, "深色底色", Config.BG_DARK);
        toggle(bg, "去掉输入法自带底板", null, Config.SKIP, "1", "0", false);

        LinearLayout keys = section(adv, "按键", null);
        slider(keys, "按键不透明度", Config.KEY_ALPHA, 0.3f, 1, 0.01f, "%");
        slider(keys, "浅色按键亮度", Config.KEY_LIGHT, 0.2f, 1, 0.01f, "%");
        slider(keys, "深色按键亮度", Config.KEY_DARK, 0.05f, 0.4f, 0.01f, "%");
        View weFont = toggle(keys, "豆包用微信的英文字体", "仅当手机上安装微信输入法时生效", Config.DOUBAO_WE_FONT, "1", "0", false);
        if (!hasWechat || !hasDoubao) dim(weFont);

        LinearLayout pinyin = section(adv, "拼音行位置", null);
        View wxDy = slider(pinyin, "微信下移", Config.COMPOSE_DY, 0, 12, 0.5f, "dp");
        View dbDy = slider(pinyin, "豆包下移", Config.COMPOSE_DY_DOUBAO, -4, 12, 0.5f, "dp");
        if (!hasWechat) dim(wxDy);
        if (!hasDoubao) dim(dbDy);

        LinearLayout glass = section(adv, "玻璃质感", null);
        slider(glass, "折射", Config.REFRACTION, 0, 1, 0.01f, "");
        slider(glass, "厚度", Config.THICKNESS, 0, 1, 0.01f, "");
        slider(glass, "深度", Config.DEPTH, 0, 1, 0.01f, "");
        slider(glass, "色散", Config.DISPERSION, 0, 1, 0.01f, "");
        toggle(glass, "边缘高光", null, Config.EDGE, "1", "0", false);

        // 「桌面搜索里铺满」只对荣耀的桌面搜索（com.hihonor.search）有效，没装它的手机（如小米）不显示
        if (installed(Config.SEARCH_PKG)) {
            LinearLayout scene = section(adv, "场景", null);
            toggle(scene, "桌面搜索里铺满", "和搜索框外圈同色、不加圆角", Config.SEARCH_FILL, "1", "0", false);
        }


        // ---- 恢复默认 + 作者 ----
        LinearLayout resetCard = card(page);
        ((LinearLayout.LayoutParams) resetCard.getLayoutParams()).topMargin = dp(24);
        TextView reset = text("恢复默认设置", 16, 0xFFFF3B30);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(0, dp(14), 0, dp(14));
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("恢复所有设置为默认值？")
                .setPositiveButton("恢复", (d, w) -> applyPreset(null))
                .setNegativeButton("取消", null)
                .show());
        resetCard.addView(reset, new LinearLayout.LayoutParams(-1, -2));

        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        TextView foot = text("作者 酷安@熠寻 · v" + ver, 12, cSub);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(24), 0, 0);
        page.addView(foot, new LinearLayout.LayoutParams(-1, -2));

        setContentView(scroll);
    }

    /**
     * 微信输入法按键上的英文、数字用的是它自带的「WE」字体（只有拉丁字母、数字、符号，没有中文）。
     * 从手机上已经装好的微信输入法里读出来存进配置，豆包那边用它画字母和数字；模块本身不带这个字体。
     */
    private void cacheWeFont() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(Config.PKG_WECHAT, 0);
            String src = pi.getLongVersionCode() + ":" + pi.lastUpdateTime;
            if (src.equals(sp.getString(Config.WE_FONT_SRC, "")) && sp.contains(Config.WE_FONT)) return;
            byte[] data;
            try (java.io.InputStream in = createPackageContext(Config.PKG_WECHAT, 0).getAssets().open(Config.WE_FONT_ASSET)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                data = out.toByteArray();
            }
            sp.edit().putString(Config.WE_FONT, android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
                    .putString(Config.WE_FONT_SRC, src).apply();
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            // 没装微信输入法：清掉旧的，豆包继续用自己的字体
            if (sp.contains(Config.WE_FONT)) sp.edit().remove(Config.WE_FONT).remove(Config.WE_FONT_SRC).apply();
        } catch (Throwable t) {
            android.util.Log.w("WGlass", "cache WE font failed", t);
        }
    }

    /**
     * 荣耀圆环标识：模块内置的矢量（res/drawable/ic_honor_ring.xml），画成 192px 的单色 PNG 存进配置，
     * 给输入法进程用（输入法进程读不到模块自己的资源）。不依赖荣耀商城。
     */
    private void cacheHonorLogo() {
        if (Config.LOGO_SRC_VER.equals(sp.getString(Config.LOGO_SRC_KEY, "")) && sp.contains(Config.LOGO_PNG)) return;
        try {
            int id = getResources().getIdentifier("ic_honor_ring", "drawable", getPackageName());
            if (id == 0) return;
            android.graphics.drawable.Drawable d = getDrawable(id);
            int size = 192;
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, size, size);
            d.draw(new android.graphics.Canvas(bm));
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            bm.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            sp.edit().putString(Config.LOGO_PNG,
                            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP))
                    .putString(Config.LOGO_SRC_KEY, Config.LOGO_SRC_VER).apply();
        } catch (Throwable t) {
            android.util.Log.w("WGlass", "cache honor logo failed", t);
        }
    }

    // ---- 数据 ----
    private String get(String key) {
        String v = sp.getString(key, null);
        return v == null || v.isEmpty() ? Config.def(key) : v;
    }

    private void put(String key, String value) {
        sp.edit().putString(key, value).apply();
        if (remote != null) remote.edit().putString(key, value).apply();
    }

    /** preset == null 表示恢复默认。 */
    private void applyPreset(Map<String, String> preset) {
        SharedPreferences.Editor ed = sp.edit();
        for (String key : Config.DEFAULTS.keySet()) {
            if (key.equals(Config.ENABLE) || key.equals(Config.IME_WECHAT) || key.equals(Config.IME_DOUBAO)) continue;
            String v = preset != null && preset.containsKey(key) ? preset.get(key) : Config.def(key);
            ed.putString(key, v);
        }
        if (preset == null) {
            for (String k : new String[]{Config.ENABLE, Config.IME_WECHAT, Config.IME_DOUBAO}) ed.putString(k, Config.def(k));
        }
        ed.apply();
        pushAll();
        for (Runnable r : refreshers) r.run();
    }

    // ---- 控件 ----
    private LinearLayout card(LinearLayout page) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(cCard);
        bg.setCornerRadius(dp(16));
        card.setBackground(bg);
        card.setClipToOutline(true);
        page.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    private LinearLayout section(LinearLayout page, String header, String footer) {
        TextView h = text(header, 13, cSub);
        h.setPadding(dp(16), dp(24), dp(16), dp(7));
        page.addView(h);
        LinearLayout card = card(page);
        if (footer != null) {
            TextView f = text(footer, 12, cSub);
            f.setPadding(dp(16), dp(6), dp(16), 0);
            page.addView(f);
        }
        return card;
    }

    private static GradientDrawable pill(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(999f);
        return d;
    }

    private void divider(LinearLayout card) {
        if (card.getChildCount() == 0) return;
        View d = new View(this);
        d.setBackgroundColor(cDivider);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
        lp.leftMargin = dp(16);
        card.addView(d, lp);
    }

    /** 开关行：标题 + 可选的一行说明；big = 主卡片里的大号总开关。 */
    private View toggle(LinearLayout card, String label, String sub, String key, String onValue, String offValue, boolean big) {
        divider(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(big ? 16 : 11), dp(12), dp(big ? 16 : 11));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(label, big ? 19 : 16, cText);
        if (big) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        texts.addView(t);
        if (sub != null) {
            TextView st = text(sub, 13, cSub);
            st.setPadding(0, dp(2), 0, 0);
            texts.addView(st);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(onValue.equals(get(key)));
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        sw.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{0xFF34C759, night ? 0xFF39393D : 0xFFE9E9EA}));
        sw.setTrackTintMode(android.graphics.PorterDuff.Mode.SRC);
        sw.setOnCheckedChangeListener((v, on) -> put(key, on ? onValue : offValue));
        refreshers.add(() -> sw.setChecked(onValue.equals(get(key))));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        card.addView(row);
        return row;
    }

    private View slider(LinearLayout card, String label, String key, float min, float max, float step, String unit) {
        divider(card);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(10), dp(16), dp(6));
        LinearLayout top = new LinearLayout(this);
        top.addView(text(label, 16, cText), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text("", 15, cAccent);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(value);
        box.addView(top);

        SeekBar bar = new SeekBar(this);
        int steps = Math.round((max - min) / step);
        bar.setMax(steps);
        float cur;
        try { cur = Float.parseFloat(get(key)); } catch (Throwable t) { cur = Float.parseFloat(Config.def(key)); }
        bar.setProgress(Math.round((Math.max(min, Math.min(max, cur)) - min) / step));
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(cAccent));
        bar.setThumbTintList(android.content.res.ColorStateList.valueOf(night ? 0xFFFFFFFF : cAccent));
        value.setText(format(min + bar.getProgress() * step, step, unit));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                float v = min + p * step;
                value.setText(format(v, step, unit));
                if (fromUser) put(key, step >= 1 ? String.valueOf(Math.round(v)) : String.format(Locale.ROOT, "%.2f", v));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        refreshers.add(() -> {
            float v;
            try { v = Float.parseFloat(get(key)); } catch (Throwable t) { v = Float.parseFloat(Config.def(key)); }
            bar.setProgress(Math.round((Math.max(min, Math.min(max, v)) - min) / step));
        });
        card.addView(box);
        return box;
    }

    private static String format(float v, float step, String unit) {
        if (unit.equals("%")) return Math.round(v * 100) + "%";
        if (unit.equals("dp") && step < 1) {
            return (Math.abs(v - Math.round(v)) < 0.01f ? String.valueOf(Math.round(v))
                    : String.format(Locale.ROOT, "%.1f", v)) + " dp";
        }
        if (step >= 1) return Math.round(v) + (unit.isEmpty() ? "" : " " + unit);
        return String.format(Locale.ROOT, "%.2f", v) + unit;
    }

    private void colorRow(LinearLayout card, String label, String key) {
        divider(card);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(16), dp(6));
        row.addView(text(label, 16, cText), new LinearLayout.LayoutParams(0, -2, 1f));

        View swatch = new View(this);
        GradientDrawable sd = new GradientDrawable();
        sd.setShape(GradientDrawable.OVAL);
        sd.setStroke(dp(1), cDivider);
        swatch.setBackground(sd);

        EditText hex = new EditText(this);
        hex.setText(get(key).toUpperCase(Locale.ROOT));
        hex.setTextColor(cText);
        hex.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hex.setTypeface(Typeface.MONOSPACE);
        hex.setSingleLine();
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setBackground(null);
        hex.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        updateSwatch(sd, get(key));
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String v = s.toString().trim();
                if (!v.startsWith("#")) v = "#" + v;
                if (v.matches("#[0-9a-fA-F]{6}")) {   // 只收 RGB，不透明度由「背景不透明度」控制
                    put(key, v.toUpperCase(Locale.ROOT));
                    updateSwatch(sd, v);
                }
            }
        });
        row.addView(hex, new LinearLayout.LayoutParams(dp(110), -2));
        refreshers.add(() -> hex.setText(get(key).toUpperCase(Locale.ROOT)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(26), dp(26));
        slp.leftMargin = dp(8);
        row.addView(swatch, slp);
        card.addView(row);
    }

    private void updateSwatch(GradientDrawable sd, String c) {
        try { sd.setColor(Color.parseColor(c)); } catch (Throwable ignored) {}
    }

    private Button button(String label) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextColor(cAccent);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(night ? 0x330A84FF : 0x14007AFF);
        bg.setCornerRadius(dp(12));
        btn.setBackground(bg);
        if (btn.getParent() == null && btn.getLayoutParams() == null) {
            btn.setLayoutParams(new ViewGroup.LayoutParams(-1, dp(44)));
        }
        return btn;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
