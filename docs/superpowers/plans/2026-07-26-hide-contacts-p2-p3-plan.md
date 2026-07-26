# 隐藏联系人 — P2 剩余项 + P3 调度器 实施计划

设计依据：`docs/superpowers/specs/2026-07-26-hide-contacts-overhaul-design.md`（第四章 P2、第五章 P3）。

## 已完成（不要重做）

- **P0** 全部 11 项缺陷修复（含 `DexDelegates.kt` 的 `isPlaceholder` 基础设施 bug）
- **P1** VoIP 重写：拒绝引擎改为 `mp5.q2.Qa()`、铃声改挂 `nq5.e.a`、通知/横幅挂 `q2.qa`、前台服务挂 `xp5.b.d`、三条通话记录插入路径、multitalk `v0.G`、以及 `nr4.y.z`（"已拒绝通话" 卡片）
- **P1 附带**：`hookNewMessageNotification` 的自触发递归（`markAsRead` → 存储回灌通知 → 无限递归 → 4 MB 栈耗尽 → 启动崩溃）已加线程局部重入保护
- **P2 第一批**（SQL 规则表）：新的朋友 `fmessage_conversation`、未读角标 `unReadCount > 0`、朋友圈消息列表 `SnsComment`
- 代码结构已拆为 `features/items/contacts/hidecontacts/`：`HideContactsSql.kt`（规则表 + FTS + 朋友圈信息流）、`HideContactsVoip.kt`

## Global Constraints

这些约束对**每一个**任务都生效，违反即为缺陷：

1. **禁止用 `try-catch` / `runCatching` 包裹 `hookBefore` / `hookAfter` 本身**（`AGENTS.md` 明文规定）。在 hook 体内部对单个反射调用用 `runCatching` 是允许的，且仓库已有先例。
2. **DexKit 委托必须声明在 `object HideContacts` 内部**，可见性 `internal`。委托靠 `PropertyDelegateProvider<BaseFeature, _>` 注册，无法移到独立文件。hook 安装体和纯逻辑用 `internal fun HideContacts.xxx()` 扩展函数放进 `hidecontacts/` 子包。
3. **每个新增匹配器都必须先在 `~/coding/wechat_8076/app/src/main/java` 里用 `rg` 验证唯一性**：字符串存在、且匹配器整体只命中一个方法/类。命中 0 个或 >1 个都会导致解析失败或挂错方法。把验证结果（`file:line` + 引用到的字符串）写进报告。
4. **确认被 hook 方法的真实签名**与 hook 体的假设一致（参数序号、参数类型、返回类型）。返回 `boolean` 的方法用 `result = false`，返回 `void` 的用 `result = null` —— hook bridge 不做基本类型转换，`result = null` 用在 boolean 上会拆箱 null。
5. **`allowFailure = true` 的委托，使用前必须检查 `isPlaceholder`**。注意：`isPlaceholder` 在本会话中刚被修好，占位符上访问 `.method` 现在会**抛异常**。**绝对不要**在某个匹配器的 matcher 块里引用另一个委托的 `.method`。
6. **判断是否隐藏统一用 `HideContacts.isHiddenNow(wxId)`**（它已包含 `temporarilyShown` 逻辑）。SQL 改写器用 `HideContacts.isTemporarilyShown` 整体早退。
7. **任何值列表都必须转义**：用 `HideContactsSql.kt` 里已有的 `Set<String>.toSqlList()`（单引号 + `''` 转义）。**空集必须早退** —— `NOT IN ()` 是 SQLite 语法错误，会打挂整个查询路径（P0 的 B2 就是这个坑）。
8. **警惕自触发递归**。hook 体里若调用会改动状态的方法，必须确认该改动不会重新触发同一个 hook。这是本会话最严重的一次崩溃的根因。
9. 仓库**没有单元测试**（`AGENTS.md`：manual testing on real WeChat only）。验证 = `./gradlew :app:compileStandardDebugKotlin` 通过 + 约束 3/4 的源码核实。**不要**新增测试框架。
10. 日志用 `WeLogger`，偏好用 `WePrefs.Companion.prefOption` 委托。注释和 KDoc 用英文，解释「为什么」而非「是什么」。
11. 每个任务完成后更新 `HideContacts` 的 `@Feature` description，加上本任务新覆盖的位置。

---

## Task 1 — 朋友圈内联评论/点赞

隐藏联系人在**共同好友**动态下的评论/点赞不在 `SnsComment` 表里，而在该动态自身的 `SnsInfo.attrBuf` → `SnsObject.CommentUserList` / `LikeUserList`，所以 P2 第一批的 `SnsComment` 规则覆盖不到。

目标：`fb4.z0.D0(...)` = `SnsUtil.snsInfoToSnsStruct`，`~/coding/wechat_8076/app/src/main/java/fb4/z0.java:589`。

DexKit 匹配：`usingEqStrings("snsInfoToSnsStruct", "com.tencent.mm.plugin.sns.data.SnsUtil", "mSnsInfo is null, why?")`

数据结构：
- `SnsObject`（`com/tencent/mm/protocal/protobuf/SnsObject.java:66-67`）有 `LinkedList LikeUserList` / `CommentUserList`
- 条目类型 `a65.ha6`：`f9152d` = Username、`f9153e` = Nickname、`f9156h` = Content、`f9160o` = ReplyUsername（经 `com/tencent/mm/plugin/sns/ui/widget/t2.java:119,164,171` 验证）

实现：`hookBefore`，取 `args[1] as SnsObject`，剔除 `CommentUserList` / `LikeUserList` 中 `f9152d`（或 `f9160o`）命中隐藏名单的条目，并相应递减 `CommentCount` / `CommentUserListCount` / `LikeCount` / `LikeUserListCount`。

**必须在克隆上操作**：`SnsInfo.cacheTimeLine`（`com/tencent/mm/plugin/sns/storage/SnsInfo.java:39`）缓存已解析对象，直接改会污染下次同步时的重新序列化。请先确认克隆方式（protobuf 有无 `clone()`/可重新解析），并在报告里说明选择理由。

字段名是混淆的且跨版本不稳定，优先按「声明类型 + 序号」取字段（仓库已有先例：`SplitGroupCall` 对 `ILinkMember`、`HideContactsVoip.readMultiTalkInvite` 对 `MultiTalkGroup`），不要硬编码 `f9152d` 这类名字。

放进新文件 `hidecontacts/HideContactsMoments.kt`，导出 `internal fun HideContacts.installMomentsHooks()`，在 `onEnable()` 里调用。

---

## Task 2 — 发现 tab「N 位朋友的新动态」头像与红点

8.0.76 是单个头像而非头像条。

写入点：`com.tencent.mm.plugin.sns.model.c3.H(c3, SnsObject)`，`com/tencent/mm/plugin/sns/model/c3.java:103-141`，其中 `:134` 执行 `j1.u().c().w(68377, snsObject.Username)`。

DexKit 匹配：`usingEqStrings("updateSyncDataCache", "com.tencent.mm.plugin.sns.model.NetSceneSnsSync", "preRdUsername", "updateSyncDataCache build previousRedDotInfo error", "isCoverPreRd")`

实现：`hookBefore`，若 `args[1]` 的 Username 命中隐藏名单则 `result = null`（跳过整个缓存写入）。这比改 UI 更安全，且同时消掉红点；KV 68377 的其他消费者（聊天列表横幅 `com/tencent/mm/ui/conversation/banner/z.java:44`、`+` 菜单 `com/tencent/mm/ui/rg.java:371`）自动受益。

先确认 `c3.H` 的真实签名与静态/实例属性（决定 `args` 的序号）。加入 Task 1 建立的 `HideContactsMoments.kt`。

---

## Task 3 — LightPush 通知绕过

`methodDealNotify`（`"jacks dealNotify, talker:%s, ..."`）是聊天消息通知的收敛点，但**有一条绕过路径**：`com.tencent.mm.booter.notification.e0.f(...)` 直接从 push 载荷弹通知并自行播放铃声/震动，**从不调用** `x.d`。

目标：`com/tencent/mm/booter/notification/e0.java:444`，调用方 `y11/g0.java:158`（tag `MicroMsg.NetPushSync`）。

DexKit 匹配（三个都在同一方法内，取其中足以唯一命中的组合）：
- `"notifyForLightPush push:isShake: %B, isSound: %B ctrlFlag:%s"`
- `"LightPush [NO NOTIFICATION] Util.isNullOrNil(userName) || Util.isNullOrNil(nickName)"`
- `"[+] LightPush [NO NOTIFICATION]already notify"`

实现：`hookBefore`，`args[1]`（userName）命中则 `result = null`。**先确认参数序号与真实签名**（设计文档记录为 `f(long msgId, String userName, String nickName, String content, String avatarPath, Map msgSource, j4 cmd)`）。

**必须确认进程归属**：这条路径可能运行在 `MicroMsg.proc.LightPushServiceImpl`（`lq1/q0.java`）而非主进程。`HideContacts` 当前未覆写 `shouldLoadInCurrentProcess`，因此只在主进程加载。

若确认它不在主进程：**不要**直接放宽 `HideContacts` 的进程集合 —— MMKV 是 `MULTI_PROCESS_MODE`，放宽后 `onEnable` 会在多个进程各跑一次，P3 的 AlarmManager 调度器会重复注册。请在报告里说明，把这一项标为 BLOCKED 交回决策，不要自行放宽。

---

## Task 4 — @成员选择器

`com/tencent/mm/ui/chatting/atsomeone/AtSomeoneLiveList.java:36` 的 `public List e(List snapshotList)` 与现有 `methodAddressMvvmListPreprocessList`（通讯录）**完全同构**。

DexKit 匹配：`declaredClass = "com.tencent.mm.ui.chatting.atsomeone.AtSomeoneLiveList"` + `usingEqStrings("snapshotList")`

条目类 `com.tencent.mm.ui.chatting.atsomeone.b`，联系人在字段 `f228756e`（类型 `com.tencent.mm.storage.y3`），username 走 `field_username`。

实现：现有通讯录 hook 体（`HideContacts.kt` 的 `methodAddressMvvmListPreprocessList.hookBefore`）的反射 `field_username` 查找已能处理该条目形状。**把两者抽成一个共享的 MvvmList 过滤函数**（含 P0 的 B6 空快照守卫），两处复用，不要复制粘贴。

放进新文件 `hidecontacts/HideContactsLists.kt`，导出 `internal fun HideContacts.installListHooks()`。

---

## Task 5 — 搜索补齐

**A. FTS 表清单。** `HideContactsSql.kt` 的 `FTS_SQL_REGEX` 目前覆盖 8 张表。缺失（表名生成于 `i23/a.java:403`，`"FTS5Meta" + t()`）：

| 表 | 类 | 处理 |
|---|---|---|
| `FTS5MetaChatroomMember` | `q23/a.java:48` | 加入正则 |
| `FTS5MetaWeShop` | `k15/m.java:38` | 加入正则 |
| `FTS5MetaAIHistory` | `wv4/h.java:40` | 加入正则 |
| `FTS5MetaAIHistoryChat` | `wv4/b.java:38` | 加入正则 |
| `FTS5MetaServiceNotify` | `q23/j.java:159` | **特殊，见下** |

`FTS5MetaServiceNotify`：`q23.j.P()`（`q23/j.java:112-135`）里 `aux_index` 是常量 `'notifymessage'`，包一层 `aux_index NOT IN (...)` 无效，必须改用 **`talker`** 列过滤（该表有额外的 `talker TEXT` 列）。

8.0.76 **不存在**朋友圈 FTS 表，朋友圈搜索无需处理。`FTS5MetaSOSHistory`（`q23/i.java:56-65`）只存搜一搜查询历史、无 talker 列，无需处理。

**B. 形状不匹配的查询。** 现有正则要求 `^SELECT FTS5MetaX.docid, type, subtype, entity_id, aux_index,`，以下 7 个任务用的是 `SELECT aux_index ...` / `SELECT member, chatroom ...`，完全匹配不到：`com.tencent.mm.plugin.fts.logic` 下的 `k0`、`n0`、`m0`、`s0`、`i`、`g`、`h`（分别对应 SearchChatroomByMemberTask / SearchChatroomInMemberTask / SearchChatroomCountTask / SearchCommonChatroomTask / SearchRelatedChatroomTask / SearchCommonChatroomTask / SearchCommonChatroomUserTask）。泄漏内容：隐藏的群出现在「共同群聊」、群聊计数虚高、隐藏联系人作为群成员建议出现。

实现：在 FTS hook 里加第二个改写器，匹配含 `FTS5ChatRoomMembers` 的 join，注入 `AND chatroom NOT IN (...) AND member NOT IN (...)`。

**C. 搜索群成员。** `com.tencent.mm.plugin.fts.logic.q0`（`q0.java:25,37,45`），字符串 `"SearchChatroomMemberTask"`、`"MMChatroomMember(%s) AS Offsets"`、`"SELECT memberlist FROM chatroom WHERE chatroomname=?;"`。FTS 部分匹配现有正则，但 `aux_index` 是**群名**而非成员，所以现有过滤只在群本身被隐藏时生效；成员是随后从 `memberlist` 解析并挂到 `j23.l.f336087e` 上的（`:57-116`）。实现：`q0.p(j23.v)` `hookAfter`，从结果里移除命中的成员条目。`logic.h`（SearchCommonChatroomUserTask）同理。

**D. 已知副作用（必须一并处理）。** 现有改写会给「在隐藏的聊天里搜索聊天记录」也加上 `aux_index NOT IN (...)`，导致 `temporarilyShown == false` 时打开隐藏聊天再搜索返回 0 条。处理：当查询已带 `aux_index = ?` 且绑定的是当前打开的 talker 时跳过改写。

A/B/D 改 `HideContactsSql.kt`；C 需要新委托，放 `hidecontacts/HideContactsSearch.kt`。

---

## Task 6 — 「N 位联系人」统计

视图 `com.tencent.mm.ui.contact.ContactCountView`（未混淆）；计数生产者 `com.tencent.mm.ui.contact.f1.run()`（`com/tencent/mm/ui/contact/f1.java:17-52`），按 `contactType` 分两支。

**A. `contactType == 1` — 通讯录「N 位联系人」** → `j4.O(false, ...)`（`com/tencent/mm/storage/j4.java:460-497`）

DexKit：`usingEqStrings("MicroMsg.ContactStorage", "getNormalContactCount, sql:%s, result:%d, includeBlack:%s, time:%d")`（`j4.java:495`）

**不能 SQL 改写**：该查询以 `" or username = 'weixin'"` 结尾（`j4.java:487`），追加 `AND` 会落进 OR 的右操作数内而失效。实现：`hookAfter` `j4.O`，从结果里减去隐藏名单中属于普通好友的数量。注意别把群聊/公众号算进去。

**B. `contactType == 2` — 群聊底部计数** → SQL 内联于 `f1.java:28`：
`select count(username) from rcontact where type & 1 !=0 and type & 32 =0 and type & 8 =0 and verifyFlag & 8 = 0` + `e01.e2.c(...)` 追加的 `" and ( 1 != 1 or username like '%@chatroom' ... )"`（`com/tencent/mm/storage/e01/e2.java:760-790` 或 `e01/e2.java`）。无尾部 OR、无 ORDER BY，可安全 SQL 改写。

实现：往 `WRAPPER_RULES` 加一条，匹配 `count(username) from rcontact` **且** `like '%@chatroom'`，注入 `rcontact.username NOT IN (...)`。

注意「仅聊天的朋友」（`OnlyChatContactMgrUI`）不受影响：其计数取自已过滤的 `j4.U(..., "@social.black.android", ...)` 游标（`com/tencent/mm/ui/contact/j7.java:130`），无需改动。

---

## Task 7 — 拍一拍 + 微信运动排行榜

两个独立的小改动，一起做。

**A. 拍一拍。** `nq3/l.java` = `PatMsgExtension`（tag `MicroMsg.PatMsgExtension`），插入方法 `yj(String talker, String fromUser, String pattedUser, String suffix, int createTime, long svrId)`（`nq3/l.java:560`）。

DexKit：`usingEqStrings("MicroMsg.PatMsgExtension", "insert pat msg %d %s %s")`（`:620`）

实现：`hookBefore`，`args[1]`（fromUser）命中则 `result = android.util.Pair.create(0L, 0L)` —— 这是该方法自身的 no-op 返回值（`:568`），**先核实**返回类型确实是 `android.util.Pair` 以及该 no-op 形式。同时消除消息行与对话置顶时间刷新。

**B. 微信运动排行榜。** 表 `HardDeviceRankInfo`，列 `rankID` / `username` / `score`。查询由 `String.format` 构建（`f42/c.java:40` 附近，形如 `select *, rowid from %s where %s = ? order by %s desc`），运行时经 `ka5.b0.g` 执行。

注意：反编译源码里表名是通过常量引用（显示为 `"n"`）传入 `String.format` 的，**必须先确认运行时真实表名**再写规则。存储类 `h42/d.java`（tag `MicroMsg.ExdeviceRankInfoStg`），管理器 `f42/c.java`（tag `MicroMsg.ExdeviceRankInfoManager`）。

实现：往 `WRAPPER_RULES` 加一条，匹配 `from <真实表名>` 注入 `username NOT IN (...)`。名次会重新编号，符合预期。若无法确认真实表名，标为 BLOCKED 并说明。

---

## Task 8 — 群成员列表

成员来自 `chatroom.memberlist` —— 一个 `;` 分隔的字符串（查询在 `com/tencent/mm/storage/a3.java:49`，解析在 `com/tencent/mm/storage/z2.java:360`），**无法 SQL 过滤**，必须在适配器层处理。

| UI | 适配器 | DexKit 锚点 | Hook 点 |
|---|---|---|---|
| `SeeRoomMemberUI`（查看全部群成员） | `com/tencent/mm/chatroom/ui/cc.java` | `"MicroMsg.SeeRoomMemberUI"` | `cc.d(List)`（`:96`） |
| `SelectMemberUI` / `SelectDelMemberUI`（@全体、删除成员、邀请） | `com/tencent/mm/chatroom/ui/kd.java` | `usingEqStrings("MicroMsg.SelectMemberAdapter", "null == item! position:%s, count:%s")` | 列表字段 `f93062i`（`List<bd>`，`bd.f92830a` 为 `y3`） |

**注意区分**：现有 `methodChatroomContactAdapterInitCursor` 相关注释提到的 `"MicroMsg.ChatroomContactAdapter"` 指向 `com/tencent/mm/ui/contact/s0.java`，那是**通讯录 → 群聊的群列表**，不是群成员列表。

加入 Task 4 建立的 `HideContactsLists.kt`。

---

## Task 9 — 收藏

表 `FavItemInfo`，发送者列 `fromUser`；存储 `q82/d.java`（tag `MicroMsg.Fav.FavItemInfoStorage`），主列表查询在 `q82/d.java:642`（方法 `ii(...)`）；适配器 `com/tencent/mm/plugin/fav/ui/adapter/c.java`（tag `MicroMsg.FavoriteAdapter`）。

**关键限制**：收藏插件部分已迁移到 WCDB ORM builder（`r82.e` / `br5.f`，见 `adapter/c.java:349-360`），**不产生可匹配的原始 SQL**。所以纯 SQL 改写只覆盖遗留路径。

实现：`hookAfter` `MicroMsg.FavoriteAdapter` 的 reset-data 方法（DexKit 锚点：`"on reset data list, do search, searchStr:%s, tagStr:%s, searchTypes:%s"`（`:344`）或 `"on reset data list, last update time is %d, type is %d"`（`:488`）），剔除 `field_fromUser` 命中的条目 —— 适配器层能同时覆盖 ORM 与原始 SQL 两条路径。

加入 `HideContactsLists.kt`。

---

## Task 10 — 视频号「朋友❤过」

`com/tencent/mm/plugin/finder/convert/r8.java` = `FinderFeedFriendLikeConvert.onBindViewHolder`（tag `Finder.FinderFeedFriendLikeConvert`，`:752`），读 `a65.je1` 的 `getString(5)` = wxUsername（字段序号：`0`=nickName、`1`=headImgUrl、`5`=wxUsername、`11`=finder_username，见 `a65/je1.java:6`）。

列表 UI `FinderFriendLikeFeedUI`（tag `Finder.FinderFriendLikeFeedUI`），加载器 `com/tencent/mm/plugin/finder/feed/gb.java`。

实现：在加载器/convert 层过滤。Finder 用网络驱动的 feed loader + 内存模型，没有可查询的表。

**范围外**：Finder 关注列表（`Finder.FinderFollowListUIC` 等）以 Finder 身份 `v2_...` 为键，无法映射到 wxid —— 记为已知限制，不要尝试实现。

加入 `HideContactsLists.kt`。

---

## Task 11 — P3 调度器（数据模型 + 触发）

用户已确认的语义：单一列表，每条是一个「闹钟」；「显示」= 切换现有 `temporarilyShown`（**不改写隐藏名单**）。

```kotlin
@Serializable
data class HideSchedule(
    val id: String,                  // 稳定标识, 用作 AlarmManager requestCode 与列表 key
    val enabled: Boolean = true,
    val action: Action,              // HIDE | SHOW
    val kind: Kind,                  // REPEATING | ONCE
    val minuteOfDay: Int = 0,        // REPEATING: 0..1439
    val daysOfWeek: Set<Int> = ALL,  // REPEATING: Calendar.SUNDAY..SATURDAY, 默认全选
    val atEpochMillis: Long = 0L,    // ONCE: 完整日期时间
)
```

`ONCE` 触发后自删。

**持久化**：JSON 字符串存 `WePrefs`，遵循既定套路 —— `prefOption(key, "")` + kotlinx.serialization + `DefaultJson`（`app/src/main/java/dev/ujhhgtg/wekit/utils/serialization/JsonUtils.kt:13`）+ `runCatching` 降级为空列表。参考实现：`features/items/chat/ChatToolbar.kt:229-247`。

**触发**：AlarmManager 精确闹钟。微信自身 manifest 已声明 `USE_EXACT_ALARM`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`，模块运行在微信进程内直接继承，**无需引导用户授权**。每条启用项注册一个 `setExactAndAllowWhileIdle`，`PendingIntent` 携带条目 id；广播接收器执行动作后为 `REPEATING` 条目重新注册下一次。列表增删改后整体 `resync()`（取消全部已注册闹钟 → 按当前列表重注册），协调模型参考 `app/src/main/java/dev/ujhhgtg/wekit/agent/trigger/TriggerScheduler.kt:46-60`。

**启动补偿**：`onEnable()` 时扫描全部条目，找出「当前时刻之前最近一次应当触发的时间点」并直接应用其动作。`REPEATING` 在过去 7 天窗口内按 `daysOfWeek` 回溯；`ONCE` 若 `atEpochMillis <= now` 则应用并删除。

⚠️ `onEnable()` 在进程 attach 极早期被同步调用（`FeaturesLoader.loadFeatures()` → `WeLauncher.init()` → `StartupAgent.startup()`，见 `loader/startup/StartupAgent.kt:64`），此时**尚无 Activity**。补偿逻辑只能改内存状态与调 `WeConversationApi.reloadConversations()`，**不得弹 Toast 或触碰 UI**。

**与手动切换的关系**：调度器只在触发时刻**写入** `temporarilyShown`，不锁定它。用户随时可用 `#show` / `#hide` / 三击标题手动改变，该状态保持到下一个触发点或再次手动切换。补偿只在进程启动时应用一次。

**生命周期**：随 `onEnable()` 注册、`onDisable()` 取消全部闹钟，功能关闭后不留任何已注册的 `PendingIntent`。

**时间计算**：沿用仓库约定 —— `java.util.Calendar` 做日历字段运算（`TriggerScheduler.kt:112-122` 的 DAILY 分支即「minuteOfDay → 下一个 epoch millis」的现成实现），`kotlin.time` 做时长。仓库无 `kotlinx-datetime`。

`temporarilyShown` 目前是 `HideContacts` 的 `private var`，需要一个 `internal` 的写入入口供调度器使用（注意不要绕过 `WeConversationApi.reloadConversations()`）。

放进 `hidecontacts/HideContactsSchedule.kt`。

---

## Task 12 — P3 调度器 UI

在现有设置对话框（`HideContacts.onClick`）里加一个入口，点击进入定时列表的 CRUD 界面。

**复用现成组件，不要自己写时间选择器**：
- `WeTimeOfDayField(minuteOfDay, onMinuteChange, label, ...)` — `app/src/main/java/dev/ujhhgtg/wekit/ui/content/WeDateTimeField.kt:105`，用于 REPEATING 的时分
- `WeDateTimeField(value, onValueChange, label, mode = WeDateTimeMode.DATE_TIME)` — 同文件 `:46`，用于 ONCE 的完整日期时间
- `formatMinuteOfDay` / `parseMinuteOfDay` / `formatDateTime` / `parseDateTime` — 同文件 `:132,138,146,152`
- 对话框壳：`showComposeDialog` + `AlertDialogContent` + `DefaultColumn`（AGENTS.md 要求用 `AlertDialogContent` 而非 `AlertDialog`）

列表增删改的交互形态参考 `features/items/chat/ChatToolbar.kt:741-860`（`showQuickReplyConfig`）：`mutableStateList` 草稿 + 头部「添加」按钮 + 每行点击进入编辑 + 尾部删除 + 确认时保存。

星期选择用 7 个可切换 chip（默认全选）。每行摘要形如 `每天 22:00 · 隐藏` / `周一 周三 09:30 · 显示` / `2026-08-01 12:00:00 · 隐藏（单次）`。

保存后必须触发 Task 11 的 `resync()`，否则改动要等下次启动才生效。

放进 `hidecontacts/HideContactsScheduleUi.kt`。

---

## 记为已知限制（不要实现）

| 面 | 原因 |
|---|---|
| 搜一搜聊天记录卡片 | 服务端 cgi 1532（`com/tencent/mm/plugin/websearch/o.java`），本地无可过滤数据 |
| 视频号关注/聚合列表 | 以 `v2_...` Finder 身份为键，无 wxid 映射 |
| 撤回提示 / 入群退群系统消息 | type 10000 消息，`content` 是已本地化的**昵称**文本而非 wxid，只能做昵称匹配，易误伤 |
| 支付/转账账单 | 服务端渲染的 WebView/CGI 列表（选择器本身已被覆盖） |
| 群聊中隐藏其消息 | 用户明确决定不做 |
