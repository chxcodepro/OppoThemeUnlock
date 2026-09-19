package io.github.oppotheme.unlock;

import android.content.Context;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 会员身份层。
 *
 * 目标：让商店认为当前账号是有效期极长的 VALID 会员。
 *
 * 做法分两路：
 *  1) 数据面 —— 直接改 VipUserDto 的字段，让所有读取路径（含被 obfuscate 的）
 *     都看到同一个伪造快照；
 *  2) 类型面 —— 把 VipUserStatus 的判定出口统一顶成 VALID。
 *     这里不硬编码方法名，而是反射扫描目标类中所有返回 VipUserStatus 的方法，
 *     因此方法被重命名/新增都不会失效（17.16.0 -> 17.19.1 已实际验证有效）。
 */
final class VipIdentityHook {

    private VipIdentityHook() {
    }

    static void install(ClassLoader cl) {
        hookVipUserDto(cl);
        hookVipStatusProviders(cl);
    }

    /* ------------------------------------------------------------------ */

    /**
     * VipUserDto 是贯穿登录、会员页、卡片、H5 的公共 DTO。
     * 字段与 getter 同时改：字段负责反射读取路径，getter 负责正常调用路径。
     */
    private static void hookVipUserDto(ClassLoader cl) {
        final Class<?> dto = XposedHelpers.findClassIfExists(UnlockConfig.CLS_VIP_USER_DTO, cl);
        if (dto == null) {
            HookLog.e("VipUserDto not found, VIP identity layer degraded", null);
            return;
        }

        XC_MethodHook patch = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                patchDtoFields(param.thisObject);
            }
        };

        hookAll(dto, "getVipStatus", patch);
        hookAll(dto, "getVipDays", patch);
        hookAll(dto, "getEndTime", patch);
        hookAll(dto, "getLastExpireTime", patch);

        // 登录/会员页首屏往往只有 setter 被走到，这里顺手把字段补上，
        // 使 UI 在任何一次 getter 之前就已经是会员态。
        hookAll(dto, "setVipStatus", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = Integer.valueOf(UnlockConfig.VIP_STATUS_VALID);
                patchDtoFields(param.thisObject);
            }
        });
        hookAll(dto, "setEndTime", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = Long.valueOf(UnlockConfig.VIP_END_TIME);
                patchDtoFields(param.thisObject);
            }
        });
        hookAll(dto, "setLastExpireTime", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = Long.valueOf(0L);
                patchDtoFields(param.thisObject);
            }
        });

        HookLog.i("VIP identity: VipUserDto patched");
    }

    /** 把 DTO 内部字段写成会员快照。字段缺失只记一次，不抛。 */
    private static void patchDtoFields(Object dto) {
        if (dto == null) {
            return;
        }
        trySetInt(dto, "vipStatus", UnlockConfig.VIP_STATUS_VALID);
        trySetInt(dto, "vipDays", UnlockConfig.VIP_DAYS);
        trySetLong(dto, "endTime", UnlockConfig.VIP_END_TIME);
        trySetLong(dto, "startTime", 0L);
        trySetLong(dto, "lastExpireTime", 0L);
    }

    private static void trySetInt(Object target, String field, int value) {
        try {
            XposedHelpers.setIntField(target, field, value);
        } catch (Throwable t) {
            HookLog.once("field-int-" + field, "VipUserDto field '" + field + "' not settable, rely on getter hook");
        }
    }

    private static void trySetLong(Object target, String field, long value) {
        try {
            XposedHelpers.setLongField(target, field, value);
        } catch (Throwable t) {
            HookLog.once("field-long-" + field, "VipUserDto field '" + field + "' not settable, rely on getter hook");
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * 扫描所有 VIP 状态提供者，把返回 VipUserStatus 的方法出口顶成 VALID。
     *
     * UserInfoManager.N() 是真正的判定实现，它要求
     *   canGetVip() && token 非空 && vipDto != null && !过期 && vipStatus == 1
     * 才返回 VALID。前面 DTO 字段已伪造，但仍可能因为未登录/无 token 走到 INVALID，
     * 因此这里做类型面的兜底，保证任何链路上的会员判断都拿到 VALID。
     */
    private static void hookVipStatusProviders(ClassLoader cl) {
        final Class<?> statusClass = XposedHelpers.findClassIfExists(UnlockConfig.CLS_VIP_USER_STATUS, cl);
        if (statusClass == null) {
            HookLog.e("VipUserStatus not found, type-level identity hook skipped", null);
            return;
        }
        if (!statusClass.isEnum()) {
            HookLog.e("VipUserStatus is not an enum, type-level identity hook skipped", null);
            return;
        }

        final Object valid;
        try {
            valid = XposedHelpers.getStaticObjectField(statusClass, "VALID");
        } catch (Throwable t) {
            HookLog.e("VipUserStatus.VALID missing", t);
            return;
        }

        for (String className : UnlockConfig.VIP_STATUS_PROVIDERS) {
            hookProvider(cl, className, statusClass, valid);
        }
    }

    private static void hookProvider(ClassLoader cl, String className, Class<?> statusClass, final Object valid) {
        Class<?> target = XposedHelpers.findClassIfExists(className, cl);
        if (target == null) {
            HookLog.once("provider-missing-" + className, "VIP provider absent (version drift): " + className);
            return;
        }

        int count = 0;
        Method[] methods;
        try {
            methods = target.getDeclaredMethods();
        } catch (Throwable t) {
            HookLog.e("cannot enumerate methods of " + className, t);
            return;
        }

        for (Method m : methods) {
            if (!statusClass.equals(m.getReturnType())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (param.getResult() != valid) {
                            param.setResult(valid);
                        }
                    }
                });
                count++;
                HookLog.d("  + " + className + "." + m.getName() + "()");
            } catch (Throwable t) {
                HookLog.once("provider-hookfail-" + className + "-" + m.getName(),
                        "hook failed " + className + "." + m.getName() + " - " + t);
            }
        }
        HookLog.i("VIP identity: " + className + " -> " + count + " status method(s) pinned to VALID");
    }

    /* ------------------------------------------------------------------ */

    /** hook 某个类里所有同名重载。 */
    private static void hookAll(Class<?> clazz, String methodName, XC_MethodHook callback) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, callback);
            HookLog.d("  + " + clazz.getSimpleName() + "." + methodName);
        } catch (Throwable t) {
            HookLog.once("hookall-" + clazz.getName() + "-" + methodName,
                    "hookAllMethods failed " + clazz.getSimpleName() + "." + methodName + " - " + t);
        }
    }

    /** 保留签名，供未来按需扩展（例如限定 Context 参数的入口）。 */
    static void installIfNeeded(ClassLoader cl, Context unused) {
        install(cl);
    }
}
