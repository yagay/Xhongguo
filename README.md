<div align="center">

# 红果综合模块

基于 libxposed API 102 开发的 Android Xposed/LSPosed 模块，旨在提升红果短剧类应用的使用体验与界面沉浸感。

[![GitHub Repository](https://img.shields.io/badge/GitHub-KEJIYUNB%2Fhongguo-blue?logo=github)](https://github.com/KEJIYUNB/hongguo/)
[![Download Release](https://img.shields.io/badge/Download-Release-brightgreen?logo=github)](https://github.com/KEJIYUNB/hongguo/releases/latest)
[![Telegram](https://img.shields.io/badge/Telegram-Kmodify-blue?logo=telegram)](https://t.me/Kmodify)

👉 **[点击跳转 Release 最新版本下载](https://github.com/KEJIYUNB/hongguo/releases/latest)** 👈  
💬 **[点击加入 Telegram 交流频道](https://t.me/Kmodify)**

</div>

---

## 1. 项目简介

本项目（<span dir="ltr"><code>xyz.kejiyu.hongguo</code></span>）是一个针对红果短剧客户端的界面精简与体验增强模块。支持通过模块内置设置面板自由自定义多种沉浸式观影体验、手势拦截、自动最高画质及体验优化功能。

---

## 2. 当前适配版本

* **红果免费短剧（国内版 `com.phoenix.read`）**
  * `v7.3.3.18`
  * `v7.3.2.32`
  * `v7.3.1.32`
* **红果短剧（海外版 `com.phoenix.read.oversea.gp`）**
  * `v7.3.1.32`

---

## 3. 环境

- JDK 17
- Android SDK 36
- Gradle Wrapper 9.0
- Android Gradle Plugin 8.13.0
- Kotlin 2.1.0
- libxposed API 102

---

## 4. 功能特性

| 功能分类 | 功能说明 |
| :--- | :--- |
| **界面精简** | 隐藏系统状态栏（视频页沉浸显示） |
| | 隐藏顶部/底部导航栏、作品信息及右侧互动控件 |
| | 隐藏选集相关控件 |
| | 隐藏视频播放进度条 |
| | 隐藏系统手势导航小白条（三键导航不受影响） |
| | **禁用商城（默认启用）**：隐藏首页底部「商城」Tab，并阻止进入独立商城页面；保留首页、剧场、我的和全集选集 |
| | **应用内视频小窗（实验性，默认关闭）**：仅在主页面切换底部 Tab 时尝试将已播放的原视频容器移动到可拖动的小窗，不另建播放器。支持关闭和返回首页；不同版本的视频 Surface/生命周期可能导致停止或黑屏。 |
| | **系统媒体后台播放（实验性，默认关闭）**：在红果原进程内提供 Android MediaSession + MediaStyle（播放/暂停/进度/总时长/拖动跳转）。**刷剧首页与全集**统一使用当前 TTVideoEngine：在滑动切换视频时刷新元数据，在确认应用真正进入后台后尝试恢复首页被生命周期暂停的播放器（不影响应用内跳转）；从正在播放的视频界面提取剧名、集数与可用封面。无法确认的信息不猜测。无需悬浮图标或第二播放器。后台保活取决于红果原生解码器能否持续运行。 |
| | 暂停播放时临时恢复显示控件 |
| **播放与手势** | 自动选择当前视频支持的最高画质 |
| | 记忆与自定义默认播放倍速（0.75x ~ 3.0x） |
| | 双击屏幕动作替换为直接打开评论区（屏蔽默认点赞） |
| | 禁用下拉手势并折叠下拉刷新提示区域 |
| | 拦截屏幕顶部区域向下滑动手势 |
| **内容与账号** | 拦截广告层、金宝箱及悬浮挂件 |
| | VIP 状态与权益 Hook 解锁 |
| | VIP 图标及挂件相关显示控制 |
| **实验性与工具** | OLED 屏幕亮度防烧屏拦截 |
| | 系统通知栏快捷控制菜单（总开关、配置面板、重启应用） |
| | 自定义下载数量限制（单日集数、单日剧数、总缓存上限） |
| | 自动注入红果原生设置页模块快捷入口
| | 视频长按菜单中可添加 Xhongguo 设置入口（取决于原生菜单结构） |
| | 支持从 Xhongguo 模块 App 的“模块设置”页面直接配置全部功能（与原设置共用配置） |
| | **诊断 ZIP 导出**：模块主页和设置页均可一键导出；获得 Root 授权后读取红果近 5 个 Hook 日志、目标主进程的相关 logcat、启用状态和功能证据报告 `REPORT.txt`。Root 未授权时报告会标注未获得目标进程日志。 |

### 全集媒体集数（7.3.5.32）

系统媒体卡片优先从已加载的 `SaasVideoData` / `SaasVideoDetailModel` 获取全集剧名、总集数。当前视频 ID 不能直接匹配时，尝试从与全集列表数量一致的 ViewPager 当前页取得集数；无法安全确认当前集数时仍显示已确认的「全 N 集」，不会猜测「第几集」。已解析的总集数在同一全集页面里暂存，防止详情加载回调结束后丢失。诊断日志包括 pager 索引、页数、是否与剧集列表对齐，且不包含剧集 ID。

### 故障诊断

1. 在 KernelSU Next 中允许 Xhongguo 使用 Root（仅用于用户主动触发的诊断导出）。
2. 启用 Xhongguo 和红果的 LSPosed 作用域，启动红果并复现问题，例如播放 → 切后台 → 检查系统媒体卡片。
3. 返回 Xhongguo 模块主页，选择「导出诊断日志 ZIP」并保存。ZIP 内的 `REPORT.txt` 分别标记开关是否开启、Hook 是否有运行证据、是否遇到错误；仅看到安装日志不代表功能实际成功。
4. 导出的 Root / logcat 文件可能含设备环境和应用使用信息，分享前请查看。
 |

---

## 5. 项目结构

```text
.
├── app/
│   ├── build.gradle                   # App 模块构建脚本
│   └── src/main/
│       ├── assets/                    # Xposed / LSPosed 属性配置 (module.prop, xposed_init)
│       ├── resources/META-INF/xposed/ # libxposed 接口及 Scope 配置文件
│       └── kotlin/xyz/kejiyu/hongguo/
│           ├── MainActivity.kt        # 模块主界面 Activity
│           ├── SettingsActivity.kt    # 模块内独立设置页面
│           ├── ModuleSettingsStore.kt # 跨进程同步与配置迁移
│           ├── MainHook.kt            # Xposed Hook 入口类
│           ├── LogUtil.kt             # 日志记录与统计辅助类
│           ├── UpdateChecker.kt       # 模块更新检测服务
│           └── hooks/
│               ├── Hooks.kt           # 核心 Hook 业务逻辑与设置面板 UI
│               └── TargetNames.kt     # 目标版本混淆类名/方法名兼容映射表
├── gradle/
│   ├── libs.versions.toml             # Gradle 依赖版本管理
│   └── wrapper/                       # Gradle Wrapper 运行时文件
├── build.gradle                       # 项目根构建配置
├── gradle.properties                  # Gradle 全局属性配置
└── settings.gradle                    # 项目 Module 引入配置
```

---

## 6. 免责声明

> **⚠️ 注意与声明**
> 
> 本项目**仅供学习以及技术交流**，开发者不对此模块造成的任何后果承担责任。


## 7. 在模块 App 中配置

安装 APK、在 LSPosed 中启用并选择红果作用域后，直接打开桌面的 **Xhongguo**，点击 **打开模块设置** 即可修改全部功能。
设置保存到模块自身的私有配置，通过仅允许红果目标应用 UID 访问的 ContentProvider 同步到 Hook；无需先打开目标 App 的设置面板。旧版保存在红果数据目录中的配置会在第一次运行红果时尝试迁移；部分启动时 Hook 需要完全退出并重启红果才能生效。
