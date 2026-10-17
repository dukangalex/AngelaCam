package com.dukangalex.angelacam

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本应用拍摄记录（隐私设计）：
 * 只记录本应用自己拍摄的照片/视频 URI，不申请 READ_MEDIA_* 权限，
 * 不扫描用户相册里的其他内容。
 */
object CaptureStore {

    private const val TAG = "CaptureStore"
    private const val FILE_NAME = "captures.json"
    private const val MAX_KEEP = 500

    data class Item(
        val uri: String,
        val mime: String,
        val date: Long,
        val name: String
    ) {
        val isVideo: Boolean get() = mime.startsWith("video")
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun add(context: Context, uri: Uri, mime: String, name: String) {
        try {
            val items = readRaw(context).toMutableList()
            // 去重：同一 URI 只保留一条，放最前面
            items.removeAll { it.uri == uri.toString() }
            items.add(0, Item(uri.toString(), mime, System.currentTimeMillis(), name))
            writeRaw(context, items.take(MAX_KEEP))
        } catch (e: Exception) {
            Log.w(TAG, "保存拍摄记录失败", e)
        }
    }

    @Synchronized
    fun remove(context: Context, uri: String) {
        try {
            val items = readRaw(context).filter { it.uri != uri }
            writeRaw(context, items)
        } catch (e: Exception) {
            Log.w(TAG, "删除拍摄记录失败", e)
        }
    }

    /**
     * 返回仍然存在的拍摄记录（自动清理已被用户在别处删除的条目）。
     * 新的在前。
     */
    @Synchronized
    fun list(context: Context): List<Item> {
        val items = readRaw(context)
        if (items.isEmpty()) return emptyList()
        val alive = mutableListOf<Item>()
        val resolver = context.contentResolver
        for (item in items) {
            try {
                resolver.openInputStream(Uri.parse(item.uri))?.close()
                alive.add(item)
            } catch (e: Exception) {
                // 文件已不存在，跳过（稍后写回清理）
            }
        }
        if (alive.size != items.size) {
            try {
                writeRaw(context, alive)
            } catch (e: Exception) {
                Log.w(TAG, "清理拍摄记录失败", e)
            }
        }
        return alive
    }

    private fun readRaw(context: Context): List<Item> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                Item(
                    uri = o.getString("uri"),
                    mime = o.optString("mime", ""),
                    date = o.optLong("date", 0L),
                    name = o.optString("name", "")
                )
            )
        }
        return out
    }

    private fun writeRaw(context: Context, items: List<Item>) {
        val arr = JSONArray()
        for (item in items) {
            arr.put(
                JSONObject()
                    .put("uri", item.uri)
                    .put("mime", item.mime)
                    .put("date", item.date)
                    .put("name", item.name)
            )
        }
        file(context).writeText(arr.toString())
    }
}
