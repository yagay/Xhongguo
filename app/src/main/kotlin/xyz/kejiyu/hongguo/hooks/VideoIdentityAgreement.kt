package xyz.kejiyu.hongguo.hooks

/**
 * TTVideoEngine.getVideoID() and a SaasVideoData.vid are not comparable on
 * every native route. A mismatch is not automatically proof that the visible
 * page is wrong. Accept it only when two independent native references agree
 * on the selected page AND that same page stays stable across probes.
 *
 * The caller must only provide a focused, idle, visible pager candidate.
 * A weak current-data-only source is never enough for a mismatched ID.
 */
internal class VideoIdentityAgreement(
    private val mismatchStableMs: Long = 800L
) {
    private var candidateKey: String? = null
    private var candidateSince: Long = 0L

    fun accept(
        key: String?, engineId: String?, pagerVideoId: String?,
        independentlySelected: Boolean, nowMs: Long
    ): Boolean {
        if (key == null || pagerVideoId.isNullOrBlank()) {
            clear()
            return false
        }
        if (engineId.isNullOrBlank() || engineId == pagerVideoId) {
            clear()
            return true
        }
        if (!independentlySelected) {
            clear()
            return false
        }
        if (candidateKey != key || nowMs < candidateSince) {
            candidateKey = key
            candidateSince = nowMs
            return false
        }
        return nowMs - candidateSince >= mismatchStableMs
    }

    fun clear() {
        candidateKey = null
        candidateSince = 0L
    }
}
