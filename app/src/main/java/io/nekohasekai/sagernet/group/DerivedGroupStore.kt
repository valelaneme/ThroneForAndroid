package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DerivedGroup
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs

/**
 * Reads and writes the local derived-group records, and keeps them from pointing at groups that no longer exist.
 *
 * The table is created with plain SQL instead of a Room entity: Room/KSP 2.0.21 crashes while validating a new
 * entity's annotations, and a local-only table has no reason to be in Room's schema. A backup restore replaces the
 * `groups` table wholesale but never touches `derived_groups`, so a group deleted on the desktop can leave a
 * dangling record here. Pruning on read is enough: no existing code path needs changing.
 */
internal object DerivedGroupStore {

    private val db get() = SagerDatabase.instance.openHelper.writableDatabase

    @Volatile
    private var tableReady = false

    /** Idempotent; the flag only avoids repeating DDL on every read after the first one succeeds. */
    private fun ensureTable() {
        if (tableReady) return
        synchronized(this) {
            if (!tableReady) {
                db.execSQL(DerivedGroup.CREATE_SQL)
                tableReady = true
            }
        }
    }

    fun get(gid: Long): DerivedGroup? {
        ensureTable()
        db.query(DerivedGroup.SELECT_BY_GID, arrayOf(gid.toString())).use { c ->
            if (!c.moveToFirst()) return null
            return DerivedGroup(
                gid = c.getLong(0),
                sourceGidsJson = c.getString(1),
                nameFilter = c.getString(2),
                nameExclude = c.getString(3),
                updateMode = c.getInt(4),
            )
        }
    }

    fun isDerived(gid: Long): Boolean = gid > 0L && get(gid) != null

    fun all(): List<DerivedGroup> {
        ensureTable()
        val out = ArrayList<DerivedGroup>()
        db.query(DerivedGroup.SELECT_ALL).use { c ->
            while (c.moveToNext()) out.add(
                DerivedGroup(
                    gid = c.getLong(0),
                    sourceGidsJson = c.getString(1),
                    nameFilter = c.getString(2),
                    nameExclude = c.getString(3),
                    updateMode = c.getInt(4),
                )
            )
        }
        return out
    }

    fun put(record: DerivedGroup) {
        ensureTable()
        db.execSQL(
            DerivedGroup.INSERT_OR_REPLACE,
            arrayOf<Any?>(
                record.gid, record.sourceGidsJson, record.nameFilter, record.nameExclude, record.updateMode
            ),
        )
    }

    fun delete(gid: Long) {
        ensureTable()
        db.execSQL(DerivedGroup.DELETE_BY_GID, arrayOf<Any?>(gid))
    }

    /**
     * Drops records whose own group is gone, and rewrites records whose source list names groups that are gone.
     * Returns the records that still have at least one live source.
     */
    fun live(): List<DerivedGroup> {
        val groupIds = GroupRepo.ids().toHashSet()
        val out = ArrayList<DerivedGroup>()
        for (record in all()) {
            if (record.gid !in groupIds) {
                delete(record.gid)
                Logs.i("[Derived] dropping record for removed group ${record.gid}")
                continue
            }
            val sources = record.sourceGids.filter { it in groupIds && it != record.gid }
            if (sources.isEmpty()) {
                delete(record.gid)
                Logs.i("[Derived] dropping record ${record.gid} with no live source")
                continue
            }
            if (sources.size != record.sourceGids.size) {
                val kept = record.copy(sourceGidsJson = DerivedGroup.gidsToJson(sources))
                put(kept)
                out.add(kept)
            } else {
                out.add(record)
            }
        }
        return out
    }
}
