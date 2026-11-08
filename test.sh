#!/bin/bash
# 作者调试用的脚本，不是模块的一部分，正常使用模块不需要它。
# 只在已 root 的荣耀手机（MagicOS 11，按 WKL-AN20 的屏幕坐标写的）上用过；桌面搜索包名、下拉手势坐标换别的手机要自己改。
# Developer debugging script, not part of the module. Written for a rooted Honor WKL-AN20 (MagicOS 11);
# the desktop-search package and swipe coordinates are device-specific.
# 注意：脚本会「强行停止」微信输入法，如果它是当前默认输入法，MagicOS 会把默认输入法换掉。
# Note: it force-stops WeChat IME; if that is the current default IME, MagicOS switches the default away.
#
# 用法: ./test.sh [out.png] [key=value ...]
#   设置 debug.wglass.<key>=<value>，重启微信输入法，在桌面搜索里唤出键盘，截图并打印模块日志。
#   value 写 - 表示清除该属性。
set -u
D=${ANDROID_SERIAL:?请先 export ANDROID_SERIAL=<设备序列号或 IP:端口>}
OUT=${1:-shot.png}; shift || true
for kv in "$@"; do k=${kv%%=*}; v=${kv#*=}; [ "$v" = "-" ] && v=""; adb -s $D shell setprop "debug.wglass.$k" "'$v'"; done
adb -s $D shell am force-stop com.tencent.wetype
adb -s $D shell am force-stop com.hihonor.search
adb -s $D shell input keyevent KEYCODE_HOME; sleep 1
adb -s $D shell input swipe 660 1200 660 2000 250; sleep 3
echo "IME shown: $(adb -s $D shell dumpsys input_method | grep -m1 -oE 'mInputShown=\w+')"
adb -s $D exec-out screencap -p > "$OUT"
F=$(adb -s $D shell "su -c 'ls -t /data/adb/lspd/log/modules*.log | head -1'" | tr -d '\r')
adb -s $D shell "su -c 'grep -a WGlass $F | tail -2'" | sed 's/.*\] \[WGlass\]/[WGlass]/' | cut -c1-300
# 默认清掉本次设置的属性，避免残留影响正常使用（KEEP=1 时保留）
if [ "${KEEP:-0}" != "1" ]; then for kv in "$@"; do adb -s $D shell setprop "debug.wglass.${kv%%=*}" "''"; done; fi
