//package dev.ujhhgtg.wekit.features.items.contacts
//
//import android.app.Activity
//import android.content.Context
//import android.content.Intent
//import androidx.activity.ComponentActivity
//import androidx.compose.foundation.layout.Arrangement
//import androidx.compose.foundation.layout.Column
//import androidx.compose.foundation.layout.Spacer
//import androidx.compose.foundation.layout.fillMaxWidth
//import androidx.compose.foundation.layout.height
//import androidx.compose.foundation.layout.padding
//import androidx.compose.material3.MaterialTheme
//import androidx.compose.material3.Text
//import androidx.compose.runtime.getValue
//import androidx.compose.runtime.mutableStateOf
//import androidx.compose.runtime.remember
//import androidx.compose.runtime.setValue
//import androidx.compose.ui.unit.dp
//import com.tencent.mm.ui.chatting.ChattingUI
//import dev.ujhhgtg.reflekt.reflekt
//import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
//import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
//import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
//import dev.ujhhgtg.wekit.dexkit.dsl.dexField
//import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
//import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
//import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
//import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
//import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
//import dev.ujhhgtg.wekit.features.core.ClickableFeature
//import dev.ujhhgtg.wekit.features.core.Feature
//import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
//import dev.ujhhgtg.wekit.ui.content.Button
//import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
//import dev.ujhhgtg.wekit.ui.content.TextButton
//import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
//import dev.ujhhgtg.wekit.utils.WeLogger
//import dev.ujhhgtg.wekit.utils.android.currentWxId
//import dev.ujhhgtg.wekit.utils.android.runOnUiThread
//import dev.ujhhgtg.wekit.utils.android.showToast
//import dev.ujhhgtg.wekit.utils.reflection.BString
//import dev.ujhhgtg.wekit.utils.reflection.int
//import kotlin.concurrent.thread
//import kotlin.random.Random
//import androidx.compose.ui.Modifier as UiModifier
//import java.lang.reflect.Modifier as ReflectModifier
//
//@Feature(
//    name = "分裂群组通话",
//    categories = ["娱乐"],
//    description = "随机生成假群 ID, 并发起群通话后挂断推送到他人手机 (没写完)"
//)
//object SplitGroupCall : ClickableFeature(), IContactInfoProvider, IResolveDex {
//
//    private const val TAG = "SplitGroupCall"
//    private const val PREF_KEY = "split_group_call"
//
//    private val classSubCoreMultiTalk by dexClass {
//        matcher {
//            usingStrings("MicroMsg.SubCoreMultiTalk", "add , is running , forbid add")
//        }
//    }
//
//    private val methodExitMultiTalk by dexMethod {
//        matcher {
//            usingStrings("exitCurrentMultiTalk: isReject %b isMissCall %b isPhoneCall %b isNetworkError %b")
//        }
//    }
//
//    private val multiTalkManagerClass by lazy { methodExitMultiTalk.method.declaringClass }
//
//    private val classILinkService by dexClass {
//        matcher {
//            usingStrings("steve: initsession : mIsInitedEngine :%b mIsInitingEngine %b mCurrentStatus %d mIsJoiningRoom %b")
//        }
//    }
//
//    private val classILinkMember by dexClass {
//        matcher {
//            usingStrings("ILinkMember{memberId=")
//        }
//    }
//
//    private val classInviteTask by dexClass {
//        matcher {
//            usingStrings("enter inviteSync. %s, %s, %d, %b")
//        }
//    }
//
//    private val methodGetMultiTalkManager by dexMethod {
//        matcher {
//            declaredClass = classSubCoreMultiTalk.getDescriptorString()!!
//            modifiers = ReflectModifier.STATIC or ReflectModifier.PUBLIC
//            returnType(multiTalkManagerClass)
//        }
//    }
//
//    private val methodExitProjectScreen by dexMethod {
//        matcher {
//            declaredClass(multiTalkManagerClass)
//            paramCount = 0
//            usingStrings("exitProjectScreen")
//        }
//    }
//
//    private val methodSwitchAVAction by dexMethod {
//        matcher {
//            declaredClass(multiTalkManagerClass)
//            paramCount = 1
//            paramTypes("int")
//            returnType = "boolean"
//        }
//    }
//
//    private val methodPostTask by dexMethod {
//        matcher {
//            declaredClass = classILinkService.getDescriptorString()!!
//            paramCount = 1
//            paramTypes("java.lang.Runnable")
//        }
//    }
//
//    private val ctorInviteTask by dexConstructor {
//        matcher {
//            declaredClass = classInviteTask.getDescriptorString()!!
//            paramCount = 3
//            paramTypes(classILinkService.getDescriptorString()!!, "java.util.ArrayList", "java.lang.String")
//        }
//    }
//
//    private val fieldILinkInstance by dexField {
//        matcher {
//            declaredClass = classILinkService.getDescriptorString()!!
//            type = classILinkService.getDescriptorString()!!
//            modifiers = ReflectModifier.PUBLIC or ReflectModifier.STATIC or ReflectModifier.FINAL
//        }
//    }
//
//    private val fieldILinkChatroom by dexField(allowMultiple = true, resultIndex = 0) {
//        matcher {
//            declaredClass = classILinkService.getDescriptorString()!!
//            type = "java.lang.String"
//        }
//    }
//
//    override fun onClick(context: ComponentActivity) {
//        showComposeDialog(context) {
//            SingleContactSelector(
//                "分裂群组通话",
//                WeDatabaseApi.getGroups(),
//                initialSelectedWxId = null,
//                onDismiss = onDismiss,
//            ) { wxId ->
//                onDismiss()
//                showSplitCallDialog(context, wxId)
//            }
//        }
//    }
//
//    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
//        val wxId = activity.currentWxId ?: return emptyList()
//        if (!wxId.endsWith("@chatroom")) return emptyList()
//
//        return listOf(
//            PreferenceItem(
//                key = PREF_KEY,
//                title = "分裂群组通话",
//                summary = "生成假群并通过发包/发起通话推送到他人手机",
//                position = 1
//            )
//        )
//    }
//
//    override fun onItemClick(activity: Activity, key: String): Boolean {
//        if (key != PREF_KEY) return false
//        val wxId = activity.currentWxId ?: return true
//        showSplitCallDialog(activity, wxId)
//        return true
//    }
//
//    override fun onEnable() {
//        WeContactPrefsScreenApi.addProvider(this)
//    }
//
//    override fun onDisable() {
//        WeContactPrefsScreenApi.removeProvider(this)
//    }
//
//    private fun generateFakeGroupId(wxId: String): String {
//        val rawId = wxId.substringBefore("@")
//        val randomCount = Random.nextInt(1, 4)
//        val cjkChars = (0 until randomCount).map {
//            (0x4E00..0x9FA5).random().toChar()
//        }.joinToString("")
//        return "${rawId}${cjkChars}@chatroom"
//    }
//
//    private fun showSplitCallDialog(context: Activity, wxId: String) {
//        showComposeDialog(context) {
//            var fakeGroupId by remember { mutableStateOf(generateFakeGroupId(wxId)) }
//
//            AlertDialogContent(
//                title = { Text("分裂群组通话") },
//                text = {
//                    Column(
//                        modifier = UiModifier
//                            .fillMaxWidth()
//                            .padding(vertical = 8.dp),
//                        verticalArrangement = Arrangement.spacedBy(8.dp)
//                    ) {
//                        Text(
//                            text = "原群聊 ID: $wxId",
//                            style = MaterialTheme.typography.bodyMedium
//                        )
//                        Text(
//                            text = "生成假群 ID: $fakeGroupId",
//                            style = MaterialTheme.typography.bodyMedium,
//                            color = MaterialTheme.colorScheme.primary
//                        )
//                        Spacer(modifier = UiModifier.height(4.dp))
//                        Button(
//                            onClick = { fakeGroupId = generateFakeGroupId(wxId) },
//                            modifier = UiModifier.fillMaxWidth()
//                        ) {
//                            Text("重新生成随机汉字假群 ID")
//                        }
//                        Spacer(modifier = UiModifier.height(8.dp))
//                        Button(
//                            onClick = {
//                                openChatroom(context, fakeGroupId)
//                            },
//                            modifier = UiModifier.fillMaxWidth()
//                        ) {
//                            Text("仅打开本地假群")
//                        }
//                        Button(
//                            onClick = {
//                                startAndCancelCall(context, wxId, fakeGroupId)
//                            },
//                            modifier = UiModifier.fillMaxWidth()
//                        ) {
//                            Text("发起假群通话并挂断")
//                        }
//                    }
//                },
//                dismissButton = {
//                    TextButton(onDismiss) { Text("取消") }
//                },
//                confirmButton = {}
//            )
//        }
//    }
//
//    private fun openChatroom(context: Context, targetGroupId: String) {
//        runCatching {
//            WeLogger.i(TAG, "launching ChattingUI for fake chatroom: $targetGroupId")
//            val intent = Intent(context, ChattingUI::class.java).apply {
//                putExtra("Chat_User", targetGroupId)
//                putExtra("Chat_Mode", 1)
//            }
//            context.startActivity(intent)
//        }.onFailure { e ->
//            WeLogger.e(TAG, "failed to launch ChattingUI for fake chatroom", e)
//            showToast("打开假群失败: ${e.message}")
//        }
//    }
//
//    private fun startAndCancelCall(context: Context, originalGroupId: String, fakeGroupId: String) {
//        thread(name = "SplitGroupCallThread") {
//            runCatching {
//                WeLogger.i(TAG, "initiating fake group call packet: $fakeGroupId (original: $originalGroupId)")
//
//                val multiTalkMgr = methodGetMultiTalkManager.method.invoke(null)
//                val i4Instance = fieldILinkInstance.field.get(null)
//
//                if (i4Instance != null) {
//                    // Set chatroom ID field on ILinkService
//                    fieldILinkChatroom.field.set(i4Instance, fakeGroupId)
//
//                    // Construct member list for fake group invite packet from original group members
//                    val groupMembers = WeDatabaseApi.getGroupMembers(originalGroupId)
//                    val memberList = ArrayList<Any>()
//                    for (member in groupMembers) {
//                        val memberObj = classILinkMember.clazz.getDeclaredConstructor().newInstance()
//                        memberObj.reflekt().apply {
//                            fields {
//                                type = BString
//                            }[1].set(member.wxId)
//
//                            fields {
//                                type = int
//                            }[1].set(2)
//                        }
//                        memberList.add(memberObj)
//                    }
//
//                    // Dispatch invite packet task to ILinkService
//                    if (memberList.isNotEmpty()) {
//                        val inviteRunnable = ctorInviteTask.constructor.newInstance(i4Instance, memberList, fakeGroupId) as Runnable
//                        methodPostTask.method.invoke(i4Instance, inviteRunnable)
//                    }
//                }
//
//                // Launch ChattingUI for target fake group
//                val intent = Intent(context, ChattingUI::class.java).apply {
//                    putExtra("Chat_User", fakeGroupId)
//                    putExtra("Chat_Mode", 1)
//                }
//                context.startActivity(intent)
//
//                // Sleep briefly, then cancel & hang up MultiTalk call
//                Thread.sleep(2000)
//                if (multiTalkMgr != null) {
//                    methodExitProjectScreen.method.invoke(multiTalkMgr)
//                    Thread.sleep(1000)
//                    methodSwitchAVAction.method.invoke(multiTalkMgr, 101)
//                    Thread.sleep(1000)
//                    methodExitMultiTalk.method.invoke(multiTalkMgr, false, false)
//                }
//                runOnUiThread {
//                    showToast("已发起假群通话并尝试挂断")
//                }
//            }.onFailure { e ->
//                WeLogger.e(TAG, "failed to start/cancel fake group call", e)
//                runOnUiThread {
//                    showToast("发起通话失败: ${e.message}")
//                }
//            }
//        }
//    }
//}
