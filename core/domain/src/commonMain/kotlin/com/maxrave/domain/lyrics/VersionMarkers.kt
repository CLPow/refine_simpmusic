package com.maxrave.domain.lyrics

object VersionMarkers {
    const val INSTRUMENTAL = "instrumental"
    const val LIVE = "live"
    const val REMIX = "remix"
    const val ACOUSTIC = "acoustic"
    const val COVER = "cover"
    const val SPED = "sped"
    const val ENGLISH = "lang:en"
    const val MANDARIN = "lang:zh"
    const val CANTONESE = "lang:yue"
    const val JAPANESE = "lang:ja"
    const val KOREAN = "lang:ko"

    private val LATIN =
        listOf(
            INSTRUMENTAL to listOf("instrumental", "inst", "off vocal", "karaoke", "backing track", "minus one"),
            LIVE to listOf("live"),
            REMIX to listOf("remix", "rmx"),
            ACOUSTIC to listOf("acoustic", "unplugged"),
            COVER to listOf("cover"),
            SPED to listOf("sped up", "speed up", "slowed", "nightcore"),
        ).map { (marker, words) -> marker to words.map { Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])") } }

    private val LANGUAGE_WORDS =
        listOf(
            ENGLISH to listOf("english", "eng"),
            MANDARIN to listOf("chinese", "mandarin"),
            CANTONESE to listOf("cantonese"),
            JAPANESE to listOf("japanese", "jpn", "jp"),
            KOREAN to listOf("korean", "kor", "kr"),
        ).map { (marker, words) ->
            marker to Regex("(?<![\\p{L}\\p{N}])(?:" + words.joinToString("|") + ")\\s*(?:ver\\.?|version)(?![\\p{L}\\p{N}])")
        }

    private val CJK =
        listOf(
            INSTRUMENTAL to listOf("伴奏", "純音樂", "纯音乐", "卡拉ok", "inst.", "인스트", "반주", "インスト", "オフボーカル"),
            LIVE to listOf("現場", "现场", "演唱會", "演唱会", "라이브"),
            REMIX to listOf("리믹스", "リミックス"),
            ACOUSTIC to listOf("어쿠스틱", "アコースティック"),
            COVER to listOf("翻唱", "커버", "カバー"),
            ENGLISH to listOf("英文版", "英語版", "英语版", "영어"),
            MANDARIN to listOf("中文版", "國語版", "国语版", "華語版", "华语版", "普通話", "普通话", "중국어"),
            CANTONESE to listOf("粵語", "粤语", "廣東話", "广东话"),
            JAPANESE to listOf("日文版", "日語版", "日语版", "日本語", "일본어"),
            KOREAN to listOf("韓文版", "韩文版", "韓語版", "韩语版", "한국어", "韓国語"),
        )

    fun of(title: String): Set<String> {
        val text = title.lowercase()
        val found = mutableSetOf<String>()
        LATIN.forEach { (marker, patterns) -> if (patterns.any { it.containsMatchIn(text) }) found += marker }
        LANGUAGE_WORDS.forEach { (marker, pattern) -> if (pattern.containsMatchIn(text)) found += marker }
        CJK.forEach { (marker, words) -> if (words.any { it in text }) found += marker }
        return found
    }

    fun hasCjk(text: String): Boolean = text.any { it.isCjk() }

    // Lyrics with no CJK text at all cannot belong to a song whose title is written in CJK.
    fun lyricsFitTitle(
        title: String,
        lyricsText: String,
    ): Boolean = !hasCjk(title) || hasCjk(lyricsText)

    private fun Char.isCjk(): Boolean =
        this in '぀'..'ヿ' ||
            this in '㐀'..'䶿' ||
            this in '一'..'鿿' ||
            this in '가'..'힯' ||
            this in 'ᄀ'..'ᇿ' ||
            this in '豈'..'﫿'
}
