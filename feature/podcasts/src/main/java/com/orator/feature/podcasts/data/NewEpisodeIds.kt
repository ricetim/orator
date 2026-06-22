package com.orator.feature.podcasts.data

import com.orator.core.database.EpisodeEntity

/**
 * Maps the rowids returned by [com.orator.core.database.EpisodeDao.insertIgnore] back to the ids of
 * the genuinely-new episodes. Room returns one rowid per input row in order; -1 marks a row that
 * was ignored as a duplicate, so a non-(-1) rowid means that episode was inserted now.
 */
object NewEpisodeIds {
    fun from(entities: List<EpisodeEntity>, rowIds: List<Long>): List<String> =
        entities.zip(rowIds).filter { it.second != -1L }.map { it.first.id }
}
