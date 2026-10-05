# 第三方组件

项目自有代码采用根目录 `LICENSE` 中的 MIT 许可证。第三方组件适用各自的许可证。

## Magisk 模块安装脚本

- 文件：`tools/module_installer.sh`。
- 来源：https://github.com/topjohnwu/Magisk/blob/v30.7/scripts/module_installer.sh 。
- 上游项目：Magisk，作者 topjohnwu。
- 许可证：GNU General Public License v3.0。
- 完整许可文本：`LICENSES/Magisk-GPL-3.0.txt`。
- 上游许可文本：https://github.com/topjohnwu/Magisk/blob/v30.7/LICENSE 。

该脚本以原始形式保留。构建时，脚本以 `META-INF/com/google/android/update-binary` 的名称加入 Magisk ZIP。ZIP 同时包含本说明和 GPLv3 许可文本。脚本源码可从上游链接或本项目的 `tools/module_installer.sh` 获取。

## Xposed API 82

- Maven 坐标：`de.robv.android.xposed:api:82`。
- 作者：rovo89。
- 许可证：Apache License 2.0，以该版本的 Maven POM 声明为依据。
- POM：https://api.xposed.info/de/robv/android/xposed/api/82/api-82.pom 。
- 下载：https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar 。
- SHA-256：`f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`。

此 JAR 用于编译时类型检查。设备运行时由 LSPosed 提供 Xposed API。
