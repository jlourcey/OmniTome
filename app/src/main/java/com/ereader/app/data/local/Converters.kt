package com.ereader.app.data.local

import androidx.room.TypeConverter
import com.ereader.app.data.local.entity.AnnotationColor
import com.ereader.app.data.local.entity.AnnotationType
import com.ereader.app.data.local.entity.BookSource
import com.ereader.app.data.local.entity.FileType

/**
 * Room TypeConverters — teach Room how to store our custom enum types.
 *
 * Room knows how to store primitive types (String, Int, Long, Float, Boolean)
 * but not enums. These two pairs of functions convert each enum to/from a
 * String so Room can save it in the SQLite database.
 *
 * @TypeConverter tells Room "use this function when reading/writing this type".
 */
class Converters {

    @TypeConverter
    fun fileTypeToString(value: FileType): String = value.name

    @TypeConverter
    fun stringToFileType(value: String): FileType =
        runCatching { FileType.valueOf(value) }.getOrDefault(FileType.UNKNOWN)

    @TypeConverter
    fun bookSourceToString(value: BookSource): String = value.name

    @TypeConverter
    fun stringToBookSource(value: String): BookSource = BookSource.valueOf(value)

    // ── Annotation enums ──────────────────────────────────────────────────────

    @TypeConverter
    fun annotationTypeToString(value: AnnotationType): String = value.name

    @TypeConverter
    fun stringToAnnotationType(value: String): AnnotationType = AnnotationType.valueOf(value)

    @TypeConverter
    fun annotationColorToString(value: AnnotationColor): String = value.name

    @TypeConverter
    fun stringToAnnotationColor(value: String): AnnotationColor = AnnotationColor.valueOf(value)
}
