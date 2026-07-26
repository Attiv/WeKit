package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.activity.ComponentActivity
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooterBottom
import com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.chat.FloatingChatFooter.bottomGapDp
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.constructor
import kotlin.math.roundToInt

@Feature(
    name = "悬浮输入框",
    categories = ["聊天"],
    description = "将聊天输入框改为悬浮卡片形式, 带有圆角、阴影和侧边距"
)
object FloatingChatFooter : ClickableFeature(), IResolveDex {

    private const val TAG = "FloatingChatFooter"

    // ChatFooter.switchPanel(state, ...) 的状态取值
    private const val PANEL_STATE_SMILEY = 2
    private const val PANEL_STATE_APP = 3

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_BOTTOM_GAP = 4
    private const val DEFAULT_ELEVATION = 4

    private const val MIN_CORNER_RADIUS = 0
    private const val MAX_CORNER_RADIUS = 32
    private const val MIN_SIDE_MARGIN = 0
    private const val MAX_SIDE_MARGIN = 32
    private const val MIN_BOTTOM_GAP = 0
    private const val MAX_BOTTOM_GAP = 24
    private const val MIN_ELEVATION = 0
    private const val MAX_ELEVATION = 16

    private var cornerRadiusDp by prefOption("floating_chat_footer_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_footer_side_margin", DEFAULT_SIDE_MARGIN)
    private var bottomGapDp by prefOption("floating_chat_footer_bottom_gap", DEFAULT_BOTTOM_GAP)
    private var elevationDp by prefOption("floating_chat_footer_elevation", DEFAULT_ELEVATION)

    /**
     * Locates ChatFooter.refreshBottomHeight() by the unique log string WeChat emits at the
     * start of the method. The intentional typo "keyborPx" is WeChat's own, copied faithfully.
     */
    private val methodRefreshBottomHeight by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "[refreshBottomHeight] keyborPx:%d")
        }
    }

    /**
     * ChatFooter.switchPanel(int state, boolean, int) —— 底部面板状态机。
     * state: 0=收起 1=键盘 2=表情面板 3=工具面板 4=语音。
     */
    private val methodSwitchPanel by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "switchPanel: %s, %s")
        }
    }

    /**
     * ChattingScrollLayout.scrollContentTo(int y, boolean, int, int) —— 微信展开面板的方式:
     * 对除消息列表宿主外的所有子 View 做 translationY(-y)。
     */
    private val methodScrollContentTo by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingScrollLayout",
                "scrollContentTo: y:%s, targetScroll:%s, alwaysScroll:%s"
            )
        }
    }

    override fun onEnable() {
        val reflekt = ChatFooter::class.reflekt()

        // Outline + elevation can be set immediately after construction (no LayoutParams needed)
        ChatFooter::class.constructor.hookAfter {
            applyDrawingStyle(thisObject as ChatFooter)
        }

        // Side margins must wait until onAttachedToWindow: LayoutParams is set by the parent
        // ViewGroup (ChattingScrollLayout) when it calls addView(), which happens after the
        // constructor returns. It is guaranteed to be non-null by the time the view attaches.
        reflekt.firstMethod { name = "onAttachedToWindow" }.hookAfter {
            applySideMargins(thisObject as ChatFooter)
        }

        // Shift the footer upward after WeChat writes its negative bottomMargin so the
        // rounded bottom corners clear the keyboard edge
        methodRefreshBottomHeight.hookAfter {
            applyBottomGap(thisObject as ChatFooter)
        }

        // TEMPORARY (Task 1 only): 验证锚点定位与参数含义, Task 2 会替换掉
        methodSwitchPanel.hookBefore {
            val footer = thisObject as ChatFooter
            val panel = footer.bottomPanel
            WeLogger.d(
                TAG,
                "probe switchPanel: state=${args[0]} panelVisibility=${panel?.visibility} " +
                    "panelHeight=${panel?.height} footerBottomMargin=" +
                    "${(footer.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin}"
            )
        }

        methodScrollContentTo.hookBefore {
            val panel = (thisObject as ChattingScrollLayout).footerBottomPanel
            WeLogger.d(TAG, "probe scrollContentTo: y=${args[0]} panelVisibility=${panel?.visibility}")
        }
    }

    /** ChatFooter 子树里的表情/工具面板容器。 */
    private val ChatFooter.bottomPanel: ChatFooterBottom?
        get() = findViewWhich { it is ChatFooterBottom }

    /**
     * 从 ChattingScrollLayout 定位面板。只遍历 ChatFooter 子树 —— 直接对整个
     * ChattingScrollLayout 做 DFS 会先把整条消息列表走一遍, 白白付出代价。
     */
    private val ChattingScrollLayout.footerBottomPanel: ChatFooterBottom?
        get() {
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child is ChatFooter) return child.bottomPanel
            }
            return null
        }

    /** Sets the outline provider, corner clipping, and elevation — all drawing properties
     *  that don't require LayoutParams. Safe to call from the constructor hook. */
    private fun applyDrawingStyle(footer: ChatFooter) {
        val density = footer.resources.displayMetrics.density
        footer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Use the view's full measured height as the outline bounds.
                //
                // When AppPanel is closed ChatFooterBottom is GONE so view.height equals
                // just the input row (~56dp). When AppPanel is open ChatFooterBottom
                // becomes VISIBLE and contributes its height, so view.height expands to
                // include it. In both cases view.height is the correct clip boundary.
                //
                // Do NOT use (view.height + bottomMargin): bottomMargin is set to
                // –keyboardHeight (≈–250dp) by WeChat, and the input row is only ~56dp,
                // so that formula produces a negative value and collapses the outline to 1px.
                val r = view.resources.displayMetrics.density * cornerRadiusDp
                outline.setRoundRect(0, 0, view.width, view.height.coerceAtLeast(1), r)
            }
        }
        footer.clipToOutline = true
        footer.elevation = elevationDp * density
        WeLogger.d(TAG, "applied drawing style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
    }

    /** Applies left/right margins so the footer appears as a floating card inset from the
     *  screen edges. Called from onAttachedToWindow where LayoutParams is guaranteed non-null. */
    private fun applySideMargins(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val sideMarginPx = (sideMarginDp * footer.resources.displayMetrics.density).toInt()
        lp.leftMargin = sideMarginPx
        lp.rightMargin = sideMarginPx
        footer.requestLayout()
        WeLogger.d(TAG, "applied side margins: side=${sideMarginDp}dp")
    }

    /**
     * WeChat's refreshBottomHeight sets bottomMargin = –keyboardHeight so the footer
     * rides flush against the IME top. We add [bottomGapDp] to lift it slightly so the
     * bottom rounded corners are visible above the keyboard surface.
     *
     * Guard: skip when bottomMargin > 0 — WeChat is not managing the position here.
     */
    private fun applyBottomGap(footer: ChatFooter) {
        val gapDp = bottomGapDp
        if (gapDp == 0) return
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.bottomMargin > 0) return
        val gapPx = (gapDp * footer.resources.displayMetrics.density).toInt()
        val adjusted = lp.bottomMargin + gapPx
        if (lp.bottomMargin != adjusted) {
            lp.bottomMargin = adjusted
            footer.requestLayout()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var cornerInput by remember { mutableFloatStateOf(cornerRadiusDp.toFloat()) }
            var sideInput by remember { mutableFloatStateOf(sideMarginDp.toFloat()) }
            var gapInput by remember { mutableFloatStateOf(bottomGapDp.toFloat()) }
            var elevInput by remember { mutableFloatStateOf(elevationDp.toFloat()) }

            AlertDialogContent(
                title = { Text("悬浮输入框") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("圆角半径: ${cornerInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = cornerInput,
                                    onValueChange = { cornerInput = it },
                                    valueRange = MIN_CORNER_RADIUS.toFloat()..MAX_CORNER_RADIUS.toFloat(),
                                    steps = MAX_CORNER_RADIUS - MIN_CORNER_RADIUS - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("侧边距: ${sideInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = sideInput,
                                    onValueChange = { sideInput = it },
                                    valueRange = MIN_SIDE_MARGIN.toFloat()..MAX_SIDE_MARGIN.toFloat(),
                                    steps = MAX_SIDE_MARGIN - MIN_SIDE_MARGIN - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("底部间距: ${gapInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = gapInput,
                                    onValueChange = { gapInput = it },
                                    valueRange = MIN_BOTTOM_GAP.toFloat()..MAX_BOTTOM_GAP.toFloat(),
                                    steps = MAX_BOTTOM_GAP - MIN_BOTTOM_GAP - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("阴影强度: ${elevInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = elevInput,
                                    onValueChange = { elevInput = it },
                                    valueRange = MIN_ELEVATION.toFloat()..MAX_ELEVATION.toFloat(),
                                    steps = MAX_ELEVATION - MIN_ELEVATION - 1
                                )
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        cornerRadiusDp = cornerInput.roundToInt()
                        sideMarginDp = sideInput.roundToInt()
                        bottomGapDp = gapInput.roundToInt()
                        elevationDp = elevInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}
