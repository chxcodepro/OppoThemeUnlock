package io.github.oppotheme.unlock;

import android.content.Intent;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 试用到期回收层。
 *
 * 17.19.1 的 ThemeTrialExpireReceiver 会在系统闹钟到点后发广播，
 * 由它负责弹"试用已到期"并触发资源回收。把它整条掐掉即可长保试用资源。
 *
 * 该 receiver 处理 5 类到期 action（主题/字体/动态壁纸/锁屏/SystemUI）
 * 以及 RESOURCE_EXPIRE / VIP_EXPIRE，全部以 "_EXPIRE" 结尾，
 * 因此用 "_EXPIRE" 做统一判定，未来新增资源类型也自动覆盖。
 */
final class TrialHook {

    private TrialHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> receiver = XposedHelpers.findClassIfExists(UnlockConfig.CLS_TRIAL_EXPIRE_RECEIVER, cl);
        if (receiver == null) {
            HookLog.d("ThemeTrialExpireReceiver absent (optional)");
            return;
        }

        // 1) onReceive 入口直接吞掉到期广播
        try {
            XposedBridge.hookAllMethods(receiver, UnlockConfig.M_ON_RECEIVE, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    for (Object arg : param.args) {
                        if (!(arg instanceof Intent)) {
                            continue;
                        }
                        String action = ((Intent) arg).getAction();
                        if (action != null && action.contains(UnlockConfig.TRIAL_EXPIRE_ACTION_TAG)) {
                            HookLog.once("trial-" + action, "trial expire swallowed: " + action);
                            // void 方法 setResult 会跳过整个方法体
                            param.setResult(null);
                            return;
                        }
                    }
                }
            });
            HookLog.i("trial: onReceive hooked");
        } catch (Throwable t) {
            HookLog.e("hook onReceive failed", t);
        }

        // 2) 内部 isInterceptTrialExpire 判定固定为"已拦截"
        try {
            int hooked = 0;
            for (java.lang.reflect.Method m : receiver.getDeclaredMethods()) {
                if (m.getReturnType() != boolean.class) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2) {
                    continue;
                }
                if (params[0] != android.content.Context.class || params[1] != String.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(Boolean.TRUE);
                    }
                });
                hooked++;
                HookLog.d("  + " + receiver.getSimpleName() + "." + m.getName() + "(Context,String) -> true");
            }
            HookLog.i("trial: intercept check hooked (" + hooked + ")");
        } catch (Throwable t) {
            HookLog.e("hook intercept check failed", t);
        }
    }
}
