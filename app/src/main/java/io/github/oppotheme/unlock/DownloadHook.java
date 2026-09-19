package io.github.oppotheme.unlock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 下载信息层 —— 解决「显示免费了，点应用却提示：获取下载信息失败，原因：未购买」。
 *
 * 问题定位
 * --------
 * UI 层由资源码决定，本模块已放行；但点"应用"后会再向服务端要一次下载信息，
 * 这条支路完全不经过资源码，是独立的第二道闸门：
 *
 *   download/c.java (Parser.kt)
 *       aVar.f = downloadResponseItemDto.getStatus();   // 服务端下发状态码
 *       aVar.b = ... getFileUrl() / getUnEncryptUrl();  // 主下载地址
 *       aVar.n = ... getBackupUrl();                    // 备用地址
 *
 *   HttpDownloadWrapper.e(download.a status)
 *       int i4 = status.f;
 *       if (i4 == 3)  throw ...(3);    // 绑定账号
 *       if (i4 == 7)  throw ...(7);    // VIP 身份无效
 *       if (i4 == 8)  throw ...(8);    // 未购买          <-- 就是这里
 *       if (i4 == 9)  throw ...(9);    // 付费资源超 5 台设备
 *       if (i4 == 10) throw ...(10);   // VIP 资源超 5 台设备
 *       if (i4 == 5)  throw ...(5);    // Token 过期
 *       if (i4 == 12 || URL 全空) throw ...(12, ...);  // 已下架
 *
 * 关键观察：e() 在 i4 == 8 时**直接抛异常，从未检查 URL**；
 * 只有 i4 == 12 那条分支才判 URL 是否为空。这说明 8 是纯逻辑拒绝，
 * 服务端很可能照常下发了 fileUrl / backupUrl。把状态码归零，
 * 流程就会越过所有拒绝分支，正常走到 URL 提取并返回 LocalProductInfo。
 *
 * hook 点选择
 * -----------
 * DownloadResponseItemDto 是**非混淆**的服务端 DTO（字段名规范、带 @Tag），
 * 且全工程只在 Parser 里被读取一次，因此改它的 getStatus() 精准且零副作用。
 *
 * 另外做了一层签名定位的兜底：按"返回 LocalProductInfo 且只有 1 个参数"
 * 找到 HttpDownloadWrapper 里的提取方法（17.19.1 中名为 e()），
 * 在入口处再次归零，避免 Parser 路径变化导致主 hook 失效。
 */
final class DownloadHook {

    private DownloadHook() {
    }

    /** 服务端下发、需要放行的拒绝状态码。12(已下架) 不在其中——那种情况下确实无 URL 可用。 */
    private static boolean isRejectCode(int code) {
        switch (code) {
            case 3:
            case 5:
            case 7:
            case 8:
            case 9:
            case 10:
                return true;
            default:
                return false;
        }
    }

    static void install(ClassLoader cl) {
        hookResponseStatus(cl);
        hookExtractMethod(cl);
    }

    /* ------------------------------------------------------------------ */

    /**
     * 主 hook：把下载响应里的拒绝状态码归零。
     *
     * 同时打印该响应的 URL 字段，用于判断服务端是否在拒绝时仍下发了地址——
     * 这是能否下载成功的决定性信息，实测时看这一行日志即可。
     */
    private static void hookResponseStatus(ClassLoader cl) {
        Class<?> dto = XposedHelpers.findClassIfExists(
                "com.oppo.cdo.theme.domain.dto.response.DownloadResponseItemDto", cl);
        if (dto == null) {
            HookLog.e("DownloadResponseItemDto not found, download gate NOT unlocked", null);
            return;
        }

        try {
            for (Method m : dto.getDeclaredMethods()) {
                if (!"getStatus".equals(m.getName()) || m.getParameterTypes().length != 0) {
                    continue;
                }
                if (m.getReturnType() != int.class && m.getReturnType() != Integer.class) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object result = param.getResult();
                        if (!(result instanceof Integer)) {
                            return;
                        }
                        int code = ((Integer) result).intValue();
                        if (!isRejectCode(code)) {
                            return;
                        }
                        param.setResult(Integer.valueOf(0));
                        HookLog.once("dl-status-" + code,
                                "download status " + code + " -> 0, urls: " + describeUrls(param.thisObject));
                    }
                });
                HookLog.i("download gate hooked: DownloadResponseItemDto.getStatus");
            }
        } catch (Throwable t) {
            HookLog.e("hook DownloadResponseItemDto.getStatus failed", t);
        }
    }

    /** 打印主/备地址与 ext，判断服务端是否真的给了下载链接。 */
    private static String describeUrls(Object dto) {
        if (dto == null) {
            return "<null dto>";
        }
        return "fileUrl=" + len(callString(dto, "getFileUrl"))
                + " unEncryptUrl=" + len(callString(dto, "getUnEncryptUrl"))
                + " backupUrl=" + len(callString(dto, "getBackupUrl"))
                + " unEncryptBackUpUrl=" + len(callString(dto, "getUnEncryptBackUpUrl"))
                + " key=" + len(callString(dto, "getKey"))
                + " subUrl=" + len(callString(dto, "getSubUrl"))
                + " ext=" + summarizeExt(dto);
    }

    /**
     * ext 是服务端自由扩展的 Map，下载地址有时藏在这里。
     * 这是服务端拒绝时最后一个可能给出地址的地方，因此完整 dump（截断保护）。
     */
    private static String summarizeExt(Object dto) {
        try {
            Object ext = XposedHelpers.callMethod(dto, "getExt");
            if (ext == null) {
                return "null";
            }
            if (ext instanceof java.util.Map) {
                StringBuilder sb = new StringBuilder("{");
                for (Object entryObj : ((java.util.Map<?, ?>) ext).entrySet()) {
                    java.util.Map.Entry<?, ?> e = (java.util.Map.Entry<?, ?>) entryObj;
                    sb.append(e.getKey()).append('=').append(e.getValue()).append("; ");
                }
                sb.append('}');
                String s = sb.toString();
                return s.length() > 600 ? s.substring(0, 600) + "..(truncated)" : s;
            }
            return String.valueOf(ext);
        } catch (Throwable t) {
            return "<err:" + t.getClass().getSimpleName() + ">";
        }
    }

    private static String len(String s) {
        if (s == null) {
            return "null";
        }
        return s.isEmpty() ? "empty" : s.length() + "chars";
    }

    private static String callString(Object target, String getter) {
        try {
            Object v = XposedHelpers.callMethod(target, getter);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * 兜底 hook：不依赖混淆方法名 e()，改用签名定位——
     * 在 HttpDownloadWrapper 里找「返回 LocalProductInfo、且恰好 1 个参数」的实例方法，
     * 在其入口把入参状态对象的拒绝码字段归零。
     */
    private static void hookExtractMethod(ClassLoader cl) {
        Class<?> wrapper = XposedHelpers.findClassIfExists(
                "com.nearme.themespace.download.HttpDownloadWrapper", cl);
        if (wrapper == null) {
            HookLog.d("HttpDownloadWrapper absent (optional)");
            return;
        }

        int hooked = 0;
        try {
            for (Method m : wrapper.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 1) {
                    continue;
                }
                if (!"com.nearme.themespace.model.LocalProductInfo".equals(m.getReturnType().getName())) {
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        neutralizeStatusObject(param.args[0]);
                    }
                });
                hooked++;
                HookLog.d("  + HttpDownloadWrapper." + m.getName() + "(<status>)");
            }
        } catch (Throwable t) {
            HookLog.e("hook HttpDownloadWrapper extract method failed", t);
        }
        if (hooked > 0) {
            HookLog.i("download gate backup hooked (" + hooked + " method(s))");
        }
    }

    /**
     * 把 download.a 状态对象里的拒绝码字段归零。
     *
     * 字段名是 R8 混淆产物（17.19.1 中为 f），因此这里做两层：
     *  1) 先按已知名尝试；
     *  2) 失败则遍历 int 字段，只认值为拒绝码、且字段名不是 type/version 类语义的那些。
     * 该对象只有 7 个 int 字段（f=status, h=type, i=versionCode, k=p2sOpen, o=point,
     * q=appendPoint, s=unfitType），其中只有 f 会承载拒绝码。
     */
    private static void neutralizeStatusObject(Object status) {
        if (status == null) {
            return;
        }
        try {
            int current = XposedHelpers.getIntField(status, "f");
            if (isRejectCode(current)) {
                XposedHelpers.setIntField(status, "f", 0);
                HookLog.once("dl-field-f-" + current, "download.a.f " + current + " -> 0 (by name)");
            }
            return;
        } catch (Throwable ignored) {
            // 字段名漂移，走下面的扫描
        }

        try {
            for (Field f : status.getClass().getDeclaredFields()) {
                if (f.getType() != int.class) {
                    continue;
                }
                f.setAccessible(true);
                int v = f.getInt(status);
                if (isRejectCode(v)) {
                    f.setInt(status, 0);
                    HookLog.once("dl-field-scan-" + v,
                            "download.a." + f.getName() + " " + v + " -> 0 (by scan)");
                }
            }
        } catch (Throwable t) {
            HookLog.d("neutralize download status failed: " + t);
        }
    }
}
