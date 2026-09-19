package io.github.oppotheme.unlock;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 资源权限码层 —— 本模块相对原项目的核心增强。
 *
 * 背景：
 *   ResTypeUtil.getResTypeWithVipStatus(...) 是整个商店唯一的权限汇聚点，
 *   被 27+ 处调用（列表 adapter、详情页、购物车、购买弹窗、UnfitManager、
 *   H5 的 ThemeJsApis / ThemeGroup.getButtonStatus ...）。
 *   它把「资源 + 会员状态」压成一个 int 资源码，UI 完全按这个码决定
 *   显示"立即购买" / "开通会员" / "立即使用"。
 *
 *   原项目只改了 DTO 的 getIsVip()/getIsVipAvailable()，那只影响展示字段，
 *   真正的闸门在 ResourceUtil.isFree() —— 它读的就是上面那个资源码。
 *
 * 做法：
 *   在该方法出口改写返回值，把"因权限不足被拦截"的码映射为"已拥有"的码。
 *   不改入参、不改判定过程，只在结果落地的最后一刻修正，
 *   因此对混淆、对内部实现重构都免疫。
 */
final class ResTypeHook {

    private ResTypeHook() {
    }

    static void install(ClassLoader cl) {
        hookResTypeUtil(cl);
        hookResTypeVipUtil(cl);
        hookResourceUtil(cl);
        hookDtoFlags(cl);
    }

    /* ------------------------------------------------------------------ */

    /**
     * ResTypeUtil 有三个重载：
     *   int getResTypeWithVipStatus(PublishProductItemDto, VipUserStatus)
     *   int getResTypeWithVipStatus(ProductDetailsInfo,  VipUserStatus)
     *   int getResTypeWithVipStatusByLocal(ProductDetailsInfo, VipUserStatus, LocalProductInfo)
     * 用 hookAllMethods 一次覆盖全部重载。
     */
    private static void hookResTypeUtil(ClassLoader cl) {
        Class<?> clazz = XposedHelpers.findClassIfExists(UnlockConfig.CLS_RES_TYPE_UTIL, cl);
        if (clazz == null) {
            HookLog.e("ResTypeUtil not found, resource gate NOT unlocked", null);
            return;
        }
        hookRewrite(clazz, UnlockConfig.M_GET_RES_TYPE_WITH_VIP_STATUS);
        hookRewrite(clazz, UnlockConfig.M_GET_RES_TYPE_WITH_VIP_STATUS_BY_LOCAL);
    }

    /** ResTypeVipUtil 是 Kotlin 侧的并行实现，语义与 ResTypeUtil 完全一致。 */
    private static void hookResTypeVipUtil(ClassLoader cl) {
        Class<?> clazz = XposedHelpers.findClassIfExists(UnlockConfig.CLS_RES_TYPE_VIP_UTIL, cl);
        if (clazz == null) {
            HookLog.d("ResTypeVipUtil absent (optional)");
            return;
        }
        hookRewrite(clazz, UnlockConfig.M_GET_RES_TYPE_WITH_VIP_STATUS);
    }

    /**
     * ResourceUtil.isFree(...) 是"能不能免费拿"的最终判定，
     * 它把资源码与一张放行白名单比对。作为兜底，直接让它放行。
     *
     * 注意：仅作为最后一道保险。主路径靠资源码改写完成，
     * 这样列表/详情页的价格与按钮文案仍然自洽。
     */
    private static void hookResourceUtil(ClassLoader cl) {
        Class<?> clazz = XposedHelpers.findClassIfExists(UnlockConfig.CLS_RESOURCE_UTIL, cl);
        if (clazz == null) {
            HookLog.d("ResourceUtil absent (optional)");
            return;
        }
        try {
            int hooked = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!UnlockConfig.M_IS_FREE.equals(m.getName())) {
                    continue;
                }
                if (m.getReturnType() != boolean.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (Boolean.FALSE.equals(param.getResult())) {
                            param.setResult(Boolean.TRUE);
                            HookLog.once("isfree-trip", "isFree(): blocked -> free");
                        }
                    }
                });
                hooked++;
            }
            HookLog.i("ResourceUtil.isFree hooked (" + hooked + " overload(s))");
        } catch (Throwable t) {
            HookLog.e("hook ResourceUtil.isFree failed", t);
        }
    }

    /**
     * 资源展示字段层的补丁，与原项目对齐。
     *
     * 语义提醒：
     *   isVip         = 该资源是否属于 VIP 专属
     *   isVipAvailable= 当前用户对 VIP 专属资源是否已放行
     * 两者同时为 1 时，ResTypeUtil 会走到"VIP 专属可用"分支；
     * 若不改，纯展示路径（未经过 ResTypeUtil 的卡片）仍会显示锁定角标。
     */
    private static void hookDtoFlags(ClassLoader cl) {
        patchIntGetter(cl, "com.oppo.cdo.theme.domain.dto.response.ResourceItemDto", "getIsVip");
        patchIntGetter(cl, "com.oppo.cdo.theme.domain.dto.response.ResourceItemDto", "getIsVipAvailable");
        patchIntGetter(cl, "com.oppo.cdo.theme.domain.dto.response.LocalResource", "getIsVip");
        patchIntGetter(cl, "com.oppo.cdo.theme.domain.dto.response.LocalResource", "getIsVipAvailable");
        // 卡片侧还有一份同名 DTO，包路径在 card 下（实测 17.19.1 有效）
        patchIntGetter(cl, "com.oppo.cdo.card.theme.dto.item.ResourceItemDto", "getIsVip");
        patchIntGetter(cl, "com.oppo.cdo.card.theme.dto.item.ResourceItemDto", "getIsVipAvailable");
        patchIntGetter(cl, "com.oppo.cdo.theme.domain.dto.response.PublishProductItemDto", "getIsVipAvailable");
    }

    /**
     * 把一个返回 int/Integer 的 getter 顶成 1。
     * 返回值类型可能是原始 int 也可能是装箱 Integer，两种都按数值结果处理。
     */
    private static void patchIntGetter(ClassLoader cl, String className, String methodName) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, cl);
        if (clazz == null) {
            HookLog.d("  dto absent: " + className);
            return;
        }
        try {
            int hooked = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!methodName.equals(m.getName()) || m.getParameterTypes().length != 0) {
                    continue;
                }
                Class<?> rt = m.getReturnType();
                if (rt != int.class && rt != Integer.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object current = param.getResult();
                        if (current instanceof Integer && ((Integer) current).intValue() == UnlockConfig.VIP_STATUS_VALID) {
                            return;
                        }
                        param.setResult(Integer.valueOf(UnlockConfig.VIP_STATUS_VALID));
                    }
                });
                hooked++;
            }
            if (hooked > 0) {
                HookLog.d("  + " + clazz.getSimpleName() + "." + methodName + " -> 1");
            }
        } catch (Throwable t) {
            HookLog.once("dto-flag-" + className + "-" + methodName,
                    "patch " + className + "." + methodName + " failed - " + t);
        }
    }

    /* ------------------------------------------------------------------ */

    /** 在某类的所有同名方法上加"出口改写资源码"的钩子。 */
    private static void hookRewrite(Class<?> clazz, String methodName) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object result = param.getResult();
                    if (!(result instanceof Integer)) {
                        return;
                    }
                    int code = ((Integer) result).intValue();
                    if (!UnlockConfig.isBlockedCode(code)) {
                        return;
                    }
                    int unlocked = UnlockConfig.unlockResCode(code);
                    param.setResult(Integer.valueOf(unlocked));
                    HookLog.once("unlock-" + code,
                            UnlockConfig.describeUnlock(code, unlocked) + "  @"
                                    + param.method.getDeclaringClass().getSimpleName()
                                    + "." + param.method.getName());
                }
            });
            HookLog.i("resource gate hooked: " + clazz.getSimpleName() + "." + methodName);
        } catch (Throwable t) {
            HookLog.e("hookRewrite " + clazz.getSimpleName() + "." + methodName + " failed", t);
        }
    }
}
