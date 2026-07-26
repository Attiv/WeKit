package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.dsl.DexMethodDelegate
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.LinkedList

private const val TAG = "HideContacts.Lists"

/**
 * List/adapter-level hiding for the surfaces whose rows never pass through a rewritable SQL query.
 *
 * The SQL rewriter in HideContactsSql.kt covers everything WeChat reads out of `rcontact` /
 * `rconversation` with a statement we can see. The four surfaces installed here are invisible to it:
 *
 * - the 通讯录 and @成员 lists are MVVM "live lists" whose rows are already-materialised
 *   `Contact` objects by the time any adapter sees them;
 * - group-member lists come from `chatroom.memberlist`, a `;`-separated string column, so there is
 *   no per-member row to filter in SQL at all;
 * - 收藏 has largely moved to the WCDB ORM builder (`r82.e` / `br5.f`), which emits no raw SQL;
 * - 视频号 like lists are network-driven protobuf, with no local table behind them.
 *
 * Every hook here filters the *backing collection* rather than remapping adapter indices. That is
 * deliberate: an earlier iteration of this feature shifted `getView` positions through a
 * `hiddenPositions` set without touching `getItem`, so tapping a row opened the wrong chat. Removing
 * the entry before the adapter ever counts it keeps `getCount()`, `getItem()` and every click
 * listener consistent by construction, and a resolve failure degrades to "the contact stays
 * visible" instead of "the wrong contact opens".
 */
internal fun HideContacts.installListHooks() {
    installMvvmListHooks()
    installGroupMemberHooks()
    installFavoriteHooks()
    installFinderLikeHooks()
}

// ---------------------------------------------------------------------------------------------
// MvvmList — 通讯录 (AddressLiveList) and the @成员选择器 (AtSomeoneLiveList)
// ---------------------------------------------------------------------------------------------

/**
 * `MvvmList.e(List snapshotList)` is WeChat's per-list "preprocess the snapshot before it reaches
 * the adapter" hook, and both list classes implement it identically: sort the snapshot in place,
 * walk it once to recompute the section headers, then `map` it into a list of *clones* which becomes
 * the adapter's data. Because the returned list is derived from `snapshotList`, dropping entries
 * from `snapshotList` in a `hookBefore` removes them from the section index and the adapter data in
 * one go — no index arithmetic anywhere.
 *
 * `AddressLiveList` (通讯录) and `AtSomeoneLiveList` (@成员) differ only in their entry class
 * (`ah5.g` vs `com.tencent.mm.ui.chatting.atsomeone.b` on 8.0.76), and both entry classes carry the
 * contact as their single `com.tencent.mm.storage.*` field, so one reflective shape works for both.
 */
private fun HideContacts.installMvvmListHooks() {
    hookMvvmListPreprocess(methodAddressMvvmListPreprocessList, "AddressLiveList")
    hookMvvmListPreprocess(methodAtSomeoneMvvmListPreprocessList, "AtSomeoneLiveList")
}

private fun HideContacts.hookMvvmListPreprocess(target: DexMethodDelegate, label: String) {
    if (target.isPlaceholder) {
        WeLogger.w(TAG, "$label preprocess method wasn't resolved; that list stays unfiltered")
        return
    }

    target.hookBefore {
        if (isTemporarilyShown) return@hookBefore

        val contacts = args[0] as? MutableList<*> ?: return@hookBefore
        // MvvmList hands us an empty snapshot on the initial load and whenever a filter matches
        // nothing; contacts[0] below would throw there, and hook bodies must not fail.
        if (contacts.isEmpty()) return@hookBefore

        // The entry classes are obfuscated, but each holds exactly one field whose type lives in
        // com.tencent.mm.storage (the Contact — y3 on 8.0.76, a3 on 8.0.69); everything else on them
        // is an int/boolean/String or a UI helper from another package. Selecting by declared type
        // rather than by name survives the per-version field renames.
        val contactInfoField = contacts[0]!!.reflekt()
            .firstField { type { it.name.startsWith("${PackageNames.WECHAT}.storage") } }
            .self.makeAccessible()
        val usernameField = contactInfoField.type.reflekt()
            .firstField {
                name = "field_username"
                superclass()
            }.self.makeAccessible()

        val hiddenContacts = hiddenContacts

        val removed = contacts.removeAll { contact ->
            val contactInfo = contactInfoField.get(contact ?: return@removeAll false)
                ?: return@removeAll false
            (usernameField.get(contactInfo) as? String) in hiddenContacts
        }
        if (removed) WeLogger.d(TAG, "filtered hidden contacts out of $label")
    }
}

// ---------------------------------------------------------------------------------------------
// 群成员列表
// ---------------------------------------------------------------------------------------------

/**
 * Group-member lists cannot be filtered in SQL: the members live in `chatroom.memberlist`, a single
 * `;`-separated text column, so a row that contains a hidden contact also contains everyone else.
 * Both member UIs do, however, start from a plain `List<String>` of usernames, which is the ideal
 * place to cut.
 *
 * - 查看全部群成员 (`SeeRoomMemberUI`): its adapter is fed by `cc.d(List usernames)`, which clears
 *   its item list and rebuilds it from the argument. Filtering the argument in a `hookBefore` means
 *   the adapter's list, `getCount()` and `getItem()` are all built from the same filtered input.
 * - @全体成员 / 删除成员 / 邀请 (`SelectMemberUI` and its subclasses): the adapter's loader Runnable
 *   reads `SelectMemberUI.V6()` (`j7()` on 8.0.69) once and builds its `bd` items from it, so a
 *   `hookAfter` returning a filtered copy is equivalent.
 *
 * `V6()` returns `ChatroomInfo.z0()`, which **caches** the parsed member list in a field and hands
 * back the same instance every time — so this must never filter in place. A fresh `ArrayList` is
 * returned instead, and only when something was actually removed, leaving WeChat's cache (used by
 * member counts, @全体 delivery and message routing) untouched.
 *
 * NB: `SelectDelRoomManagerUI` / `SelectRoomFollowMemberManagerUI` override `V6()` without calling
 * super, so the 管理员 lists are deliberately not covered — they are a different surface.
 */
private fun HideContacts.installGroupMemberHooks() {
    if (methodSeeRoomMemberSetMemberList.isPlaceholder) {
        WeLogger.w(TAG, "SeeRoomMemberUI adapter setter wasn't resolved; 查看全部群成员 stays unfiltered")
    } else {
        methodSeeRoomMemberSetMemberList.hookBefore {
            if (isTemporarilyShown) return@hookBefore
            val members = args[0] as? List<*> ?: return@hookBefore
            val filtered = filterHiddenUsernames(members) ?: return@hookBefore
            WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) from SeeRoomMemberUI")
            args[0] = filtered
        }
    }

    if (methodSelectMemberUiGetMemberList.isPlaceholder) {
        WeLogger.w(TAG, "SelectMemberUI member-list getter wasn't resolved; 群成员选择器 stays unfiltered")
        return
    }

    methodSelectMemberUiGetMemberList.hookAfter {
        if (isTemporarilyShown) return@hookAfter
        val members = result as? List<*> ?: return@hookAfter
        val filtered = filterHiddenUsernames(members) ?: return@hookAfter
        WeLogger.d(TAG, "filtered ${members.size - filtered.size} hidden member(s) from SelectMemberUI")
        result = filtered
    }
}

/**
 * Returns a copy of [members] without the hidden usernames, or `null` when nothing was removed —
 * so callers can leave the host's own (possibly cached) list object completely alone.
 */
private fun filterHiddenUsernames(members: List<*>): ArrayList<Any?>? {
    if (members.isEmpty()) return null
    val filtered = members.filterNot { it is String && HideContacts.isHiddenNow(it) }
    if (filtered.size == members.size) return null
    return ArrayList(filtered)
}

// ---------------------------------------------------------------------------------------------
// 收藏
// ---------------------------------------------------------------------------------------------

/**
 * 收藏 rows carry their sender in `FavItemInfo.field_fromUser`, but the plugin has largely migrated
 * to the WCDB ORM builder (`r82.e` / `br5.f`, see `fav/ui/adapter/c.java:349-360`), which produces
 * no raw SQL for the query rewriter to intercept. Only the legacy path still emits a statement.
 *
 * `FavoriteAdapter` funnels *both* paths — the ORM branch, the legacy branch, the search branch and
 * the "get null list, new empty one" fallback — through one private setter, `r(List)` (`t(List)` on
 * 8.0.69), which is the sole writer of the adapter's pending data list. Filtering its argument
 * therefore covers every load path at once, and since `getCount()`/`getItem()` both read the list
 * this method installs (after `notifyDataSetChanged` swaps it in), positions can never desync.
 *
 * The filtered result is handed over as a fresh `ArrayList` rather than removed in place: some call
 * sites pass Kotlin's immutable `EmptyList` singleton, whose iterator rejects `remove()`.
 */
private fun HideContacts.installFavoriteHooks() {
    if (methodFavoriteAdapterSetDataList.isPlaceholder) {
        WeLogger.w(TAG, "FavoriteAdapter data setter wasn't resolved; 收藏 stays unfiltered")
        return
    }

    methodFavoriteAdapterSetDataList.hookBefore {
        if (isTemporarilyShown) return@hookBefore

        // `r(null)` is a real call shape (FavApiLogic returns null when the storage is missing),
        // so this must tolerate a null argument rather than assume a list.
        val items = args[0] as? List<*> ?: return@hookBefore
        if (items.isEmpty()) return@hookBefore

        val sample = items.firstNotNullOfOrNull { it } ?: return@hookBefore
        // `field_fromUser` is a real (unobfuscated) storage column name, declared on FavItemInfo's
        // generated base class — hence the superclass walk.
        val fromUserField = sample.reflekt()
            .firstFieldOrNull {
                name = "field_fromUser"
                superclass()
            }?.self?.makeAccessible()
        if (fromUserField == null) {
            WeLogger.w(TAG, "FavItemInfo.field_fromUser not found; 收藏 stays unfiltered")
            return@hookBefore
        }

        val filtered = items.filterNot { item ->
            val fromUser = item?.let { fromUserField.get(it) as? String } ?: return@filterNot false
            isHiddenNow(fromUser)
        }
        if (filtered.size == items.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${items.size - filtered.size} hidden favourite(s)")
        args[0] = ArrayList(filtered)
    }
}

// ---------------------------------------------------------------------------------------------
// 视频号「朋友❤过」
// ---------------------------------------------------------------------------------------------

/**
 * Index of `wxUsername` in the like-entry protobuf (`a65.je1` on 8.0.76, `mx4.v91` on 8.0.69).
 *
 * The generated field table is identical on both trees — `0=nickName, 1=headImgUrl, 2=likeId,
 * 3=likeFlag, 4=refuseFlag, 5=wxUsername, …, 11=finder_username` — and index 5 is the only entry
 * that is a real wxid. `finder_username` is a `v2_…` Finder identity with no mapping back to a
 * contact, which is why the Finder follow/aggregation lists are explicitly out of scope.
 */
private const val LIKE_ENTRY_WX_USERNAME = 5

/**
 * Hides a hidden contact from the 视频号 like list ("朋友❤过" / the ❤ drawer).
 *
 * Finder feeds have no local table, so there is nothing for the SQL rewriter to touch. The drawer
 * presenter (`FinderLikeDrawerPresenter`, and its "朋友❤过" subclass
 * `FinderFriendLikeListDrawerPresenter`) keeps one `ArrayList` of `FinderFeedLike` items that is
 * handed straight to `WxRecyclerAdapter` as its backing list, and exactly two callbacks append to
 * it: the refresh callback and the load-more callback. Both build their items in a loop over an
 * incoming list of raw like protobufs, so filtering that input list before the loop keeps the
 * adapter's list, its item count and the `notifyItemRangeInserted` offsets all in agreement — which
 * a `FinderFeedFriendLikeConvert.onBindViewHolder` hook could never do, since a bind cannot remove a
 * row.
 *
 * The refresh callback receives a `GetFinderFeedLikedListData` holder whose single `LinkedList`
 * field is the like list; it is a per-response object with no other consumer, so it is filtered in
 * place. The load-more callback receives the list directly, and gets a filtered copy instead —
 * cheaper than proving the network layer does not retain it.
 */
private fun HideContacts.installFinderLikeHooks() {
    if (methodFinderLikeDrawerRefresh.isPlaceholder) {
        WeLogger.w(TAG, "Finder like-drawer refresh callback wasn't resolved; 视频号点赞列表 stays unfiltered")
    } else {
        methodFinderLikeDrawerRefresh.hookBefore {
            if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookBefore

            val data = args[0] ?: return@hookBefore
            val listField = data.reflekt()
                .firstFieldOrNull { type = LinkedList::class }
                ?.self?.makeAccessible() ?: return@hookBefore

            @Suppress("UNCHECKED_CAST")
            val likes = listField.get(data) as? MutableList<Any?> ?: return@hookBefore
            val removed = likes.removeAll { it != null && isHiddenLikeEntry(it) }
            if (removed) WeLogger.d(TAG, "filtered hidden contacts out of the finder like list")
        }
    }

    if (methodFinderLikeDrawerLoadMore.isPlaceholder) {
        WeLogger.w(TAG, "Finder like-drawer load-more callback wasn't resolved; 视频号点赞列表 stays unfiltered")
        return
    }

    methodFinderLikeDrawerLoadMore.hookBefore {
        if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookBefore

        val likes = args[0] as? List<*> ?: return@hookBefore
        val filtered = likes.filterNot { it != null && isHiddenLikeEntry(it) }
        if (filtered.size == likes.size) return@hookBefore

        WeLogger.d(TAG, "filtered ${likes.size - filtered.size} hidden like(s) from the finder like list")
        args[0] = ArrayList(filtered)
    }
}

private fun HideContacts.isHiddenLikeEntry(entry: Any): Boolean {
    val wxId = protoGetString(entry, LIKE_ENTRY_WX_USERNAME) ?: return false
    return isHiddenNow(wxId)
}

/**
 * Reads a string field out of a `com.tencent.mm.protobuf.e` subclass by its *position* in the
 * generated field table. The concrete protobuf classes are obfuscated and renamed every version
 * (`a65.je1` -> `mx4.v91`), but `getString(int)` is declared on the unobfuscated base class, so it
 * is resolved by walking up the hierarchy — mirroring `WeMomentsApi.xs4GetString`.
 */
private fun protoGetString(proto: Any, fieldIndex: Int): String? = runCatching {
    var cls: Class<*>? = proto.javaClass
    while (cls != null) {
        val getter = cls.declaredMethods.firstOrNull {
            it.name == "getString" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
        if (getter != null) {
            getter.isAccessible = true
            return@runCatching getter.invoke(proto, fieldIndex) as? String
        }
        cls = cls.superclass
    }
    null
}.getOrNull()
