package cgluwxh.bilising.bili

data class BiliVideoId(val bvid: String, val page: Int)

object BiliParser {
    fun parse(url: String): BiliVideoId? {
        return try {
            val uri = java.net.URI(url)

            // Try to extract bvid from path
            val pathMatch = Regex("/(BV[\\w]+)").find(uri.path ?: "")
            val bvid = if (pathMatch != null) {
                pathMatch.groupValues[1]
            } else {
                // Fallback: try query parameter
                val queryParams = parseQuery(uri.query ?: "")
                queryParams["bvid"] ?: return null
            }

            val queryParams = parseQuery(uri.query ?: "")
            val page = queryParams["p"]?.toIntOrNull() ?: 1

            BiliVideoId(bvid, page)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").associate {
            val parts = it.split("=", limit = 2)
            parts[0] to (parts.getOrNull(1) ?: "")
        }
    }
}
