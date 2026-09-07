package com.lin0721.linmusic.core.contentfilter

import com.lin0721.linmusic.core.auth.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow

// 跨业务域共享的"屏蔽歌手"内容过滤能力
class ContentFilter(private val blockedArtistIds: Flow<Set<Long>>) {
    constructor(userPreferences: UserPreferences) : this(userPreferences.blockedArtistIds)

    suspend fun <T> filterBlockedArtists(items: List<T>, artistIds: (T) -> List<Long>): List<T> {
        val blocked = blockedArtistIds.first()
        if (blocked.isEmpty()) return items
        return items.filter { item -> artistIds(item).none { it in blocked } }
    }
}
