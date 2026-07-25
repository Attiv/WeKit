package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.view.View
import androidx.compose.material3.Text
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.FavInfoProto
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

@Feature(name = "转发收藏语音", categories = ["聊天"], description = "在聊天菜单的「收藏」中允许转发语音")
object ForwardFavoriteVoices : SwitchFeature() {

    @OptIn(ExperimentalSerializationApi::class)
    override fun onEnable() {
        "com.tencent.mm.plugin.fav.ui.FavSelectUI".toClass().reflekt().firstMethod { name = "onItemClick" }.hookBefore {
            val view = args[1] as View

            val tag = view.tag

            val a = tag.reflekt().firstField { name = "a"; superclass() }.get()!!

            val type = a.reflekt().firstField { name = "field_type"; superclass() }.get()!! as Int

            if (type != 3) return@hookBefore

            val favPhoto = a.reflekt().firstField { name = "field_favProto"; superclass() }.get()!!
            val bytes = favPhoto.reflekt().firstMethod { name = "getData"; superclass() }.invoke()!! as ByteArray

            val favInfo = ProtoBuf.decodeFromByteArray<FavInfoProto>(bytes)
            val voiceInfo = favInfo.voiceInfo

            val cacheName = voiceInfo.fileCacheName
            val bucketId = cacheName.hashCode() and 0xFF
            val cacheDir = RuntimeConfig.userDataDir / "favorite" / bucketId.toString()
            val voiceFilePath =
                (cacheDir / "$cacheName.${voiceInfo.fileCacheType}").absolutePathString()

            val ctx = thisObject as Activity

            showComposeDialog(ctx) {
                AlertDialogContent(
                    title = { Text("转发收藏语音") },
                    text = {
                        Text(
                            "确定发送以下文件?\n" +
                                    voiceFilePath
                        )
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                    confirmButton = {
                        TextButton({
                            copyToClipboard(ctx, voiceFilePath)
                            showToast(ctx, "已复制")
                        }) { Text("复制路径") }
                        Button({
                            WeMessageApi.sendVoice(
                                WeCurrentConversationApi.value,
                                voiceFilePath,
                                voiceInfo.duration
                            )
                            showToast(ctx, "已发送")
                            onDismiss()
                            getTopMostActivity()?.finish()
                        }) { Text("确定") }
                    })
            }

            result = null
        }
    }
}
