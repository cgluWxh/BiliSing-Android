package cgluwxh.bilising.bili

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class BiliVideoInfo(val aid: Long, val cid: Long)

object BiliPlayUrlApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getVideoInfoWithPage(bvid: String, page: Int): BiliVideoInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
            .addHeader("User-Agent", BiliAppSign.USER_AGENT)
            .addHeader("Referer", "https://www.bilibili.com")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("响应为空")
        val json = JSONObject(body)

        if (json.getInt("code") != 0) {
            throw Exception("API错误: ${json.optString("message")}")
        }

        val data = json.getJSONObject("data")
        val aid = data.getLong("aid")
        val pages = data.getJSONArray("pages")
        val pageIndex = (page - 1).coerceIn(0, pages.length() - 1)
        val cid = pages.getJSONObject(pageIndex).getLong("cid")

        BiliVideoInfo(aid, cid)
    }

    suspend fun getPlayUrl(aid: Long, cid: Long): String = withContext(Dispatchers.IO) {
        val qnList = listOf(64, 32, 16)
        for (qn in qnList) {
            val url = fetchPlayUrl(aid, cid, qn)
            if (url != null) return@withContext url
        }
        throw Exception("无法获取播放地址（所有画质均失败）")
    }

    private fun fetchPlayUrl(aid: Long, cid: Long, qn: Int): String? {
        val params = mapOf(
            "actionKey" to "appkey",
            "cid" to cid.toString(),
            "fourk" to "1",
            "is_proj" to "1",
            "mobi_app" to "android",
            "object_id" to aid.toString(),
            "platform" to "android",
            "playurl_type" to "1",
            "protocol" to "0",
            "qn" to qn.toString()
        )
        val signed = BiliAppSign.sign(params)
        val query = signed.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }

        val request = Request.Builder()
            .url("https://api.bilibili.com/x/tv/playurl?$query")
            .addHeader("User-Agent", BiliAppSign.USER_AGENT)
            .addHeader("env", "prod")
            .addHeader("app-key", "android64")
            .addHeader("x-bili-aurora-zone", "sh001")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return null
        val json = JSONObject(body)

        if (json.getInt("code") != 0) return null

        val data = json.optJSONObject("data") ?: return null
        val durl = data.optJSONArray("durl") ?: return null
        if (durl.length() == 0) return null

        return durl.getJSONObject(0).getString("url")
    }
}
