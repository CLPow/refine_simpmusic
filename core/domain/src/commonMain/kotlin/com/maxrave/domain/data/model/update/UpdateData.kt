package com.maxrave.domain.data.model.update

data class UpdateData(
    val tagName: String,
    val releaseTime: String?,
    val body: String,
    /** Direct URL of the first `.apk` asset, when the release has one. */
    val apkUrl: String? = null,
) {
    /**
     * True when [tagName] is a newer version than [currentVersion].
     * Both sides are normalized (leading `v` stripped) so `v2.0.42` and `2.0.42` compare equal
     * to the installed `2.0.41`.
     */
    fun isNewerThan(currentVersion: String): Boolean {
        val remote = normalizeVersion(tagName)
        val local = normalizeVersion(currentVersion)
        if (remote.isBlank() || local.isBlank()) return remote != local
        return compareVersions(remote, local) > 0
    }

    companion object {
        fun normalizeVersion(raw: String): String =
            raw.trim().removePrefix("v").removePrefix("V").substringBefore("+").substringBefore("-dev")

        /** Semantic-ish compare: split on `.` and compare numeric parts. */
        fun compareVersions(
            a: String,
            b: String,
        ): Int {
            val pa = a.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
            val n = maxOf(pa.size, pb.size)
            for (i in 0 until n) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x.compareTo(y)
            }
            return 0
        }
    }
}
