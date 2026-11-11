# 我的页面精简（官方红果 7.3.5.32，通用入口）

## 需求与默认值

模块总开关启用时，以下入口 **默认全部隐藏/禁用**；每个选项独立配置。

| 设置组 | 入口 | 持久化开关键 |
| --- | --- | --- |
| 我的页面 · 快捷入口 | 福利 | `mine_hide_welfare` |
| 我的页面 · 快捷入口 | 订单 | `mine_hide_orders` |
| 我的页面 · 快捷入口 | 消息 | `mine_hide_messages` |
| 我的页面 · 快捷入口 | 游戏 | `mine_hide_games` |
| 我的页面 · 快捷入口 | 追更 | `mine_hide_following` |
| 我的页面 · 右侧菜单 | 我的钱包 | `mine_hide_wallet` |
| 我的页面 · 右侧菜单 | 会员中心 | `mine_hide_membership` |
| 我的页面 · 右侧菜单 | 游戏中心 | `mine_hide_game_center` |
| 我的页面 · 右侧菜单 | 创作中心 | `mine_hide_creator_center` |

勾选「隐藏」即删除页面上的该入口并禁止其点击；取消勾选则恢复该入口。不会卸载红果原生组件、删除订单数据或改变账号状态。

## 对 7.3.5.32 的 APK 分析

同一安装包包含原生 `HongguoMineFragment[V2]` /
`VariantMineFragment`，以及 `KmpHongguoMineFragment`。
后者和右侧侧边栏可由 Compose 渲染，不能仅靠
`TextView.setVisibility` 完整隐藏。

找到以下可复用语义边界：

- `com.dragon.read.kmp.mine.config.JgZoneItemId` 的枚举值
  `Gold / Order / Message / GameCenter / FollowUpdate`。
- KMP 我的页面数据模型中包含 `JgZoneItemId`，
  本模块按枚举值匹配而非按列表序号删除。
- `MineSidebarController` / `MineSidebarRepository` 接收或返回
  侧边栏条目 List，`SidebarPageDataManager` 接收原生菜单 RPC 数据。
- RPC `MyTabCellData` 提供 `myTabCellName` 与子项列表，
  按确切标题过滤目标条目，不删除其父栏目。
- 原生 Fragment 通过 `onCreateContent`、`onViewCreated`、
  `onVisible` 识别「我的」页面根视图；仅在这个范围隐藏旧版 View。
- 右侧弹出菜单中的原生 View 必须能观察到至少两个目标菜单标题，
  才按菜单范围进行过滤；避免误隐藏单独的「会员中心」卡片。

对于可能改变名字的混淆状态模型，使用类型和实际字段结构
校验。不存在匹配数据时不删除非目标项目。

## 设置保存与生效

九个开关存在 `ModuleSettingsStore.BOOLEANS` 白名单，通过
模块 App 和红果内设置面板同步到目标进程。

原生 View 开关更改后直接恢复/重新扫描。对于 Compose，
更改后可退出「我的」页面再进入或关闭/重新打开右侧菜单，
让原生数据重新生成；部分内容需要强制停止红果后重启。

## 功能验证

1. 全部保持默认隐藏：进入「我的」，确认福利、订单、消息、游戏、追更消失。
2. 点击右上角三横线：确认我的钱包、会员中心、游戏中心、创作中心消失，其余菜单保留。
3. 分别关闭九个隐藏开关，重新进入「我的」/打开菜单，验证只恢复对应入口，且能正常进入。
4. 重新开启九个隐藏开关，检查入口及点击区域消失。
5. 检查首页、剧场、视频详情、评论等其他页面没有误隐藏同名内容。
6. 如果项目仍显示，导出 Xhongguo 诊断 ZIP，检查日志：
   `mine page fragment scoped observers installed`、
   `mine sidebar native/KMP item boundaries installed`、
   `mine KMP typed JgZoneItemId state observers`、
   `我的页面精简: 数据入口已过滤`、
   `我的页面精简: 原生入口已隐藏`。
   有安装日志不代表运行数据已经命中。

## 安全边界

只针对设置指定的入口，不拦截其它页面的相同文字，也不
通过全局资源替换把短剧的「消息」「游戏」一类文本清空。
KMP Compose 的实际条目结构可能在后续版本变化，未观察到
对应数据源时不强行修改其布局结构。
