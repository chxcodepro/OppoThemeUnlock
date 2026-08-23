# Oppo / OnePlus 主题商店 LSPosed 模块

![版本](https://img.shields.io/badge/version-1.5-blue)
![主题商店](https://img.shields.io/badge/Theme%20Store-17.16.0-green)
![构建](https://img.shields.io/badge/build-Gradle%208.7-orange)

适配包名 `com.heytap.themestore`，当前模块版本已还原为 `1.5`。模块入口：

```text
io.github.Retmon403.oppotheme.MainHook
```

1.5 Hook 范围：

- 会员 DTO 与会员状态层
- 资源 VIP 权限字段
- 试用到期广播
- 开屏广告

该版本不包含 1.6 之后新增的下载请求和下载响应 Hook。

## 项目结构

```text
app/src/main/java/             Hook 源码
app/src/main/assets/xposed_init
app/src/main/res/              LSPosed 模块描述与默认作用域
.github/workflows/build.yml    GitHub Actions 构建和发布
```

## 本地构建

要求：

- JDK 17
- Android SDK Platform 34

在项目根目录配置 `local.properties`：

```properties
sdk.dir=D\:\\path\\to\\Android\\Sdk
```

构建可安装的 Debug APK并执行 Lint：

```powershell
.\gradlew.bat clean assembleDebug lintDebug
```

输出：

```text
app/build/outputs/apk/debug/OppoOPlusThemeUnlock-1.5.apk
```

## GitHub Actions

工作流在以下情况运行：

- 推送任意分支
- Pull Request
- Actions 页面手动运行
- 推送 `v*` 标签

普通构建会生成由 Android Debug Key 自动签名的 APK，并上传到该次 Actions 运行的 **Artifacts**：

```text
OppoOPlusThemeUnlock-debug
```

## 配置 Release 签名

在 GitHub 仓库进入：

```text
Settings -> Secrets and variables -> Actions -> New repository secret
```

添加四个 Secrets：

| Secret | 内容 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | Keystore 文件的 Base64 文本 |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_STORE_PASSWORD` | Keystore 密码 |
| `RELEASE_KEY_PASSWORD` | Key 密码 |

Windows PowerShell 生成 Base64：

```powershell
[Convert]::ToBase64String(
    [IO.File]::ReadAllBytes("D:\path\release.jks")
) | Set-Content -NoNewline release-keystore-base64.txt
```

Linux 生成 Base64：

```bash
base64 -w 0 release.jks > release-keystore-base64.txt
```

macOS 生成 Base64：

```bash
base64 < release.jks | tr -d '\n' > release-keystore-base64.txt
```

四个 Secrets 全部存在时，Actions 会额外构建并上传签名 Release APK：

```text
OppoOPlusThemeUnlock-release
```

密钥、密码和 `local.properties` 均由 `.gitignore` 排除，不应提交到仓库。

## 发布 GitHub Release

先确保四个签名 Secrets 已配置，然后推送 `v*` 标签：

```bash
git tag v1.5
git push origin v1.5
```

Actions 会：

1. 构建 Debug APK并执行 Lint。
2. 解码临时 Release Keystore。
3. 构建签名 Release APK。
4. 创建对应标签的 GitHub Release 并上传 APK。

若标签构建缺少任一签名 Secret，工作流会明确失败，避免发布未签名 APK。

## 安装

1. 安装构建出的 APK。
2. 在 LSPosed 中启用模块。
3. 作用域选择 `com.heytap.themestore`。
4. 强制停止主题商店后重新打开。

## 说明

本项目使用 Xposed API 82，`minSdk 28`、`targetSdk 34`、`compileSdk 34`。
