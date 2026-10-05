> **警告：这是一个完全由 LLM 生成的项目，未经任何人工审核，本人完全不保证代码的可靠性。**

# MiPad Guided Access

小米平板 6 的引导式访问模块。通过 Magisk 和 LSPosed 锁定当前应用，适合游戏等需要防止误触系统导航的场景。

- 同时按住两个音量键 5 秒，进入或退出锁定。尽量同时按下，松开两个键后再进行下一次切换。
- 锁定期间屏蔽 Home、最近任务、返回等系统按键，隐藏系统栏、HyperOS 三点窗口控制和系统游戏侧边栏。
- 单独使用音量键调节音量，退出后恢复保存的系统控制策略。重启后结束锁定会话。

## 使用要求与验证范围

- 小米平板 6，设备代号 `pipa`，Android 14。
- 已取得 root 权限，使用 Magisk 并开启 Zygisk。
- LSPosed Zygisk 版，提供 Xposed API 100 或更高版本及 `XposedBridge.deoptimizeMethod`。

适配基于 HyperOS `OS2.0.10.0.UMZCNXM`。系统更新后请重新验证兼容性。

CI 已验证构建、逻辑测试和 APK 签名。当前发布包及实体双音量键操作的实机验证仍待补充。

安装前请备份数据，并准备已获 root 授权的 ADB 连接。首次锁定前，先确认下方的紧急退出命令可用。

## 下载与安装

1. 登录 GitHub，打开 [构建列表](https://github.com/443eb9/mipad-guided-access/actions/workflows/build.yml)，选择一次成功的运行。
2. 在页面底部 **Artifacts** 下载构建产物并解压，取出其中的 `mipad-guided-access-*.zip` 模块包。
3. 在 Magisk 应用内安装该模块包。安装脚本会一并安装配套 APK。
4. 在 LSPosed 中启用“平板引导式访问”，勾选“系统框架”和“系统界面”，然后重启设备。
5. 解锁屏幕，在主屏幕打开一个普通应用并切为整屏显示，再按住两个音量键 5 秒。

分屏和浮窗请先切回整屏显示，系统屏幕固定或其他锁定任务请先结束。锁定期间，返回键的屏蔽也会影响应用内依赖该按键的返回操作。

下载产物保留 30 天。已过期时可选择较新的成功构建。

## 退出与卸载

正常退出时，再次按住两个音量键 5 秒。Magisk 中该模块的操作按钮也会发送退出命令。

卸载时，先退出锁定，再在 Magisk 中移除模块并重启。卸载脚本会移除配套 APK。

### ADB 紧急退出

在电脑上运行 `adb devices` 查找目标设备。将 `SERIAL` 替换为设备序列号，进入设备 shell：

```sh
adb -s SERIAL shell
```

随后在设备 shell 中执行：

```sh
su -c 'am broadcast --user 0 -a dev.mipad.guidedaccess.CONTROL --es command exit'
```

退出成功的状态回执包含 `active=false`。查询状态时，将最后的 `exit` 改为 `status`。

## 从源码构建

构建环境为 Windows、Python 3.12、JDK 21、Android SDK Platform 35 和 Build Tools 36.0.0。将 JDK 的 `bin` 目录加入 `PATH`，设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 SDK。

以下命令在项目根目录的 PowerShell 中运行。

下载 Xposed API 82 编译依赖，并核对校验值：

```powershell
Invoke-WebRequest -Uri 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar' -OutFile 'tools/xposed-api-82.jar'
$hash = (Get-FileHash 'tools/xposed-api-82.jar' -Algorithm SHA256).Hash
if ($hash -ne 'f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25') {
    throw 'Xposed API checksum mismatch.'
}
```

每次构建前设置签名密码。首次创建密钥时选择强密码，后续构建使用已有密钥的密码：

```powershell
$env:MIPAD_KEYSTORE_PASSWORD = '<密钥文件密码>'
$env:MIPAD_KEY_PASSWORD = $env:MIPAD_KEYSTORE_PASSWORD
```

首次构建时创建密钥，已有 `keys/mipad.p12` 时跳过此步：

```powershell
New-Item -ItemType Directory -Force -Path 'keys' | Out-Null
keytool -genkeypair -keystore keys/mipad.p12 -storetype PKCS12 `
    -storepass:env MIPAD_KEYSTORE_PASSWORD -keypass:env MIPAD_KEY_PASSWORD `
    -alias mipad -keyalg RSA -keysize 3072 -validity 3650 `
    -dname 'CN=MiPad Guided Access'
```

运行构建：

```powershell
python build.py
```

构建会运行 Python 和 Java 测试，并生成已签名的 `dist/guided-access.apk` 和 `dist/mipad-guided-access-<版本>.zip`。请安全备份密钥及密码，使用相同签名可覆盖升级同包名 APK。

### 在自己的仓库启用 CI

工作流位于 [`.github/workflows/build.yml`](.github/workflows/build.yml)，每次 push 到 `main` 时构建，也支持在 Actions 页面手动运行。

Fork 后，先在 Actions 页面检查工作流的启用状态。随后在 **Settings → Secrets and variables → Actions → Secrets** 中添加三个 **Repository secrets**：

| 名称 | 值 |
| --- | --- |
| `MIPAD_KEYSTORE_BASE64` | `keys/mipad.p12` 的 Base64 内容 |
| `MIPAD_KEYSTORE_PASSWORD` | 密钥文件密码 |
| `MIPAD_KEY_PASSWORD` | `mipad` 别名的私钥密码，按上述步骤创建时与文件密码相同 |

将密钥的 Base64 内容复制到剪贴板：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $PWD 'keys/mipad.p12'))) | Set-Clipboard
```

签名资料保留在本机和 GitHub Secrets 中。自行构建使用自己的签名，与本仓库产物相互替换时需要先卸载已有同包名 APK。

## 许可证

项目自有代码采用 [MIT 许可证](LICENSE)。第三方组件适用各自的许可证，详见 [第三方说明](THIRD_PARTY_NOTICES.md)。
