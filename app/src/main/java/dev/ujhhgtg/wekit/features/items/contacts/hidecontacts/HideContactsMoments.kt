package dev.ujhhgtg.wekit.features.items.contacts.hidecontacts

import com.tencent.mm.plugin.sns.ui.SnsCommentFooter
import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.features.items.contacts.HideContacts
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import java.util.LinkedList

private const val TAG = "HideContacts.Moments"

/**
 * Hides a hidden contact's likes/comments that surface inline under a *mutual friend's* Moments
 * post.
 *
 * These never touch the `SnsComment` table, so the "moments-comments" SQL rule in
 * HideContactsSql.kt (WRAPPER_RULES) — which filters rows selected `from SnsComment` — never sees
 * them. WeChat instead serializes them straight into the post's own protobuf blob:
 * `SnsInfo.attrBuf` -> `SnsObject.LikeUserList` / `CommentUserList` (`a65.ha6` entries).
 *
 * `fb4.z0.D0` ("SnsUtil.snsInfoToSnsStruct") is the single chokepoint that turns that raw
 * `SnsObject` into the UI-facing `dt` struct, for both Moments renderers that exist in 8.0.76 (the
 * classic feed item and the "improve" recycler feed — see the two call sites, `vc4/g0.java:76` and
 * `.../ui/improve/component/f2.java:765`). Filtering there means every renderer downstream already
 * gets a clean object.
 */
internal fun HideContacts.installMomentsHooks() {
    // `a65.ha6`'s own field names are obfuscated and drift across versions, and the class name
    // itself isn't guaranteed either — so it is never referenced directly. Instead we borrow the
    // same trick FakeMomentsLikes already uses: ha6 is also the return type of
    // SnsCommentFooter.getCommentInfo(), and that method name is real (not obfuscated).
    val entryClass = runCatching {
        SnsCommentFooter::class.java.getMethod("getCommentInfo").returnType
    }.getOrElse {
        WeLogger.w(TAG, "failed to resolve the SNS like/comment entry class; moments inline hiding unavailable", it)
        return
    }

    // Declared String fields, in source order, are [Username, Nickname, Content, ReplyUsername,
    // ...] — verified against a65/ha6.java (f9152d/f9153e/f9156h/f9160o) and cross-checked in
    // com/tencent/mm/plugin/sns/ui/widget/t2.java, which reads the same four fields by their real
    // names. Selecting by declared type + ordinal (mirrors SplitGroupCall's ILinkMember lookup and
    // HideContactsVoip.readMultiTalkInvite) survives a field rename; hardcoding "f9152d" would not.
    val stringFields = entryClass.reflekt().fields { type = BString }.map { it.self.makeAccessible() }
    val usernameField = stringFields.getOrNull(0)
    val replyUsernameField = stringFields.getOrNull(3)
    if (usernameField == null || replyUsernameField == null) {
        WeLogger.w(TAG, "failed to resolve username/replyUsername fields on the SNS entry class; moments inline hiding unavailable")
        return
    }

    // SnsObject (com.tencent.mm.protobuf.f) has no clone(); the only reliable way to duplicate one
    // is a full serialize + reparse round trip through the protobuf codec it already implements.
    // parseFrom() isn't declared on the SnsObject stub (only toByteArray()/the LikeUserList family
    // are), so it is resolved reflectively once here, exactly as FakeMomentsLikes already does.
    val parseFromMethod = runCatching {
        SnsObject::class.reflekt().firstMethod { name = "parseFrom"; superclass() }.self
    }.getOrElse {
        WeLogger.w(TAG, "failed to resolve SnsObject.parseFrom; moments inline hiding unavailable", it)
        return
    }

    fun isEntryHidden(entry: Any): Boolean {
        val username = usernameField.get(entry) as? String
        if (username != null && isHiddenNow(username)) return true
        // A reply that quotes a hidden contact's own comment ("回复 张三: ...") still names them,
        // even when the reply's own author isn't hidden — so it must be stripped too.
        val replyUsername = replyUsernameField.get(entry) as? String
        return replyUsername != null && isHiddenNow(replyUsername)
    }

    methodSnsInfoToSnsStruct.hookBefore {
        // isEntryHidden() below already re-checks isHiddenNow() per-entry (so #show / triple-tap
        // keep working); this is purely a fast path to skip all reflection when nothing is hidden.
        if (isTemporarilyShown || hiddenContacts.isEmpty()) return@hookBefore

        val original = args.getOrNull(1) as? SnsObject ?: return@hookBefore

        val likeList = original.LikeUserList as? List<*>
        val commentList = original.CommentUserList as? List<*>
        val hasHiddenLike = likeList?.any { it != null && isEntryHidden(it) } == true
        val hasHiddenComment = commentList?.any { it != null && isEntryHidden(it) } == true
        if (!hasHiddenLike && !hasHiddenComment) return@hookBefore

        // MUST operate on a clone, never on `original` directly: SnsInfoStorageLogic.e (s5.e in
        // the decompile) caches the parsed SnsObject keyed by a content hash and hands back that
        // SAME instance on every later render of this post. Stripping entries in place would
        // permanently truncate the cached object; the next time anyone (dis)likes or comments and
        // this object is re-serialized back into SnsInfo.attrBuf, the entries we removed here would
        // be gone from what gets persisted, not just from what gets displayed.
        val clone = SnsObject().also { parseFromMethod.invoke(it, original.toByteArray()) }

        if (hasHiddenLike) {
            val filtered = LinkedList((clone.LikeUserList as List<*>).filterNotNull().filterNot(::isEntryHidden))
            clone.LikeUserList = filtered
            clone.LikeUserListCount = filtered.size
            clone.LikeCount = filtered.size
        }
        if (hasHiddenComment) {
            val filtered = LinkedList((clone.CommentUserList as List<*>).filterNotNull().filterNot(::isEntryHidden))
            clone.CommentUserList = filtered
            clone.CommentUserListCount = filtered.size
            clone.CommentCount = filtered.size
        }

        args[1] = clone
    }
}
