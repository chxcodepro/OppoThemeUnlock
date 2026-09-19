package io.github.oppotheme.unlock;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。
 *
 * 安装顺序即依赖顺序：
 *   身份层先落地，客户端后续任何一次会员判定都会拿到 VALID；
 *   资源码层再接管出口改写；
 *   试用与广告层相互独立，失败不影响前两层。
 *
 * 商店是多进程应用（:engine / :bathmos / :pet 各自承载主题引擎、
 * 动态壁纸与宠物组件），因此默认对所有进程生效——壁纸与息屏插件
 * 走的就是 :bathmos 与 :engine。
 */
public class HookEntry implements IXposedHookLoadPackage {

    /** 每个进程一份，:engine/:bathmos 各自独立安装。 */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || !UnlockConfig.TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (!UnlockConfig.HOOK_ALL_PROCESSES
                && !UnlockConfig.TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        HookLog.i("=== ThemeUnlock attach ===");
        HookLog.i("pkg=" + lpparam.packageName
                + " proc=" + lpparam.processName
                + " ver=" + BuildConfigCompat.VERSION_NAME);

        try {
            install(lpparam.classLoader);
        } catch (Throwable t) {
            HookLog.e("layer install aborted", t);
        }
    }

    private void install(ClassLoader cl) {
        safeInstall("vip-identity", new Layer() {
            @Override
            public void run() {
                VipIdentityHook.install(cl);
            }
        });
        safeInstall("res-code", new Layer() {
            @Override
            public void run() {
                ResTypeHook.install(cl);
            }
        });
        safeInstall("download", new Layer() {
            @Override
            public void run() {
                DownloadHook.install(cl);
            }
        });
        safeInstall("trial", new Layer() {
            @Override
            public void run() {
                TrialHook.install(cl);
            }
        });
        safeInstall("ad", new Layer() {
            @Override
            public void run() {
                AdHook.install(cl);
            }
        });
        HookLog.i("=== ThemeUnlock ready ===");
    }

    private void safeInstall(String name, Layer layer) {
        try {
            layer.run();
        } catch (Throwable t) {
            HookLog.e("layer '" + name + "' failed, continuing", t);
        }
    }

    private interface Layer {
        void run();
    }

    /** 与 app/build.gradle 的 versionName 保持一致，避免引入 BuildConfig 依赖顺序问题。 */
    static final class BuildConfigCompat {
        static final String VERSION_NAME = "1.1.0";

        private BuildConfigCompat() {
        }
    }
}
