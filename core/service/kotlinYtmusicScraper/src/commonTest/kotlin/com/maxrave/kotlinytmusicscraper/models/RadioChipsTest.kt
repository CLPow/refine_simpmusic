package com.maxrave.kotlinytmusicscraper.models

import com.maxrave.kotlinytmusicscraper.toRadioChips
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CHIPS =
    """
    {"chipCloudRenderer":{"chips":[
      {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Save"}]},"navigationEndpoint":{"addToPlaylistEndpoint":{"playlistId":"RDAMVMrlYOsUfpi3M"}}}},
      {"chipCloudChipRenderer":{"text":{"runs":[{"text":"All"}]},"navigationEndpoint":{"queueUpdateCommand":{"queueUpdateSection":"QUEUE_UPDATE_SECTION_QUEUE","fetchContentsCommand":{"watchEndpoint":{"playlistId":"RDAMVMrlYOsUfpi3M","params":"kgQjAll","loggingContext":{"vssLoggingContext":{"serializedContextData":"x"}}}},"dedupeAgainstLocalQueue":true}},"isSelected":true,"uniqueId":"All"}},
      {"chipCloudChipRenderer":{"text":{"runs":[{"text":"Familiar"}]},"navigationEndpoint":{"queueUpdateCommand":{"fetchContentsCommand":{"watchEndpoint":{"playlistId":"RDATiYvrlYOsUfpi3M","params":"kgQjFam","playerParams":"0gcJ"}}}},"isSelected":false,"uniqueId":"Familiar"}}
    ]}}
    """.trimIndent()

class RadioChipsTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun readsRadioEndpointsFromQueueUpdateCommand() {
        val chips = json.decodeFromString<MusicQueueRenderer.SubHeaderChipCloud>(CHIPS).toRadioChips()
        assertEquals(listOf("Save", "All", "Familiar"), chips.map { it.title })
        assertEquals("RDATiYvrlYOsUfpi3M", chips[2].playlistId)
        assertEquals("kgQjFam", chips[2].params)
        assertTrue(chips[1].selected)
    }

    @Test
    fun saveChipHasNoRadio() {
        val save = json.decodeFromString<MusicQueueRenderer.SubHeaderChipCloud>(CHIPS).toRadioChips().first()
        assertNull(save.playlistId)
    }
}
