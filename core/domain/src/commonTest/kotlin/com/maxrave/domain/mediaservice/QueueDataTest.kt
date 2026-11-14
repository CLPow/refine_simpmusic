package com.maxrave.domain.mediaservice

import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueDataTest {
    @Test
    fun addToQueueWhileAutoplayPlaysKeepsTheOrder() {
        // An album of 3 ended; Autoplay began at 3 and its 2nd song (index 4) is playing.
        var data = QueueData.Data(autoplayStartIndex = 3)
        val current = 4
        val first = data.addToQueueIndex(current)!!
        assertEquals(5, first)
        data = data.afterQueueAdd(first, 1)
        val second = data.addToQueueIndex(current)!!
        assertEquals(6, second, "B goes after A, not in front of it")
        data = data.afterQueueAdd(second, 1)
        assertEquals(7, data.autoplayStartIndex)
    }

    @Test
    fun albumWithoutAutoplayAppendsAndKeepsNoBoundary() {
        val data = QueueData.Data(playlistType = PlaylistType.ALBUM)
        assertNull(data.addToQueueIndex(0))
        assertNull(data.afterQueueAdd(10, 1).autoplayStartIndex)
    }

    @Test
    fun radioQueuesGoRightAfterTheSongThenAfterEachOther() {
        var data = QueueData.Data(playlistType = PlaylistType.RADIO)
        assertEquals(1, data.addToQueueIndex(0))
        data = data.afterQueueAdd(1, 2)
        assertEquals(3, data.addToQueueIndex(0))
    }

    @Test
    fun removalAndPlayNextShiftTheBoundary() {
        val data = QueueData.Data(autoplayStartIndex = 5)
        assertEquals(4, data.afterRemove(2).autoplayStartIndex)
        assertEquals(5, data.afterRemove(7).autoplayStartIndex)
        assertEquals(6, data.afterInsert(3, 1).autoplayStartIndex)
        assertEquals(5, data.afterInsert(6, 1).autoplayStartIndex)
    }
}
