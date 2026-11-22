package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DerivedGroup
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.Outbound
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Rebuilds the members of a derived group from its live source groups. This is the whole update path: the record in
 * `derived_groups` (not anything the desktop synced) decides which group updates and how. Members are copied, never
 * moved: a node belongs to exactly one group, so each hit becomes a fresh profile in the target group.
 *
 * A rebuild replaces the target's member list, so latency / traffic records of the old copies are lost. If the
 * running profile belongs to this group it is stopped with the old list; this is the documented minimal-version limit.
 */
internal object DerivedGroupSync {

    /** One rebuild at a time: an explicit update and an automatic one can land together. */
    private val mutex = Mutex()

    /** Result of a rebuild, for the caller to show as a snackbar. */
    data class Result(val collected: Int, val kept: Int, val dropped: Boolean)

    suspend fun sync(gid: Long): Result? = mutex.withLock {
        val record = DerivedGroupStore.get(gid) ?: return@withLock null
        val target = GroupRepo.get(gid) ?: run {
            DerivedGroupStore.delete(gid)
            return@withLock null
        }
        val liveSources = record.sourceGids.filter { it != gid && GroupRepo.get(it) != null }
        if (liveSources.isEmpty()) return@withLock null

        // Reuse the subscription filter so derived groups obey the same regex rules as a normal subscription.
        val options = SubscriptionOptions(nameFilter = record.nameFilter, nameExclude = record.nameExclude)
        val filter = SubscriptionNameFilter.Filter(options, target.displayName())

        val seen = HashSet<String>()
        val hits = ArrayList<Outbound>()
        for (sourceGid in liveSources) {
            val sourceName = GroupRepo.get(sourceGid)?.displayName() ?: sourceGid.toString()
            for (profile in ProfileManager.members(sourceGid)) {
                if (!filter.admits(profile.type) { profile.displayName() }) continue
                // dedupKey() is the desktop's own key; keep the first occurrence in source/tab order.
                val key = profile.dedupKey()
                if (!seen.add(key)) continue
                hits.add(profile.outbound)
            }
            Logs.i("[Derived] source $sourceName contributed ${hits.size} cumulative hit(s)")
        }

        val oldIds = ProfileManager.memberIds(gid)
        if (oldIds.isNotEmpty()) {
            // A rebuilt member may be the one running; replacing the list stops it (minimal-version behaviour).
            ProfileManager.batchDeleteProfiles(oldIds, stopRunning = true)
        }
        if (hits.isNotEmpty()) {
            ProfileManager.addProfileBatch(hits, groupId = gid)
        }
        GroupRepo.postReload(gid)
        Logs.i("[Derived] rebuilt group ${target.displayName()}: ${hits.size} kept")
        Result(collected = hits.size, kept = hits.size, dropped = oldIds.isNotEmpty() && hits.isEmpty())
    }

    /** Rebuild every live derived record; called after a restore or when the user asks to refresh all. */
    suspend fun syncAll() {
        for (record in DerivedGroupStore.live()) sync(record.gid)
    }

    /** After a source subscription refresh, rebuild only the derived groups that opted in and depend on it. */
    suspend fun onSourceUpdated(sourceGid: Long) {
        for (record in DerivedGroupStore.live()) {
            if (record.updateMode == DerivedGroup.MODE_AUTOMATIC && sourceGid in record.sourceGids) {
                sync(record.gid)
            }
        }
    }

    /** Whether a group id is a source of at least one automatic derived group. */
    fun hasAutomaticDependent(sourceGid: Long): Boolean =
        DerivedGroupStore.live().any { it.updateMode == DerivedGroup.MODE_AUTOMATIC && sourceGid in it.sourceGids }
}
