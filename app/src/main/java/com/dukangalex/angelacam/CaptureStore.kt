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
     * 只取最新一条仍然存在的记录（给主界面缩略图用）：
     * 从最新开始逐条探活，命中即返回，不做全量扫描、不写回文件，
     * 避免记录攒多后每次拍照都为取一张缩略图付出全表探测的代价。
     * 失效条目的清理留给全量 list() 时顺带完成。
     */
    @Synchronized
    fun latest(context: Context): Item? {
        val items = readRaw(context)
        val resolver = context.contentResolver
        for (item in items) {
            try {
                resolver.openInputStream(Uri.parse(item.uri))?.close()
                return item
            } catch (e: Exception) {
                // 已被删除，继续往后找
            }
        }
        return null
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
        // 文件损坏（写入中断、存储异常）时按空记录处理并自愈，
        // 绝不让一次坏写变成相册/详情页每次打开必崩
        val arr = try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "拍摄记录文件损坏，按空处理", e)
            return emptyList()
        }
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
        // 原子写：先写临时文件再 rename 替换，进程被杀/断电也不会留下半截文件
        val target = file(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(target)) {
            // 极少数文件系统 rename 失败时退回直写，保证功能可用
            target.writeText(arr.toString())
            tmp.delete()
        }
    }
}
