package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarApi
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.contacts.hidecontacts.installMomentsHooks
import dev.ujhhgtg.wekit.features.items.contacts.hidecontacts.installSqlHooks
import dev.ujhhgtg.wekit.features.items.contacts.hidecontacts.installVoipHooks
import dev.ujhhgtg.wekit.features.items.contacts.hidecontacts.rewriteMomentsFeedSql
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.now
import java.lang.ref.WeakReference
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import java.lang.reflect.Modifier as JavaModifier


@Feature(
    name = "隐藏联系人", categories = ["联系人与群组"], description =
        """隐藏指定的联系人
隐藏位置:
1. 首页对话列表
2. 通讯录内联系人&群聊列表
3. 首页搜索界面
4. 锁屏自动关闭聊天界面
5. 摇一摇设备关闭聊天界面
6. 朋友圈信息流
7. 联系人选择页面
8. 音视频通话与群通话 (来电横幅、铃声、通知、通话记录)
9. 通讯录内新的朋友 (列表、头像、红点)
10. 桌面角标与底栏未读计数
11. 朋友圈消息列表 (点赞与评论)
12. 共同好友朋友圈动态下的内联点赞/评论 (非 SnsComment 表, 随动态本身下发)"""
)
object HideContacts : ClickableFeature(), IResolveDex, WeChatInputBarApi.IInputBarListener,
    WeDatabaseListenerApi.IQueryListener {

    private const val TAG = "HideContacts"

    private const val KEY_CONTACTS = "hidden_contacts"

    // One-time flag: older versions hid chats by writing parentRef='hidden_conv_parent'. Once we've
    // cleared that stale marker for the current hidden set (so #show / un-hide work again), we never
    // need to re-check. New hides rely purely on the query-time filter and never set the marker.
    private const val KEY_LEGACY_MIGRATED = "hidden_parentref_migrated"

    var hiddenContacts
        get() = WePrefs.getStringSetOrDef(KEY_CONTACTS, emptySet())
        set(value) {
            // Muting is a server-synced oplog (OpenImOpLogLogic), so only send it for contacts that
            // were just added — the previous version re-sent it for the entire set on every save.
            // NB: un-hiding deliberately does NOT restore the prior mute state; doing so would
            // overwrite a mute the user set themselves. See the design doc.
            val newlyHidden = value - WePrefs.getStringSetOrDef(KEY_CONTACTS, emptySet())
            WePrefs.putStringSet(KEY_CONTACTS, value)
            for (convId in newlyHidden) {
                WeConversationApi.setDnd(convId, true)
            }
            WeConversationApi.reloadConversations()
        }

    private object ScreenOffReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != Intent.ACTION_SCREEN_OFF) return

            val chattingUi = chattingUi?.get() ?: return
            val wxId = chattingUi.intent.getStringExtra("Chat_User")
            if (temporarilyShown || wxId !in hiddenContacts) return

            exitToMainActivity()
        }
    }

    private var chattingUi: WeakReference<ChattingUI>? = null

    // Registered against the application context, exactly once. It used to be registered on the
    // LauncherUI Activity inside doOnCreate — so every Activity recreation added another
    // registration — while onDisable unregistered against the application context, a different
    // Context, which throws and was being swallowed. Net effect: the receiver outlived the feature
    // and kept kicking the user out of hidden chats on screen-off after it was turned off.
    private var screenOffReceiverRegistered = false

    private fun registerScreenOffReceiver() {
        if (screenOffReceiverRegistered) return
        // ACTION_USER_PRESENT used to be in this filter but onReceive never handled it.
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        HostInfo.application.registerReceiver(ScreenOffReceiver, filter)
        screenOffReceiverRegistered = true
        WeLogger.d(TAG, "registered screen off receiver")
    }

    private fun unregisterScreenOffReceiver() {
        if (!screenOffReceiverRegistered) return
        screenOffReceiverRegistered = false
        runCatching { HostInfo.application.unregisterReceiver(ScreenOffReceiver) }
            .onFailure { WeLogger.w(TAG, "failed to unregister screen off receiver", it) }
    }

    /**
     * Wraps whatever OnClickListener WeChat had on the main-screen title rather than replacing it.
     *
     * The previous version called [android.view.View.setOnClickListener] unconditionally and only
     * bailed *inside* the lambda when [tripleClickTitle] was off — silently killing WeChat's own
     * title behaviour even with the option disabled. Delegating keeps the host's behaviour intact,
     * and reading the preference per click (rather than at install time) means toggling the option
     * takes effect without recreating LauncherUI.
     */
    private class TitleClickListener(
        private val activity: Activity,
        /** Not private: the installer unwraps this to avoid nesting wrappers across recreations. */
        val original: View.OnClickListener?,
    ) : View.OnClickListener {

        private var clickCount = 0
        private var lastClickTime = Instant.DISTANT_PAST

        override fun onClick(v: View) {
            if (!tripleClickTitle) {
                original?.onClick(v)
                return
            }
            val now = now()
            if (now - lastClickTime > TRIPLE_TAP_WINDOW) clickCount = 1 else clickCount++
            lastClickTime = now
            if (clickCount >= 3) {
                clickCount = 0
                toggleTemporarilyShown(activity)
                return
            }
            original?.onClick(v)
        }
    }

    // Reads the View's current OnClickListener out of its ListenerInfo, mirroring
    // SwipeConversationOperations.getAttachedTouchListener.
    private fun getAttachedClickListener(view: View): View.OnClickListener? = runCatching {
        val info = view.reflekt()
            .firstFieldOrNull { name = "mListenerInfo"; superclass() }
            ?.get() ?: return null
        info.reflekt()
            .firstFieldOrNull { name = "mOnClickListener" }
            ?.get() as? View.OnClickListener
    }.getOrNull()

    private object ShakeDetector : SensorEventListener {

        private var sensorManager: SensorManager? = null
        private var lastShakeTime: Long = 0
        private const val SHAKE_THRESHOLD = 4.5f // higher = harder shake required

        fun start(context: Context) {
            WeLogger.d(TAG, "starting shake detector")

            if (sensorManager != null) return

            sensorManager = context.getSystemService<SensorManager>()
            val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

            sensorManager?.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_UI
            )
        }

        fun stop() {
            WeLogger.d(TAG, "stopping shake detector")

            sensorManager?.unregisterListener(this)
            sensorManager = null
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH

            if (gForce > SHAKE_THRESHOLD) {
                val now = System.currentTimeMillis()
                if (lastShakeTime + 1000 > now) return // 1-second debounce
                lastShakeTime = now

                exitToMainActivity()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // unused
        }
    }

    private fun exitToMainActivity() {
        WeLogger.d(TAG, "leaving conversation page")
        val ctx = HostInfo.application
        val intent = Intent(ctx, LauncherUI::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ctx.startActivity(intent)
    }

    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    override fun onEnable() {
        // --- home screen conversation list ---

        // Hide at query time: inject `username NOT IN (...)` into WeChat's list queries so hidden
        // contacts are filtered on every full read. Covers the homepage conversation list, the
        // contact selector / 群聊 / 标签 / 公众号 lists, and global search.
        installSqlHooks()

        // Block the per-row live-update notification that WeChat fires (type 3) when a new
        // message arrives. Without this the native ConversationStorage dispatcher pushes the
        // hidden contact's row directly to the list adapter — bypassing the SQL hook above —
        // and the contact reappears until the next full query. Cancelling the notification at
        // source means the adapter never sees the row, so there is no flash at all.
        hookNewMessageNotification()

        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            migrateLegacyHiddenParentRef()

            val context = thisObject!!.reflekt()
                .firstField { type { it isSubclassOf Activity::class } }
                .get()!! as Activity

            registerScreenOffReceiver()

            // Triple-click on the main-screen title to toggle temporary show/hide.
            val titleView = context.window?.decorView
                ?.findViewById<TextView>(android.R.id.text1) ?: return@hookAfter
            // Re-wrap rather than skip when our listener is already attached: doOnCreate can fire
            // again for a recreated LauncherUI that reuses the decorView, and the old wrapper would
            // still be holding the previous (destroyed) Activity. Unwrapping first also stops us
            // from nesting wrappers on every recreation.
            val existing = getAttachedClickListener(titleView)
            val original = (existing as? TitleClickListener)?.original ?: existing
            titleView.setOnClickListener(TitleClickListener(context, original))
        }

        // --- shake to leave ---

        ChattingUI::class.reflekt().apply {
            firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as ChattingUI

                chattingUi = WeakReference(activity)

                val wxId = activity.intent.getStringExtra("Chat_User")
                if (temporarilyShown || wxId !in hiddenContacts) return@hookAfter

                ShakeDetector.start(activity)
            }

            firstMethod { name = "onPause" }.hookAfter {
                chattingUi?.clear()
                chattingUi = null
                ShakeDetector.stop()
            }
        }

        // --- friends & groups list ---

        methodAddressMvvmListPreprocessList.hookBefore {
            if (temporarilyShown) return@hookBefore

            val contacts = args[0] as MutableList<*>
            // MvvmList hands us an empty snapshot on the initial load and whenever a filter matches
            // nothing; contacts[0] below would throw there, and hook bodies must not fail.
            if (contacts.isEmpty()) return@hookBefore

            val contactInfoField = contacts[0]!!.reflekt()
                .firstField { type { it.name.startsWith("${PackageNames.WECHAT}.storage") } }
                .self
            val usernameField = contactInfoField.type.reflekt()
                .firstField {
                    name = "field_username"
                    superclass()
                }.self.makeAccessible()

            val hiddenContacts = hiddenContacts

            contacts.removeAll { contact ->
                val contactInfo = contactInfoField.get(contact!!)
                val username = usernameField.get(contactInfo) as String
                username in hiddenContacts
            }
        }

        // NB: the 通讯录 -> 群聊 list (ChatroomContactAdapter) is NOT hooked at the adapter level.
        // Its cursor comes from ContactStorage.y(), whose SQL carries `from rcontact` + `pyInitial`
        // and is therefore already filtered by rewriteContactSelectorSql at the SQLite wrapper.
        //
        // A previous implementation additionally shifted adapter positions via a `hiddenPositions`
        // set. That was dead code (the set was always empty), and it was wrong in three ways: it
        // folded an ascending-only shift over an unordered MutableIntSet, it remapped getView but
        // not getItem/getItemId (so ChatroomContactUI's click listener would open the WRONG chat),
        // and it ignored temporarilyShown. Deleting it means a resolve failure degrades to "hidden
        // contact stays visible" instead of "tapping a row opens someone else's chat".

        // --- voip ---

        installVoipHooks()

        // --- moments inline likes/comments (mutual-friend posts) ---

        installMomentsHooks()

        // --- command ---

        WeChatInputBarApi.addListener(this)

        // --- moments feed ---

        WeDatabaseListenerApi.addListener(this)

        // --- notification ---

        methodDealNotify.hookBefore(100) {
            val talker = args[1] as? String? ?: return@hookBefore
            if (talker in hiddenContacts) {
                result = null
            }
        }

        WeConversationApi.reloadConversations()
    }

    override fun onDisable() {
        unregisterScreenOffReceiver()
        ShakeDetector.stop()
        chattingUi?.clear()
        chattingUi = null
        WeChatInputBarApi.removeListener(this)
        WeDatabaseListenerApi.removeListener(this)
        temporarilyShown = false
        WeConversationApi.reloadConversations()
    }

    /**
     * Toggles the temporary-show state. Mirrors the `#show` / `#hide` input-bar commands for
     * use by gesture-based triggers (e.g. triple-clicking the main-screen title).
     */
    internal fun toggleTemporarilyShown(context: Context) {
        if (temporarilyShown) {
            temporarilyShown = false
            showToast(context, "已恢复隐藏联系人")
        } else {
            temporarilyShown = true
            showToast(context, "已临时显示所有隐藏的联系人")
        }
        WeConversationApi.reloadConversations()
    }

    override fun onTextChanged(chatFooter: ChatFooter, text: String) {
        when (text) {
            "#show" -> {
                chatFooter.lastText = ""
                if (temporarilyShown) {
                    showToast(chatFooter.context, "已经是临时显示状态")
                    return
                }
                temporarilyShown = true
                showToast(chatFooter.context, "已临时显示所有隐藏的联系人, 输入 #hide 恢复隐藏")
                WeConversationApi.reloadConversations()
            }

            "#hide" -> {
                chatFooter.lastText = ""
                if (!temporarilyShown) {
                    showToast(chatFooter.context, "没有需要恢复的隐藏联系人")
                    return
                }
                temporarilyShown = false
                showToast(chatFooter.context, "已恢复隐藏联系人")
                WeConversationApi.reloadConversations()
            }
        }
    }

    override fun onQuery(sql: String): String? = rewriteMomentsFeedSql(sql)

    // The parentRef marker older versions wrote via WeConversationApi.setConversationsVisibility to
    // hide a chat. WeChat's native list filter (m4.O) hides rows whose parentRef isn't null/empty.
    private const val LEGACY_HIDDEN_PARENT_REF = "hidden_conv_parent"

    // One-time cleanup for users upgrading from the parentRef-based hiding: clear the stale marker
    // for our currently-hidden chats. Without this, WeChat's own filter keeps hiding a chat (until
    // its next message resets parentRef) even after the user un-hides it, since un-hiding only drops
    // it from our set and never touched parentRef. Scoped to our hidden set so we don't disturb rows
    // hidden by 显隐全部对话 (ToggleAllConversationsVisibility), which shares the same marker.
    private fun migrateLegacyHiddenParentRef() {
        if (WePrefs.getBoolOrFalse(KEY_LEGACY_MIGRATED)) return

        val hidden = hiddenContacts
        if (hidden.isEmpty()) {
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            return
        }

        // DB not ready yet: leave the flag unset so we retry on the next launch.
        if (!WeDatabaseApi.isReady) return

        try {
            val inClause = hidden.joinToString(",") { "'${it.replace("'", "''")}'" }
            WeDatabaseApi.execStatement(
                "UPDATE rconversation SET parentRef = '' " +
                        "WHERE parentRef = '$LEGACY_HIDDEN_PARENT_REF' " +
                        "AND username IN ($inClause)"
            )
            WePrefs.putBool(KEY_LEGACY_MIGRATED, true)
            WeLogger.d(TAG, "cleared legacy hidden parentRef markers for ${hidden.size} chats")
        } catch (ex: Exception) {
            WeLogger.w(TAG, "failed to clear legacy hidden parentRef markers", ex)
        }
    }

    private var temporarilyShown = false

    /**
     * The predicate every hook should use: a contact counts as hidden only while the temporary-show
     * escape hatch (`#show` / triple-tap title) is off.
     */
    internal fun isHiddenNow(wxId: String): Boolean = !temporarilyShown && wxId in hiddenContacts

    /** For SQL rewriters, which bail wholesale rather than testing individual wxids. */
    internal val isTemporarilyShown: Boolean get() = temporarilyShown

    internal val autoRejectVoipEnabled: Boolean get() = autoRejectVoip

    private var autoRejectVoip by prefOption("hide_auto_reject", false)
    private var tripleClickTitle by prefOption("hide_triple_click_title", false)

    // Three taps within this window on the main-screen title register as a triple-click.
    // Matches WeChat's own double-tap detection threshold (f8/r8 tab listener, 300 ms),
    // with a slightly wider window so the gesture stays comfortable.
    private val TRIPLE_TAP_WINDOW = 500L.milliseconds

    // Hooks the ConversationStorage notify dispatcher to cancel per-row update events (type 3)
    // for hidden contacts before they reach list adapters. WeChat fires b(3, storage, talker)
    // synchronously after every new message, pin, or unread-state change; without this hook the
    // adapter sees the row immediately — before any SQL query runs — so the contact reappears
    // regardless of the query-rewrite filter. Cancelling the notification at source is
    // race-free: the hidden contact never reaches the adapter at all.
    //
    // Event type 5 (global reload) is not suppressed — that is the path reloadConversations() uses to
    // trigger a full re-query (which our SQL hook then filters correctly). The empty-talker check
    // additionally guards the "" sentinel used by reloadConversations().
    /**
     * Re-entrancy guard for [hookNewMessageNotification].
     *
     * `markAsRead` mutates the conversation via `ConversationStorage.updateUnreadByTalker`, and that
     * mutation makes the storage fire *this very notification again* for the same talker. Without a
     * guard the hook body re-enters itself with every condition still satisfied, recursing until the
     * thread's 4 MB stack is exhausted — which killed WeChat on startup (SIGSEGV in the guard page,
     * surfacing inside xlog's printf because ART only inserts stack-overflow checks in Java frames,
     * not in JNI) whenever a hidden contact had unread messages waiting to sync.
     *
     * Thread-local because WeChat dispatches this notification synchronously on the calling thread.
     */
    private val markingAsRead = ThreadLocal.withInitial { false }

    private fun hookNewMessageNotification() {
        val method = WeConversationApi.methodNotifyConversationChanged
        if (method.isPlaceholder) {
            WeLogger.w(TAG, "conversation notify method not resolved; new-message suppression unavailable")
            return
        }

        method.hookBefore {
            val eventType = args[0] as? Int ?: return@hookBefore
            if (eventType != 3) return@hookBefore
            if (temporarilyShown) return@hookBefore
            val talker = args[2] as? String ?: return@hookBefore
            if (talker.isEmpty()) return@hookBefore
            if (talker !in hiddenContacts) return@hookBefore

            // Already inside our own markAsRead: this is the storage echoing our write back at us.
            // Still cancel the event so the row never reaches an adapter, but do not write again.
            if (markingAsRead.get() == true) {
                result = null
                return@hookBefore
            }

            markingAsRead.set(true)
            try {
                WeConversationApi.markAsRead(talker)
            } finally {
                markingAsRead.set(false)
            }
            result = null
        }
    }

    override fun onClick(context: ComponentActivity) {
        val regularContacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("隐藏联系人") },
                text = {
                    DefaultColumn {
                        var autoRejectVoipInput by remember { mutableStateOf(autoRejectVoip) }
                        var tripleClickTitleInput by remember { mutableStateOf(tripleClickTitle) }

                        ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = "选择要隐藏的联系人",
                                        contacts = regularContacts,
                                        initialSelectedWxIds = hiddenContacts,
                                        onDismiss = onDismiss
                                    ) {
                                        showToast("已保存 ${it.size} 个联系人")
                                        hiddenContacts = it
                                        onDismiss()
                                    }
                                }
                            },
                            supportingContent = { Text("点击配置联系人隐藏列表") },
                            headlineContent = { Text("配置隐藏列表") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                autoRejectVoipInput = !autoRejectVoipInput
                                autoRejectVoip = autoRejectVoipInput
                            },
                            trailingContent = {
                                Switch(checked = autoRejectVoipInput, onCheckedChange = null)
                            },
                            supportingContent = { Text("关闭时仅隐藏来电, 对方会一直响到超时; 开启后立即向对方发送拒接") },
                            headlineContent = { Text("自动拒绝音视频通话") },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                tripleClickTitleInput = !tripleClickTitleInput
                                tripleClickTitle = tripleClickTitleInput
                            },
                            trailingContent = {
                                Switch(checked = tripleClickTitleInput, onCheckedChange = null)
                            },
                            supportingContent = { Text("连续三击主页顶部标题栏, 可临时显示或恢复隐藏联系人") },
                            headlineContent = { Text("三击标题切换显隐") },
                        )
                    }
                })
        }
    }

    //    private val methodMainAdapterPerformSearch by dexMethod()

    // WeChat's SQLite wrapper query: d95.b0.f(String sql, String[] args, int) -> Cursor. The
    // homepage conversation-list cursor (com.tencent.mm.storage.m4.A/B) is built through this
    // wrapper, NOT the standard SQLiteDatabase.rawQuery path WeDatabaseListenerApi hooks, so we
    // intercept it directly — the same chokepoint ConversationGrouping/AggregateChats use.
    internal val methodSqliteWrapperRawQuery by dexMethod(allowFailure = true) {
        matcher {
            modifiers = JavaModifier.PUBLIC
            usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")
            paramTypes("java.lang.String", "java.lang.String[]", "int")
            returnType("android.database.Cursor")
        }
    }
    private val methodAddressMvvmListPreprocessList by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.AddressLiveList"
            usingEqStrings("snapshotList")
        }
    }

    /**
     * `fb4.z0.D0(SnsInfo, SnsObject, Context, rs, boolean, d8, String, Map, Map, List)` —
     * `SnsUtil.snsInfoToSnsStruct`. Turns a post's raw `SnsObject` (attrBuf) into the UI-facing
     * struct for every Moments renderer. See hidecontacts/HideContactsMoments.kt for why this is
     * the right chokepoint for a hidden contact's inline likes/comments on someone else's post.
     */
    internal val methodSnsInfoToSnsStruct by dexMethod {
        matcher {
            usingEqStrings("snsInfoToSnsStruct", "com.tencent.mm.plugin.sns.data.SnsUtil", "mSnsInfo is null, why?")
        }
    }

    // ── VoIPMP / ILink (the stack that actually runs on 8.0.7x) ──────────────────────────────
    // See hidecontacts/HideContactsVoip.kt for how these fit together.

    /** `ZIDL_ibmKH7hbMB.ZIDL_FBV(long, int, int, long, long, byte[] username, byte[][], boolean)` */
    internal val methodVoipMpLaunchIncomingCard by dexMethod {
        matcher {
            // 8.0.76 changed from "launchInComingCardAsync: " to "[volume report] launchInComingCardAsync: "
            usingStrings("MicroMsg.VoIPMP.CoreV2", "launchInComingCardAsync: ")
        }
    }

    /**
     * `mp5.q2.qa(Context, int, is4.r, long, long, String username, ArrayList, boolean)` — the
     * banner/notification/ringtone dispatcher. q2 declares exactly one 8-parameter method, so the
     * class anchor plus the parameter count is unambiguous.
     */
    internal val methodVoipMpLaunchBanner by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.VoIPMP.Launcher", "closeReceiverBanner")
            }
            paramCount = 8
            returnType("void")
        }
    }

    /** `mp5.q2.Qa()` — "rejectByShortCut", the entry WeChat's own quick-reject uses. */
    internal val methodVoipMpReject by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoIPMP.CoreV2", "rejectByShortCut")
            paramCount = 0
            returnType("void")
        }
    }

    /**
     * `nq5.e.a(String username, boolean videoCall, boolean outCall, long, boolean)` — the incoming
     * ringtone. NB: this is NOT the old `MicroMsg.RingPlayer` / "playSound, type: ..." match, which
     * resolved to the call-ENDED tone and therefore never silenced anything.
     */
    internal val methodVoipMpStartRing by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPRingtoneController", "startRing() called with: username = ")
        }
    }

    /** `xp5.b.d(String username, boolean, boolean, boolean)` — starts the VoIP foreground service. */
    internal val methodVoipMpStartFgs by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPVoIPNotificationHelper", "startFGS isBindVoIPForegroundService ")
        }
    }

    /** `mp5.q2.Ii(String toUser, ...)` — VoIPMP call-record insertion (未接听 / 已取消 / duration). */
    internal val methodVoipMpInsertMsg by dexMethod {
        matcher {
            // The CoreV2 ZIDL stub logs the same text under a different tag; pairing with the
            // Launcher tag picks out q2.Ii.
            usingEqStrings("MicroMsg.VoIPMP.Launcher", "insertMsg() called with: toUser = ")
        }
    }

    // ── multitalk (群通话), used when the VoIPMP multitalk experiment is off ───────────────────

    /** `v0.G(MultiTalkGroup)` — MultiTalkManager.onInviteMultiTalk. */
    internal val methodMultiTalkOnInvite by dexMethod {
        matcher {
            usingEqStrings(
                "MicroMsg.MT.MultiTalkManager",
                "onInviteMultiTalk All Var Value:\n isMute: %b isHandsFree: %b isCameraFace: %b multiTalkStatus: %s groupIsNull: %b"
            )
        }
    }

    /**
     * `v0.g(isReject, isMissCall, isPhoneCall, isNetworkError, boolean, boolean)` —
     * exitCurrentMultiTalk. Declared on the same `v0` (MultiTalkManager) as [methodMultiTalkOnInvite],
     * so the invite hook's `thisObject` is the receiver to invoke this on — no separate singleton
     * lookup needed.
     *
     * NB: do NOT resolve a singleton getter by referencing `methodExitMultiTalk.method` from another
     * matcher block. With `allowFailure = true` a failed resolution leaves a placeholder, and reading
     * `.method` on a placeholder throws — which would take down dex resolution for the whole feature
     * on a cold cache.
     */
    internal val methodExitMultiTalk by dexMethod(allowFailure = true) {
        matcher {
            usingStrings("exitCurrentMultiTalk: isReject %b isMissCall %b isPhoneCall %b isNetworkError %b")
        }
    }

    // ── legacy v2protocal stack (only reached when the peer downgrades) ───────────────────────

    /** `nr4.y.x(...)` — the incoming float card. Shared by both stacks, so live on 8.0.76 as well. */
    internal val methodVoipShowFloatingCard by dexMethod {
        matcher {
            usingEqStrings(".ui.voip.VoipFloatView")
            paramCount = 8
        }
    }

    /**
     * `nr4.y.z(Context, String toUser)` — AnimatedVoipBaseFloatCardManager.showFinishCard, the
     * "已拒绝通话" banner shown *after* a rejection. Distinct from [methodVoipShowFloatingCard]
     * (the incoming card) even though both live on `nr4.y`, so suppressing the incoming card does
     * not cover it.
     *
     * The bare "showFinishCard" string constant occurs only in this method (the lambda classes
     * carry longer `...$showFinishCard$3$2$...` constants, which `usingEqStrings` will not match).
     */
    internal val methodVoipShowFinishCard by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("showFinishCard", "(Landroid/content/Context;Ljava/lang/String;)V")
            paramCount = 2
        }
    }
    internal val methodVoipAcceptIncomingCall by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "acceptIncomingCal, roomInfo:")
        }
    }
    internal val methodVoipStartAcceptVoip by dexMethod {
        searchPackages("com.tencent.mm.plugin.voip")
        matcher {
            usingEqStrings("MicroMsg.VoipIncomingCallManager", "startAcceptVoIP, roomInfo:")
        }
    }
    internal val methodVoipServiceExSetInviteContent by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to setInviteContent during calling, status =")
        }
    }
    internal val methodVoipServiceExReject by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Voip.VoipServiceEx", "Failed to reject with calling, status =")
        }
    }

    /** `j0.j(String content, a65.j4 addMsg)` — server-pushed `<voipmsg>` bubble (msg type 50). */
    internal val methodVoipBubbleHandle by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.VoIPBubbleHelper", "handlerBubbleMsg: parse bubble info error")
        }
    }

    /**
     * `b2.d(String talker, String, int, int, String, boolean, k0, f16.l)` — legacy call-record
     * insertion.
     *
     * NB: do NOT match on "insertMsg() called with: voipInfo = " — those strings live in the
     * synthetic Runnable `b2$$a.run()`, which takes ZERO parameters, so the previous matcher made
     * `args[0]` throw on every legacy call record. The callagain URL is unique to b2 itself, and
     * `d` is the only 8-parameter method it declares.
     */
    internal val methodVoipLegacyInsertMsg by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.VoipPluginManager", "weixin://voip/callagain/?username=")
            }
            paramCount = 8
            returnType("void")
        }
    }

//    private val classVoipService by dexClass()
//    private val classVoipManager by dexClass()
//    private val classIncomingVoipInvite by dexClass()
//    private val classIncomingVoipILinkInvite by dexClass()
//    private val classMultiTalkInvite by dexClass()
//    private val classVoipFloatCard by dexClass()
//    private val classRecentForwardInfoHelperV3 by dexClass()
//    private val classContactRecommendHelperV3 by dexClass()
}
