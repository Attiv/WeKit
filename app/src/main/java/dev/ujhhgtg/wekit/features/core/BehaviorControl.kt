package dev.ujhhgtg.wekit.features.core

import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.utils.WeLogger

/*
 * Global opt-in gate for features that perform actions, mutate host data, or expose automation.
 *
 * The gate defaults to off. Individual feature preferences are retained while the gate is off,
 * but the affected features are neither resolved nor started until the user explicitly opts in.
 */
object BehaviorControl {

    private const val TAG = "BehaviorControl"

    // Keep this list explicit so newly added action-oriented features require a conscious review.
    private val behaviorTechnicalIds = setOf(
        "批量删除聊天记录",
        "群发消息",
        "防撤回",
        "批量撤回",
        "转发收藏语音",
        "转发消息",
        "一键撤回并重新编辑",
        "消息复读",
        "安全消息发送",
        "反安全消息",
        "发送卡片消息",
        "发送语音文件",
        "移除媒体发送数量限制",
        "自动启用合并发送媒体",
        "自动启用发送原图",
        "自动语音转文字",
        "自动查看原图",
        "自动缓存文件",
        "自动缓存图片",
        "自动添加附近的人",
        "添加自动备注",
        "加入群聊自动免打扰",
        "移除消息批量转发限制",
        "隐藏联系人",
        "隐藏联系人通知抑制",
        "朋友圈评论防撤回",
        "朋友圈防撤回",
        "自动点赞",
        "自动转发",
        "转发 & 一键转发",
        "自动刷新",
        "伪集赞",
        "自定义尾巴",
        "允许领取私聊红包",
        "自动接收转账",
        "自动抢红包",
        "捡漏历史红包",
        "运动排行榜自动点赞",
        "自动批准设备登录",
        "清空未读",
        "发包调试",
        "启动微信内部 URL",
        "脚本引擎 (Java)",
        "脚本 Hook 服务",
        "Python 插件引擎",
        "API + MCP 服务器",
        "WeAgent",
    )

    val isEnabled: Boolean
        get() = Preferences.behaviorFeaturesEnabled

    fun isBehaviorFeature(feature: BaseFeature): Boolean =
        feature.technicalId in behaviorTechnicalIds

    fun canEnable(feature: BaseFeature): Boolean =
        !isBehaviorFeature(feature) || isEnabled

    fun setEnabled(enabled: Boolean) {
        Preferences.behaviorFeaturesEnabled = enabled
        if (!enabled) {
            FeaturesProvider.ALL_FEATURES
                .filter(::isBehaviorFeature)
                .forEach(BaseFeature::disable)
        }
        WeLogger.i(TAG, "behavior features ${if (enabled) "enabled" else "disabled"}")
    }
}
