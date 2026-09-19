# 主题商店解锁 · LSPosed 模块

针对 `com.heytap.themestore`（ColorOS / OxygenOS 主题商店）的 LSPosed 模块。
解锁会员权益与付费资源：**主题、壁纸、字体、息屏(AOD)插件、桌面小组件**。

本模块是 [chxcodepro/OppoThemeUnlock](https://github.com/chxcodepro/OppoThemeUnlock) 的延续与重写：
原项目面向 **17.16.0**，本模块面向 **17.19.1**，并补上了原项目缺失的**资源权限闸门**。

---

## 一、原理：商店的权限模型长什么样

权限判定不是散落在各处的 `if`，而是一条收敛到单点的管线：

```
用户登录态 ──┐
             ├─→ VipUserStatus {VALID, INVALID, CHECKING}
VipUserDto ──┘        │
                      ▼
        ResTypeUtil.getResTypeWithVipStatus(product, vipStatus)
                      │  把「资源 + 会员状态」压成一个 int 资源码
                      ▼
              资源码 (0..17)
                      │
                      ▼
        ResourceUtil.isFree() / UI 按钮文案 / 价格展示
```

资源码语义（`ResTypeUtil` 常量集）：

| 码 | 含义 | `isFree()` |
|---|---|---|
| 0 | 免费 | 放行 |
| 1 | 正常付费 | **拦截** |
| 2 | 正常已购 | 放行 |
| 3 | 折扣付费 | **拦截** |
| 4 / 6 | 折扣已购 / 已领取 | 放行 |
| 5 | 折扣免费领取 | 放行 |
| 7 | VIP 免费 | 放行 |
| 8 | VIP 折扣 | **拦截** |
| 9 | VIP 折扣已购 | 放行 |
| 10 / 11 | VIP 折扣 | **拦截** |
| 12 / 13 | VIP 折扣已购 | 放行 |
| 14 | VIP 专享已解锁 | 放行 |
| 15 | VIP 专享**锁定** | **拦截** |
| 16 | VIP 专属**可用** | 放行 |
| 17 | VIP 专属**未开通**（"会员优先"） | **拦截** |

### 原项目的盲区

原项目只改 `ResourceItemDto.getIsVip()` / `getIsVipAvailable()`。
这两个只是**展示字段**——它们影响卡片上的角标，但不决定能否下载。

真正的闸门是 `getResTypeWithVipStatus()` 的返回值，而它被 **27+ 处**调用：
列表 adapter、详情页、购物车、购买弹窗 `MashUpPurchaseDialog`、`CouponCheckUtil`、
`UnfitManager`、H5 的 `ThemeJsApis` / `ThemeGroup.getButtonStatus`……

`ResourceUtil.isFree()` 读的就是这个码。只改 DTO 字段会出现
"角标没了，但点进去还是要开通会员"的现象。

---

## 二、本模块做了什么

### 1. 会员身份层 `VipIdentityHook`

- `VipUserDto`：字段（`vipStatus` / `vipDays` / `endTime` / `lastExpireTime`）
  与 getter/setter 双向伪造。字段负责反射读取路径，getter 负责正常调用路径。
- `VipUserStatus` 出口固定为 `VALID`。**不硬编码方法名**，而是反射扫描目标类中
  所有返回 `VipUserStatus` 的方法并逐个 hook，因此方法被重命名/新增不会失效。
- 覆盖 4 个提供者：`UserInfoManager`(4 个方法)、`cards.BizManager.L()`、
  `interfaceLayer.hc`(4 个方法)、`interfaceLayer.l17.e()`。
  后两者是混淆包中的真实实现，原项目只覆盖了 `hc`。
- `endTime` 设为 `4102444799000`（2100 年），用于绕开
  `UserInfoManager.Q()` 的 `now >= endTime` 过期判定。

### 2. 资源权限码层 `ResTypeHook` —— 核心增强

在 `getResTypeWithVipStatus()` **出口**改写返回值，把"因权限不足被拦截"的码
映射为"已拥有"的码：

```
1  → 2      正常付费    → 正常已购
3  → 4      折扣付费    → 折扣已购
8  → 9      VIP 折扣    → VIP 折扣已购
10 → 12     VIP 折扣    → VIP 折扣已购
11 → 13     VIP 折扣    → VIP 折扣已购
15 → 14     VIP 专享锁定 → VIP 专享已解锁
17 → 16     会员优先    → VIP 专属可用
```

映射结果 `{2,4,9,12,13,14,16}` **全部落在 `isFree()` 的放行集合内**，
且不触碰任何免费码，因此不会破坏已购与免费的既有判定。

改写点在**出口**而非入口，所以：
- 不关心 `vipUserStatus` 从哪来（`UserInfoManager` 还是 `t65.d().y()`）
- 对方法内部重构、混淆免疫

覆盖的重载：

```
ResTypeUtil.getResTypeWithVipStatus(PublishProductItemDto, VipUserStatus)
ResTypeUtil.getResTypeWithVipStatus(ProductDetailsInfo,  VipUserStatus)
ResTypeUtil.getResTypeWithVipStatusByLocal(ProductDetailsInfo, VipUserStatus, LocalProductInfo)
ResTypeVipUtil.getResTypeWithVipStatus(PublishProductItemDto, VipUserStatus)   // Kotlin 侧并行实现
```

外加 `ResourceUtil.isFree()` 兜底放行，以及 `ResourceItemDto` /
`LocalResource` / `PublishProductItemDto` 的展示字段补丁。

### 3. 下载信息层 `DownloadHook` —— 第二道闸门

**症状**：UI 已经显示免费，点"应用"却弹 **「获取下载信息失败，原因：未购买」**。

原因是有两条完全独立的支路。资源码只管 UI；点"应用"后会再向服务端要一次
下载信息，走的是另一条链路：

```
download/c.java (Parser.kt)
    aVar.f = downloadResponseItemDto.getStatus();    // 服务端下发状态码
    aVar.b = getFileUrl() / getUnEncryptUrl();       // 主下载地址
    aVar.n = getBackupUrl();                         // 备用地址

HttpDownloadWrapper.e(download.a status)
    int i4 = status.f;
    i4 == 3  -> 绑定账号            i4 == 8  -> 未购买     <-- 就是这个
    i4 == 7  -> VIP 身份无效        i4 == 9  -> 付费资源超 5 台设备
    i4 == 5  -> Token 过期          i4 == 10 -> VIP 资源超 5 台设备
    i4 == 12 -> 已下架（或 URL 全空）
```

**关键细节**：`e()` 在 `i4 == 8` 时**直接抛异常，从未检查 URL**；
只有 `i4 == 12` 那条分支才判 URL 是否为空。这说明状态码 8 是**纯逻辑拒绝**，
服务端很可能照常下发了 `fileUrl` / `backupUrl`。把状态码归零，
流程就会越过全部拒绝分支，正常走到 URL 提取并返回 `LocalProductInfo`。

hook 点选在 `DownloadResponseItemDto.getStatus()`。这个类**非混淆**
（字段名规范且带 `@Tag`），且全工程只在 Parser 里被读取一次，改它零副作用。

另加一层签名定位的兜底：按"返回 `LocalProductInfo` 且仅 1 个参数"找到
`HttpDownloadWrapper` 里的提取方法（17.19.1 中名为 `e()`），在入口再次归零，
防止 Parser 路径变化让主 hook 失效。

**诊断日志**：拦截时会打印该响应实际的 URL 字段长度——

```
[ThemeUnlock] download status 8 -> 0, urls: fileUrl=87chars unEncryptUrl=null backupUrl=null key=24chars
```

`fileUrl` 有长度 = 服务端下发了地址，下载应当成功；
全是 `null` / `empty` = 服务端在拒绝时确实没给地址，属于客户端不可控范围。

### 4. 试用到期层 `TrialHook`

`ThemeTrialExpireReceiver` 在 `onReceive` 入口直接吞掉到期广播。
该 receiver 处理 5 类到期 action（主题 / 字体 / 动态壁纸 / 锁屏 / SystemUI）
以及 `RESOURCE_EXPIRE`、`VIP_EXPIRE`，全部含 `_EXPIRE`，因此统一按该片段判定，
未来新增资源类型自动覆盖。

### 5. 开屏广告层 `AdHook`

`SplashDto.getAdData()` 返回后立刻 `setShowTime(1)` + `setIsSkip(true)`。
只动展示参数，不碰网络层，避免影响商店其他请求。

### 6. 多进程

商店是**多进程**应用，`:bathmos`（动态壁纸/差异包）、`:engine`（主题引擎）、
`:pet`（宠物组件）各自持有独立类加载器。壁纸与息屏插件的渲染就在这些子进程里，
因此默认 **`HOOK_ALL_PROCESSES = true`**，每个进程独立安装一次（幂等）。

---

## 三、安装

1. 构建产物：`app/build/outputs/apk/debug/app-debug.apk`
2. 安装 APK，在 LSPosed 中启用本模块
3. 作用域勾选 `com.heytap.themestore`
4. 强制停止主题商店后重新打开

### 日志

```
adb logcat | grep ThemeUnlock
```

正常启动会出现：

```
[ThemeUnlock] === ThemeUnlock attach ===
[ThemeUnlock] pkg=com.heytap.themestore proc=com.heytap.themestore ver=1.0.0
[ThemeUnlock] VIP identity: VipUserDto patched
[ThemeUnlock] VIP identity: com.nearme.themespace.UserInfoManager -> 4 status method(s) pinned to VALID
[ThemeUnlock] resource gate hooked: ResTypeUtil.getResTypeWithVipStatus
[ThemeUnlock] resource gate hooked: ResTypeUtil.getResTypeWithVipStatusByLocal
[ThemeUnlock] download gate hooked: DownloadResponseItemDto.getStatus
[ThemeUnlock] isFree(): blocked -> free
[ThemeUnlock] res code 17 -> 16  @ResTypeUtil.getResTypeWithVipStatus
[ThemeUnlock] download status 8 -> 0, urls: fileUrl=87chars unEncryptUrl=null backupUrl=null key=24chars
[ThemeUnlock] === ThemeUnlock ready ===
```

`res code 17 -> 16` 这类行只在**首次**出现时打印，避免列表滚动刷爆日志。

---

## 四、构建

| 工具 | 版本 |
|---|---|
| JDK | 17+（本机 21 已验证） |
| Android SDK Platform | 36 |
| Gradle | 8.7（wrapper 自带） |
| AGP | 8.5.0 |

```bash
./gradlew assembleDebug
```

`local.properties` 里的 `sdk.dir` 按本机路径调整。

**注意**：`de.robv.android.xposed:api:82` 只发布在 `https://api.xposed.info/`，
MavenCentral 与 Google 仓库都没有这个 artifact。`settings.gradle` 里必须保留：

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://api.xposed.info/' }   // 缺这行会报 Could not find de.robv.android.xposed:api:82
    }
}
```

---

## 五、版本适配说明

本模块的类名与方法签名以 **17.19.1** 的真实反编译结果为准，同时保留了对
17.16.0 的兼容（`VIP_STATUS_PROVIDERS` 里的类名在两个版本中都存在）。

设计上刻意规避了版本脆弱性：

- 类缺失走 `findClassIfExists`，只记日志不抛异常
- 方法用 `hookAllMethods` 按名字匹配全部重载，不写签名
- 会员状态用**反射扫描返回类型**定位方法，不硬编码混淆名
- 四层 hook 相互隔离，任一层失败不影响其余层

若商店大版本更新后失效，排查顺序：

1. `logcat` 里找 `not found` / `absent` 开头的行，定位是哪个类消失了
2. 用 jadx 打开新版 APK，确认 `ResTypeUtil` 是否仍在
   `com.nearme.themespace.util` 包内、`getResTypeWithVipStatus` 名字是否变化
3. 若资源码语义变化，更新 `UnlockConfig.UNLOCK_MAP` 即可

---

## 六、边界与不确定性

诚实划清已验证与未验证的界线：

**已通过反编译确认（17.19.1 实际产物）**

- `VipUserDto` 的 4 个字段与 4 个 getter 实际存在，签名与本模块假设一致
- `UserInfoManager` 中返回 `VipUserStatus` 的方法为 `N()` / `K()` / `L()` / `M()`，共 4 个
- `interfaceLayer.hc` 在 17.19.1 中仍存在，含 4 个返回 `VipUserStatus` 的方法
  （`D` / `U` / `b` / `y`）——但它是**混淆名**，下个版本随时可能改名
- `ResTypeUtil.getResTypeWithVipStatus` 的 3 个重载、
  `ResTypeVipUtil.getResTypeWithVipStatus`、`ResourceUtil.isFree` 均存在
- 资源码映射的结果集合完全落在 `isFree()` 的放行白名单内
- `resapply` 包内**无任何** VIP 校验，应用环节只判断本地文件与已拥有状态
- 下载支路的拒绝码映射已逐条核对：`3` 绑定账号 / `5` Token 过期 / `7` VIP 身份无效 /
  `8` 未购买 / `9,10` 超 5 台设备 / `12` 已下架
- `DownloadResponseItemDto` 带 `@Tag` 注解、字段名非混淆，
  且全工程仅在 `download/c.java` 的 Parser 中被读取一次——改它零副作用

**尚未验证**

- 真机运行表现：本模块只完成了编译与产物结构校验，未在实际设备上跑过
- **服务端是否在拒绝时仍下发下载地址**：这是下载层能否生效的唯一变量。
  代码结构强烈暗示会下发（`e()` 在 `i4==8` 时根本没检查 URL），
  但需要实测日志确认。看这一行即可判断：
  `download status 8 -> 0, urls: fileUrl=... ` 若 `fileUrl` 有长度则应当成功
- 若 `fileUrl` 与 `backupUrl` 全为空，流程会落到 `i4 == 12` 分支并提示"已下架"，
  这种情况属于客户端不可控范围，需要另找地址来源
- `:engine` / `:bathmos` 子进程的类加载时序：若某层类在 `handleLoadPackage`
  时尚未就绪，该层会静默跳过，需要看日志确认

**已知的语义副作用**

资源码被改写后，UI 会把受限资源显示为"已拥有"。这意味着
`ResourceUtil.getDisplayPrice()` 对 VIP 类资源会返回 0，
价格数字会与商店原始数据不一致——这是解锁的必然代价，不是缺陷。

---

## 七、声明

仅供技术研究与学习交流，请勿用于商业用途。测试后请于 24 小时内自行删除，
并支持正版。
