package com.gmcnav.navi

import com.gmcnav.navi.core.Search

/** Keep original floor-2 endpoints; never mask doors or alter routing/access data. */
object EventPresentation {
    fun filter(search: Search) {
        search.items.removeAll { it.floor != 2 }
    }
}
