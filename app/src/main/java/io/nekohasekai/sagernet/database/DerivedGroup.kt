package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonValues

/**
 * A local-only record that marks a group as "derived": its members are rebuilt from the profiles of one or more
 * source groups, filtered by the name patterns stored here. This is deliberately NOT a Room entity: the table is
 * created and accessed with plain SQL in [io.nekohasekai.sagernet.group.DerivedGroupStore]. That keeps it out of
 * Room's schema, out of every backup, and out of KSP's annotation processing entirely.
 */
data class DerivedGroup(
    /** The id of the group in `groups` that this record drives. */
    var gid: Long = 0L,
    /** Source group ids, as a compact JSON array of integers, e.g. "[3,7]". */
    var sourceGidsJson: String = "[]",
    /** Case-insensitive regex on the member name; blank keeps every name. */
    var nameFilter: String = "",
    /** Case-insensitive regex on the member name; matched names are dropped. */
    var nameExclude: String = "",
    /** 0 = manual (only on an explicit update), 1 = automatic (also on a source subscription refresh). */
    var updateMode: Int = MODE_MANUAL,
) {
    val sourceGids: List<Long> get() = parseGids(sourceGidsJson)

    companion object {
        const val TABLE = "derived_groups"
        const val MODE_MANUAL = 0
        const val MODE_AUTOMATIC = 1

        const val CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS `$TABLE` (" +
                "`gid` INTEGER PRIMARY KEY NOT NULL, " +
                "`source_gids_json` TEXT NOT NULL DEFAULT '[]', " +
                "`name_filter` TEXT NOT NULL DEFAULT '', " +
                "`name_exclude` TEXT NOT NULL DEFAULT '', " +
                "`update_mode` INTEGER NOT NULL DEFAULT 0)"

        private const val COLUMNS = "`gid`, `source_gids_json`, `name_filter`, `name_exclude`, `update_mode`"
        const val SELECT_ALL = "SELECT $COLUMNS FROM `$TABLE`"
        const val SELECT_BY_GID = "SELECT $COLUMNS FROM `$TABLE` WHERE `gid` = ?"
        const val INSERT_OR_REPLACE =
            "INSERT OR REPLACE INTO `$TABLE` ($COLUMNS) VALUES (?, ?, ?, ?, ?)"
        const val DELETE_BY_GID = "DELETE FROM `$TABLE` WHERE `gid` = ?"

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
}
