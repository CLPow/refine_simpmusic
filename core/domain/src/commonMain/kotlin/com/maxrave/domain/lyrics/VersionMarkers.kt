package com.maxrave.domain.lyrics

enum class VersionMarker {
    INSTRUMENTAL,
    LIVE,
    REMIX,
    ACOUSTIC,
    COVER,
    SPED,
    ENGLISH,
    MANDARIN,
    CANTONESE,
    JAPANESE,
    KOREAN,
}

object VersionMarkers {
    private val LATIN =
        listOf(
            VersionMarker.INSTRUMENTAL to listOf("instrumental", "inst", "off vocal", "karaoke", "backing track", "minus one"),
            VersionMarker.LIVE to listOf("live"),
            VersionMarker.REMIX to listOf("remix", "rmx"),
            VersionMarker.ACOUSTIC to listOf("acoustic", "unplugged"),
            VersionMarker.COVER to listOf("cover"),
            VersionMarker.SPED to listOf("sped up", "speed up", "slowed", "nightcore"),
        ).map { (marker, words) -> marker to words.map { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])") } }

    private val LANGUAGE_WORDS =
        listOf(
            VersionMarker.ENGLISH to listOf("english", "eng"),
            VersionMarker.MANDARIN to listOf("chinese", "mandarin"),
            VersionMarker.CANTONESE to listOf("cantonese"),
            VersionMarker.JAPANESE to listOf("japanese", "jpn", "jp"),
            VersionMarker.KOREAN to listOf("korean", "kor", "kr"),
        ).map { (marker, words) ->
            marker to Regex("(?<![\\p{L}\\p{N}])(?:" + words.joinToString("|") + ")\\s*(?:ver\\.?|version)(?![\\p{L}\\p{N}])")
        }

    private val CJK =
        listOf(
            VersionMarker.INSTRUMENTAL to listOf("伴奏", "純音樂", "纯音乐", "卡拉ok", "인스트", "반주", "インスト", "オフボーカル"),
            VersionMarker.LIVE to listOf("現場", "现场", "演唱會", "演唱会", "라이브"),
            VersionMarker.REMIX to listOf("리믹스", "リミックス"),
            VersionMarker.ACOUSTIC to listOf("어쿠스틱", "アコースティック"),
            VersionMarker.COVER to listOf("翻唱", "커버", "カバー"),
            VersionMarker.ENGLISH to listOf("英文版", "英語版", "英语版", "영어"),
            VersionMarker.MANDARIN to listOf("中文版", "國語版", "国语版", "華語版", "华语版", "普通話", "普通话", "중국어"),
            VersionMarker.CANTONESE to listOf("粵語", "粤语", "廣東話", "广东话"),
            VersionMarker.JAPANESE to listOf("日文版", "日語版", "日语版", "日本語", "일본어"),
            VersionMarker.KOREAN to listOf("韓文版", "韩文版", "韓語版", "韩语版", "한국어", "韓国語"),
        )

    fun of(title: String): Set<VersionMarker> {
        val text = title.lowercase()
        val found = mutableSetOf<VersionMarker>()
        LATIN.forEach { (marker, patterns) -> if (patterns.any { it.containsMatchIn(text) }) found += marker }
        LANGUAGE_WORDS.forEach { (marker, pattern) -> if (pattern.containsMatchIn(text)) found += marker }
        CJK.forEach { (marker, words) -> if (words.any { it in text }) found += marker }
        return found
    }

    // Live, remix, acoustic and cover keep the same words, so lyrics may cross those; never instrumental, sped or language.
    private val LYRICS_STRICT =
        setOf(
            VersionMarker.INSTRUMENTAL,
            VersionMarker.SPED,
            VersionMarker.ENGLISH,
            VersionMarker.MANDARIN,
            VersionMarker.CANTONESE,
            VersionMarker.JAPANESE,
            VersionMarker.KOREAN,
        )

    fun lyricsVersion(title: String): Set<VersionMarker> = of(title).intersect(LYRICS_STRICT)

    // Lyrics with no CJK text at all cannot belong to a song whose title is written in CJK.
    fun lyricsFitTitle(
        title: String,
        lyricsText: String,
    ): Boolean = !hasCjk(title) || hasCjk(lyricsText)

    private fun hasCjk(text: String): Boolean = text.any { it.isCjk() }

    private fun Char.isCjk(): Boolean =
        this in '぀'..'ヿ' ||
            this in '㐀'..'䶿' ||
            this in '一'..'鿿' ||
            this in '가'..'힯' ||
            this in 'ᄀ'..'ᇿ' ||
            this in '豈'..'﫿'
}
