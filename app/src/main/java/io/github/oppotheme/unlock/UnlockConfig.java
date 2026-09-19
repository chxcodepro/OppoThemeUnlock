package io.github.oppotheme.unlock;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 全部 hook 目标的集中登记处。
 *
 * 类名与方法签名均以 com.heytap.themestore 17.19.1
 * (ColorOS 16 / Android 16) 的真实反编译结果为准。
 * 运行期一律走 findClassIfExists / hookAllMethods，
 * 目标缺失只记日志并跳过，绝不抛异常打断商店启动。
 */
public final class UnlockConfig {

    private UnlockConfig() {
    }

    /* ------------------------------------------------------------------ *
     * 作用域
     * ------------------------------------------------------------------ */

    public static final String TARGET_PACKAGE = "com.heytap.themestore";

    /** 分体进程也要覆盖：:engine :bathmos :pet 各自持有独立的类加载器视图。 */
    public static final boolean HOOK_ALL_PROCESSES = true;

    public static final boolean VERBOSE_DEFAULT = true;

    /* ------------------------------------------------------------------ *
     * 会员身份层
     * ------------------------------------------------------------------ */

    public static final String CLS_VIP_USER_DTO = "com.oppo.cdo.card.theme.dto.vip.VipUserDto";
    public static final String CLS_VIP_USER_STATUS = "com.nearme.themespace.account.VipUserStatus";

    /**
     * 所有返回 VipUserStatus 的方法都会被反射扫出来并统一改写为 VALID。
     * 这张表只负责"去哪儿扫"，不关心里面有什么方法——方法名混淆变了也无所谓。
     */
    public static final String[] VIP_STATUS_PROVIDERS = {
            // 主入口：全局账号/VIP 状态
            "com.nearme.themespace.UserInfoManager",
            // 卡片业务侧的 VIP 状态缓存
            "com.nearme.themespace.cards.BizManager",
            // 以下两个是 interfaceLayer 混淆包中被 17.19.1 实际使用的实现类
            "com.oplus.aiunit.interfaceLayer.hc",
            "com.oplus.aiunit.interfaceLayer.l17",
    };

    public static final int VIP_STATUS_VALID = 1;
    public static final int VIP_DAYS = 99999;
    /** 2100-01-01 00:00:00 UTC，远期有效期，绕开 UserInfoManager.Q() 的过期判定。 */
    public static final long VIP_END_TIME = 4102444799000L;

    /* ------------------------------------------------------------------ *
     * 资源权限码层（本模块的核心增强）
     * ------------------------------------------------------------------ */

    public static final String CLS_RES_TYPE_UTIL = "com.nearme.themespace.util.ResTypeUtil";
    public static final String CLS_RES_TYPE_VIP_UTIL = "com.nearme.themespace.util.ResTypeVipUtil";
    public static final String CLS_RESOURCE_UTIL = "com.nearme.themespace.util.ResourceUtil";

    public static final String M_GET_RES_TYPE_WITH_VIP_STATUS = "getResTypeWithVipStatus";
    public static final String M_GET_RES_TYPE_WITH_VIP_STATUS_BY_LOCAL = "getResTypeWithVipStatusByLocal";
    public static final String M_IS_FREE = "isFree";

    /**
     * 资源码语义（来自 ResTypeUtil 的常量集）：
     *
     * 放行集合 isFree()==true : {0,2,4,5,6,7,9,12,13,14,16}
     * 拦截集合 isFree()==false: {1,3,8,10,11,15,17}
     *
     * 0  免费          1  正常付费      2  正常已购
     * 3  折扣付费      4  折扣已购      5  折扣免费领取    6  折扣已领取
     * 7  VIP免费       8  VIP折扣       9  VIP折扣已购
     * 10 VIP折扣      11 VIP折扣       12 VIP折扣已购    13 VIP折扣已购
     * 14 VIP专享已解锁 15 VIP专享锁定
     * 16 VIP专属可用   17 VIP专属未开通（"会员优先"）
     *
     * 映射原则：只把"因权限不足而拦截"的码改写成"已拥有"的码，
     * 不碰免费码，避免破坏已购与免费的既有判定。
     */
    private static final Map<Integer, Integer> UNLOCK_MAP;

    static {
        Map<Integer, Integer> m = new HashMap<Integer, Integer>();
        m.put(1, 2);    // 正常付费   -> 正常已购
        m.put(3, 4);    // 折扣付费   -> 折扣已购
        m.put(8, 9);    // VIP折扣    -> VIP折扣已购
        m.put(10, 12);  // VIP折扣    -> VIP折扣已购
        m.put(11, 13);  // VIP折扣    -> VIP折扣已购
        m.put(15, 14);  // VIP专享锁定 -> VIP专享已解锁
        m.put(17, 16);  // 会员优先   -> VIP专属可用
        UNLOCK_MAP = Collections.unmodifiableMap(m);
    }

    /** 改写受限资源码；不在表内的码原样返回。 */
    public static int unlockResCode(int code) {
        Integer mapped = UNLOCK_MAP.get(code);
        return mapped == null ? code : mapped.intValue();
    }

    /** 该码是否落在 isFree() 的拦截集合内。 */
    public static boolean isBlockedCode(int code) {
        return UNLOCK_MAP.containsKey(code);
    }

    /** 记录一次改写，用于日志采样。 */
    public static String describeUnlock(int from, int to) {
        return "res code " + from + " -> " + to;
    }

    /* ------------------------------------------------------------------ *
     * 试用到期层
     * ------------------------------------------------------------------ */

    public static final String CLS_TRIAL_EXPIRE_RECEIVER = "com.nearme.themespace.trial.ThemeTrialExpireReceiver";
    public static final String M_ON_RECEIVE = "onReceive";

    /** 只要 action 含该片段，就认定为试用到期广播，直接吞掉。 */
    public static final String TRIAL_EXPIRE_ACTION_TAG = "_EXPIRE";

    /* ------------------------------------------------------------------ *
     * 开屏广告层
     * ------------------------------------------------------------------ */

    public static final String CLS_SPLASH_DTO = "com.oppo.cdo.card.theme.dto.SplashDto";
    public static final String M_GET_AD_DATA = "getAdData";
    public static final String M_SET_SHOW_TIME = "setShowTime";
    public static final String M_SET_IS_SKIP = "setIsSkip";

    /** 开屏展示时长压到最小。 */
    public static final int SPLASH_SHOW_TIME = 1;
}
