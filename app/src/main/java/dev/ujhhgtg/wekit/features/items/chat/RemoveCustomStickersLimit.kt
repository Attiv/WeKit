package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.reflection.int
import java.lang.reflect.Modifier

@Feature(
    name = "解除单个表情数量上限",
    categories = ["聊天"],
    description = "解除表情面板「添加的单个表情」分类的 999 个数量上限, 允许无限添加图片表情"
)
object UnlockCustomEmojiLimit : SwitchFeature(), IResolveDex {

    private val methodGetCustomEmojiMaxSize by dexMethod {
        matcher {
            usingEqStrings("CustomEmojiMaxSize")
            returnType(int)
            paramCount(0)
            modifiers = Modifier.STATIC
        }
    }

    private val methodComputeEmojiStorageState by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.EmojiStorageState", "normal_custom_size")
            returnType(Void.TYPE)
            paramCount(1)
        }
    }

    private val classMmkv by dexClass {
        matcher {
            usingEqStrings("MicroMsg.MultiProcessMMKV", "getMMKV name is illegal")
        }
    }

    private val methodGetMmkv by dexMethod {
        matcher {
            declaredClass(classMmkv.clazz)
            usingEqStrings("MicroMsg.MultiProcessMMKV", "getMMKV name is illegal")
        }
    }

    private val methodAddEmojiOnSceneEnd by dexMethod {
        matcher {
            usingEqStrings("CgiBackupEmojiOperate onResult: errType=")
        }
    }

    private val classCgiBack by dexClass {
        matcher {
            usingEqStrings("CgiBack{errType=")
        }
    }

    private val methodChattingUiEmoji by dexMethod {
        matcher {
            usingEqStrings("addToCustom. over max size.")
        }
    }

    private val methodConditionallyShowDialog by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("uninstallEmoticon Failed to send net scene: ")
            }
            usingEqStrings("custom_full")
        }
    }

    private fun putCustomFullFalseInMmkv() {
        runCatching {
            val mmkv = methodGetMmkv.method.invoke(null, "emoji_stg", 2, null)
            mmkv.reflekt().invokeMethod("putBoolean", "custom_full", false)
        }
    }

    override fun onEnable() {
        methodGetCustomEmojiMaxSize.hookBefore {
            result = Int.MAX_VALUE
        }

        methodAddEmojiOnSceneEnd.hookBefore {
            val resp = args[0]
            classCgiBack.reflekt<Any>().fields {
                type = int
            }.forEach {
                it.set(resp, 0)
            }
        }

        listOf(
            methodComputeEmojiStorageState,
            methodChattingUiEmoji,
            methodConditionallyShowDialog
        ).forEach {
            it.hookBefore {
                putCustomFullFalseInMmkv()
            }
        }
    }
}
