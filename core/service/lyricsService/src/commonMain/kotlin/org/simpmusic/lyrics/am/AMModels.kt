package org.simpmusic.lyrics.am

import kotlinx.serialization.Serializable

@Serializable
data class AMSearchResponse(
    val results: AMResults? = null,
    val resources: AMResources? = null,
)

@Serializable
data class AMResults(
    val artists: AMRefList? = null,
)

@Serializable
data class AMRefList(
    val data: List<AMRef> = emptyList(),
)

@Serializable
data class AMRef(
    val id: String,
)

@Serializable
data class AMResources(
    val artists: Map<String, AMArtistResource> = emptyMap(),
)

@Serializable
data class AMArtistResource(
    val id: String,
    val attributes: AMArtistAttributes? = null,
)

@Serializable
data class AMArtistAttributes(
    val name: String? = null,
    val url: String? = null,
    val keyColor: String? = null,
    val artwork: AMArtwork? = null,
    val editorialArtwork: AMEditorialArtwork? = null,
)

@Serializable
data class AMEditorialArtwork(
    val musicContentColorLogoTrimmed: AMArtwork? = null,
    val subscriptionHero: AMArtwork? = null,
    val bannerUber: AMArtwork? = null,
)

@Serializable
data class AMArtwork(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bgColor: String? = null,
    val textColor1: String? = null,
    val textColor2: String? = null,
    val textColor3: String? = null,
    val textColor4: String? = null,
)

fun AMArtwork.toImageUrl(
    width: Int,
    height: Int,
    format: String = "png",
    crop: String = "",
): String? =
    url
        ?.replace("{w}", width.toString())
        ?.replace("{h}", height.toString())
        ?.replace("{c}", crop)
        ?.replace("{f}", format)
