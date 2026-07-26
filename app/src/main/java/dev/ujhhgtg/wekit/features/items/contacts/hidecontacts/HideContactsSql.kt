package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import com.tencent.wcdb.database.SQLiteDatabase
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString

private const val TAG = "HideContacts.Sql"

/**
 * Query-time hiding.
 *
 * Almost everything WeChat displays comes out of SQLite, and almost every read funnels through one
 * wrapper — `ka5.b0.g(String sql, String[] args, int) -> Cursor` (`ka5/b0.java:1009`; `b0.B(sql,
 * args)` is just `g(sql, args, 0)`). Every `com.tencent.mm.storage.*` storage class goes through it,
 * so hiding a contact from a new surface is usually a matter of recognising one more query shape
 * rather than finding a new hook. That is what [WRAPPER_RULES] is for.
 *
 * Two paths bypass the wrapper and are handled separately:
 * - FTS (global search) issues its reads via `com.tencent.wcdb.database.SQLiteDatabase
 *   .rawQueryWithFactory` — see [installFtsHook].
 * - The Moments feed goes through `WeDatabaseListenerApi`, which calls [rewriteMomentsFeedSql].
 *
 * Bind arguments are always passed separately from the SQL text, so injecting literal
 * `NOT IN ('...')` predicates is safe everywhere here.
 */
internal fun HideContacts.installSqlHooks() {
    installWrapperHook()
    installFtsHook()
}

// ── the wrapper chokepoint ───────────────────────────────────────────────────────────────────

/**
 * One recognisable query shape and the predicate to add to it.
 *
 * [matches] is handed the SQL already lowercased, so predicates must be written in lower case.
 * [condition] receives the hidden-contact set and returns a single boolean expression; it is
 * inserted by [injectCondition], which handles joining onto an existing `WHERE` and staying ahead
 * of any `ORDER BY` / `GROUP BY` / `LIMIT` tail.
 */
private class SqlRule(
    val name: String,
    val matches: (lowerSql: String) -> Boolean,
    val condition: (hidden: Set<String>) -> String,
)

private val WRAPPER_RULES = listOf(
    SqlRule("conversation-list", ::looksLikeConversationListQuery) {
        "rconversation.username NOT IN (${it.toSqlList()})"
    },
    // Qualify as rcontact.username: several of these queries are joins (e.g. "from rcontact,
    // bizinfo" for the 公众号 list, or the OpenIM left join) where a bare `username` would be an
    // ambiguous column reference.
    SqlRule("contact-list", ::looksLikeContactSelectorQuery) {
        "rcontact.username NOT IN (${it.toSqlList()})"
    },
    // 通讯录 -> 新的朋友. Friend requests live in their own table (FMessageConversationStorage),
    // keyed by `talker`, so none of the rcontact rules above reach them. One rule covers the list,
    // the 4-avatar header strip, the total count AND the red-dot count, because all four run through
    // this same wrapper:
    //   select * from fmessage_conversation  ORDER BY lastModifiedTime DESC          (list)
    //   select * from fmessage_conversation  where isNew = 1 ORDER BY ... limit 4    (avatars)
    //   select count(*) from fmessage_conversation                                  (total)
    //   select count(*) from fmessage_conversation where isNew = 1 and fmsgIsSend < 2 (red dot)
    SqlRule("new-friends", ::looksLikeNewFriendsQuery) {
        "talker NOT IN (${it.toSqlList()})"
    },
    // Unread totals: the launcher-icon badge and the 微信 bottom-tab count both come from
    // ConversationLogic's `unReadCount > 0` reads, which a hidden contact's messages would otherwise
    // still inflate. Covers the aggregation, the per-username incremental refresh and the
    // per-contact shortcut-badge join in one rule.
    SqlRule("unread-count", ::looksLikeUnreadCountQuery) {
        "rconversation.username NOT IN (${it.toSqlList()})"
    },
    // 朋友圈 -> 消息列表: likes and comments a hidden contact left, on our posts or on mutual
    // friends'. `talker` is the actor. The count(*) variants must be rewritten too or the 发现-tab
    // red dot and the list's paging counters go out of sync with the rows actually returned.
    //
    // NB: SnsComment DELETEs run through a different (exec) entry point and contain no "select", so
    // they can never match here — narrowing this rule would otherwise risk silently skipping the
    // deletion of a hidden contact's comments.
    SqlRule("moments-comments", { it.contains("from snscomment") }) {
        "talker NOT IN (${it.toSqlList()})"
    },
)

private fun looksLikeNewFriendsQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("from fmessage_conversation")) return false
    // getByEncryptTalker is a single-row lookup, not a display list — filtering it would make a
    // hidden contact's own friend-request row unreadable to the rest of WeChat.
    return !lower.contains("encrypttalker=")
}

private fun looksLikeUnreadCountQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("rconversation")) return false
    // The literal predicate, not just the column: the homepage list query also selects unReadCount
    // but never filters on it, and it is already handled by looksLikeConversationListQuery.
    return lower.contains("unreadcount > 0")
}

private fun HideContacts.installWrapperHook() {
    if (methodSqliteWrapperRawQuery.isPlaceholder) {
        WeLogger.w(TAG, "SQLite wrapper query method not resolved; query-time hiding disabled")
        return
    }
    methodSqliteWrapperRawQuery.hookBefore {
        val sql = args.firstOrNull() as? String ?: return@hookBefore
        val rewritten = rewriteWrapperSql(sql) ?: return@hookBefore
        args[0] = rewritten
    }
}

/** Returns the rewritten SQL, or null to leave the query untouched. */
private fun rewriteWrapperSql(sql: String): String? {
    if (HideContacts.isTemporarilyShown) return null
    val hidden = HideContacts.hiddenContacts
    if (hidden.isEmpty()) return null

    val lower = sql.lowercase()
    val rule = WRAPPER_RULES.firstOrNull { it.matches(lower) } ?: return null
    return injectCondition(sql, rule.condition(hidden))
}

private fun looksLikeConversationListQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("from rconversation")) return false
    // Match only the homepage list query, which spells out per-conversation display columns.
    // Folder-container / single-row lookups use `select *` (no such columns) and aggregate/count
    // reads lack them too, so they're skipped and left untouched. NB: we deliberately do NOT bail
    // on the substring "wekit_folder_" — when AggregateChats is enabled it appends its own
    // `NOT LIKE 'wekit_folder_%'` clause to this very query, and bailing on it would skip hiding.
    return lower.contains("conversationtime") &&
            lower.contains("unreadcount") &&
            lower.contains("digestuser")
}

/**
 * Recognises full contact-list queries (contact selector, 群聊 list, 标签 members, OpenIM, 公众号).
 * The table must be rcontact and the query must select pyinitial / quanpin.
 *
 * Those columns alone are NOT enough: WeChat uses one identical column list for list queries and
 * for single-row getters, so keying on them also matched `ContactStorage.p(rowid)`
 * ("... where rowid=N") and `ContactStorage.e0/v` ("... where username=X or encryptUsername=X").
 * Appending `AND rcontact.username NOT IN (...)` there made getContactByRowId return null for a
 * hidden contact, and — since AND binds tighter than OR — silently broke lookup by encryptUsername.
 * Both are lookups the rest of WeChat relies on, not display lists.
 *
 * Every real display list (`ContactStorage.x/y/z`-sorted, `U` for labels, `R` for OpenIM, the
 * BrandService join) ends in `order by showHead asc, ...`, while none of the single-row getters has
 * an ORDER BY at all — so requiring one separates them cleanly. The explicit bails are
 * belt-and-braces in case a future list query shape shows up without a sort.
 */
private fun looksLikeContactSelectorQuery(lower: String): Boolean {
    if (!lower.contains("select")) return false
    if (!lower.contains("from rcontact")) return false
    if (!lower.contains("pyinitial") && !lower.contains("quanpin")) return false
    if (lower.contains("where rowid=") || lower.contains("encryptusername=")) return false
    return lower.contains(" order by ")
}

/**
 * Inserts an extra WHERE predicate ahead of any ORDER BY / GROUP BY / LIMIT tail, joining onto an
 * existing WHERE when there is one. Mirrors ConversationGrouping.injectCondition.
 *
 * The trailing `AND` is safe against WeChat's WHERE builders because they parenthesise their OR
 * groups (e.g. ConversationStorage.O returns `((parentRef is null) or (parentRef in (...)))`).
 * Callers must not use this on a query whose WHERE ends in a bare OR — see the 通讯录 contact-count
 * query, which ends in `or username = 'weixin'`.
 */
internal fun injectCondition(sql: String, condition: String): String {
    val insertionPoint = listOf(" order by ", " group by ", " limit ")
        .map { sql.indexOf(it, ignoreCase = true) }
        .filter { it >= 0 }
        .minOrNull() ?: sql.length
    val head = sql.substring(0, insertionPoint)
    val tail = sql.substring(insertionPoint)
    val connector = if (head.contains(" where ", ignoreCase = true)) " AND " else " WHERE "
    return "$head$connector$condition$tail"
}

/** Renders a hidden-contact set as a single-quoted SQL value list with `''` escaping. */
internal fun Set<String>.toSqlList(): String =
    joinToString(",") { "'${it.replace("'", "''")}'" }

// ── global search (FTS) ──────────────────────────────────────────────────────────────────────

private const val SQL_SELECT_MESSAGE =
    "SELECT type, subtype, entity_id, aux_index, MAX(timestamp) as maxTime, count(aux_index) as msgCount, talker FROM FTS5MetaMessage"

private const val SQL_SELECT_MESSAGES_BY_KEYWORD =
    "SELECT FTS5MetaMessage.docid, type, subtype, entity_id, aux_index, timestamp, talker FROM FTS5MetaMessage"

private val FTS_SQL_REGEX =
    Regex("^SELECT (FTS5MetaContact|FTS5MetaTopHits|FTS5MetaKefuContact|FTS5MetaFeature|FTS5MetaWeApp|FTS5MetaFinderFollow|FTS5MetaFavorite)\\.docid, type, subtype, entity_id, aux_index,.*")

private fun HideContacts.installFtsHook() {
    SQLiteDatabase::class.reflekt().firstMethod {
        name = "rawQueryWithFactory"
        parameters(SQLiteDatabase.CursorFactory::class, BString, Array<Any>::class, BString)
    }.hookBefore {
        if (isTemporarilyShown) return@hookBefore

        // An empty set would render `aux_index NOT IN ()` — a SQLite syntax error that breaks ALL
        // global search while the feature is enabled but nothing is hidden yet.
        val hidden = hiddenContacts
        if (hidden.isEmpty()) return@hookBefore

        val sql = args[1] as? String ?: return@hookBefore
        if (!FTS_SQL_REGEX.containsMatchIn(sql) &&
            !sql.startsWith(SQL_SELECT_MESSAGE) &&
            !sql.startsWith(SQL_SELECT_MESSAGES_BY_KEYWORD)
        ) return@hookBefore

        val body = sql.removeSuffix(";")
        args[1] = "SELECT * FROM ($body) AS a WHERE aux_index NOT IN (${hidden.toSqlList()});"
    }
}

// ── moments feed ─────────────────────────────────────────────────────────────────────────────

// 在朋友圈信息流中隐藏被隐藏联系人发布的朋友圈; EnhanceQuery 会把信息流标记替换为 (1=1)
private const val FEED_MARKER_RAW = "(sourceType & 2 != 0 )"
private const val FEED_MARKER_ENHANCED = "(1=1)"

/** Called from `HideContacts.onQuery`; returns null to leave the query untouched. */
internal fun rewriteMomentsFeedSql(sql: String): String? {
    if (HideContacts.isTemporarilyShown) return null

    val hidden = HideContacts.hiddenContacts
    if (hidden.isEmpty()) return null

    // 只处理主信息流查询: 排除个人主页 (userName=) 与已注入的查询
    if (!sql.contains("from SnsInfo", false)) return null
    if (sql.contains("SnsInfo.userName=", false)) return null
    if (sql.contains("SnsInfo.userName not in", true)) return null

    val filter = " AND SnsInfo.userName NOT IN (${hidden.toSqlList()}) "

    val rewritten = when {
        sql.contains(FEED_MARKER_RAW) ->
            sql.replaceFirst(FEED_MARKER_RAW, FEED_MARKER_RAW + filter)

        // EnhanceQuery 先执行时, 信息流标记已变为 (1=1); 个人主页不会出现该精确形式
        sql.contains(FEED_MARKER_ENHANCED) ->
            sql.replaceFirst(FEED_MARKER_ENHANCED, FEED_MARKER_ENHANCED + filter)

        else -> return null
    }

    WeLogger.i(TAG, "hid ${hidden.size} contacts from moments feed")
    return rewritten
}
