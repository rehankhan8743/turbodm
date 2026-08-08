package com.turbodm.app.data.local

import androidx.room.TypeConverter
import com.turbodm.app.domain.model.DownloadStatus

class Converters {
    @TypeConverter fun fromStatus(s: DownloadStatus?): String? = s?.name
    @TypeConverter fun toStatus(s: String?): DownloadStatus? = s?.let { DownloadStatus.valueOf(it) }
}
