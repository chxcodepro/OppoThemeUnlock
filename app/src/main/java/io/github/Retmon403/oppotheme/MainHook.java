package io.github.Retmon403.oppotheme;

import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes2.dex */
public class MainHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.heytap.themestore";
    private static final int VIP_DAYS = 99999;
    private static final long VIP_END_TIME = 4102444799000L;
    private static final int VIP_STATUS_VALID = 1;
    private static final String VIP_USER_DTO = "com.oppo.cdo.card.theme.dto.vip.VipUserDto";
    private static final String VIP_USER_STATUS = "com.nearme.themespace.account.VipUserStatus";

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        log("loading process: " + lpparam.processName);
        hookVipDto(lpparam);
        hookVipStatusLayer(lpparam);
        hookResourceAccess(lpparam);
        hookTrialExpiration(lpparam);
        hookSplashAd(lpparam);
        log("hooks installed for Theme Store 17.16.0");
    }

    private void hookVipDto(XC_LoadPackage.LoadPackageParam lpparam) {
        hookMethod(lpparam, VIP_USER_DTO, "getVipStatus", new XC_MethodHook() { // from class: io.github.Retmon403.oppotheme.MainHook.1
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                MainHook.this.patchVipDto(param.thisObject);
                param.setResult(Integer.valueOf(MainHook.VIP_STATUS_VALID));
            }
        }, new Class[0]);
        hookMethod(lpparam, VIP_USER_DTO, "getVipDays", new XC_MethodHook() { // from class: io.github.Retmon403.oppotheme.MainHook.2
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                MainHook.this.patchVipDto(param.thisObject);
                param.setResult(Integer.valueOf(MainHook.VIP_DAYS));
            }
        }, new Class[0]);
        hookMethod(lpparam, VIP_USER_DTO, "getEndTime", new XC_MethodHook() { // from class: io.github.Retmon403.oppotheme.MainHook.3
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                MainHook.this.patchVipDto(param.thisObject);
                param.setResult(Long.valueOf(MainHook.VIP_END_TIME));
            }
        }, new Class[0]);
        hookMethod(lpparam, VIP_USER_DTO, "getLastExpireTime", new XC_MethodHook() { // from class: io.github.Retmon403.oppotheme.MainHook.4
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                MainHook.this.patchVipDto(param.thisObject);
                param.setResult(0L);
            }
        }, new Class[0]);
        hookResult(lpparam, "com.oppo.cdo.card.theme.dto.page.WeatherPageResponseDto", "getVipStatus", Integer.valueOf(VIP_STATUS_VALID), new Class[0]);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void patchVipDto(Object vipUserDto) {
        if (vipUserDto == null) {
            return;
        }
        try {
            XposedHelpers.setIntField(vipUserDto, "vipStatus", VIP_STATUS_VALID);
            XposedHelpers.setIntField(vipUserDto, "vipDays", VIP_DAYS);
            XposedHelpers.setLongField(vipUserDto, "endTime", VIP_END_TIME);
            XposedHelpers.setLongField(vipUserDto, "lastExpireTime", 0L);
        } catch (Throwable throwable) {
            log("patch VipUserDto fields failed: " + throwable.getMessage());
        }
    }

    private void hookVipStatusLayer(XC_LoadPackage.LoadPackageParam lpparam) {
        hookMethodsReturningVipStatus(lpparam, "com.nearme.themespace.UserInfoManager");
        hookMethodsReturningVipStatus(lpparam, "com.oplus.aiunit.interfaceLayer.hc");
    }

    private void hookMethodsReturningVipStatus(XC_LoadPackage.LoadPackageParam lpparam, String className) {
        Class<?> targetClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        Class<?> statusClass = XposedHelpers.findClassIfExists(VIP_USER_STATUS, lpparam.classLoader);
        if (targetClass == null || statusClass == null) {
            log("not found VIP status layer: " + className);
            return;
        }
        try {
            final Object validStatus = XposedHelpers.getStaticObjectField(statusClass, "VALID");
            int hookCount = 0;
            Method[] declaredMethods = targetClass.getDeclaredMethods();
            int length = declaredMethods.length;
            for (int i = 0; i < length; i += VIP_STATUS_VALID) {
                Method method = declaredMethods[i];
                if (statusClass.equals(method.getReturnType())) {
                    try {
                        XposedBridge.hookMethod(method, new XC_MethodHook() { // from class: io.github.Retmon403.oppotheme.MainHook.5
                            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                                param.setResult(validStatus);
                            }
                        });
                        hookCount += VIP_STATUS_VALID;
                        log("hook VIP status: " + className + "." + method.getName());
                    } catch (Throwable throwable) {
                        log("hook VIP status failed: " + className + "." + method.getName() + " - " + throwable.getMessage());
                    }
                }
            }
            log("VIP status methods hooked: " + className + " count=" + hookCount);
        } catch (Throwable throwable2) {
            log("not found VipUserStatus.VALID: " + throwable2.getMessage());
        }
    }

    private void hookResourceAccess(XC_LoadPackage.LoadPackageParam lpparam) {
        Integer numValueOf = Integer.valueOf(VIP_STATUS_VALID);
        hookResult(lpparam, "com.oppo.cdo.theme.domain.dto.response.ResourceItemDto", "getIsVip", numValueOf, new Class[0]);
        hookResult(lpparam, "com.oppo.cdo.theme.domain.dto.response.ResourceItemDto", "getIsVipAvailable", numValueOf, new Class[0]);
        hookResult(lpparam, "com.oppo.cdo.theme.domain.dto.response.LocalResource", "getIsVip", numValueOf, new Class[0]);
        hookResult(lpparam, "com.oppo.cdo.theme.domain.dto.response.LocalResource", "getIsVipAvailable", numValueOf, new Class[0]);
    }

    private void hookTrialExpiration(XC_LoadPackage.LoadPackageParam lpparam) {
        hookMethod(lpparam, "com.nearme.themespace.trial.ThemeTrialExpireReceiver", "onReceive", new XC_MethodHook() { // from class: io.github.Retmon403.oppotheme.MainHook.6
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                Intent intent = (Intent) param.args[MainHook.VIP_STATUS_VALID];
                String action = intent == null ? null : intent.getAction();
                if (action != null && action.contains("_EXPIRE")) {
                    MainHook.this.log("block expiration action: " + action);
                    param.setResult((Object) null);
                }
            }
        }, Context.class, Intent.class);
        hookResult(lpparam, "com.nearme.themespace.trial.ThemeTrialExpireReceiver", "a", true, Context.class, String.class);
    }

    private void hookSplashAd(XC_LoadPackage.LoadPackageParam lpparam) {
        hookMethod(lpparam, "com.oppo.cdo.card.theme.dto.SplashDto", "getAdData", new XC_MethodHook() { // from class: io.github.Retmon403.oppotheme.MainHook.7
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) {
                XposedHelpers.callMethod(param.thisObject, "setShowTime", new Object[]{Integer.valueOf(MainHook.VIP_STATUS_VALID)});
                XposedHelpers.callMethod(param.thisObject, "setIsSkip", new Object[]{true});
            }
        }, new Class[0]);
    }

    private void hookResult(XC_LoadPackage.LoadPackageParam lpparam, String className, String methodName, final Object result, Class<?>... parameterTypes) {
        hookMethod(lpparam, className, methodName, new XC_MethodHook() { // from class: io.github.Retmon403.oppotheme.MainHook.8
            protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                param.setResult(result);
            }
        }, parameterTypes);
    }

    private void hookMethod(XC_LoadPackage.LoadPackageParam lpparam, String className, String methodName, XC_MethodHook callback, Class<?>... parameterTypes) {
        Class<?> targetClass = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
        if (targetClass == null) {
            log("not found class: " + className);
            return;
        }
        try {
            Method targetMethod = targetClass.getDeclaredMethod(methodName, parameterTypes);
            XposedBridge.hookMethod(targetMethod, callback);
            log("hook: " + className + "." + methodName);
        } catch (NoSuchMethodException exception) {
            log("not found method: " + className + "." + methodName + " - " + exception.getMessage());
        } catch (Throwable throwable) {
            log("hook error: " + className + "." + methodName + " - " + throwable.getMessage());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void log(String message) {
        XposedBridge.log("[theme_unlock] " + message);
    }
}
