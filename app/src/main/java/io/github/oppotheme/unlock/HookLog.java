package io.github.oppotheme.unlock;

import de.robv.android.xposed.XposedBridge;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 统一日志出口。
 *
 * 主题商店是个重列表 App，任何挂在 getter 上的 hook 都会被列表滚动刷爆，
 * 所以这里对高频事件做 once() 去重，只记第一次，避免把 LSPosed 日志冲垮。
 */
public final class HookLog {

    public static final String PREFIX = "[ThemeUnlock] ";

    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<String>());

    private static volatile boolean verbose = UnlockConfig.VERBOSE_DEFAULT;

    private HookLog() {
    }

    public static void setVerbose(boolean enabled) {
        verbose = enabled;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    /** 关键节点，始终输出。 */
    public static void i(String msg) {
        XposedBridge.log(PREFIX + msg);
    }

    /** 细节输出，受 verbose 开关控制。 */
    public static void d(String msg) {
        if (verbose) {
            XposedBridge.log(PREFIX + msg);
        }
    }

    /** 同一个 key 只输出一次，用于 hook 安装回执与高频改写的首例采样。 */
    public static void once(String key, String msg) {
        if (SEEN.add(key)) {
            XposedBridge.log(PREFIX + msg);
        }
    }

    public static void e(String msg, Throwable t) {
        XposedBridge.log(PREFIX + "ERR " + msg + (t == null ? "" : " -> " + t));
    }
}
