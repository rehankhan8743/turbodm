package com.turbodm.app.data.local

import androidx.room.TypeConverter
import com.turbodm.app.domain.model.DownloadStatus
import com.turbodm.app.domain.model.PauseReason

class Converters {
    @TypeConverter fun fromStatus(s: DownloadStatus?): String? = s?.name
    @TypeConverter fun toStatus(s: String?): DownloadStatus? = s?.let { DownloadStatus.valueOf(it) }
    @TypeConverter fun fromPauseReason(p: PauseReason?): String? = p?.name
    @TypeConverter fun toPauseReason(s: String?): PauseReason? = s?.let { PauseReason.valueOf(it) }
}
