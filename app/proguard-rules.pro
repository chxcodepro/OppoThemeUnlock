# LSPosed 通过 xposed_init / manifest 元数据发现模块，入口类必须保留
-keep class io.github.oppotheme.unlock.HookEntry { *; }
-keep class io.github.oppotheme.unlock.** { *; }

# Xposed 框架接口由宿主提供
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }
