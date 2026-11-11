# Beta: 功能级诊断日志

## 目标

把“开关已启用 / Hook 是否存在 / 回调是否触发 / 原生操作是否完成 /
异步数据是否成功写入 / 失败原因”分开记录。不能把 Hook 安装成功当成
某个界面或播放器实际成功。

## 如何使用

1. 安装 beta Debug APK，确保 LSPosed 启用 Xhongguo 并勾选红果主进程。
2. Xhongguo → **诊断与日志**，按需打开 **详细诊断日志**。
   - 关闭（默认）：仍记录关键状态变化、异常和每个功能计数器的
     首次命中，重复计数约每 15 秒汇总一次。
   - 开启：计数器约每 3 秒汇总一次。建议仅在复现期间使用。
3. 强制停止并重新打开红果以便捕获 Hook 安装事件；重要的常驻功能
   在不重启时也会继续发出运行事件。
4. 逐项复现：主页滑动 → 进入全集 → 播到最后一集 → 下一部 →
   Home/最近任务切到其他 App → 小窗 → 返回 → 我的/设置/导航。
5. 回到 Xhongguo → **导出诊断日志 ZIP**。
   如果授权 KernelSU Next Root，导出器读取宿主日志和 logcat；
   没有 Root 时仍导出设置与设备信息，并注明缺少运行证据。

## 新增 ZIP 文件

| ZIP 路径 | 作用 |
| --- | --- |
| `REPORT.txt` | 原有 Hook/能力验证报告，末尾附加功能概要 |
| `feature/TRACE_SUMMARY.txt` | 各功能事件数量、异常数量、最新阶段 |
| `feature/TRACE_EVENTS.log` | 结构化事件时间线（截取最近 1400 条） |
| `feature/TRACE_ERRORS.log` | 失败、降级事件（最近 200 条） |
| `feature/FEATURE_MATRIX.txt` | 每项开关配置值、实际受总开关影响的状态、分类证据 |
| `feature/README.txt` | 字段解释 |
| `hook/`、`logcat/` | 原有宿主原始诊断来源（经过基本脱敏） |

**注意：** FEATURE_MATRIX 中 `categoryObserved=true` 仅表示同一类
功能出现了运行日志，不能证明某个具体开关功能成功。

## 事件格式与示例

```text
XH_TRACE feature=media stage=flow_start result=observed flow=7 seq=25 pid=1234 monoMs=150000 detail=reason=new-native-engine
XH_TRACE feature=media stage=resolution result=degraded flow=7 seq=26 pid=1234 monoMs=150350 detail=page=series,title=false,episode=false,series=false,posterUrl=false
XH_TRACE feature=artwork stage=commit result=ok flow=7 seq=27 pid=1234 monoMs=152200 detail=source=disk-cache,width=224,height=320
```

`flow` 关联同一次原生视频切换后的操作，不使用剧集 ID；
`seq` 只在当前进程内递增；`monoMs` 是设备启动后的单调时间。
`result` 包含 `started`、`observed`、`committed`、
`ok`、`fail`、`degraded`、`stale` 等。
`committed` 只表示对应操作已提交（例如 MediaSession 写入），
不保证通知一定被系统显示或视频真的继续播放。

## 覆盖功能

- 播放权与原生引擎：切换、播放/暂停指令、页面交接、旧引擎保护
- 首页和全集：可见项选择、视频身份验证、DTO、剧名、集数、封面
- MediaSession：媒体信息发布、通知创建、封面写入、控制与进度计数
- 后台：离开快照、真正切后台、恢复命令、验证结果
- 应用内小窗：Dock、页面交接、归位、控制、失败路径
- UI：状态栏、手势条、暂停控件、进度条/布局等功能计数
- 我的页面：配置策略、数据过滤、View 隐藏、恢复与 Compose 写入失败
- 菜单/商城：长按入口、购物 Tab 和导航拦截
- 画质、倍速、广告、会员相关 Hook、下载限制：配置与实际 Hook
  命中，已经存在的功能计数器统一归类
- Hook：能力探测、安装完成、安装失败、异常隔离
- 设置：开关变更、宿主同步与详细诊断模式

## 控制日志量与隐私

- 忽略 `inflate`、`scanTree` 等每帧通用计数，只汇总功能信号。
- 后台大量事件使用限流；新 `flow` 不受上个视频限流窗口影响。
- 不记录账号、手机号码、Cookie、Token、视频 ID、视频标题、
  具体封面 URL。结构化日志仅包含功能名称、布尔值、数量和阶段。
- 导出器的旧版 logcat/Hook 原文仍可能有个人或设备信息，
  分享 ZIP 前请检查敏感内容。
- 异常只记录异常类型，不直接把堆栈及原始消息写入结构化字段。
