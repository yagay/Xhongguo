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
  * **`v7.3.5.32`：通用 Hook 模式（官方 APK 静态分析，真机效果需要逐项验证）**
  * 以后版本：自动尝试通用接口；不套用旧版混淆映射；缺失功能单独报告
  * 以下历史版本仅保留精确匹配，不作为新 APK 的兜底：
  * `v7.3.3.18`
  * `v7.3.2.32`
  * `v7.3.1.32`
* **红果短剧（海外版 `com.phoenix.read.oversea.gp`）**
  * `v7.3.1.32`（旧版精确映射）
  * 其他版本使用通用 Hook，不回退历史映射

**[通用 Hook 重构说明与全部功能验证矩阵](docs/UNIVERSAL_HOOKS.md)**

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
| | **系统媒体后台播放（实验性，默认关闭）**：在红果原进程内提供 Android MediaSession + MediaStyle（播放/暂停/进度/总时长/拖动跳转）。**刷剧首页与全集**统一使用当前 TTVideoEngine：在滑动切换视频时刷新元数据，仅在确认应用真正进入后台后对当前播放器执行单次生命周期暂停恢复（首页与全集共用，不影响应用内跳转，也不拦截用户主动暂停）；从当前播放器和剧集数据提取剧名、集数；**系统媒体图片仅使用红果官方剧集封面**（优先 `episodesCover`/`smallEpisodesCover`），禁止以 `episodeCover` 视频截图或播放页面 `ImageView` 替代。没有可靠海报时不显示错误图片。无需悬浮图标或第二播放器。后台保活取决于红果原生解码器能否持续运行。 |
| | 暂停播放时临时恢复显示控件 |
| **播放与手势** | 自动选择当前视频支持的最高画质 |
| | 记忆与自定义默认播放倍速（0.75x ~ 3.0x） |
| | 双击屏幕打开评论区（旧版精确映射可用；通用模式仍需验证实际评论操作接口） |
| | 禁用下拉手势并折叠下拉刷新提示区域 |
| | 拦截屏幕顶部区域向下滑动手势 |
| **内容与账号** | 拦截广告层、金宝箱及悬浮挂件 |
| | VIP 状态与权益 Hook 解锁 |
| | VIP 图标及挂件相关显示控制 |
| **实验性与工具** | OLED 屏幕亮度动作拦截（仅已验证目标 Action 的版本可用；通用模式不猜测混淆 Action） |
| | 系统通知栏快捷控制菜单（总开关、配置面板、重启应用） |
| | 自定义下载数量限制（单日集数、单日剧数、总缓存上限） |
| | 原生设置页模块快捷入口（依赖当前列表 item 结构，通用模式可使用独立模块 App 设置页） |
| | 视频长按菜单中可添加 Xhongguo 设置入口（取决于原生菜单结构） |
| | 支持从 Xhongguo 模块 App 的“模块设置”页面直接配置全部功能（与原设置共用配置） |
| | **诊断 ZIP 导出**：模块主页和设置页均可一键导出；获得 Root 授权后读取红果近 5 个 Hook 日志、目标主进程的相关 logcat、启用状态和功能证据报告 `REPORT.txt`。Root 未授权时报告会标注未获得目标进程日志。 |

### 全集媒体集数（7.3.5.32）

系统媒体卡片优先从已加载的 `SaasVideoData` / `SaasVideoDetailModel` 获取全集剧名、总集数。当前视频 ID 不能直接匹配时，尝试从与全集列表数量一致的 ViewPager 当前页取得集数；无法安全确认当前集数时仍显示已确认的「全 N 集」，不会猜测「第几集」。已解析的总集数在同一全集页面里暂存，防止详情加载回调结束后丢失。诊断日志包括 pager 索引、页数、是否与剧集列表对齐，且不包含剧集 ID。

### 系统媒体一致性约束（首页 / 全集）

首页和全集必须使用同一个 `SystemMediaBridge.resolveCurrentMedia` 解析及发布流程。页面差异只允许存在于数据来源：当前引擎关联的 `PlayEntity`、当前全集 `SaasVideoData` / `SaasVideoDetailModel`、焦点页面可见文字。两种模式共用同一 MediaSession、通知、进度、封面下载、异步防串图和后台暂停恢复。不得再次为某一页面单独添加直接写 MediaSession 的逻辑。

- 原生全集详情优先依据稳定的 `SaasVideoDetailModel` 对象获取，禁止再次硬编码失效的混淆 `uo4.y0.x`；缓存按实际剧集 ID 绑定，退出或切换剧集后不串图。
- 同一部短剧换集时保留剧名与正式海报，重新计算当前集数和视频时长；更换作品才清除旧媒体资料。
- 绝不将 `episodeCover` 视频帧或播放器页面截图当作正式海报。未找到官方封面时允许卡片不显示图片，不得显示错误剧集。
- 每次改动媒体系统必须联合回归：**首页播放 → 首页后台 → 返回首页 → 进入全集 → 换集 → 全集后台 → 返回全集 → 切换另一短剧**。确认播放/暂停、标题、当前集数、总时长、封面和系统媒体卡片。诊断的 `统一媒体信息采集`、`统一媒体元数据已绑定`、`已捕获官方全集详情`、`媒体封面已写入 MediaSession` 用于定位是哪一步缺失。编译成功不代表全部运行场景已通过。

### Beta 功能级诊断

`beta` 分支新增结构化功能事件（`XH_TRACE`），记录各功能配置、
Hook 能力/运行命中、播放切换、媒体封面、后台恢复、界面变化和异常。
`设置 → 诊断与日志 → 详细诊断日志` 可提高计数器汇总频率，
默认关闭时仍保留关键事件。

导出 ZIP 包含 `feature/TRACE_SUMMARY.txt`、
`feature/TRACE_EVENTS.log`、`feature/TRACE_ERRORS.log` 和
`feature/FEATURE_MATRIX.txt`，可以按功能排查但不能仅凭 Hook 事件
判断实际效果。参阅 **[Beta 功能诊断说明](docs/FEATURE_DIAGNOSTICS.md)**。

### 故障诊断

1. 在 KernelSU Next 中允许 Xhongguo 使用 Root（仅用于用户主动触发的诊断导出）。
2. 启用 Xhongguo 和红果的 LSPosed 作用域，启动红果并复现问题，例如播放 → 切后台 → 检查系统媒体卡片。
3. 返回 Xhongguo 模块主页，选择「导出诊断日志 ZIP」并保存。ZIP 内的 `REPORT.txt` 分别标记开关是否开启、Hook 是否有运行证据、是否遇到错误；仅看到安装日志不代表功能实际成功。
4. 导出的 Root / logcat 文件可能含设备环境和应用使用信息，分享前请查看。

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
│               ├── TargetNames.kt     # 精确版本匹配或完全空白的 AUTO 配置
│               └── RuntimeCapabilities.kt # 稳定 Hook 接口运行时能力检测
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
