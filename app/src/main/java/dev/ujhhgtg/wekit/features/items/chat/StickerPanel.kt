package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentResolver
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import dev.ujhhgtg.wekit.activity.PickRootTelegramStickerSetsContract
import dev.ujhhgtg.wekit.activity.RootTelegramStickerSetsResult
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelPaths
import dev.ujhhgtg.wekit.features.items.chat.panel.PickedPanelFile
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerItem
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerPack
import dev.ujhhgtg.wekit.features.items.chat.panel.listPanelTreeFiles
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelDirectory
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelFile
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelFiles
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxServiceClient
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxStickerRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.StickerPanelRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.StickerOnlineSourceRecoveryProgress
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.StickerOnlineSourceRecoveryResult
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramInstalledStickerSet
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerDatabase
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerPackRepository
import dev.ujhhgtg.wekit.ui.panel.StickerImportMode
import dev.ujhhgtg.wekit.ui.panel.StickerPanelActions
import dev.ujhhgtg.wekit.ui.panel.TelegramDatabaseSource
import dev.ujhhgtg.wekit.ui.panel.showStickerPanelSheet
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

@Feature(
    name = "表情面板",
    categories = ["聊天"],
    description = "长按表情按钮打开表情面板"
)
object StickerPanel : SwitchFeature() { // entry implementation in ChatFooterHooks

    fun openPanel(anchor: View) {
        showStickerPanelSheet(
            context = anchor.context,
            actions = StickerPanelActions(
                reloadLocal = ::loadLocalPacks,
                importSticker = { packId, mode, onStarted, onComplete ->
                    when (mode) {
                        StickerImportMode.MULTIPLE_FILES -> pickPanelFiles(
                            anchor.context,
                            STICKER_MIME_TYPES,
                        ) { files, activity ->
                            onStarted()
                            CoroutineScope(Dispatchers.IO).launch {
                                val result = importStickerBatch(
                                    packId,
                                    files,
                                    activity.contentResolver,
                                )
                                withContext(Dispatchers.Main) {
                                    onComplete(result)
                                    activity.finish()
                                }
                            }
                        }

                        StickerImportMode.DIRECTORY -> pickPanelDirectory(anchor.context) { treeUri, activity ->
                            onStarted()
                            CoroutineScope(Dispatchers.IO).launch {
                                val result = runCatching {
                                    listPanelTreeFiles(activity.contentResolver, treeUri)
                                }.mapCatching { files ->
                                    importStickerBatch(
                                        packId,
                                        files,
                                        activity.contentResolver,
                                    ).getOrThrow()
                                }
                                withContext(Dispatchers.Main) {
                                    onComplete(result)
                                    activity.finish()
                                }
                            }
                        }

                        StickerImportMode.TELEGRAM_SINGLE,
                        StickerImportMode.TELEGRAM_BATCH,
                            -> Unit
                    }
                },
                importTelegramStickerSet = TelegramStickerPackRepository::importStickerSet,
                pickTelegramStickerSets = { source, onComplete ->
                    pickTelegramStickerSets(anchor, source, onComplete)
                },
                loadImportedTelegramStickerSetNames =
                    TelegramStickerPackRepository::importedStickerSetNames,
                createPack = { name -> withContext(Dispatchers.IO) { StickerPanelRepository.createPack(name) } },
                renamePack = StickerPanelRepository::renamePack,
                deletePack = StickerPanelRepository::deletePack,
                loadOnlinePacks = FunBoxStickerRepository::loadCatalog,
                loadMyUploads = FunBoxStickerRepository::loadMyUploads,
                loadOnlineItems = FunBoxStickerRepository::loadPack,
                searchOnline = FunBoxStickerRepository::searchText,
                pickSimilarityImage = { onComplete ->
                    pickPanelFile(anchor.context, arrayOf("image/*")) { _, uri, activity ->
                        CoroutineScope(Dispatchers.IO).launch {
                            val result = runCatching {
                                activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: error("无法读取所选图片")
                            }.mapCatching { bytes ->
                                require(bytes.isNotEmpty()) { "所选图片内容为空" }
                                bytes
                            }
                            withContext(Dispatchers.Main) {
                                onComplete(result)
                                activity.finish()
                            }
                        }
                    }
                },
                loadSimilarityImage = ::resolveStickerBytes,
                searchSimilar = FunBoxStickerRepository::searchSimilar,
                uploadPack = FunBoxStickerRepository::uploadPack,
                setCustomTitle = StickerPanelRepository::setCustomTitle,
                setPackCover = StickerPanelRepository::setPackCover,
                deleteSticker = StickerPanelRepository::deleteSticker,
                deleteStickers = StickerPanelRepository::deleteStickers,
                savePackOrder = StickerPanelRepository::savePackOrder,
                saveItemOrder = StickerPanelRepository::saveItemOrder,
                ensurePack = { name -> withContext(Dispatchers.IO) { StickerPanelRepository.ensurePack(name) } },
                setOnlinePackSource = { packId, onlinePackId ->
                    withContext(Dispatchers.IO) {
                        StickerPanelRepository.setOnlinePackSource(packId, onlinePackId)
                    }
                },
                recoverOnlinePackSources = ::recoverOnlinePackSources,
                saveOnlineSticker = { packId, item, overwrite ->
                    saveOnlineSticker(packId, item, overwrite)
                },
            ),
        ) { item ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val path = resolveStickerPath(item).getOrThrow()
                    val temporary = item.localPath == null
                    try {
                        check(WeMessageApi.sendSticker(WeCurrentConversationApi.value, path)) {
                            "表情发送失败"
                        }
                        if (temporary) StickerPanelRepository.recordOnlineRecent(item)
                        else StickerPanelRepository.recordRecent(path)
                    } finally {
                        if (temporary) path.asPath.deleteIfExists()
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            PanelPaths.cleanupStalePanelCache()
        }
    }

    private fun loadLocalPacks() = buildList {
        val recents = StickerPanelRepository.getRecents()
        if (recents.items.isNotEmpty()) add(recents)
        addAll(StickerPanelRepository.loadPacks())
    }

    private suspend fun resolveStickerPath(item: StickerItem): Result<String> = withContext(Dispatchers.IO) {
        cancellableResult {
            item.localPath?.let { return@cancellableResult it }
            val bytes = resolveStickerBytes(item).getOrThrow()
            val extension = StickerPanelRepository.detectImageExtension(bytes)
                ?: error("服务器返回了不支持的图片格式")
            val path = PanelPaths.panelCacheDir / "sticker-${UUID.randomUUID()}.$extension"
            path.writeBytes(bytes)
            path.absolutePathString()
        }
    }

    private suspend fun resolveStickerBytes(item: StickerItem): Result<ByteArray> = withContext(Dispatchers.IO) {
        cancellableResult {
            val bytes = item.localPath?.asPath?.readBytes() ?: run {
                val objectId = item.remoteObjectId ?: error("没有可用表情对象")
                FunBoxServiceClient.downloadObject("image", objectId).getOrThrow()
            }
            require(bytes.isNotEmpty()) { "表情图片内容为空" }
            bytes
        }
    }

    private suspend fun saveOnlineSticker(
        packId: String,
        item: StickerItem,
        overwrite: Boolean,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            cancellableResult {
                if (!overwrite && StickerPanelRepository.hasOnlineSticker(packId, item)) return@cancellableResult
                val path = resolveStickerPath(item).getOrThrow()
                val temporary = item.localPath == null
                try {
                    Files.newInputStream(path.asPath).use { input ->
                        StickerPanelRepository.importOnlineSticker(item, packId, input, overwrite).getOrThrow()
                    }
                } finally {
                    if (temporary) path.asPath.deleteIfExists()
                }
            }.map { }
        }

    private suspend fun recoverOnlinePackSources(
        packIds: List<String>,
        onProgress: suspend (StickerOnlineSourceRecoveryProgress) -> Unit,
    ): Result<StickerOnlineSourceRecoveryResult> = withContext(Dispatchers.IO) {
        cancellableResult {
            val requestedIds = packIds.toSet()
            val localPacks = StickerPanelRepository.loadPacks().filter { it.id in requestedIds }
            val total = localPacks.size
            var recovered = 0
            var alreadyLinked = 0
            var unmatched = 0

            if (localPacks.all { it.onlineSourcePackId != null }) {
                alreadyLinked = total
                onProgress(StickerOnlineSourceRecoveryProgress(total, total, "所选本地包均已有在线来源"))
                return@cancellableResult StickerOnlineSourceRecoveryResult(
                    selected = total,
                    recovered = 0,
                    alreadyLinked = alreadyLinked,
                    unmatched = 0,
                )
            }

            onProgress(StickerOnlineSourceRecoveryProgress(0, total, "正在读取在线表情包列表"))
            val catalog = FunBoxStickerRepository.loadCatalog().getOrThrow()
            val uploads = FunBoxStickerRepository.loadMyUploads().getOrDefault(emptyList())
            val onlinePacks = (catalog + uploads).distinctBy(StickerPack::id)

            localPacks.forEachIndexed { index, localPack ->
                onProgress(
                    StickerOnlineSourceRecoveryProgress(
                        completed = index,
                        total = total,
                        message = "正在匹配“${localPack.title}”",
                    ),
                )
                when {
                    localPack.onlineSourcePackId != null -> alreadyLinked++
                    else -> {
                        val onlinePackId = onlinePacks.singleOrNull {
                            it.title.equals(localPack.title, ignoreCase = true)
                        }?.id
                        if (onlinePackId == null) {
                            unmatched++
                        } else {
                            StickerPanelRepository.setOnlinePackSource(localPack.id, onlinePackId).getOrThrow()
                            recovered++
                        }
                    }
                }
                onProgress(
                    StickerOnlineSourceRecoveryProgress(
                        completed = index + 1,
                        total = total,
                        message = "已处理“${localPack.title}”",
                    ),
                )
            }

            StickerOnlineSourceRecoveryResult(
                selected = total,
                recovered = recovered,
                alreadyLinked = alreadyLinked,
                unmatched = unmatched,
            )
        }
    }

    private suspend inline fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun pickTelegramStickerSets(
        anchor: View,
        source: TelegramDatabaseSource,
        onComplete: (Result<List<TelegramInstalledStickerSet>>?) -> Unit,
    ) {
        TransparentActivity.launch(anchor.context) {
            when (source) {
                TelegramDatabaseSource.ROOT -> {
                    val launcher = registerForActivityResult(PickRootTelegramStickerSetsContract()) { result ->
                        onComplete(
                            when (result) {
                                is RootTelegramStickerSetsResult.Success -> Result.success(result.stickerSets)
                                is RootTelegramStickerSetsResult.Failure ->
                                    Result.failure(IllegalStateException(result.message))

                                RootTelegramStickerSetsResult.Cancelled -> null
                            },
                        )
                        finish()
                    }
                    launcher.launch(Unit)
                }

                TelegramDatabaseSource.MANUAL -> {
                    val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri == null) {
                            onComplete(null)
                            finish()
                            return@registerForActivityResult
                        }
                        CoroutineScope(Dispatchers.IO).launch {
                            val temporary = PanelPaths.panelCacheDir / "telegram-cache4-${UUID.randomUUID()}.db"
                            val result = runCatching {
                                contentResolver.openInputStream(uri)?.use { input ->
                                    Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING)
                                } ?: error("无法读取所选 Telegram 数据库")
                                TelegramStickerDatabase.readInstalledSets(temporary).getOrThrow()
                            }
                            temporary.deleteIfExists()
                            withContext(Dispatchers.Main) {
                                onComplete(result)
                                finish()
                            }
                        }
                    }
                    launcher.launch(arrayOf("application/x-sqlite3", "application/octet-stream", "*/*"))
                }
            }
        }
    }

    private suspend fun importStickerBatch(
        packId: String,
        files: List<PickedPanelFile>,
        resolver: ContentResolver,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (files.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("所选内容中没有支持的图片文件"))
        }

        var imported = 0
        val failures = mutableListOf<Pair<String, Throwable>>()
        files.forEach { file ->
            runCatching {
                val input = resolver.openInputStream(file.uri) ?: error("无法读取文件")
                input.use {
                    StickerPanelRepository.importSticker(packId, file.name, it).getOrThrow()
                }
            }.onSuccess {
                imported++
            }.onFailure {
                failures += file.name to it
            }
        }

        if (failures.isEmpty()) {
            Result.success(Unit)
        } else {
            val first = failures.first()
            Result.failure(
                IllegalStateException(
                    "已导入 $imported 个，${failures.size} 个失败；${first.first}: " +
                            (first.second.message ?: "未知错误"),
                    first.second,
                ),
            )
        }
    }

    private val STICKER_MIME_TYPES = arrayOf(
        "*/*",
    )
}
