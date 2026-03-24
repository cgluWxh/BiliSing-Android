package cgluwxh.bilising.bili

import java.net.URLEncoder
import java.security.MessageDigest

object BiliAppSign {
    const val APP_KEY = "dfca71928277209b"
    private const val APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e"
    const val USER_AGENT = "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2"

    fun sign(params: Map<String, String>): Map<String, String> {
        val mutable = params.toMutableMap()
        mutable["appkey"] = APP_KEY
        mutable["ts"] = (System.currentTimeMillis() / 1000).toString()

        // Sort by key Unicode order
        val sorted = mutable.toSortedMap()

        // Build query string with URL encoding
        val query = sorted.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        // Append appsec directly (no separator)
        val toHash = query + APP_SEC

        // MD5 as lowercase hex
        val md5 = MessageDigest.getInstance("MD5")
            .digest(toHash.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        mutable["sign"] = md5
        return mutable
    }
}
