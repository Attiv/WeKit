package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeUnsafeApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.contacts.SplitGroupCall.resolveDex
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.int
import org.luckypray.dexkit.DexKitBridge
import kotlin.concurrent.thread
import kotlin.random.Random
import androidx.compose.ui.Modifier as UiModifier
import java.lang.reflect.Modifier as ReflectModifier

@Feature(
    name = "分裂群组通话",
    categories = ["娱乐"],
    description = "随机生成假群 ID, 并发起群通话后挂断推送到他人手机"
)
object SplitGroupCall : ClickableFeature(), IContactInfoProvider, IResolveDex {

    private const val TAG = "SplitGroupCall"
    private const val PREF_KEY = "split_group_call"

    /** com.tencent.mm.plugin.multitalk.model.e3 —— SubCoreMultiTalk. */
    private val classSubCoreMultiTalk by dexClass {
        matcher {
            usingStrings("MicroMsg.SubCoreMultiTalk", "add , is running , forbid add")
        }
    }

    /** com.tencent.mm.plugin.multitalk.model.v0 —— MultiTalkManager. */
    private val methodExitMultiTalk by dexMethod {
        matcher {
            usingStrings("exitCurrentMultiTalk: isReject %b isMissCall %b isPhoneCall %b isNetworkError %b")
        }
    }

    private val multiTalkManagerClass by lazy { methodExitMultiTalk.method.declaringClass }

    /** com.tencent.mm.plugin.multitalk.ilinkservice.i4 —— ILinkService (enum, 单例 INSTANCE). */
    private val classILinkService by dexClass {
        matcher {
            usingStrings("steve: initsession : mIsInitedEngine :%b mIsInitingEngine %b mCurrentStatus %d mIsJoiningRoom %b")
        }
    }

    /** com.tencent.mm.plugin.multitalk.ilinkservice.w —— ILinkMember. */
    private val classILinkMember by dexClass {
        matcher {
            usingStrings("ILinkMember{memberId=")
        }
    }

    /** com.tencent.mm.plugin.multitalk.ilinkservice.n1 —— 邀请任务 (Runnable). */
    private val classInviteTask by dexClass {
        matcher {
            usingStrings("enter inviteSync. %s, %s, %d, %b")
        }
    }

    /** e3.Ri() —— 获取 MultiTalkManager 单例. */
    private val methodGetMultiTalkManager by dexMethod {
        matcher {
            declaredClass = classSubCoreMultiTalk.getDescriptorString()!!
            modifiers = ReflectModifier.STATIC or ReflectModifier.PUBLIC
            returnType(multiTalkManagerClass)
        }
    }

    /** v0.D(e4) —— 设置通话状态 (onChangeMultiTalkStatus). */
    private val methodSetStatus by dexMethod {
        matcher {
            declaredClass(multiTalkManagerClass)
            paramCount = 1
            usingStrings("onChangeMultiTalkStatus is %s")
        }
    }

    /** v0.O(String, int) —— setCurrentMTSDKMode, 记录群 -> 通话模式. */
    private val methodSetMtSdkMode by dexMethod {
        matcher {
            declaredClass(multiTalkManagerClass)
            paramCount = 2
            usingStrings("setCurrentMTSDKMode groupid:%s, mode:%d")
        }
    }

    /** i4.N(long, String) —— 设置自身 uin 与用户名 (set name). */
    private val methodSetName by dexMethod {
        matcher {
            declaredClass = classILinkService.getDescriptorString()!!
            paramCount = 2
            usingStrings("set name=%s, uin=%d")
        }
    }

    /** i4.J(Runnable) —— 投递任务到 ILink 串行工作线程. */
    private val methodPostTask by dexMethod {
        matcher {
            declaredClass = classILinkService.getDescriptorString()!!
            paramCount = 1
            paramTypes("java.lang.Runnable")
        }
    }

    /** n1(i4, ArrayList<w>, String) —— 邀请任务构造器. */
    private val ctorInviteTask by dexConstructor {
        matcher {
            declaredClass = classInviteTask.getDescriptorString()!!
            paramCount = 3
            paramTypes(classILinkService.getDescriptorString()!!, "java.util.ArrayList", "java.lang.String")
        }
    }

    /**
     * c1(i4, int) —— 挂断任务 (Runnable), run() 调用 native Hangup(int)。
     * c1 与 i4 都含有字符串 "Hangup ret:", 但 i4 (enum) 的构造器签名是 (String, int),
     * 因此用 (i4, int) 的参数签名即可唯一命中 c1 的构造器。
     */
    private val ctorHangupTask by dexConstructor {
        matcher {
            declaredClass {
                usingStrings("Hangup ret:")
            }
            paramCount = 2
            paramTypes(classILinkService.getDescriptorString()!!, "int")
        }
    }

    /** i4.INSTANCE —— ILinkService 单例. */
    private val fieldILinkInstance by dexField {
        matcher {
            declaredClass = classILinkService.getDescriptorString()!!
            type = classILinkService.getDescriptorString()!!
            modifiers = ReflectModifier.PUBLIC or ReflectModifier.STATIC or ReflectModifier.FINAL
        }
    }

    /**
     * i4.f166883p1 —— 房间 ID (chatroom username) 字符串字段, 进入 native Invite。
     * i4 上有多个 String 字段, 无法按名字/顺序命中; 通过 "唯一读取该字段的方法" 反查:
     * 方法 p(b) 含字符串 "start audio device failed", 且其中唯一被读取的 String 字段即 f166883p1。
     * 由 [resolveDex] 手动填充。
     */
    private val fieldRoomId by dexField()

    override fun resolveDex(dexKit: DexKitBridge) {
        val iLinkServiceName = classILinkService.clazz.name
        val readerMethod = dexKit.findMethod {
            matcher {
                declaredClass = classILinkService.getDescriptorString()!!
                usingStrings("start audio device failed")
            }
        }.single()
        val roomIdField = readerMethod.usingFields
            .map { it.field }
            .single { it.className == iLinkServiceName && it.typeName == "java.lang.String" }
        fieldRoomId.setDescriptor(roomIdField)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            SingleContactSelector(
                "分裂群组通话",
                WeDatabaseApi.getGroups(),
                initialSelectedWxId = null,
                onDismiss = onDismiss,
            ) { wxId ->
                onDismiss()
                showSplitCallDialog(context, wxId)
            }
        }
    }

    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val wxId = activity.currentWxId ?: return emptyList()
        if (!wxId.endsWith("@chatroom")) return emptyList()

        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = "分裂群组通话",
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false
        val wxId = activity.currentWxId ?: return true
        showSplitCallDialog(activity, wxId)
        return true
    }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    private fun generateFakeGroupId(wxId: String): String {
        val rawId = wxId.substringBefore("@")
        val randomCount = Random.nextInt(1, 4)
        val cjkChars = (0 until randomCount).map {
            (0x4E00..0x9FA5).random().toChar()
        }.joinToString("")
        return "${rawId}${cjkChars}@chatroom"
    }

    private fun showSplitCallDialog(context: Activity, wxId: String) {
        showComposeDialog(context) {
            var fakeGroupId by remember { mutableStateOf(generateFakeGroupId(wxId)) }

            AlertDialogContent(
                title = { Text("分裂群组通话") },
                text = {
                    Column(
                        modifier = UiModifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "原群聊 ID: $wxId",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "生成假群 ID: $fakeGroupId",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = UiModifier.height(4.dp))
                        Button(
                            onClick = { fakeGroupId = generateFakeGroupId(wxId) },
                            modifier = UiModifier.fillMaxWidth()
                        ) {
                            Text("重新生成随机汉字假群 ID")
                        }
                        Spacer(modifier = UiModifier.height(8.dp))
                        Button(
                            onClick = {
                                openChatroom(context, fakeGroupId)
                            },
                            modifier = UiModifier.fillMaxWidth()
                        ) {
                            Text("仅打开本地假群")
                        }
                        Button(
                            onClick = {
                                startAndCancelCall(context, wxId, fakeGroupId)
                            },
                            modifier = UiModifier.fillMaxWidth()
                        ) {
                            Text("发起假群通话并挂断")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {}
            )
        }
    }

    private fun openChatroom(context: Context, targetGroupId: String) {
        runCatching {
            WeLogger.i(TAG, "launching ChattingUI for fake chatroom: $targetGroupId")
            val intent = Intent(context, ChattingUI::class.java).apply {
                putExtra("Chat_User", targetGroupId)
                putExtra("Chat_Mode", 1)
            }
            context.startActivity(intent)
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to launch ChattingUI for fake chatroom", e)
            showToast("打开假群失败: ${e.message}")
        }
    }

    /**
     * 复刻 WeChat 发起群通话的真实流程 (com.tencent.mm.plugin.multitalk.ui.u#onMenuItemClick):
     *   1. v0.D(e4.Creating)          —— 通话状态置为「创建中」
     *   2. i4.N(selfUin, selfWxId)     —— 设置自身身份 (e2 据此把自己从被邀请者中剔除)
     *   3. i4.f166883p1 = fakeGroupId  —— 房间 ID
     *   4. i4.J(new n1(i4, members, fakeGroupId)) —— 投递邀请任务 -> 引擎初始化 -> native Invite (对方响铃)
     *   5. v0.O(fakeGroupId, 2)        —— 记录群 -> 通话模式
     * 之后延时若干秒 (让邀请下发、对方响铃), 再投递 c1(i4, 1) 触发 native Hangup 挂断。
     */
    private fun startAndCancelCall(context: Context, originalGroupId: String, fakeGroupId: String) {
        thread(name = "SplitGroupCallThread") {
            runCatching {
                WeLogger.i(TAG, "initiating fake group call: $fakeGroupId (original: $originalGroupId)")

                val iLink = fieldILinkInstance.field.get(null)
                    ?: error("ILinkService instance is null")
                val mgr = methodGetMultiTalkManager.method.invoke(null)
                    ?: error("MultiTalkManager instance is null")

                // e4 状态枚举: [Init, Inviting, Creating, Starting, Talking]
                val statusEnumClass = methodSetStatus.method.parameterTypes[0]
                val statusValues = statusEnumClass.enumConstants
                    ?: error("multitalk status is not an enum")
                check(statusValues.size >= 3) { "unexpected multitalk status enum: ${statusValues.size}" }
                val statusInit = statusValues[0]
                val statusCreating = statusValues[2]

                val statusField = mgr.javaClass.declaredFields
                    .first { it.type == statusEnumClass }
                    .apply { isAccessible = true }

                if (statusField.get(mgr) != statusInit) {
                    WeLogger.w(TAG, "multitalk is not idle, aborting")
                    runOnUiThread { showToast("微信当前可能正在通话, 无法发起假群通话") }
                    return@runCatching
                }

                // 被邀请成员 = 原群真实成员 + 自己 (自己会在 e2 中被剔除, 不会响铃自身)
                val selfWxId = RuntimeConfig.loggedInWxId
                val selfUin = context
                    .getSharedPreferences("system_config_prefs", Context.MODE_PRIVATE)
                    .getInt("default_uin", 0)
                    .toLong()

                val memberWxIds = WeDatabaseApi.getGroupMembers(originalGroupId)
                    .map { it.wxId }
                    .filter { it.isNotEmpty() }
                    .toMutableList()
                if (selfWxId.isNotEmpty() && selfWxId !in memberWxIds) memberWxIds += selfWxId

                if (memberWxIds.isEmpty()) {
                    runOnUiThread { showToast("未获取到群成员") }
                    return@runCatching
                }

                val memberList = ArrayList<Any>(memberWxIds.size)
                for (memberWxId in memberWxIds) {
                    val member = WeUnsafeApi.allocateInstance(classILinkMember.clazz)!!
                    member.reflekt().apply {
                        // w 的 String 字段顺序: [openId, mUserName, mInviteUserName] -> [1] = mUserName
                        fields { type = BString }[1].set(memberWxId)
                        // w 的 int 字段顺序: [memberId, mStatus, mScreenStatus] -> [1] = mStatus
                        fields { type = int }[1].set(2)
                    }
                    memberList.add(member)
                }

                runOnUiThread {
                    runCatching {
                        methodSetStatus.method.invoke(mgr, statusCreating)
                        methodSetName.method.invoke(iLink, selfUin, selfWxId)
                        fieldRoomId.field.set(iLink, fakeGroupId)

                        val inviteTask =
                            ctorInviteTask.constructor.newInstance(iLink, memberList, fakeGroupId) as Runnable
                        methodPostTask.method.invoke(iLink, inviteTask)

                        methodSetMtSdkMode.method.invoke(mgr, fakeGroupId, 2)
                        WeLogger.i(TAG, "invite posted for ${memberList.size} members")
                    }.onFailure { e ->
                        WeLogger.e(TAG, "failed to post invite", e)
                        showToast("发起通话失败: ${e.message}")
                    }
                }

                runOnUiThread { showToast("已发起假群通话, 数秒后自动挂断") }

                // 等待邀请下发并让对方响铃, 再挂断
                Thread.sleep(3000)

                runOnUiThread {
                    runCatching {
                        // native Hangup —— 停止响铃/结束通话
                        val hangupTask = ctorHangupTask.constructor.newInstance(iLink, 1) as Runnable
                        methodPostTask.method.invoke(iLink, hangupTask)
                    }.onFailure { e -> WeLogger.e(TAG, "failed to post hangup task", e) }

                    // 复位 MultiTalkManager 状态 (等价于 v0.f(false, false))
                    runCatching {
                        methodExitMultiTalk.method.invoke(mgr, false, false, false, false, true, false)
                    }.onFailure { e ->
                        WeLogger.w(TAG, "exitCurrentMultiTalk failed, resetting status directly", e)
                        runCatching { statusField.set(mgr, statusInit) }
                    }
                    showToast("假群通话已挂断")
                }
            }.onFailure { e ->
                WeLogger.e(TAG, "failed to start/cancel fake group call", e)
                runOnUiThread {
                    showToast("发起通话失败: ${e.message}")
                }
            }
        }
    }
}
