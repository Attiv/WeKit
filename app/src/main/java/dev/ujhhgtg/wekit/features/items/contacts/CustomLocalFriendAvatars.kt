package dev.ujhhgtg.wekit.features.items.contacts

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.view.View
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.SpinnerAdapter
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import dev.ujhhgtg.wekit.R
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import dev.ujhhgtg.reflekt.fields
import dev.ujhhgtg.reflekt.firstField
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.BaseContactSelector
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.TargetProcess
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.moveReplacing
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.bool
import kotlinx.serialization.json.Json
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.Collator
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.min

object CustomLocalFriendAvatars : ClickableFeature(), IContactInfoProvider, IResolveDex {

    override val technicalId = "自定义好友本地头像"
    override val nameRes = R.string.feature_custom_local_friend_avatars_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_custom_local_friend_avatars_description
    override val targetProcesses = setOf(TargetProcess.MAIN, TargetProcess.PUSH)

    private const val PREF_KEY = "custom_avatar"
    private const val SEP = ";"
    private const val VIEW_TAG_CUSTOM_AVATAR = 0x57434156
    private const val VIEW_TAG_AVATAR_SCOPE = 0x57434153

    private const val TAG = "CustomLocalFriendAvatars"
    private val avatarMapFile by lazy { KnownPaths.moduleData / "custom_avatars_map.json" }

    private enum class AvatarScope(val key: String, @StringRes val titleRes: Int) {
        CHAT("chat", R.string.contacts_custom_avatar_scope_chat),
        CONVERSATION("conversation", R.string.contacts_custom_avatar_scope_conversation),
        CONTACTS("contacts", R.string.contacts_custom_avatar_scope_contacts),
        PROFILE("profile", R.string.contacts_custom_avatar_scope_profile),
        MOMENTS("moments", R.string.contacts_custom_avatar_scope_moments),
        OTHER("other", R.string.contacts_custom_avatar_scope_other),
        NOTIFICATIONS("notifications", R.string.contacts_custom_avatar_scope_notifications),
        SHORTCUTS("shortcuts", R.string.contacts_custom_avatar_scope_shortcuts);

        var enabled by prefOption("custom_avatar_scope_$key", true)
    }

    private var avatarRevision by prefOption("custom_avatar_revision", 0L)
    @Volatile
    private var loadedAvatarRevision = Long.MIN_VALUE

    private val methodNotificationAvatar by dexMethod {
        matcher {
            paramTypes("android.content.Context", "java.lang.String", "java.lang.String")
            returnType = "android.graphics.Bitmap"
            usingEqStrings("MicroMsg.NotificationAvatar", "wcf://avatar/")
        }
    }

    private val methodDesktopShortcut by dexMethod {
        matcher {
            paramTypes("android.content.Context", "java.lang.String", "boolean", "java.lang.String")
            returnType = "android.content.Intent"
            usingEqStrings(
                "MicroMsg.ShortcutManager", "getScaledBitmap fail, bmp is null",
                "com.tencent.qlauncher.extra.EXTRA_PUSH_ITEM_UNIQUE_ID",
            )
        }
    }

    private val methodAddressLayout by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"
            name = "getLayoutView"
            paramCount = 0
            returnType = "android.view.View"
        }
    }

    // ji1.s.og, most of com.tencent.mm.feature.avatar.w calls this,
    // e.g. Cg, ig, cg, og, rg
    private val methodMvvmLoadAvatar1 by dexMethod(allowFailure = true) {
        matcher {
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
                "java.lang.String",
                "float"
            )
            returnType(Void.TYPE)
            usingEqStrings("MicroMsg.AvatarGetContactServiceHelper", "put stack into pool: ")
        }
    }

    // ji1.s.pg: another exception
    private val methodMvvmLoadAvatar2 by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AvatarGetContactServiceHelper", "put stack into pool: ")
            }

            usingEqStrings("imageView")
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
            )
            returnType(Void.TYPE)
            usingNumbers(30000)
        }
    }

    private val classAvatarDrawable by dexClass {
        searchPackages("com.tencent.mm.feature.avatar")
        matcher {
            usingEqStrings("MicroMsg.AvatarDrawable", "imageView is null", "?access_token=")
        }
    }

    // com.tencent.mm.feature.avatar.w.pg; an exception: this doesn't call methodMvvmLoadAvatar
    private val methodFeatureAvatarSimple1 by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodFeatureAvatarSimple1.setDescriptor(
            dexKit.findMethod {
                matcher {
                    declaredClass(classAvatarDrawable.data.name)
                    paramTypes(
                        "android.widget.ImageView",
                        "java.lang.String"
                    )
                    returnType(Void.TYPE)

                    usingNumbers(0.5f)
                }
            }.singleOrNull() ?: dexKit.findMethod {
                matcher {
                    declaredClass(classAvatarDrawable.data.name)
                    paramTypes(
                        "android.widget.ImageView",
                        "java.lang.String"
                    )
                    returnType(Void.TYPE)

                    addInvoke {
                        declaredClass = "android.view.View"
                        name = "invalidate"
                    }
                }
            }.single()
        )
    }

    private val methodPluginsdkLoadAvatar by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.pluginsdk.ui")
        matcher {
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String"
            )
            returnType(Void.TYPE)
            usingEqStrings("MicroMsg.AvatarDrawable")
        }
    }

    private val methodHdGallerySetUsername by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.setting.ui.setting.view.GetHdHeadImageGalleryView"
            name = "setUsername"
            paramTypes("java.lang.String")
            returnType(Void.TYPE)
        }
    }

    private val methodRoundBitmap by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.sdk.platformtools")
        matcher {
            paramTypes("android.graphics.Bitmap", "boolean", "float")
            returnType = "android.graphics.Bitmap"

            addInvoke {
                usingEqStrings("MicroMsg.BitmapUtil", "getRoundedCornerBitmap in bitmap is null")
            }
        }
    }

    // com.tencent.mm.pluginsdk.ui.u.b
    val methodConversationAvatar by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui")
        matcher {
            usingEqStrings("MicroMsg.AvatarDrawable", "imageView is null")
            paramTypes(
                "android.widget.ImageView",
                "java.lang.String",
                "float",
                "boolean"
            )
            returnType = "void"

            addInvoke {
                declaredClass = "android.view.View"
                name = "invalidate"
            }
        }
    }

    @Volatile
    private var avatarMapCache: Map<String, String>? = null

    @Volatile
    var fallbackUsernameProvider: ((String) -> String?)? = null

    private val roundedBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val originalBitmapCache = ConcurrentHashMap<String, Bitmap>()
    private val boundAvatarViews = Collections.synchronizedMap(WeakHashMap<ImageView, BoundAvatar>())
    private val hostAvatarRequests = WeakHashMap<ImageView, HostAvatarRequest>()
    private val avatarAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            if (!isActive) return
            val imageView = view as ImageView
            val request = hostAvatarRequests[imageView] ?: return
            if (isViewScopeEnabled(imageView) != request.scopeEnabled) request.reload(imageView)
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    private lateinit var hdGalleryUsernameField: Field
    private lateinit var hdGalleryThumbBitmapField: Field
    private lateinit var hdGalleryHdBitmapField: Field
    private lateinit var hdGalleryLoadedField: Field
    private lateinit var hdGalleryAdapterField: Field
    private lateinit var hdGallerySetAdapterMethod: Method

    var avatarMap: Map<String, String>
        get() = synchronized(this) {
            // The notification builder may run in :push. MMKV is multi-process, whereas
            // the JSON cache is process-local; reload it after edits in the main process.
            val revision = avatarRevision
            if (loadedAvatarRevision != revision || avatarMapCache == null) {
                avatarMapCache = loadAvatarMap()
                loadedAvatarRevision = revision
                clearBitmapCaches()
            }
            avatarMapCache!!
        }
        set(value) {
            val normalized = value
                .mapKeys { it.key.trim() }
                .mapValues { it.value.trim() }
                .filterKeys { it.isNotEmpty() }
                .filterValues { it.isNotEmpty() }
            synchronized(this) {
                saveAvatarMap(normalized)
                avatarMapCache = normalized
                avatarRevision += 1L
                loadedAvatarRevision = avatarRevision
                clearBitmapCaches()
            }
        }

    override fun onEnable() {
        methodNotificationAvatar.hookBefore {
            if (!AvatarScope.NOTIFICATIONS.enabled) return@hookBefore
            val username = args[1] as? String ?: return@hookBefore
            val uri = avatarMap[username] ?: return@hookBefore
            val bitmap = decodeAvatarBitmap(uri, 192, round = false, radiusFactor = 0f)
                ?: return@hookBefore
            // WeChat recycles the bitmap returned by this loader after posting a
            // notification. Never lend it a bitmap owned by our cache or a bound View.
            result = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (!TargetProcesses.isInMain) return

        methodAddressLayout.hookAfter {
            // MvvmAddressUI's root and row holders are generic Views/obfuscated classes;
            // its fragment type is the stable owner of the contacts page.
            (result as View).setTag(VIEW_TAG_AVATAR_SCOPE, AvatarScope.CONTACTS)
        }

        methodDesktopShortcut.hookAfter {
            if (!AvatarScope.SHORTCUTS.enabled || !(args[2] as Boolean)) return@hookAfter
            val intent = result as? Intent ?: return@hookAfter
            val uri = avatarMap[args[1] as String] ?: return@hookAfter
            @Suppress("DEPRECATION")
            val original = intent.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON) ?: return@hookAfter
            val bitmap = decodeAvatarBitmap(uri, original.width, round = false, radiusFactor = 0f)
                ?: return@hookAfter
            // Both legacy shortcut broadcasts and the host's ShortcutInfo builder use
            // this result. Keep all launch/account identity extras untouched.
            @Suppress("DEPRECATION")
            intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, bitmap.copy(Bitmap.Config.ARGB_8888, false))
        }

        WeContactPrefsScreenApi.addProvider(this)

        listOf(
            methodConversationAvatar,
            methodMvvmLoadAvatar1,
            methodMvvmLoadAvatar2,
            methodFeatureAvatarSimple1,
            methodPluginsdkLoadAvatar
        ).filterNot { it.isPlaceholder }.forEach { target ->
            target.hookBefore {
                // The host explicitly accepts a null ImageView in these avatar loaders.
                val imageView = args[0] as ImageView? ?: return@hookBefore
                val wxId = args[1] as? String ?: return@hookBefore
                // Cancel pending re-application from a previous recycled binding even
                // when this binding has no override or its scope has just been disabled.
                imageView.setTag(VIEW_TAG_CUSTOM_AVATAR, null)
                boundAvatarViews.remove(imageView)
                imageView.removeOnAttachStateChangeListener(avatarAttachListener)
                hostAvatarRequests.remove(imageView)

                val redirectedId = fallbackUsernameProvider?.invoke(wxId)
                if (redirectedId != null) {
                    args[1] = redirectedId
                    return@hookBefore
                }

                if (!avatarMap.containsKey(wxId)) return@hookBefore
                val scopeEnabled = isViewScopeEnabled(imageView)
                // Do not retain the ImageView in the value of its WeakHashMap entry.
                hostAvatarRequests[imageView] = HostAvatarRequest(
                    target.method, thisObject, args.copyOf().also { it[0] = null }, scopeEnabled,
                )
                imageView.addOnAttachStateChangeListener(avatarAttachListener)
                if (scopeEnabled && applyCustomAvatar(imageView, wxId, roundAvatarRadiusFactor)) {
                    result = null
                }
            }
        }

        if (!methodHdGallerySetUsername.isPlaceholder) {
            methodHdGallerySetUsername.hookBefore {
                if (!AvatarScope.PROFILE.enabled) return@hookBefore
                val username = args[0] as? String ?: return@hookBefore
                val gallery = thisObject
                if (applyCustomHdAvatar(gallery, username)) {
                    result = null
                    (gallery as? View)?.let { view ->
                        view.post { applyCustomHdAvatar(gallery, username) }
                        view.postDelayed({ applyCustomHdAvatar(gallery, username) }, 300L)
                    }
                }
            }
        }
    }

    override fun onDisable() {
        if (TargetProcesses.isInMain) WeContactPrefsScreenApi.removeProvider(this)
        avatarMapCache = null
        clearBitmapCaches()
        boundAvatarViews.clear()
        hostAvatarRequests.keys.toList().forEach { it.removeOnAttachStateChangeListener(avatarAttachListener) }
        hostAvatarRequests.clear()
    }

    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val wxId = activity.currentWxId ?: return emptyList()
        val hasCustomAvatar = avatarMap.containsKey(wxId)
        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = activity.localizedContactsString(
                    if (hasCustomAvatar) R.string.contacts_custom_avatar_change
                    else R.string.contacts_custom_avatar_add,
                ),
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false
        val wxId = activity.currentWxId ?: return true

        if (avatarMap.containsKey(wxId)) {
            showContactAvatarDialog(activity, wxId)
        } else {
            selectAvatarImage(activity, wxId)
        }

        return true
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_custom_local_friend_avatars_name)) },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                                item {
                                    BaseWidget(
                                        title = stringResource(R.string.contacts_custom_avatar_manage),
                                        onClick = { showAvatarManager(context) },
                                    )
                                }
                            }
                        }
                        item {
                            SegmentedColumn(
                                title = stringResource(R.string.contacts_custom_avatar_scopes),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                AvatarScope.entries.forEach { scope ->
                                    item(key = scope.key) {
                                        var enabled by remember { mutableStateOf(scope.enabled) }
                                        SwitchWidget(
                                            title = stringResource(scope.titleRes),
                                            description = if (scope == AvatarScope.SHORTCUTS) {
                                                stringResource(R.string.contacts_custom_avatar_shortcut_hint)
                                            } else null,
                                            checked = enabled,
                                            onCheckedChange = {
                                                enabled = it
                                                scope.enabled = it
                                                refreshAvatarViews()
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    private fun showAvatarManager(context: Context) {
        showComposeDialog(context) {
            val clearedMessage = stringResource(R.string.contacts_custom_avatar_cleared)
            CustomAvatarManagerDialog(
                contacts = remember { loadContacts() },
                entries = avatarMap,
                onDismiss = onDismiss,
                onSelectImage = { wxId ->
                    onDismiss()
                    selectAvatarImage(context, wxId)
                },
                onRemove = { wxId ->
                    removeAvatar(wxId)
                    showToast(clearedMessage)
                    onDismiss()
                }
            )
        }
    }

    private var roundAvatarRadiusFactor by prefOption("custom_avatar_round_radius", 0.5f)

    private fun effectiveRadiusFactor(loaderRadiusFactor: Float): Float {
        return if (RoundAvatars.isEnabled) roundAvatarRadiusFactor else loaderRadiusFactor
    }

    // Used by NotificationsEvolved's own avatar cache, so enabling/disabling this
    // override or editing an avatar cannot leave a five-minute stale notification icon.
    fun notificationAvatarCacheKey(username: String): String {
        if (!isActive || !AvatarScope.NOTIFICATIONS.enabled) return ""
        val uri = avatarMap[username] ?: return ""
        return "$avatarRevision|$uri"
    }

    private fun scopeForClass(name: String): AvatarScope? = when {
        name.startsWith("com.tencent.mm.plugin.profile.") ||
            name.startsWith("com.tencent.mm.plugin.setting.ui.setting.view.GetHdHeadImageGalleryView") ||
            name.startsWith("com.tencent.mm.chatroom.ui.RoomInfoUI") -> AvatarScope.PROFILE
        name.startsWith("com.tencent.mm.ui.chatting.") ||
            name.startsWith("com.tencent.mm.pluginsdk.ui.chat.") -> AvatarScope.CHAT
        name.startsWith("com.tencent.mm.ui.conversation.") -> AvatarScope.CONVERSATION
        name.startsWith("com.tencent.mm.plugin.sns.") -> AvatarScope.MOMENTS
        name.startsWith("com.tencent.mm.ui.contact.") -> AvatarScope.CONTACTS
        else -> null
    }

    private fun isViewScopeEnabled(view: View): Boolean {
        if (AvatarScope.entries.take(6).all { it.enabled }) return true
        // The nearest host container/holder wins (LauncherUI hosts both chatting and
        // conversation pages). ContactInfoUI is classified before general contacts.
        var current: View? = view
        while (current != null) {
            (current.getTag(VIEW_TAG_AVATAR_SCOPE) as? AvatarScope)?.let { return it.enabled }
            scopeForClass(current.javaClass.name)?.let { return it.enabled }
            current.tag?.let { tag -> scopeForClass(tag.javaClass.name)?.let { return it.enabled } }
            current = current.parent as? View
        }
        var context = view.context
        while (true) {
            scopeForClass(context.javaClass.name)?.let { return it.enabled }
            if (context !is ContextWrapper || context.baseContext === context) break
            context = context.baseContext
        }
        // Newly inflated rows may not yet have a parent. Their synchronous host bind
        // call supplies the semantic page until the row is attached.
        Throwable().stackTrace.forEach { frame ->
            scopeForClass(frame.className)?.let { return it.enabled }
        }
        return AvatarScope.OTHER.enabled
    }

    private fun refreshAvatarViews() {
        // Called on the UI thread; replay the actual original binding so disabling a
        // scope restores the host avatar as well as applying newly enabled scopes.
        hostAvatarRequests.entries.map { it.key to it.value }.forEach { (view, request) ->
            request.reload(view)
        }
    }

    private fun applyCustomAvatar(imageView: ImageView, username: String, radiusFactor: Float): Boolean {
        val uri = avatarMap[username]?.takeIf { it.isNotBlank() } ?: return false
        val effectiveRadiusFactor = effectiveRadiusFactor(radiusFactor)
        val tag = "$username$SEP$uri$SEP$effectiveRadiusFactor"
        imageView.setTag(VIEW_TAG_CUSTOM_AVATAR, tag)
        boundAvatarViews[imageView] = BoundAvatar(username, uri, radiusFactor)
        loadAvatarInto(imageView, uri, effectiveRadiusFactor)
        imageView.post {
            if (isActive && imageView.getTag(VIEW_TAG_CUSTOM_AVATAR) == tag && isViewScopeEnabled(imageView)) {
                loadAvatarInto(imageView, uri, effectiveRadiusFactor)
            }
        }
        return true
    }

    private fun loadAvatarInto(imageView: ImageView, uri: String, radiusFactor: Float) {
        val targetSize = imageView.width
            .takeIf { it > 0 }
            ?: imageView.layoutParams?.width?.takeIf { it > 0 }
            ?: 156

        val shouldRound = RoundAvatars.isEnabled
        val bitmap = decodeAvatarBitmap(
            uri = uri,
            targetSize = targetSize,
            round = shouldRound,
            radiusFactor = if (shouldRound) radiusFactor else 0f
        ) ?: run {
            imageView.load(uri) {
                allowHardware(false)
                crossfade(false)
            }
            return
        }

        imageView.scaleType = ImageView.ScaleType.FIT_XY
        imageView.setImageDrawable(bitmap.toDrawable(imageView.resources))
        imageView.invalidate()
    }

    private fun applyCustomHdAvatar(gallery: Any?, username: String): Boolean {
        if (!isActive || !AvatarScope.PROFILE.enabled) return false
        val uri = avatarMap[username]?.takeIf { it.isNotBlank() } ?: return false
        val view = gallery as? View ?: return false
        val width = view.resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val bitmap = decodeAvatarBitmap(uri, width, round = false, radiusFactor = 0f) ?: return false

        runCatching {
            ensureReflection()

            hdGalleryUsernameField.set(gallery, username)
            hdGalleryThumbBitmapField.set(gallery, bitmap)
            hdGalleryHdBitmapField.set(gallery, bitmap)
            hdGalleryLoadedField.setBoolean(gallery, true)
            @Suppress("UNCHECKED_CAST")
            val adapter = hdGalleryAdapterField.get(gallery) as? SpinnerAdapter
            if (adapter is BaseAdapter) {
                adapter.notifyDataSetChanged()
            } else if (adapter != null) {
                hdGallerySetAdapterMethod.invoke(gallery, adapter)
            }
            view.invalidate()
        }.onFailure {
            WeLogger.e(TAG, "failed to apply custom HD avatar for $username", it)
            return false
        }
        return true
    }

    private fun decodeAvatarBitmap(uri: String, targetSize: Int, round: Boolean, radiusFactor: Float): Bitmap? {
        val cacheKey = "$uri|$targetSize|$round|$radiusFactor"
        val cache = if (round) roundedBitmapCache else originalBitmapCache
        cache[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }

        val bitmap = runCatching {
            HostInfo.application.contentResolver.openInputStream(uri.toUri())?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return null

        val cropped = centerCrop(bitmap, targetSize, targetSize)
        if (cropped !== bitmap && !bitmap.isRecycled) bitmap.recycle()

        val result = if (round) roundBitmap(cropped, radiusFactor) else cropped
        if (round && result !== cropped && !cropped.isRecycled) cropped.recycle()

        cache[cacheKey] = result
        trimBitmapCache(cache)
        return result
    }

    private fun centerCrop(source: Bitmap, width: Int, height: Int): Bitmap {
        val srcWidth = source.width
        val srcHeight = source.height
        if (srcWidth <= 0 || srcHeight <= 0) return source

        val srcRatio = srcWidth.toFloat() / srcHeight
        val dstRatio = width.toFloat() / height
        val rect = if (srcRatio > dstRatio) {
            val cropWidth = (srcHeight * dstRatio).toInt().coerceAtLeast(1)
            val left = (srcWidth - cropWidth) / 2
            Rect(left, 0, left + cropWidth, srcHeight)
        } else {
            val cropHeight = (srcWidth / dstRatio).toInt().coerceAtLeast(1)
            val top = (srcHeight - cropHeight) / 2
            Rect(0, top, srcWidth, top + cropHeight)
        }

        return createBitmap(width, height).also { out ->
            Canvas(out).drawBitmap(source, rect, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true
                isDither = true
            })
        }
    }

    private fun roundBitmap(source: Bitmap, radiusFactor: Float): Bitmap {
        val radius = (min(source.width, source.height) * radiusFactor).coerceAtLeast(0f)
        roundBitmapWithWeChat(source, radius)?.let { return it }
        return roundBitmapFallback(source, radius)
    }

    private fun roundBitmapWithWeChat(source: Bitmap, radius: Float): Bitmap? {
        return runCatching {
            methodRoundBitmap.method.invoke(null, source, false, radius) as? Bitmap?
        }.getOrNull()
    }

    private fun roundBitmapFallback(source: Bitmap, radius: Float): Bitmap {
        val out = createBitmap(source.width, source.height)
        val rect = RectF(0f, 0f, out.width.toFloat(), out.height.toFloat())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            isFilterBitmap = true
            color = -0x3f3f40
        }

        val path = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        Canvas(out).apply {
            drawARGB(0, 0, 0, 0)
            drawPath(path, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            drawBitmap(source, 0f, 0f, paint)
            paint.xfermode = null
        }
        return out
    }

    private fun trimBitmapCache(cache: ConcurrentHashMap<String, Bitmap>) {
        if (cache.size <= 24) return
        cache.keys.take(cache.size - 24).forEach { key -> cache.remove(key) }
    }

    private fun showContactAvatarDialog(context: Context, wxId: String) {
        val displayName = WeDatabaseApi.getDisplayName(wxId)
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.contacts_custom_avatar_title)) },
                text = {
                    DefaultColumn {
                        Text(displayName)
                        Text(text = wxId, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            removeAvatar(wxId)
                            showToast(
                                context.localizedContactsString(
                                    R.string.contacts_custom_avatar_cleared_reopen,
                                ),
                            )
                            onDismiss()
                        }) { Text(stringResource(R.string.contacts_custom_avatar_clear)) }
                        Button(onClick = {
                            onDismiss()
                            selectAvatarImage(context, wxId)
                        }) { Text(stringResource(R.string.contacts_custom_avatar_change_action)) }
                    }
                }
            )
        }
    }

    fun selectAvatarImage(context: Context, wxId: String) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                persistReadPermission(uri)
                setAvatar(wxId, uri.toString())
                showToast(
                    context.localizedContactsString(R.string.contacts_custom_avatar_set_reopen),
                )
            }
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            HostInfo.application.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { WeLogger.w(TAG, "failed to persist avatar uri permission: $uri", it) }
    }

    private fun setAvatar(wxId: String, uri: String) {
        avatarMap = avatarMap + (wxId to uri)
        clearBitmapCaches()
    }

    fun removeAvatar(wxId: String) {
        avatarMap = avatarMap - wxId
        clearBitmapCaches()
    }

    private fun clearBitmapCaches() {
        roundedBitmapCache.clear()
        originalBitmapCache.clear()
    }

    fun onRoundAvatarConfigChanged() {
        clearBitmapCaches()
        boundAvatarViews.entries.toList().forEach { (imageView, binding) ->
            if (!isViewScopeEnabled(imageView)) return@forEach
            if (avatarMap[binding.username] != binding.uri) return@forEach
            val radiusFactor = effectiveRadiusFactor(binding.loaderRadiusFactor)
            val tag = "${binding.username}$SEP${binding.uri}$SEP$radiusFactor"
            imageView.setTag(VIEW_TAG_CUSTOM_AVATAR, tag)
            loadAvatarInto(imageView, binding.uri, radiusFactor)
            imageView.post {
                if (isActive && imageView.getTag(VIEW_TAG_CUSTOM_AVATAR) == tag && isViewScopeEnabled(imageView)) {
                    loadAvatarInto(imageView, binding.uri, radiusFactor)
                }
            }
        }
    }

    private fun ensureReflection() {
        if (::hdGalleryUsernameField.isInitialized) return
        val galleryClass = "${PackageNames.WECHAT}.plugin.setting.ui.setting.view.GetHdHeadImageGalleryView".toClass()
        val mutableBitmapFields = galleryClass.fields {
            type = Bitmap::class.java
            modifiers { !it.contains(Modifiers.FINAL) }
        }
        hdGalleryThumbBitmapField = mutableBitmapFields[0].self
        hdGalleryHdBitmapField = mutableBitmapFields[1].self
        hdGalleryUsernameField = galleryClass.firstField {
            type = BString
            modifiers { !it.contains(Modifiers.FINAL) }
        }.self.makeAccessible()
        hdGalleryLoadedField = galleryClass.firstField { type = bool }.self.makeAccessible()
        hdGalleryAdapterField = galleryClass.firstField { type { it isSubclassOf SpinnerAdapter::class } }.self.makeAccessible()
        hdGallerySetAdapterMethod = galleryClass.firstMethod {
            name = "setAdapter"
            parameters(SpinnerAdapter::class)
        }.self.makeAccessible()
    }

    private fun loadAvatarMap(): Map<String, String> {
        if (!avatarMapFile.exists()) return emptyMap()
        return runCatching {
            val raw = avatarMapFile.readText()
            Json.decodeFromString<Map<String, String>>(raw).filter { it.key.isNotBlank() && it.value.isNotBlank() }
        }.getOrElse {
            WeLogger.e(TAG, "failed to parse custom avatar map", it)
            emptyMap()
        }
    }

    private fun saveAvatarMap(value: Map<String, String>) {
        // Publish the cross-process revision only after the file was successfully saved.
        val temporary = avatarMapFile.resolveSibling("${avatarMapFile.fileName}.tmp")
        temporary.writeText(Json.encodeToString(value))
        temporary.moveReplacing(avatarMapFile)
    }

    private fun loadContacts(): List<IWeContact> {
        return runCatching {
            (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()).sortedBy { it.displayName.ifBlank { it.wxId } }
        }.getOrElse {
            WeLogger.e(TAG, "failed to load contacts for custom avatar manager", it)
            emptyList()
        }
    }

    @Composable
    private fun CustomAvatarManagerDialog(
        contacts: List<IWeContact>,
        entries: Map<String, String>,
        onDismiss: () -> Unit,
        onSelectImage: (String) -> Unit,
        onRemove: (String) -> Unit
    ) {
        var searchQuery by remember { mutableStateOf("") }
        val chinaCollator = remember { Collator.getInstance(Locale.CHINA) }
        val localizedContext = LocalWeKitLocalizedContext.current

        val fullContactsList = remember(contacts, entries) {
            val entryContacts = entries.keys.map { wxId ->
                contacts.firstOrNull { it.wxId == wxId } ?: SimpleContact(wxId, WeDatabaseApi.getDisplayName(wxId))
            }
            (entryContacts + contacts).distinctBy { it.wxId }
        }

        val filteredContacts = remember(searchQuery, fullContactsList, chinaCollator) {
            fullContactsList.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                        it.wxId.contains(searchQuery, ignoreCase = true)
            }.sortedWith(
                compareBy<IWeContact> { it.displayName.isBlank() }
                    .thenComparator { c1, c2 -> chinaCollator.compare(c1.displayName, c2.displayName) }
            )
        }

        BaseContactSelector(
            title = stringResource(R.string.feature_custom_local_friend_avatars_name),
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            filteredContacts = filteredContacts,
            confirmButtonText = "",
            confirmButtonEnabled = false,
            showConfirmButton = false,
            dismissButtonText = stringResource(R.string.dialog_close),
            onDismiss = onDismiss,
            onConfirm = {},
            selectionKey = entries,
            isSelected = { it.wxId in entries.keys },
            avatarModelProvider = { contact -> entries[contact.wxId] ?: contact.avatarUrl },
            subtitleProvider = { contact ->
                if (contact.wxId in entries.keys) {
                    localizedContext.localizedContactsString(
                        R.string.contacts_custom_avatar_configured,
                        contact.wxId,
                    )
                } else {
                    contact.wxId
                }
            },
            trailingControl = { contact ->
                if (contact.wxId in entries.keys) {
                    TextButton(onClick = { onRemove(contact.wxId) }) {
                        Text(stringResource(R.string.contacts_custom_avatar_clear))
                    }
                } else {
                    TextButton(onClick = { onSelectImage(contact.wxId) }) {
                        Text(stringResource(R.string.contacts_custom_avatar_select))
                    }
                }
            },
            onItemClick = { contact -> onSelectImage(contact.wxId) }
        )
    }

    private data class SimpleContact(
        override val wxId: String,
        override val nickname: String
    ) : IWeContact {
        override val displayName: String get() = nickname
        override val avatarUrl: String get() = ""
    }

    private data class BoundAvatar(
        val username: String,
        val uri: String,
        val loaderRadiusFactor: Float
    )

    private class HostAvatarRequest(
        val method: Method,
        val receiver: Any?,
        val arguments: Array<Any?>,
        val scopeEnabled: Boolean,
    ) {
        fun reload(view: ImageView) {
            runCatching {
                method.invoke(receiver, *arguments.copyOf().also { it[0] = view })
            }.onFailure { WeLogger.w(TAG, "failed to refresh scoped avatar", it) }
        }
    }
}
