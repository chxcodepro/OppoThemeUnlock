package io.github.oppotheme.unlock;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 开屏广告层。
 *
 * SplashDto 携带开屏广告配置（AdDataDto、showTime、isSkip）。
 * 取到广告数据后立刻把展示时长压到最小并标记可跳过，
 * 从而让开屏一闪而过。不做网络层拦截，避免影响商店其他请求。
 */
final class AdHook {

    private AdHook() {
    }

    static void install(ClassLoader cl) {
        Class<?> splash = XposedHelpers.findClassIfExists(UnlockConfig.CLS_SPLASH_DTO, cl);
        if (splash == null) {
            HookLog.d("SplashDto absent (optional)");
            return;
        }
        try {
            XposedBridge.hookAllMethods(splash, UnlockConfig.M_GET_AD_DATA, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object self = param.thisObject;
                    if (self == null) {
                        return;
                    }
                    callIfPresent(self, UnlockConfig.M_SET_SHOW_TIME, UnlockConfig.SPLASH_SHOW_TIME);
                    callIfPresent(self, UnlockConfig.M_SET_IS_SKIP, Boolean.TRUE);
                    HookLog.once("splash", "splash ad neutered");
                }
            });
            HookLog.i("ad: SplashDto.getAdData hooked");
        } catch (Throwable t) {
            HookLog.e("hook SplashDto.getAdData failed", t);
        }
    }

    /** setter 参数类型可能有装箱差异，这里按运行时实际类型匹配。 */
    private static void callIfPresent(Object target, String setter, Object value) {
        try {
            for (java.lang.reflect.Method m : target.getClass().getDeclaredMethods()) {
                if (!setter.equals(m.getName()) || m.getParameterTypes().length != 1) {
                    continue;
                }
                Class<?> p = m.getParameterTypes()[0];
                if (value instanceof Integer && p == int.class) {
                    XposedHelpers.callMethod(target, setter, Integer.valueOf(((Integer) value).intValue()));
                    return;
                }
                if (value instanceof Boolean && p == boolean.class) {
                    XposedHelpers.callMethod(target, setter, Boolean.valueOf(((Boolean) value).booleanValue()));
                    return;
                }
            }
        } catch (Throwable t) {
            HookLog.d("setter " + setter + " skipped: " + t);
        }
    }
}
