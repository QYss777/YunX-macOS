# 构建与打包

> 概览见 [README](../README.md)

## 环境要求

要求：macOS 12 或更高版本，JDK 17。Apple Silicon 与 Intel 架构需使用对应的构建环境。

| 依赖 | 说明 |
| --- | --- |
| JDK 17 | 用于 Gradle 编译与 jpackage 打包 |
| Gradle | 使用本机 Gradle 或项目 wrapper |
| Xcode Command Line Tools | 提供 macOS 原生构建工具与 `codesign` / `hdiutil` |

macOS 构建不需要 Windows 的 C++、Inno Setup 或 PowerShell 工具链。

## 命令

```bash
git clone https://github.com/liuyinkang0115-debug/YunX-macOS.git
cd YunX-macOS

gradle :desktop:run        # 编译并启动开发版
gradle :desktop:test       # 运行桌面测试
gradle :desktop:packageDmg # 生成 DMG 安装包
gradle :desktop:packagePkg # 生成 PKG 安装包
```

## 版本号

根目录 `version.txt` 是**唯一版本来源**：jpackage 版本、便携包 / 安装包的文件名与程序版本、
应用内「关于页 / 更新检测」全部由它派生。**发版只需改这一个文件。**

启动器 exe 的版本资源（公司 `tidain`、版权 `Copyright (C) 2026 tidain`）也在打包时由该版本号生成。

## 打包产物

| 产物 | 位置 | 说明 |
| --- | --- | --- |
| DMG 安装包 | `desktop/build/compose/binaries/main/dmg/YunX-Desktop-<版本>.dmg` | 双击后将应用拖入「应用程序」目录 |
| PKG 安装包 | `desktop/build/compose/binaries/main/pkg/YunX-Desktop-<版本>.pkg` | 使用 macOS 安装器安装 |
| ZIP 压缩包 | GitHub Release 附件 | 解压后直接运行 `YunX-Desktop.app`，适合便携分发 |

打包脚本会自动结束正在运行的程序实例，并在输出目录被占用时重试删除。

## 目录结构

| 目录 | 说明 |
| --- | --- |
| `desktop/` | 应用本体（Kotlin + Compose Multiplatform） |
| `desktop/native/` | 原生桥接层源码；macOS 使用 JNA 与系统命令完成平台集成 |
| `launcher/` | 历史 Windows 启动器兼容代码；macOS 由 `jpackage` 生成应用启动器 |
| `installer/` | 历史 Windows 安装脚本目录，macOS 打包由 Gradle 与 `jpackage` 完成 |
| `docs/` | 文档（见 [README](../README.md#文档)） |
