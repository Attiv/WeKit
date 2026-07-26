# 隐藏联系人 (HideContacts) 全面增强 — 设计文档

**日期**: 2026-07-26
**目标版本**: WeChat 8.0.76 (兼容 8.0.65–8.0.76)
**现有实现**: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/HideContacts.kt` (859 行)

## 背景

现有 `HideContacts` 覆盖 7 个隐藏位置（首页对话列表、通讯录联系人&群聊列表、首页搜索、锁屏关闭聊天、摇一摇关闭聊天、朋友圈信息流、联系人选择页）。用户反馈「自动拒绝音视频通话」无效，并要求扩大覆盖面、新增定时自动隐藏/显示。

本设计基于对 WeChat 8.0.76 反编译源码 (`~/coding/wechat_8076`) 的六路并行调查，全部结论均有 `file:line` 与可用于 DexKit 匹配的字符串佐证。

## 范围决策（已确认）

| 项 | 决定 |
|---|---|
| 推进方式 | 分阶段，每阶段停下来验证 |
| 修复 `DexDelegates` 基础设施 bug | **做** |
| 拆分为 `hidecontacts/` 子包 | **做** |
| 「群聊中隐藏其消息」子开关 | **不做** |
| 免打扰状态还原（取消隐藏时 un-DND） | **不做**（保持现有强制免打扰行为） |
| 「自动显示」语义 | 切换现有 `temporarilyShown`，不改写隐藏名单 |
| 定时器可靠性 | AlarmManager 精确闹钟 + 进程启动补偿 |

---

## 一、关键架构事实

### 1.1 SQL 单一收敛点

`ka5.b0.g(String sql, String[] args, int) -> Cursor` (`ka5/b0.java:1009`) 是 WeChat 全部 SQLite 读取的收敛点：

- `ka5.b0.B(sql, args)` (`ka5/b0.java:114`) 即 `g(sql, args, 0)`
- 所有 `com.tencent.mm.storage.*` 存储类（`ConversationStorage l4`、`ContactStorage j4`、`FMessageConversationStorage n7`、`SnsCommentStorage w1`、`FavItemInfoStorage q82.d`、`HardDeviceRankInfoStg h42.d`）均经此路径

WeKit 现有 `methodSqliteWrapperRawQuery`（`usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")`）已解析到该方法。**新增的绝大多数 SQL 层过滤只需往规则表里加条目，无需新 DexKit 目标。**

> 注：文件内注释写的 `d95.b0.f` / `com.tencent.mm.storage.m4.A/B` 在 8.0.76 已变为 `ka5.b0.g` / `l4.A/B`。字符串匹配器仍然有效，仅注释过时。

绑定参数与 SQL 分离传递，因此字面量 `NOT IN ('...')` 注入在所有位置都安全。

### 1.2 委托声明约束

`by dexMethod {}` 通过 `PropertyDelegateProvider<BaseFeature, _>.provideDelegate(item: BaseFeature, _)` 注册到 `BaseFeature._dexDelegates`（`BaseFeature.kt:77`）。**因此全部 dex 委托必须声明在 feature object 内部，不能移到独立文件。** 但 hook 安装体与纯逻辑可以通过 `internal fun HideContacts.xxx()` 扩展函数移出。

### 1.3 进程模型

`HideContacts` 是 `object : ClickableFeature()`，未覆写 `shouldLoadInCurrentProcess`，因此仅在 `com.tencent.mm` 主进程加载。调度器无重复触发风险。

**例外**：LightPush 通知（见 3.4）可能运行在 `MicroMsg.proc.LightPushServiceImpl` 进程，需单独确认进程归属。

### 1.4 AlarmManager 权限

WeChat 自身 manifest 已声明（`~/coding/wechat_8076/app/src/main/AndroidManifest.xml`）：

- `USE_EXACT_ALARM` (:260)
- `SCHEDULE_EXACT_ALARM` (:321)
- `RECEIVE_BOOT_COMPLETED` (:203)

模块运行在微信进程内，直接继承这些权限，无需引导用户授权。

---

## 二、P0 — 现有实现缺陷修复

### B1. `DexMethodDelegate.isPlaceholder` 恒为 false（基础设施，全仓库影响）

**位置**: `dexkit/dsl/DexDelegates.kt:241` 与 `:259-261`

```kotlin
val isPlaceholder
    get() = descriptor != null &&
            descriptor!!.name == "Lcom/tencent/mm/ui/LauncherUI;->getInstance()Lcom/tencent/mm/ui/LauncherUI;"
```

`DexMethodDescriptor` 构造器（`dexkit/DexMethodDescriptor.kt:21`）将 `name` 解析为 `desc.substring(a + 2, b)`，即 `"getInstance"` —— 而非完整描述符。比较恒为 `false`。

`method` getter 的守卫（`:241`）比较的字符串 `"Lcom/tencent/mm/ui/LauncherUI;->()Lcom/tencent/mm/ui/LauncherUI;"` 连方法名都缺失，同样恒为 `false`。

`DexClassDelegate.isPlaceholder` (`:82`) 与 `DexFieldDelegate.isPlaceholder` (`:164`) 比较的是原始字符串，**正确**，无需改动。

**影响**: 全仓库约 20 处 `methodXxx.isPlaceholder` 守卫失效（`ConversationAggregation`、`PipVoip`、`MarkdownRendering`、`WeConversationApi`、`WeMessageApi` 等）。DexKit 解析失败时不走降级分支，而是去 hook 零参的 `LauncherUI.getInstance()`。

落到 HideContacts：`HideContacts.kt:690` 的 `hookNewMessageNotification` 会对空 `args` 取下标，每次 `LauncherUI.getInstance()` 调用都抛 `ArrayIndexOutOfBoundsException`。

**修复**: 引入统一的 `PLACEHOLDER_METHOD_DESCRIPTOR` 常量，两处均改为比较 `descriptor!!.descriptor`（即 `toString()`）。

**风险**: 修复后此前被掩盖的解析失败会真正走降级分支或报错，可能暴露其他功能的既有问题。这是期望行为，但需在阶段验证时留意日志。

### B2. FTS 改写缺空集守卫 → `NOT IN ()` 语法错误

**位置**: `HideContacts.kt:322-332`

`onQuery` (`:501`) 与 `rewriteConversationListSql` (`:551`) 都有 `hidden.isEmpty()` 早退，唯独 FTS 分支没有。功能启用但未配置隐藏联系人时，`hideValueText` 为空字符串，生成 `... WHERE aux_index NOT IN ();` → SQLite 语法错误 → **全局搜索完全失效**。

**修复**: 补 `isEmpty()` 早退。同时将双引号 `"$it"` 改为单引号 + `''` 转义，与同文件另外两处改写一致（SQLite 会优先把 `"x"` 解析为标识符，仅在失败时回退为字符串字面量）。

### B3. `methodVoipBubbleHelperInsertMsg` 匹配到 0 参合成方法

**位置**: `HideContacts.kt:845-849`，hook 体 `:396-401`

`usingEqStrings("MicroMsg.VoIPBubbleHelper", "insertMsg() called with: voipInfo = ")` 唯一命中 `com/tencent/mm/plugin/voip/model/b2.java:91`，但其外层方法是匿名 `Runnable` `b2$$a.run()`（见 `b2.java:65-67`），**零参数**。`args[0] as String` 必然抛 `ArrayIndexOutOfBoundsException`。

8.0.69 / 8.0.74 形状相同，即此 hook 从未正常工作过（因为老栈很少被走到而被掩盖）。

**修复**: 见 P1 第 6 项 —— 三条消息插入路径统一重做。

### B4. `result = null` 用于 `boolean` 返回值

`IHookBridge.IMemberHookParam.result`（`loader/abc/IHookBridge.kt:24`）与 Zygisk 运行时（`loader/entry/zygisk/ArtHookBridgeRuntime.kt:57-84`）都不做基本类型强制转换：

- `methodVoipAcceptIncomingCall` → `n.a(b57)` 返回 `boolean` (`n.java:78`)
- `methodVoipServiceExSetInviteContent` → `d3.p(b57)` 返回 `boolean` (`d3.java:737`)

**修复**: 改为 `result = false`。（`methodVoipStartAcceptVoip` → `c0.A` 是 `void`、`VoipForegroundService.onStartCommand` 正确返回 Int，无需改动。）

### B5. `looksLikeContactSelectorQuery` 过于宽松

**位置**: `HideContacts.kt:600-605`

WeChat 的列表查询与单行 getter 使用**同一份列清单**，因此以下查询也被注入了 `NOT IN`：

- `j4.p(long rowid)` — `... from rcontact where rowid=<n>` (`j4.java:1073`)，无 ORDER BY → 变成 `where rowid=N AND rcontact.username NOT IN (...)` → **隐藏联系人的 `getContactByRowId` 返回 null**
- `j4.e0(String)` / `j4.v(String)` — `where username=X or encryptUsername=X` (`j4.java:686-689`, `:1305`)。AND 优先级高于 OR，按 `username` 查仍可用，但**按 `encryptUsername` 查静默失败**

**修复**: 收紧启发式 —— 追加要求 `" order by "` 或 `"showhead asc"`，并对 `where rowid=` / `encryptusername=` 显式早退。

### B6. `contacts[0]!!` 无空表判断

**位置**: `HideContacts.kt:250`

`AddressLiveList.e()` 是通用 MvvmList 预处理器，初次加载或筛选为空时会收到空快照 → `IndexOutOfBoundsException`。按 `AGENTS.md`，hook 体不允许失败也不允许 try-catch 包裹。

**修复**: `if (contacts.isEmpty()) return@hookBefore`。

### B7. 广播接收器注册/注销上下文不匹配

**位置**: 注册 `HideContacts.kt:202`，注销 `:442`

- 在 `methodDoOnCreate.hookAfter` 内用 Activity context 注册；`ScreenOffReceiver` 是单例 `object`，每次 LauncherUI 重建都会重复注册
- `onDisable` 用 `HostInfo.application` 注销 —— 不同 Context，抛 `IllegalArgumentException`，被 `runCatching` 吞掉 → **功能关闭后接收器仍存活，继续在锁屏时把用户踢出隐藏聊天**
- IntentFilter 加了 `ACTION_USER_PRESENT` (`:200`) 但 `onReceive` (`:99`) 只处理 `ACTION_SCREEN_OFF`，是死条目

**修复**: 统一用 `HostInfo.application` 注册；用布尔标志保证只注册一次；移除 `ACTION_USER_PRESENT`。

### B8. 标题点击监听器无条件安装

**位置**: `HideContacts.kt:210-219`

无论 `tripleClickTitle` 偏好是否开启都调用 `titleView.setOnClickListener {}`，仅在 lambda 内部提前 return（`:211`）。这会替换掉 WeChat 原有的标题监听器，导致其行为在选项关闭时也失效。

**修复**: 仅在偏好开启时安装；保存并在自身逻辑未消费手势时链式调用原监听器。

### B9. `hiddenContacts` setter 的免打扰行为（部分修复）

**位置**: `HideContacts.kt:87-95`

`WeConversationApi.setDnd(convId, true)` 触发 WeChat 的 `OpenImOpLogLogic` 静音 oplog —— 这是**服务端同步**状态。

按范围决策，**不实现取消隐藏时的还原**。但保留一处纯粹的缺陷修复：当前每次保存都会对**已经隐藏**的每个联系人重发 oplog，且同步执行在对话框回调的 UI 线程上。

**修复（有限）**: 仅对**新增**的联系人发送 DND oplog；改为在后台线程执行。用户可见语义不变。

### B10. `hiddenPositions` 位置重映射 — 删除

**位置**: `HideContacts.kt:268-311`，状态 `:668`

三个独立缺陷：

1. `MutableIntSet.forEach` 按开放寻址哈希槽顺序遍历，既非插入序也非升序；而 `if (actualPos >= it) actualPos++` 的移位算法**仅对升序遍历正确**。隐藏 `{0,2}`、请求 `1` 时：升序 → `2`→`3`（正确）；降序 `{2,0}` → `2`（错误）
2. 只重映射了 `getView`，未重映射 `getItem` / `getItemId`。`ChatroomContactUI` 的点击监听器（`com/tencent/mm/ui/contact/c1.java:54`）用**可见位置**调 `getItem` 再取 `Chat_User` → **点击隐藏项之后的任意行会打开错误的聊天**
3. `temporarilyShown` 时 `:269` 在 `clear()` (`:278`) 之前 return，且 `getCount`/`getView` hook 均不检查该标志 → `#show` 之后仍在错误地缩减与移位

**当前是死代码**：`j4.y` 的游标 SQL（`j4.java:1337`）同样匹配 `looksLikeContactSelectorQuery`（含 `from rcontact` + `quanpin`），已在 SQL 层过滤，`hiddenPositions` 恒为空。

**修复**: 整段删除。SQL 层已完整覆盖该场景，保留一个逻辑错误的降级路径只会把「功能失效」变成「打开错误联系人」。

### B11. `pendingVoipUser` 竞态与泄漏

**位置**: 声明 `HideContacts.kt:657`，写入 `:340/351/361/374/408`，消费 `:414-419`

- 普通 `var`，由 VoIP binder/worker 线程写、由音效播放 worker（`rv5.t0` 调度器）读，无 `@Volatile` 无同步
- 除 `playSound` 触发外从不清除。若提示音未播放（通话取消、静音、走了隐藏路径），标志会残留并吞掉**下一通无关来电**的提示音
- 五个不同 hook 为同一逻辑通话写入同一个标志

**修复**: 随 P1 的 VoIP 重写一并移除该状态机（新的铃声抑制直接按 wxid 判断，不需要跨 hook 传递状态）。

---

## 三、P1 — VoIP 重写

### 3.1 根因

8.0.76 的 1:1 来电**完全经由 VoIPMP / ILink 原生核心**投递：

```
网络推送 (hp5.m.L0 / hp5.t.yi / kr4.b0 case 12)
  → qq5.j.b9(msgSubType, newxml, ts, username, notifyFrom)      mp5/q2.java:618
  → com.tencent.mm.plugin.voipmp.platform.v0.z(...)              v0.java:159
  → ZIDL_r9_sVJknK.ZIDL_CCV(...)                                 [native]
  → ZIDL_ibmKH7hbMB.ZIDL_FBV(...)                                ZIDL_ibmKH7hbMB.java:372
  → qq5.j.qa(ctx, roomId, roomType, ..., username, members, isSubCall)   mp5/q2.java:1075
  → 协程 mp5.p1 → 分叉：
       A. 后台 → mp5.l1 全屏/横幅通知 (notification id 41)
       B. 前台 → mp5.r0.A → sp5.m.x 浮窗卡片 + qq5.j.Ri 铃声
```

现有自动拒绝挂在 `MicroMsg.Voip.VoipServiceEx` 的 `setInviteContent`/`reject` 上，属于**老 v2protocal RUDP 栈**（`com.tencent.mm.plugin.voip.model.d3`/`h2`/`c0`），仅当对端降级旧协议时才进入（`kr4/o.java:118` 的 `checkUseNew` 判定）。正常 8.0.7x 之间通话**永不触发** → 从不发出拒绝包。

### 3.2 正确的拒绝入口

```
mp5.q2.Qa()                                     mp5/q2.java:380 — 无参, void
    Log.i("MicroMsg.VoIPMP.CoreV2", "rejectByShortCut");
    launch(mp5.n0)   // 在 r0.p() 守卫之外, 总会执行
mp5/n0.java:
    sp5.h.b(r0.f371844n, false, 1, null);        // 关闭浮窗卡片
    v0.f(false, mp5.m0.f371773a);                // ← 真正的拒绝
        → ZIDL_r9_sVJknK.ZIDL_HBV(...)           [native CallHangup]
```

这正是微信自身蓝牙快捷拒接使用的调用；浮窗卡片的拒绝按钮（`sp5.m.r()` → `mp5.l0.a(Hangup)` → `mp5/h0.java`）也汇入 `v0.f`。

DexKit 匹配器：

```kotlin
matcher {
    usingEqStrings("MicroMsg.VoIPMP.CoreV2", "rejectByShortCut")   // 全 APK 唯一
    paramCount = 0
    returnType("void")
}
```

**时序约束（关键）**: `v0.f()` 只能拒绝原生核心已知晓的房间。因此：

- 在 `hp5.m.L0` / `q2.b9` 处杀掉邀请 → 对方毫不知情、一直响到 60 秒超时，产生未接来电记录。适合「纯隐藏」，**不适合自动拒绝**
- 拒绝必须发生在 `ZIDL_FBV` **之后**。现有的 `hookBefore { result = null }`（`HideContacts.kt:337`）跳过了 `f3.f371733a.y2(j17)`（欠着原生的 ZIDL 完成回执）与 `r0.f371852v/f371853w/f371854x/f371855y` 的状态写入，使 `r0.p()` 恒为 false、CoreV2 半初始化，**导致之后任何拒绝都是 no-op**

**方案**: `methodVoipLaunchIncomingCardAsync` 由 `hookBefore` 改为 `hookAfter`；命中隐藏联系人时 post 一个零延迟任务调用 `Qa()`。

### 3.3 铃声

现有 `methodVoipPlaySound`（`"MicroMsg.RingPlayer"` + `"playSound, type: %s, changeStreamType: %s, shake: %s"`）唯一命中 `nq5/f.java:45`，但外层是 `nq5/f.java:25 run()`，数据源为 `k0Var.b("playend")` —— 这是**通话结束提示音**，不是来电铃声。隐藏联系人来电时手机照常响铃。

真正的入口：

```
nq5.e.a(String username, boolean videoCall, boolean outCall, long ts, boolean speakerOn)   nq5/e.java:67
    usingEqStrings("MicroMsg.VoIPMPRingtoneController", "startRing() called with: username = ")
    args[0] = wxid
  → py3.u.kj(py3.i.ILINK_VOIP, Bundle{scene="start", username, ...})   py3/u.java:67
```

下游的 `py3.u.kj` 正是 `BlockVoipRingtone` 已经 hook 的位置，bundle 里已带 `username`。

**方案**: hook `nq5.e.a`，`args[0]` 命中隐藏名单则 `result = null`。删除 `methodVoipPlaySound` 及其 `pendingVoipUser` 消费逻辑。

### 3.4 通知与前台服务

| 目标 | 匹配字符串 | 说明 |
|---|---|---|
| `q2.qa(Context, int, is4.r, long, long, String, ArrayList, boolean)` | 声明类由 `usingEqStrings("MicroMsg.VoIPMP.Launcher", "closeReceiverBanner")` 定位，再 `paramCount = 8` | `args[5]` = wxid。取消它同时杀掉横幅**与**通知分支 |
| `xp5.b.d(String username, boolean, boolean, boolean)` | `usingEqStrings("MicroMsg.VoIPMPVoIPNotificationHelper", "startFGS isBindVoIPForegroundService ")` | `args[0]` = wxid。比现有 `VoipForegroundService.onStartCommand` + `stopSelf()` 更早、更安全 |

现有 `VoipForegroundService.onStartCommand` hook **确实会触发**（`VoipNewForegroundService extends VoipForegroundService` 且未覆写 `onStartCommand`，`"Voip_User"` extra 存在）。但在 Android 12+ 对一个正在 `startForeground` 的服务调 `stopSelf()` 有 `ForegroundServiceDidNotStartInTimeException` 风险，应改为在 `xp5.b.d` 处抑制。

`methodDealNotify`（`"jacks dealNotify, talker:%s, ..."`）是聊天消息通知专用，**不覆盖**来电通知（`mp5/l1.java:42`，notification id 41）。

### 3.5 通话记录消息插入 — 三条路径

| # | 目标 | 匹配字符串 | 覆盖 |
|---|---|---|---|
| A | `q2.Ii(String toUser, boolean, int, long, long, long, int)` `mp5/q2.java:269` | `usingEqStrings("MicroMsg.VoIPMP.Launcher", "insertMsg() called with: toUser = ")` | **8.0.76 主路径** —— VoIPMP 本地插入（未接听/已取消/通话时长）。`args[0]` = wxid |
| B | `j0.j(String content, a65.j4 addMsg)` `com/tencent/mm/plugin/voip/model/j0.java:130` | `usingEqStrings("MicroMsg.VoIPBubbleHelper", "handlerBubbleMsg: parse bubble info error")` | 服务端下发的 `<voipmsg>` 气泡（type 50） |
| C | `b2.d(String talker, ...)` `b2.java:63`，8 参 | 声明类由 `usingEqStrings("MicroMsg.VoipPluginManager", "insertRecallTip() called with: talker = [")` 定位 + `paramCount = 8` | 老栈。**不可**用 `voipInfo` 字符串匹配（那些字符串在 0 参的 `b2$$a.run()` 里，即 B3） |

### 3.6 多人通话 (multitalk)

由 `RepairerConfigMultiTalkSwitchVoIPMPSwitch`（`mp5/u1.java`，`"isMultitalkChangeToVoipMP: "`，经 `qq5.j.Wa()` 暴露）决定走哪条路：

- **`Wa() == true`** → 多人通话也走 VoIPMP，`roomType == MP_ROOM_TYPE_MULTI_TALK`，上述同一套 hook 全覆盖
- **`Wa() == false`** → 经典 ILink 多人通话：

```
fk3.u.L0(p0)                              fk3/u.java:32
    "MicroMsg.SubCoreMultiTalk.MultiTalkMsgRecevie", "receive invite "
  → i4.INSTANCE.L(byte[], int)            [SplitGroupCall 已解析的同一个 i4]
  → v0.G(MultiTalkGroup)                  v0.java:807 = MultiTalkManager.onInviteMultiTalk
    "MicroMsg.MT.MultiTalkManager"
    "onInviteMultiTalk All Var Value:\n isMute: %b isHandsFree: %b isCameraFace: %b multiTalkStatus: %s groupIsNull: %b"
```

邀请者 wxid = `o2.d(multiTalkGroup)`；群 = `MultiTalkGroup.f100328f`。

WeChat 自身有先例：邀请者在黑名单时记 `"not open multitalk receiver or black user"` 并直接返回，不显示 UI。

拒绝：`v0.g(isReject, isMissCall, isPhoneCall, isNetworkError, boolean, boolean)` (`v0.java:1428`)，即 `SplitGroupCall.methodExitMultiTalk`。底层 `i4.K(new c1(i4, 1))` → native `Hangup(1)` = `ReasonManual`。

**方案**: hook `v0.G`，命中则 `result = null`；自动拒绝开启时再调 `v0.g(true, false, false, false, true, false)`。同时抑制 `v0.k` / `v0.l`（type 64 群通话系统消息）。

### 3.7 共享 API 抽取

`SplitGroupCall` 已解析 multitalk 内部结构（`classSubCoreMultiTalk`、`methodExitMultiTalk`、`classILinkService`、`ctorHangupTask`、`methodPostTask`），`PipVoip` 已解析 `methodVoipMpHangUp`（`PipVoip.kt:451`，即 `v0.f(boolean, d0)`）。

**方案**: 抽取 `features/api/core/WeVoipApi.kt`（`ApiFeature`），集中承载 VoIPMP 与 multitalk 的解析与操作，供 `HideContacts` / `SplitGroupCall` / `PipVoip` 共用，避免三份重复解析。

> 注：`PipVoip.methodVoipMpHangUp` 声明了 `allowFailure = true`，在 B1 修复前它的 `isPlaceholder` 守卫（`PipVoip.kt:293`）是失效的。修 B1 后该守卫才真正生效。

### 3.8 保留的老栈 hook

`methodVoipServiceExSetInviteContent` / `methodVoipServiceExReject` / `methodVoipAcceptIncomingCall` / `methodVoipStartAcceptVoip` 作为**对端降级到旧协议时的兜底**保留，但修正 B4（`result = false`），且不再是自动拒绝的主路径。

`methodVoipShowFloatingCard`（`".ui.voip.VoipFloatView"`, `paramCount = 8` → `nr4.y.x`，`args[5]` = wxid）对**两条栈都有效**，保留不变。

UI 上「自动拒绝音视频通话」的说明文字「不保证有效」在本阶段验证通过后移除。

---

## 四、P2 — 新增覆盖面

### 4.1 新的朋友（用户明确点名，确认未覆盖）

存储 `com.tencent.mm.storage.n7`，表 **`fmessage_conversation`**（非 `rcontact`），主键列 **`talker`**。
类标识字符串：`"MicroMsg.FMessageConversationStorage"`、`"clearAllNew fail"`、`"deleteByTalker rowId: %d, talker: %s, stack = %s"`。

| 用途 | SQL | 位置 |
|---|---|---|
| 列表游标 | `select * from fmessage_conversation  ORDER BY lastModifiedTime DESC` | `n7.java:146` |
| **红点计数** | `select count(*) from fmessage_conversation where isNew = 1 and fmsgIsSend < 2` | `n7.java:122` |
| 总数 | `select count(*) from fmessage_conversation` | `n7.java:151` |
| 头像条（最多 4） | `select * from fmessage_conversation  where isNew = 1 ORDER BY lastModifiedTime DESC limit 4` | `com/tencent/mm/ui/contact/a4.java:72` |

红点链路：`com.tencent.mm.ui.contact.a4`（`"MicroMsg.FMessageContactView"`）读 `t21.w.hj().a1()` (`a4.java:228`) → 写入 KV **143618** (`a4.java:256-258`) → 通讯录底栏红点 `com/tencent/mm/ui/pe.java:19`（`"MicroMsg.UnreadCountHelper"`）读取。

**方案**: 在 SQL 规则表中新增一条 —— 匹配 `from fmessage_conversation`，注入 `talker NOT IN (...)`。四个查询全部走 `ka5.b0.g`，一条规则同时修好列表、头像条、计数与底栏红点。

**守卫**: 跳过 `select * from fmessage_conversation  where encryptTalker=`（`n7.java:96` 单行 getter）。

> 版本稳定性：8.0.69 (`storage/p6.java:167,175,180`) 与 8.0.74 (`storage/o7.java:99,149,154`) 字面量相同，类名 p6→o7→n7 变化，因此必须字符串匹配。

> 分歧记录：搜索/通知调查认为 KV 143618 是服务端下发的好友请求计数、与隐藏无关；通讯录调查追踪到了 `a4.c()` 的写入点。实现时以后者为准并实机验证。

### 4.2 未读角标与底栏「微信」计数

隐藏联系人的未读消息目前仍计入桌面角标与底栏红点。

聚合链路：`m0.c(int, Map)` → `ip.l.b()` → `e01.h2.c(1)` → `e01.h2.f(1)`

| 目标 | 匹配字符串 | 方案 |
|---|---|---|
| `e01.h2.f(int) -> HashMap<username, k4>` `e01/h2.java:214` | `"getUnreadConversationCursor filterType[%d] [%s]"`、`"unReadCount > 0 AND (parentRef is null or parentRef = '' ) "` | `hookAfter` 从返回的 map 移除隐藏 key。**一处同时修好桌面角标与底栏计数** |
| `e01.h2.d(String, String[])` `e01/h2.java:111` | `"get total unread, but has not set uin"`、`"select unReadCount, parentRef from rconversation where username = '"` | `hookBefore` 隐藏 talker 返回 `0`（增量刷新路径） |
| `e01.h2.e(String)` `e01/h2.java:179` | `"[getTotalUnreadTalker] cost %d ms  unread contact: %s, stack %s"`（注意两个空格） | 每联系人快捷方式角标 |

这些同样经 `ka5.b0.g`，也可选择纯 SQL 改写（对 `unReadCount > 0` 类查询追加 `AND rconversation.username NOT IN (...)`），风险更低。**优先采用 SQL 规则表方案**，与整体架构一致。

### 4.3 朋友圈消息列表（赞/评论通知）

表 `SnsComment`，行为者列 **`talker`**（`dm/ma.java:57`，列名注册于 `dm/ma.java:164`）。
存储 `com.tencent.mm.plugin.sns.storage.w1`；UI `SnsMsgUI`（+ `SnsMsgUIWithAll` / `SnsMsgUIWithRelevance`）；适配器 `com/tencent/mm/plugin/sns/ui/rm.java`。

需过滤的 SQL（全部经 `onQuery`）：

| SQL | 位置 | 用途 |
|---|---|---|
| `select *, rowid from SnsComment where isSend = 0 order by createTime desc LIMIT ` | `rm.java:3379`, `:3397` | 主列表 |
| `select *, rowid from SnsComment where isRead = ?  and isSilence != ?  order by createTime desc` | `w1.java:114` | 未读段 |
| `select *, rowid from SnsComment where isRead = ? and ( isSilence != ? or isReminding = ? ) and msgRelevanceType = ?  order by createTime desc` | `w1.java:122` | 与我相关 |
| 同上变体 | `w1.java:141`, `:143` | relevance 变体 |
| `select count(*) from SnsComment where isSend = ` | `w1.java:81` | 列表总数（不改会导致分页错乱） |
| `select count(*) from SnsComment where isRead = ? and isSilence != ? ` | `w1.java:129` | **发现 tab 红点** |
| 另外三个 `count(*)` 变体 | `w1.java:154`, `:190`, `:226` | 红点 |

**方案**: `onQuery` 新增分支 —— `from SnsComment` 且未注入过 → `injectCondition(sql, "talker NOT IN (...)")`。现有 `injectCondition`（`HideContacts.kt:609`）已能在 ` order by ` / ` limit ` 前插入。**必须一并处理 `count(*)` 查询**，否则红点与分页计数错误。

### 4.4 朋友圈内联评论/点赞

隐藏联系人在**共同好友**的动态下的评论不在 `SnsComment` 里，而在该动态自身的 `SnsInfo.attrBuf` → `SnsObject.CommentUserList` / `LikeUserList`。

- 协议：`SnsObject.LikeUserList` / `CommentUserList`（`protocal/protobuf/SnsObject.java:66-67`），条目类型 `a65.ha6`
- `ha6` 字段：`f9152d` = Username、`f9153e` = Nickname、`f9156h` = Content、`f9160o` = ReplyUsername（经 `ui/widget/t2.java:119,164,171` 验证）
- 结构构建：`fb4.z0.D0(SnsInfo, SnsObject, Context, rs, boolean, d8, String, Map, Map, List) -> dt` (`fb4/z0.java:589`)

DexKit 匹配：`usingEqStrings("snsInfoToSnsStruct", "com.tencent.mm.plugin.sns.data.SnsUtil", "mSnsInfo is null, why?")`

**方案**: `hookBefore` `D0`，取 `args[1] as SnsObject`，剔除 `CommentUserList` / `LikeUserList` 中 `f9152d`（或 `f9160o`）命中隐藏名单的条目，并相应递减 `CommentCount` / `CommentUserListCount` / `LikeCount` / `LikeUserListCount`。

**必须在克隆上操作** —— `SnsInfo.cacheTimeLine`（`storage/SnsInfo.java:39`）缓存已解析对象，直接改会污染下次同步时的重新序列化。

此 hook 同时覆盖「个人相册」页的赞/评论条（`SnsUserUI` 的信息流查询含 `SnsInfo.userName=`，现有实现已正确跳过，这是期望行为）。

### 4.5 发现 tab「N 位朋友的新动态」头像

8.0.76 是单个头像而非头像条。

- 写入：`com.tencent.mm.plugin.sns.model.c3.H(c3, SnsObject)`（`c3.java:103-141`），`:134` 写 `j1.u().c().w(68377, snsObject.Username)`
- 读取/渲染：`FindMoreFriendsUI.updateSnsEntry`（`:835` 读 KV 68377，`:893` 渲染）

DexKit：`usingEqStrings("updateSyncDataCache", "com.tencent.mm.plugin.sns.model.NetSceneSnsSync", "preRdUsername", "updateSyncDataCache build previousRedDotInfo error", "isCoverPreRd")`

**方案**: `hookBefore` `c3.H`，`args[1].Username` 命中则 `result = null`（跳过整个缓存写入）。同时消除红点，且 KV 68377 的其他消费者（聊天列表横幅 `ui/conversation/banner/z.java:44`、`+` 菜单 `ui/rg.java:371`）自动受益。

### 4.6 搜索补齐

**A. FTS 表清单缺口**（表名生成于 `i23/a.java:403`，`"FTS5Meta" + t()`）

现有正则覆盖 8 张。缺失：

| 表 | 类 | 处理 |
|---|---|---|
| `FTS5MetaChatroomMember` | `q23/a.java:48` | 加入正则 |
| `FTS5MetaWeShop` | `k15/m.java:38` | 加入正则 |
| `FTS5MetaAIHistory` | `wv4/h.java:40` | 加入正则 |
| `FTS5MetaAIHistoryChat` | `wv4/b.java:38` | 加入正则 |
| `FTS5MetaServiceNotify` | `q23/j.java:159` | **特殊**：`q23.j.P()` (`:112-135`) 中 `aux_index` 是常量 `'notifymessage'`，包一层 `WHERE aux_index NOT IN (...)` 无效，须改用 **`talker`** 列过滤 |

8.0.76 **不存在** 朋友圈 FTS 表，朋友圈搜索无需处理。
`FTS5MetaSOSHistory`（`q23/i.java:56-65`）只存搜一搜查询历史，无 talker 列，无需处理。

**B. 形状不匹配的 FTS 查询**（现有正则要求 `^SELECT FTS5MetaX.docid, type, subtype, entity_id, aux_index,`，以下均为 `SELECT aux_index ...` / `SELECT member, chatroom ...`）

| 任务类 | 匹配字符串 | 泄漏 |
|---|---|---|
| `logic.k0` SearchChatroomByMemberTask | `"SearchChatroomByMemberTask"` + `"SELECT aux_index FROM %s NOT INDEXED JOIN FTS5ChatRoomMembers ON (aux_index = chatroom) WHERE member=? AND subtype=38 AND type=131075 ORDER BY timestamp desc"` | 隐藏的群出现在「共同群聊」 |
| `logic.n0` SearchChatroomInMemberTask | `"SearchChatroomInMemberTask"` | 同上 |
| `logic.m0` SearchChatroomCountTask | `"SearchChatroomCountTask"` | 群聊计数虚高 |
| `logic.s0` SearchCommonChatroomTask | `"SearchCommonChatroomTask"` + `"SELECT member, chatroom, entity_id FROM FTS5ChatRoomMembers, %s WHERE %s AND chatroom = aux_index"` | 群与成员都泄漏 |
| `logic.i` SearchRelatedChatroomTask | `"SearchRelatedChatroomTask"` + `"MicroMsg.FTS.FTS5SearchChatroomMemberLogic"` | 隐藏的群 |
| `logic.g` SearchCommonChatroomTask | `"SearchCommonChatroomTask"` | 隐藏的群 |
| `logic.h` SearchCommonChatroomUserTask | `"SearchCommonChatroomUserTask"` | **隐藏联系人**作为群成员建议出现 |

**方案**: 在 `rawQueryWithFactory` hook 中增加第二个改写器 —— 匹配含 `FTS5ChatRoomMembers` 的 join，注入 `AND chatroom NOT IN (...) AND member NOT IN (...)`。

**C. 搜索群成员**

`com.tencent.mm.plugin.fts.logic.q0`（`q0.java:25,37,45`），字符串 `"SearchChatroomMemberTask"`、`"MMChatroomMember(%s) AS Offsets"`、`"SELECT memberlist FROM chatroom WHERE chatroomname=?;"`。

FTS 部分匹配现有正则，但 `aux_index` 是**群名**而非成员，因此现有过滤只在群本身被隐藏时生效。成员是随后从 `memberlist` 解析并挂到 `j23.l.f336087e` 上的（`:57-116`）。

**方案**: `q0.p(j23.v)` `hookAfter`，从 `((j23.y) v.f336133e[0]).f336170n` 移除 `f336087e` 命中的条目。`logic.h` 同理。

**D. 已知副作用**

现有改写会给**在隐藏聊天内搜索聊天记录**也加上 `aux_index NOT IN (...)`，导致 `temporarilyShown == false` 时打开隐藏聊天再搜索返回 0 条。**方案**: 当查询已带 `aux_index = ?` 且绑定的是当前打开的 talker 时跳过改写。

### 4.7 通知绕过：LightPush

`com.tencent.mm.booter.notification.e0.f(long msgId, String userName, String nickName, String content, String avatarPath, Map msgSource, j4 cmd)`（`booter/notification/e0.java:444`）

匹配字符串：`"notifyForLightPush push:isShake: %B, isSound: %B ctrlFlag:%s"`、`"LightPush [NO NOTIFICATION] Util.isNullOrNil(userName) || Util.isNullOrNil(nickName)"`

调用方 `y11/g0.java:158`（`"MicroMsg.NetPushSync"`）。此路径**从不调用 `x.d`**，直接从 push 载荷弹通知并自行播放铃声/震动。

**方案**: `hookBefore`，`args[1]`（userName）命中则 `result = null`。**需确认进程归属** —— 可能运行在 `MicroMsg.proc.LightPushServiceImpl`（`lq1/q0.java`），若如此需放宽 `shouldLoadInCurrentProcess`（注意 MMKV 是 `MULTI_PROCESS_MODE`，放宽后须确保调度器不重复运行）。

`methodDealNotify` 本身在 8.0.76 **仍然有效**（`booter/notification/x.java:231`，`args[1]` = talker，6 参，`void`），且 `x.a`/`x.c`/撤回监听三条入口全汇入它；群@我与服务通知无独立路径。声音与震动决策在 `x.d` 内部完成（`x.java:249-460`），因此取消 `x.d` 已同时消除消息通知的声音与震动。

### 4.8 @成员选择器

`com/tencent/mm/ui/chatting/atsomeone/AtSomeoneLiveList.java:36` — `public List e(List snapshotList)`，与现有 `methodAddressMvvmListPreprocessList` **完全同构**。

- 条目类 `com.tencent.mm.ui.chatting.atsomeone.b`，联系人在字段 `f228756e`（类型 `com.tencent.mm.storage.y3`）
- DexKit：`declaredClass = "com.tencent.mm.ui.chatting.atsomeone.AtSomeoneLiveList"` + `usingEqStrings("snapshotList")`

**方案**: 复用现有 hook 体（`HideContacts.kt:250-257` 的反射 `field_username` 查找已能处理该形状），连同 B6 空表守卫一并抽成共享函数。

### 4.9 联系人数量统计

视图 `com.tencent.mm.ui.contact.ContactCountView`（未混淆）；计数生产者 `com.tencent.mm.ui.contact.f1.run()`（`f1.java:17-52`）。

**A. `contactType == 1` — 通讯录「N 位联系人」** → `j4.O(false, ...)`（`j4.java:460-497`）

字符串：`"MicroMsg.ContactStorage"` + `"getNormalContactCount, sql:%s, result:%d, includeBlack:%s, time:%d"` (`j4.java:495`)

**不能 SQL 改写** —— 查询以 `" or username = 'weixin'"` 结尾（`j4.java:487`），追加 `AND` 会落进 OR 的右操作数内而失效。

**方案**: `hookAfter` `j4.O`，减去隐藏名单中属于普通好友的数量。

**B. `contactType == 2` — 群聊底部计数** → SQL 内联于 `f1.java:28`

`select count(username) from rcontact where type & 1 !=0 and type & 32 =0 and type & 8 =0 and verifyFlag & 8 = 0` + `e01.e2.c(...)` 追加的 `" and ( 1 != 1 or username like '%@chatroom' ... )"`（`e01/e2.java:760-790`）。无尾部 OR、无 ORDER BY。

**方案**: 专用规则 —— 匹配 `count(username) from rcontact` **且** `like '%@chatroom'`，追加 `AND rcontact.username NOT IN (...)`。

> 「仅聊天的朋友」(`OnlyChatContactMgrUI`) 不受影响：其计数取自已过滤的 `j4.U(..., "@social.black.android", ...)` 游标（`ui/contact/j7.java:130`）。

### 4.10 群成员列表

成员来自 `chatroom.memberlist` —— 一个 `;` 分隔的字符串（`storage/a3.java:49` 查询，`storage/z2.java:360` 解析），**无法 SQL 过滤**，必须在适配器层处理。

| UI | 适配器 | 匹配字符串 | Hook 点 |
|---|---|---|---|
| `SeeRoomMemberUI`（查看全部群成员） | `com/tencent/mm/chatroom/ui/cc.java` | `"MicroMsg.SeeRoomMemberUI"` | `cc.d(List)` (`:96`) |
| `SelectMemberUI` / `SelectDelMemberUI`（@全体、删除成员、邀请） | `com/tencent/mm/chatroom/ui/kd.java` | `usingEqStrings("MicroMsg.SelectMemberAdapter", "null == item! position:%s, count:%s")` | 列表字段 `f93062i`（`List<bd>`，`bd.f92830a` 为 `y3`） |

> 注意区分：现有 `methodChatroomContactAdapterInitCursor`（`"MicroMsg.ChatroomContactAdapter"`）指向 `ui/contact/s0.java`，是**通讯录 → 群聊的群列表**，不是群成员列表。

### 4.11 收藏

表 `FavItemInfo`，发送者列 `fromUser`；存储 `q82/d.java`（`"MicroMsg.Fav.FavItemInfoStorage"`）；适配器 `com/tencent/mm/plugin/fav/ui/adapter/c.java`（`"MicroMsg.FavoriteAdapter"`）。

主列表查询 `q82/d.java:642`（`ii(...)`）。

**注意**: 收藏插件部分已迁移到 WCDB ORM builder（`r82.e` / `br5.f`，见 `adapter/c.java:349-360`），**不产生可匹配的原始 SQL**。因此 SQL 改写只覆盖遗留路径。

**方案**: `hookAfter` `MicroMsg.FavoriteAdapter` 的 reset-data 方法（`"on reset data list, do search, searchStr:%s, tagStr:%s, searchTypes:%s"` `:344`，`"on reset data list, last update time is %d, type is %d"` `:488`），剔除 `field_fromUser` 命中的条目 —— 同时覆盖 ORM 与原始 SQL 两条路径。

### 4.12 视频号朋友点赞

`com/tencent/mm/plugin/finder/convert/r8.java` = `FinderFeedFriendLikeConvert.onBindViewHolder`（`"Finder.FinderFeedFriendLikeConvert"` `:752`），读 `a65.je1` 的 `getString(5)` = wxUsername。

列表 UI `FinderFriendLikeFeedUI`（`"Finder.FinderFriendLikeFeedUI"`），加载器 `plugin/finder/feed/gb.java`。

**方案**: 在加载器/convert 层过滤。Finder 用网络驱动的 feed loader + 内存模型，无可查询的表。

**范围外**: Finder 关注列表（`Finder.FinderFollowListUIC` 等）以 Finder 身份 `v2_...` 为键，无法映射到 wxid，记为已知限制。

### 4.13 拍一拍

`nq3/l.java` = `PatMsgExtension`（`"MicroMsg.PatMsgExtension"`）
插入方法：`yj(String talker, String fromUser, String pattedUser, String suffix, int createTime, long svrId)` (`:560`)
匹配字符串：`"insert pat msg %d %s %s"` (`:620`)

**方案**: `hookBefore`，`args[1]`（fromUser）命中则 `result = android.util.Pair.create(0L, 0L)`（该方法自身的 no-op 返回值，`:568`）。同时消除消息行与对话置顶时间刷新。

### 4.14 微信运动排行榜

表 `HardDeviceRankInfo`，列 `rankID` / `username` / `score`。
查询：`select *, rowid from HardDeviceRankInfo where rankID = ? order by score desc`（`f42/c.java:40` 构建，经 `ka5.b0.g` 执行）。
存储 `h42/d.java`（`"MicroMsg.ExdeviceRankInfoStg"`）；UI `ExdeviceRankInfoUI`。

**方案**: SQL 规则表加一条 —— `from HardDeviceRankInfo` → 注入 `username NOT IN (...)`。名次会重新编号，符合预期。

### 4.15 已确认「已覆盖，无需改动」

| 面 | 原因 |
|---|---|
| 转发/分享选择器（最近聊天 + 最近转发） | `l4.C` (`l4.java:172`) 与 `l4.t` (`l4.java:860`) 生成的列清单同时含 `conversationtime` / `unreadcount` / `digestuser`，已被 `looksLikeConversationListQuery` 命中 |
| 通讯录字母索引与分节标题 | `AddressLiveList.e()` 正是分节标记赋值处（`:33-58`），现有 `hookBefore` 在赋值**之前**移除条目 → 无残留字母头、无空分节 |
| 通讯录 → 群聊列表 | `j4.y` 的 SQL 含 `from rcontact` + `pyinitial`，已被 `looksLikeContactSelectorQuery` 命中 |
| 标签（列表 + 成员页 + `(N)` 计数） | `j4.U` (`j4.java:544-546`) 含 `pyInitial`/`quanPin`；计数取自同一过滤后游标 |
| 企业微信联系人 | `j4.R` (`j4.java:533-535`) 含 `from rcontact` + `quanpin`；`rcontact.username` 限定在 join 中正确 |
| 公众号列表 | `wr1/s.java:39` 的 `from rcontact, bizinfo` join 含 `rcontact.pyInitial` |
| 聊天记录迁移/备份 | 复用 `SelectConversationUI` / `l4.C` 游标 |
| 文件传输助手 | 固定 `filehelper` 会话，无联系人枚举 |
| 朋友圈个人相册信息流 | 现有实现显式跳过 `SnsInfo.userName=`，这是正确行为 |

### 4.16 记为已知限制（不实现）

| 面 | 原因 |
|---|---|
| 搜一搜聊天记录卡片 | 服务端 cgi 1532（`plugin/websearch/o.java`），本地无可过滤数据 |
| 视频号关注/聚合列表 | 以 `v2_...` Finder 身份为键，无 wxid 映射 |
| 撤回提示 / 入群退群系统消息 | type 10000 消息，`content` 是已本地化的**昵称**文本而非 wxid。只能做昵称字符串匹配，易误伤 |
| 支付/转账账单 | 服务端渲染的 WebView/CGI 列表（选择器本身已被 4.15 覆盖） |
| 群聊中隐藏其消息 | 按范围决策不做。发送者编码在 `message.content` 前缀中，无索引列，须在 `ChattingDataAdapterV3` 折叠行，侵入性最高 |

---

## 五、P3 — 定时自动隐藏/显示

### 5.1 数据模型

单一列表，每条是一个「闹钟」：

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

- `SHOW` = 置 `temporarilyShown = true`；`HIDE` = 置 `false`。**不改写隐藏名单。**
- `ONCE` 触发后自删。
- 持久化：JSON 字符串存 `WePrefs`，遵循 `ChatToolbar.kt:229-247` 的既定套路（`prefOption(key, "")` + `kotlinx.serialization` + `DefaultJson`（`utils/serialization/JsonUtils.kt:13`）+ `runCatching` 降级为空列表）。

### 5.2 触发机制

**AlarmManager 精确闹钟 + 启动补偿**（权限见 1.4）。

- 每条启用的条目注册一个 `setExactAndAllowWhileIdle`，`PendingIntent` 携带条目 id
- 广播接收器在微信进程内接收，执行动作后为 `REPEATING` 条目重新注册下一次
- 列表增删改后整体 `resync()`（取消全部已注册闹钟 → 按当前列表重注册），沿用 `TriggerScheduler.resync` 的协调模型（`agent/trigger/TriggerScheduler.kt:46-60`）

**启动补偿**：`onEnable()` 时扫描全部条目，找出「当前时刻之前最近一次应当触发的时间点」，直接应用其动作。这保证进程被杀期间错过的切换在下次启动时得到纠正。

- `REPEATING`：在过去 7 天窗口内按 `daysOfWeek` 回溯
- `ONCE`：`atEpochMillis <= now` 则应用并删除

> 注意：`onEnable()` 在进程 attach 极早期由 `FeaturesLoader.loadFeatures()` → `WeLauncher.init()` → `StartupAgent.startup()`（`StartupAgent.kt:64`）同步调用，此时尚无 Activity。补偿逻辑只能改内存状态与调 `WeConversationApi.reloadConversations()`，**不得弹 Toast 或触碰 UI**。

**与手动切换的关系**：调度器只在触发时刻**写入** `temporarilyShown`，不锁定它。用户随时可用 `#show` / `#hide` / 三击标题手动改变状态；该状态保持到下一个定时触发点或用户再次手动切换为止。补偿逻辑同理 —— 它只在进程启动时应用一次，之后不干预。

**生命周期**：调度器随功能 `onEnable()` 注册、`onDisable()` 取消全部闹钟。功能关闭时不留下任何已注册的 `PendingIntent`。

**时间计算**：沿用仓库约定 —— `java.util.Calendar` 做日历字段运算（`TriggerScheduler.kt:112-122` 的 DAILY 分支即为「minuteOfDay → 下一个 epoch millis」的现成实现），`kotlin.time` 做时长。仓库无 `kotlinx-datetime`。

### 5.3 UI

复用现成组件：

- `WeTimeOfDayField(minuteOfDay, onMinuteChange, label, ...)`（`ui/content/WeDateTimeField.kt:105`）— REPEATING 的时分输入
- `WeDateTimeField(value, onValueChange, label, mode = WeDateTimeMode.DATE_TIME)`（`:46`）— ONCE 的完整日期时间
- `formatMinuteOfDay` / `parseMinuteOfDay` / `formatDateTime` / `parseDateTime`（`:132,138,146,152`）
- 列表增删改的交互形态参考 `ChatToolbar.showQuickReplyConfig`（`ChatToolbar.kt:741-860`）：`mutableStateList` 草稿 + 头部「添加」按钮 + 每行点击进入编辑 / 尾部删除 + 确认时保存
- 对话框壳：`showComposeDialog` + `AlertDialogContent` + `DefaultColumn`

星期选择用 7 个可切换的 chip（默认全选）。每行摘要形如 `每天 22:00 · 隐藏` / `周一 周三 09:30 · 显示` / `2026-08-01 12:00:00 · 隐藏（单次）`。

---

## 六、代码结构

### 6.1 目录

```
features/items/contacts/
  HideContacts.kt                 # object + 全部 dex 委托(internal) + 生命周期 + 设置入口
  hidecontacts/
    HideContactsSql.kt            # SQL 改写规则表 + injectCondition + 各 matcher/injector
    HideContactsVoip.kt           # installVoipHooks() — VoIPMP + multitalk + 老栈兜底
    HideContactsLists.kt          # 适配器/MvvmList 层过滤(通讯录/@选择器/群成员/收藏/Finder)
    HideContactsMoments.kt        # SnsComment / SnsObject 洗净 / 发现 tab 头像
    HideContactsNotify.kt         # dealNotify / LightPush / 未读角标
    HideContactsSearch.kt         # FTS 正则 + ChatRoomMembers join + 任务级过滤
    HideContactsSchedule.kt       # 数据模型 + AlarmManager 调度 + 启动补偿
    HideContactsScheduleUi.kt     # 定时列表 CRUD Compose UI
```

全部子文件用 `internal fun HideContacts.xxx()` 扩展函数，可访问 object 的 `internal` 成员并调用 `BaseFeature` 的 `hookBefore`/`hookAfter`。

原有 dex 委托由 `private` 改为 `internal`。

### 6.2 SQL 规则表

将现有的两条 `?:` 链（`HideContacts.kt:539-543`）重构为有序规则表：

```kotlin
private class SqlRule(
    val name: String,
    val matches: (lowerSql: String) -> Boolean,
    val inject: (sql: String, hidden: Set<String>) -> String,
)
```

单次遍历，首个命中者生效。新增覆盖面只需加表项。统一在表层做 `temporarilyShown` / `hidden.isEmpty()` 早退，避免 B2 类遗漏重演。

### 6.3 共享 VoIP API

新增 `features/api/core/WeVoipApi.kt`（`ApiFeature`），承载 VoIPMP 与 multitalk 的委托解析与操作（`rejectByShortCut`、`v0.f` hangup、`v0.g` exitMultiTalk、`c1` hangup task、`i4` 单例访问），供 `HideContacts` / `SplitGroupCall` / `PipVoip` 共用。

---

## 七、分阶段计划

每阶段结束后停下来，由用户 review 并实机验证，确认后再进入下一阶段。

| 阶段 | 内容 | 验证重点 |
|---|---|---|
| **P0** | B1（DexDelegates 基础设施）+ B2、B4、B5、B6、B7、B8、B9(有限)、B10 | 编译通过；全局搜索正常；隐藏联系人按 rowid/encryptUsername 可查；关闭功能后锁屏不再踢出；留意 B1 修复后新暴露的解析失败日志 |
| **P1** | VoIP 重写（3.1–3.8）+ B3、B11 + `WeVoipApi` 抽取 | **实机**：隐藏联系人来电 → 不响铃、不弹通知/浮窗、对方收到拒绝（而非一直响到超时）；多人通话邀请同样；无通话记录消息 |
| **P2** | 新增覆盖面（4.1–4.14），按 4.1 → 4.2 → 4.3 → 4.4 → 4.7 → 4.5 → 4.6 → 4.8 → 4.9 → 4.10 → 4.11 → 4.12 → 4.13 → 4.14 顺序 | 逐项实机；4.1 需确认 KV 143618 归属分歧；4.7 需确认进程归属 |
| **P3** | 调度器（5.1–5.3） | 重复/单次条目按时触发；进程被杀后重启补偿正确；单次条目触发后自删 |

### 阶段内失败处理

任一子项若实机验证不通过（DexKit 匹配失效、hook 无效、行为不符），不阻塞同阶段其他子项；记录为待查项，在该阶段收尾时一并汇报，由用户决定是继续调查还是记为限制。

---

## 八、测试策略

仓库无单元测试（`AGENTS.md`：「No unit tests — manual testing on real WeChat only」）。

- 编译验证：`./x build`
- 每阶段实机验证清单如上表
- P0 的 B1 修复影响面广，需额外观察 `ConversationAggregation` / `MarkdownRendering` / `PipVoip` 等功能是否出现新的解析失败告警

## 九、文档更新

`@Feature` 的 `description` 需重写以反映新的覆盖面清单。若 `docs/features/` 下有对应条目，一并更新。
