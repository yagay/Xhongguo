# Xhongguo 通用 Hook 重构与兼容性契约

> 基线：用户提供的官方 `红果免费短剧(1).apk`，包名 `com.phoenix.read`，
> Manifest 版本 `7.3.5.32`，SHA-256
> `39862b53ee7512a6bbfa8d047cd84a0a28dce49879682f921c9c2ab907d40425`。
> 已检查全部 21 个 classes*.dex 文件。下表是**静态存在性证据**，并不声称真机上的行为全部已验证。

## 必须保持的版本选择规则

1. `TargetNames.namesFor()` 只接受 APK 的**精确版本号**映射；该映射所属的必要类
   不存在时进入 `AUTO`。不得扫描老版 holder 名称来猜测“最近似版本”。
2. `AUTO-CN-<真实版本>` / `AUTO-OVERSEA-<真实版本>` 使用全空混淆映射；
   `shortHolder`、`seriesPagerGetter`、静态资源 ID 均不得继承自旧版本。
3. `RuntimeCapabilities.inspect()` 每个进程初始化时按稳定类型和**方法签名**
   检查能力，并将结果写到诊断 ZIP。能力存在不等于 Hook 已安装，Hook 已安装
   也不等于最终 UI/播放器效果正确。
4. 只使用带明确语义、相对稳定的 Android/VideoShop/TTVideoEngine 接口；
   若内部业务 API 不满足预期，就跳过该功能并记录，不猜方法索引或旧字段。
5. 保留已知旧版精确匹配映射只服务于那几个**真实旧版 APK**，
   不得作为 7.3.5.32 或未来版本的兜底。

## 7.3.5.32 官方 APK DEX 核验结果

| 原生能力 | APK 中确认存在的入口 | 重构策略 |
|---|---|---|
| 首页播放、全集播放 | `VideoContext`, `LayerHostMediaLayout`, `PlayEntity` | 同一 `SystemMediaBridge`，按当前真正播放的引擎和页面解析 |
| 播放、暂停、进度 | `VideoContext.isPlaying/pause/getCurrentPosition/getDuration`；`TTVideoEngine` | 用视频 SDK，不新建解码器 |
| 全集当前集 | `ShortSeriesSingleFragment.getCurrentPosition/getDataList` | 验证列表、页索引与正在播放的视频 |
| 剧名、总集、正式封面 | `SaasVideoDetailModel.episodesId/episodesTitle/episodeTotalCnt/episodesCover/smallEpisodesCover` | 官方对象观察、ID 缓存、同剧换集保留海报 |
| 最高画质 | `VideoModel.getSupportResolutions`、`TTVideoEngine.setVideoModel/configResolution` | 按支持的 Resolution 排序并从模型获取 |
| 默认倍速 | `PlaybackParams.getSpeed/setSpeed`、`VideoContext.setPlayBackParams` | 通用播放器参数，首页/全集共用 |
| 状态栏、手势栏 | Android `Activity/Window/WindowInsets` | 不需要红果混淆类或静态资源 ID |
| 页面控件、进度条 | `View/ViewGroup/SeekBar/ProgressBar` | 根据视频页面、大小、位置和文本限定候选 |
| 应用内小窗 | `LayerHostMediaLayout`、`SurfaceView/TextureView` | 复用原生容器，只在真实视频节点上操作 |
| 长按菜单 | `Activity.dispatchTouchEvent`、`Dialog/PopupWindow` | 观察通用 UI 事件，避免猜旧版手势类 |
| 下载限制 | `SsConfigMgr.getABValueJson` | 按稳定配置服务绑定 |
| 暂停广告 | `SeriesPauseAdImpl` | 仅按存在的方法挂钩，不借旧版类 |
| 页面设置入口 | 原生 `SettingsActivity/KmpSettingsActivity` | 目标页存在；旧版 item 对象结构不可直接套用 |

## 目前尚不能宣称完全通用的功能

- **双击打开评论**：老代码依赖混淆的 `rightViewAgency` 和双击手势类。
  通用模式不会假装这些旧名字有效；必须从真实的可见评论控件和稳定动作接口建立安全绑定后才能视为完成。
- **OLED 亮度动作过滤**：旧实现依赖一个混淆 Action 类的精确类型。
  通用模式跳过未知 Action，不可粗暴拦截所有亮度调整或删除任意对象。
- **KMP VIP 模型等业务服务**：具体 KMP service 名称、构造参数和语义可能随 APK 更新。
  稳定类/方法可独立安装；未知模型绝不借用旧版构造器。
- **原生设置列表项目注入**：旧版 `pv5/qv5/mz5` item 模型及 click 监听
  不能跨版本沿用。通用模式优先保留模块 App 的独立设置页，不插入结构未经验证的原生项。
- **部分原生清屏/工具栏业务方法**：通过结构化视图扫描提供通用覆盖，
  但布局发生重大变化时可能找不到控件；应报告“未匹配”而非强制使用旧资源 ID。

## 联合功能回归（任何一次重构必须一起验证）

1. 启动 Xhongguo，确认日志 `目标运行模式=AUTO-CN-7.3.5.32` 且
   `legacyObfuscatedMappings=false`、`oldResourceIds=0`。
2. 打开红果首页：清屏、隐藏导航/状态栏、进度条、长按菜单、商城 Tab，
   逐个开关，确保关闭功能后 UI 可恢复。
3. 首页开始视频 → 通知媒体卡片 → 前后台切换 → 暂停/继续 →
   上下滑动更换作品；检查没有剧名或封面串集、没有重载造成的循环暂停。
4. 进入全集：播放、选集、进度、封面、倍速、画质、长按、系统媒体；
   切换多个集数，再到其他短剧；验证与首页同一套发布机制。
5. 测试应用内小窗、下载限制、广告开关、OLED、双击评论、原生设置入口、
   需要业务 Hook 的功能。区分“接口缺失”“Hook 已安装”“触发成功”；
   **不要把未验证功能记录成修复成功**。
6. 只运行 `:app:assembleDebug`，检查 CI Success 后再下载 APK 真机回归。
   对未来版本若稳定接口消失，应在诊断中报告缺失，不恢复老版 profile。

## 设计结论

“通用 Hook”代表优先使用稳定 SDK 和 UI 语义、每项单独发现和失败隔离；
**不代表红果无论怎样重写代码都永远兼容**。只要官方保留相应接口，
无需因为版本号变动手工重新映射混淆类。内部业务模型/控件行为发生实质变化时，
仍需要针对新的结构分析，但不允许再误用其他 APK 版本的映射。
