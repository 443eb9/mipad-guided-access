> **警告：这是一个完全由 LLM 生成的项目，未经任何人工审核，本人完全不保证代码的可靠性。**

# MiPad Guided Access

小米平板 6 的引导式访问模块，通过 Magisk 和 LSPosed 锁定当前应用。使用者自行承担安装、使用和修改产生的风险。建议先备份数据，并准备可用的 root ADB 连接。

## 功能

- 同时按住两个音量键 5 秒，进入或退出当前应用的引导式访问。
- 使用 Android Lock Task Mode 锁定当前任务，限制通过 Home、最近任务和返回键离开应用。
- 锁定期间隐藏状态栏、导航栏、HyperOS 三点窗口控制和选定的游戏工具栏窗口。
- 普通单音量键通过缓冲与重放继续调节音量。双键组合的合并窗口为 150 毫秒。
- 退出时恢复保存的 Lock Task 策略与界面控制。
- 会话状态保存在内存中，重启后回到普通模式。
- 提供 root ADB 紧急退出命令。

## 适用环境

设备检查限定为 `Build.DEVICE=pipa` 和 Android SDK 34。

| 项目 | 开发时测试环境 |
| --- | --- |
| 设备 | 小米平板 6，型号 `23043RP34C` |
| 系统 | Android 14，HyperOS `OS2.0.10.0.UMZCNXM` |
| Magisk | `30.7`，开启 Zygisk |
| LSPosed | `1.9.2 (7024)` |
| 作用域 | 系统框架 `android`，系统界面 `com.android.systemui` |

运行时需要提供 Xposed API 100 及 `XposedBridge.deoptimizeMethod` 的 LSPosed 环境。编译使用 Xposed API 82 类型声明，相关运行时方法通过反射调用。

框架和 HyperOS 内部接口随系统版本变化，升级系统后请重新验证。现有设备测试基于改名前的 1.3 构建及注入的底层输入事件，包含 Phigros、设置应用、系统手势、工具栏恢复、紧急退出和重启复位。实体按键操作及当前改名构建的设备表现仍待验证。

## 项目标识

| 项目 | 值 |
| --- | --- |
| APK 包名 | `dev.mipad.guidedaccess` |
| 应用名称 | 平板引导式访问 |
| 版本 | `1.3`，版本代码 `4` |
| Magisk 模块 ID | `mipad_guided_access` |
| 模块名称 | MiPad Guided Access |
| 作者字段 | `mipad` |
| Xposed 入口 | `dev.mipad.guidedaccess.GuidedAccess` |
| 控制广播 | `dev.mipad.guidedaccess.CONTROL` |
| 状态广播 | `dev.mipad.guidedaccess.STATE` |
| 广播权限 | `android.permission.MANAGE_ACTIVITY_TASKS` |
| 最低及目标 SDK | `34` |

## 安装

1. 备份数据，确认 root ADB 连接可用。
2. 安装并启用 Magisk、Zygisk 和支持上述 API 的 LSPosed。
3. 在 Magisk 应用内安装 `mipad-guided-access-1.3.zip`，安装过程会安装配套 APK。
4. 在 LSPosed 中启用“平板引导式访问”，勾选“系统框架”和“系统界面”。
5. 重启设备。
6. 打开需要锁定的全屏应用，同时按住两个音量键 5 秒。退出时重复该操作。

旧版与当前版本采用各自独立的 APK 包名、Magisk 模块 ID 和签名。迁移时，先退出旧版引导式访问，在 Magisk 中移除旧模块并重启，再安装当前版本、配置作用域并重启。请在安装与卸载前退出引导式访问。

## 紧急退出与状态查询

在电脑上运行下列命令，将 `<设备序列号>` 替换为 `adb devices` 中的目标设备序列号。

```sh
adb -s <设备序列号> shell 'su -c "am broadcast --user 0 -a dev.mipad.guidedaccess.CONTROL --es command exit"'
```

查询状态：

```sh
adb -s <设备序列号> shell 'su -c "am broadcast --user 0 -a dev.mipad.guidedaccess.CONTROL --es command status"'
```

其他命令为 `enable` 和 `disable`。`disable` 会退出当前会话并停用切换处理，`enable` 会重新启用处理。Magisk 的模块操作按钮也会发送 `exit`。

## 本地构建

构建脚本使用 Windows 工具路径。准备以下环境：

- Windows，Python 3.12，JDK 21。
- Android SDK Platform 35，Build Tools 36.0.0。
- SDK 环境变量 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。默认位置为 `%LOCALAPPDATA%\Android\Sdk`。
- `java` 加入 `PATH`。

下列命令在项目根目录的 PowerShell 中运行。

下载 Xposed 编译依赖并核对 SHA-256：

```powershell
Invoke-WebRequest -Uri 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar' -OutFile 'tools/xposed-api-82.jar'
$hash = (Get-FileHash 'tools/xposed-api-82.jar' -Algorithm SHA256).Hash
if ($hash -ne 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25') {
    throw 'Xposed API checksum mismatch.'
}
```

首次构建时创建固定密钥。选择一个强密码，将两个环境变量设为相同值。已有 `keys/mipad.p12` 时直接使用该文件及对应密码。

```powershell
$env:MIPAD_KEYSTORE_PASSWORD = '<选择的密码>'
$env:MIPAD_KEY_PASSWORD = $env:MIPAD_KEYSTORE_PASSWORD
New-Item -ItemType Directory -Force -Path 'keys' | Out-Null
keytool -genkeypair -keystore keys/mipad.p12 -storetype PKCS12 -storepass:env MIPAD_KEYSTORE_PASSWORD -keypass:env MIPAD_KEY_PASSWORD -alias mipad -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=MiPad Guided Access'
```

随后构建：

```powershell
python build.py
```

脚本会先运行签名输入检查测试，再编译 Java、运行 `VolumeChordTest` 和 `AccessStateTest`，随后执行 DEX 转换、资源打包、APK 对齐、签名与签名验证。成功后得到：

- `dist/guided-access.apk`。
- `dist/mipad-guided-access-1.3.zip`。

APK 在 `assets/LICENSE` 中附带 MIT 许可文本。ZIP 包含配套 APK、Magisk 脚本、README 和许可文件。

请安全备份 `keys/mipad.p12` 及密码。持续使用相同密钥可覆盖升级同包名 APK。密钥变更后，安装过程需要先卸载已有同包名 APK。`.gitignore` 已覆盖密钥目录、构建目录、设备检查记录、截图所在目录和本地分析环境。

## GitHub Actions

工作流位于 `.github/workflows/build.yml`。每次 push 到 `main` 都会执行一次构建，也支持在 Actions 页面手动运行。

在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中添加三个 Repository Secrets：

| Secret | 内容 |
| --- | --- |
| `MIPAD_KEYSTORE_BASE64` | 固定 `keys/mipad.p12` 文件的 Base64 内容 |
| `MIPAD_KEYSTORE_PASSWORD` | PKCS12 文件密码 |
| `MIPAD_KEY_PASSWORD` | `mipad` 别名的私钥密码，按上述步骤创建时与文件密码相同 |

可用 PowerShell 将密钥的 Base64 内容复制到剪贴板，再粘贴到 Secret：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PWD 'keys/mipad.p12'))) | Set-Clipboard
```

Secrets 通过步骤环境变量传入。构建日志中的签名参数引用环境变量名称。CI 上传范围仅包含 `dist` 中的 APK 和模块 ZIP，并在构建步骤后清理密钥文件。请仅向可信任人员开放仓库写入权限。

在 Actions 对应运行的 **Artifacts** 中下载 `mipad-guided-access-<提交 SHA>`。产物保留 30 天。工作流将第三方 Actions 固定到已核对的提交，并核对 Xposed JAR 的 SHA-256。

## 目录

```text
app/                       Android 清单、资源、Xposed 入口及 Java 源码
tests/                     Java 行为测试和 Python 签名输入检查测试
module/                    Magisk 元数据及安装、启动、退出、卸载脚本
tools/module_installer.sh  Magisk 模块安装入口
build.py                   编译、测试、签名及打包脚本
.github/workflows/         GitHub Actions 配置
LICENSE                    项目自有代码的 MIT 许可证
LICENSES/                  第三方许可文本
THIRD_PARTY_NOTICES.md     第三方来源与许可说明
```

## 许可证

项目自有代码采用 [MIT 许可证](LICENSE)。第三方组件适用各自的许可证，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。
