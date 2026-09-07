package com.lin0721.linmusic.core.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.io.IOException

@UnstableApi
object AudioCacheManager {
    private var cache: SimpleCache? = null
    private var evictor: ResizableCacheEvictor? = null

    // 同一进程始终复用同一实例。数据源可能仍持有它，设置页不能 release。
    @Synchronized
    fun getCache(context: Context, maxSize: Long): SimpleCache {
        require(maxSize > 0)
        val current = cache ?: run {
            val policy = ResizableCacheEvictor(maxSize)
            SimpleCache(
                File(context.applicationContext.cacheDir, "audio_cache"),
                policy,
                StandaloneDatabaseProvider(context.applicationContext)
            ).also {
                cache = it
                evictor = policy
            }
        }
        current.checkInitialization()
        return current
    }

    // 通过驱逐策略调整容量，不替换正在被数据源使用的缓存。
    @Synchronized
    fun updateMaxSize(context: Context, maxSize: Long) {
        val current = getCache(context, maxSize)
        synchronized(current) {
            checkNotNull(evictor).updateMaxBytes(current, maxSize)
        }
    }

    fun clearCache(context: Context, maxSize: Long) {
        clearCachedAudio(getCache(context, maxSize))
    }
}

// 与 SimpleCache 自身的读写使用同一把锁，清理索引和已提交的片段，保留活动写入。
// 后续播放仍可产生新缓存，因此清理不等于关闭“边听边存”。
@UnstableApi
internal fun clearCachedAudio(cache: Cache) = synchronized(cache) {
    val keys = cache.keys.toList()
    val files = keys.flatMap { key -> cache.getCachedSpans(key).mapNotNull { it.file } }
    keys.forEach(cache::removeResource)
    // SimpleCache 底层删除文件是尽力而为；失败不能被 UI 当作清理完成。
    if (files.any { it.exists() }) throw IOException("部分音频缓存文件未能删除")
}
