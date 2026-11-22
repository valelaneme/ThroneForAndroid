package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DerivedGroup
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Reads and writes the local derived-group records, and keeps them from pointing at groups that no longer exist.
 * A backup restore replaces the `groups` table wholesale but never touches `derived_groups`, so a group deleted on
 * the desktop can leave a dangling record here. Pruning on read is enough: no existing code path needs changing.
 */
internal object DerivedGroupStore {

    private val dao get() = SagerDatabase.derivedGroupDao

    fun get(gid: Long): DerivedGroup? = dao.get(gid)

    fun isDerived(gid: Long): Boolean = gid > 0L && dao.get(gid) != null

    fun all(): List<DerivedGroup> = dao.all()

    fun put(record: DerivedGroup) {
        dao.put(record)
    }

    fun delete(gid: Long) {
        dao.delete(gid)
    }

    /**
     * Drops records whose own group is gone, and rewrites records whose source list names groups that are gone.
     * Returns the records that still have at least one live source.
     */
    fun live(): List<DerivedGroup> {
        val groupIds = GroupRepo.ids().toHashSet()
        val out = ArrayList<DerivedGroup>()
        for (record in dao.all()) {
            if (record.gid !in groupIds) {
                dao.delete(record.gid)
                Logs.i("[Derived] dropping record for removed group ${record.gid}")
                continue
            }
            val sources = record.sourceGids.filter { it in groupIds && it != record.gid }
            if (sources.isEmpty()) {
                dao.delete(record.gid)
                Logs.i("[Derived] dropping record ${record.gid} with no live source")
                continue
            }
            if (sources.size != record.sourceGids.size) {
                val kept = record.copy(sourceGidsJson = DerivedGroup.gidsToJson(sources))
                dao.put(kept)
                out.add(kept)
            } else {
                out.add(record)
            }
        }
        return out
    }
}
