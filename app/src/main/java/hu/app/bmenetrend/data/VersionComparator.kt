package hu.app.bmenetrend.data

object VersionComparator {
    fun parseNumbers(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V")
        val main = clean.substringBefore('-').substringBefore('+')
        return main.split('.').mapNotNull { it.trim().toIntOrNull() }
    }

    /**
     * Returns true ONLY if [remoteVersion] is strictly newer than [localVersion].
     * If they are equal or remote is older, returns false.
     */
    fun isNewer(remoteVersion: String, localVersion: String): Boolean {
        val remote = parseNumbers(remoteVersion)
        val local = parseNumbers(localVersion)
        if (remote.isEmpty() || local.isEmpty()) return false
        val length = maxOf(remote.size, local.size)
        for (i in 0 until length) {
            val r = remote.getOrElse(i) { 0 }
            val l = local.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
