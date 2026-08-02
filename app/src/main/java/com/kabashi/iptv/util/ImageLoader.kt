package com.kabashi.iptv.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.kabashi.iptv.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(imageView: ImageView, url: String) {
        imageView.setImageResource(R.drawable.channel_placeholder)
        imageView.tag = url
        if (url.isBlank()) return

        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", "KABASHI-IPTV/1.0")
                }
                try {
                    connection.inputStream.use(BitmapFactory::decodeStream)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            if (bitmap != null) {
                cache.put(url, bitmap)
                main.post {
                    if (imageView.tag == url) imageView.setImageBitmap(bitmap)
                }
            }
        }
    }
}
