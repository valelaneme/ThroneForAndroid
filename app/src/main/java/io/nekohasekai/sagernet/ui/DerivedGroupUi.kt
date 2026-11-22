package io.nekohasekai.sagernet.ui

import android.content.Context
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.group.DerivedGroupStore

/** Builds the choices offered by the "source groups" multi-select. */
internal object DerivedGroupUi {

    /**
     * Only subscription groups can be a source: a derived group has no URL of its own, so listing it would allow
     * chaining derived groups, which this minimal version does not support. Archived groups and the group being
     * edited are left out too. Returns (id, label) in tab order.
     */
    fun sourceChoices(context: Context, editingId: Long): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (group in GroupRepo.all()) {
            if (!group.isSubscription || group.id == editingId) continue
            if (group.archive) continue
            if (DerivedGroupStore.isDerived(group.id)) continue
            out.add(group.id to group.displayName())
        }
        return out
    }
}
