# ══════════════════════════════════════════════════════════════
#  HY_VQ 混淆规则
# ══════════════════════════════════════════════════════════════

# ── Shizuku ──
# 本应用通过**反射**调用 Shizuku.newProcess() 以 shell 权限执行 logcat，
# 并且依赖其 AIDL 生成的 Binder 接口类。若被混淆：
#   · 反射会因方法名改变而抛 NoSuchMethodException
#   · Binder 事务会因接口描述符改变而失败
# 症状是 debug 版正常、release 版功能失效 —— 极难排查，故整体保留。
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class rikka.sui.** { *; }

# 反射目标方法名必须原样保留
-keepclassmembers class rikka.shizuku.Shizuku {
    *** newProcess(...);
}

# ShizukuProvider 在 Manifest 中声明，组件类名不可混淆
-keep class rikka.shizuku.ShizukuProvider { *; }
