package io.nekohasekai.sagernet.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonValues

/**
 * A local-only record that marks a group as "derived": its members are rebuilt from the profiles of one or more
 * source groups, filtered by the name patterns stored here. This table is deliberately NOT part of the desktop schema:
 * backups carry only the `groups` / `profiles` rows (a plain group plus its nodes), never this relationship. The
 * Android side stays the single source of truth for how a derived group updates.
 */
@Entity(tableName = DerivedGroup.TABLE)
data class DerivedGroup(
    /** The id of the group in `groups` that this record drives. */
    @PrimaryKey var gid: Long = 0L,
    /** Source group ids, as a compact JSON array of integers, e.g. "[3,7]". */
    @ColumnInfo(name = "source_gids_json", defaultValue = "[]") var sourceGidsJson: String = "[]",
    /** Case-insensitive regex on the member name; blank keeps every name. */
    @ColumnInfo(name = "name_filter", defaultValue = "") var nameFilter: String = "",
    /** Case-insensitive regex on the member name; matched names are dropped. */
    @ColumnInfo(name = "name_exclude", defaultValue = "") var nameExclude: String = "",
    /** 0 = manual (only on an explicit update), 1 = automatic (also on a source subscription refresh). */
    @ColumnInfo(name = "update_mode", defaultValue = "0") var updateMode: Int = MODE_MANUAL,
) {
    val sourceGids: List<Long> get() = parseGids(sourceGidsJson)

    companion object {
        const val TABLE = "derived_groups"
        const val MODE_MANUAL = 0
        const val MODE_AUTOMATIC = 1

        @JvmStatic
        fun gidsToJson(ids: List<Long>): String {
            val array = JsonArray()
            ids.forEach { array.add(it) }
            return array.toCompact()
        }

        @JvmStatic
        fun parseGids(text: String?): List<Long> {
            if (text.isNullOrBlank()) return emptyList()
            val array = JsonInput.parseValue(text) as? JsonArray ?: return emptyList()
            val out = LinkedHashSet<Long>()
            for (item in array) JsonValues.toInteger(item).takeIf { it > 0L }?.let(out::add)
            return out.toList()
        }
    }

    @Dao
    interface Dao {
        @Query("SELECT * FROM `derived_groups`")
        fun all(): List<DerivedGroup>

        @Query("SELECT * FROM `derived_groups` WHERE `gid` = :gid")
        fun get(gid: Long): DerivedGroup?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun put(record: DerivedGroup)

        @Query("DELETE FROM `derived_groups` WHERE `gid` = :gid")
        fun delete(gid: Long)

        @Query("DELETE FROM `derived_groups`")
        fun clear()
    }
}
