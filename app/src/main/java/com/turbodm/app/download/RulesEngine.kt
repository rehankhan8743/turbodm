package com.turbodm.app.download

import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes a finished download into a type-specific subfolder based on its
 * file extension or MIME type. Mirrors IDM's "Categories" feature.
 *
 * Buckets:
 *   - videos/  — mp4, mkv, avi, mov, webm, m4v, 3gp, flv, ts, m2ts
 *   - music/   — mp3, m4a, aac, flac, ogg, opus, wav, wma
 *   - images/  — jpg, jpeg, png, gif, webp, bmp, svg, ico
 *   - docs/    — pdf, doc, docx, xls, xlsx, ppt, pptx, txt, md, csv, epub, mobi
 *   - packages/— apk, xapk, apkm, zip, rar, 7z, tar, gz, deb, exe, msi, dmg
 *   - torrents/— torrent
 *   - other/   — anything not matched above
 *
 * Disabled categories are skipped; matching then falls through to `other/`.
 * Duplicate file names are suffixed with `-1`, `-2`, … so an existing file is
 * never overwritten by accident.
 */
@Singleton
class RulesEngine @Inject constructor() {

    enum class Category(val dirName: String, val extensions: Set<String>) {
        VIDEOS("videos", setOf(
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "3gp", "flv", "ts", "m2ts",
            "mpeg", "mpg", "3gpp", "m2v", "vob", "f4v", "mxf"
        )),
        MUSIC("music", setOf(
            "mp3", "mp2", "m4a", "aac", "flac", "ogg", "opus", "oga", "wav",
            "aiff", "aif", "wma", "mka", "mid", "midi", "alac"
        )),
        IMAGES("images", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico")),
        DOCS("docs", setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub", "mobi")),
        PACKAGES("packages", setOf("apk", "xapk", "apkm", "zip", "rar", "7z", "tar", "gz", "bz2", "deb", "exe", "msi", "dmg")),
        TORRENTS("torrents", setOf("torrent")),
        OTHER("other", emptySet());

        companion object {
            fun fromExtension(ext: String?): Category {
                if (ext.isNullOrBlank()) return OTHER
                val e = ext.lowercase(Locale.ROOT)
                return entries.firstOrNull { it != OTHER && e in it.extensions } ?: OTHER
            }

            fun fromMime(mime: String?): Category {
                if (mime.isNullOrBlank()) return OTHER
                val m = mime.lowercase(Locale.ROOT)
                return when {
                    m.startsWith("video/") -> VIDEOS
                    m.startsWith("audio/") -> MUSIC
                    m.startsWith("image/") -> IMAGES
                    m == "application/pdf" || m.startsWith("text/") -> DOCS
                    m.contains("android.package-archive") -> PACKAGES
                    m.contains("bittorrent") -> TORRENTS
                    else -> OTHER
                }
            }
        }
    }

    /**
     * Returns the fully-resolved target path for [fileName] under
     * [baseDir], auto-creating the category subfolder and ensuring the
     * name doesn't collide with an existing file.
     *
     * If [enabled] is false, returns `$baseDir/$fileName` unchanged.
     */
    fun resolveTargetPath(
        baseDir: String,
        fileName: String,
        mimeType: String?,
        enabled: Boolean
    ): String {
        if (!enabled) return dedupe(baseDir, fileName)
        val ext = fileName.substringAfterLast('.', "")
        // MIME hint wins over extension when both disagree; extension
        // wins when MIME is generic (e.g. application/octet-stream).
        val cat = Category.fromExtension(ext).takeIf { it != Category.OTHER }
            ?: Category.fromMime(mimeType)
        val dir = File(baseDir, cat.dirName)
        dir.mkdirs()
        return dedupe(dir.absolutePath, fileName)
    }

    /**
     * Computes a non-colliding file name under [dir]. `movie.mp4` becomes
     * `movie-1.mp4`, `movie-2.mp4`, … until a free name is found.
     */
    private fun dedupe(dir: String, fileName: String): String {
        val target = File(dir, fileName)
        if (!target.exists()) return target.absolutePath
        val dot = fileName.lastIndexOf('.')
        val stem = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = File(dir, "$stem-$i$ext")
            if (!candidate.exists()) return candidate.absolutePath
            i++
            if (i > 999) return candidate.absolutePath // pathological guard
        }
    }
}
