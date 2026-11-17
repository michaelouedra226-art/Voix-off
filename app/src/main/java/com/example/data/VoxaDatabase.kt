package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String,
    val briefDescription: String,
    val briefRawText: String,
    val expertScript: String,
    val defaultVoice: String,
    val ttsModelId: String,
    val languageCode: String,
    val voiceVolume: Float,
    val ambienceVolume: Float,
    val duckingAmountDb: Float,
    val fadeDurationSec: Float,
    val crossfadeMs: Int,
    val updatedAt: Long
)

@Entity(tableName = "script_versions")
data class ScriptVersionEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val script: String,
    val label: String,
    val createdAt: Long
)

@Entity(tableName = "cached_segments")
data class CachedSegmentEntity(
    @PrimaryKey val fingerprint: String,
    val pcmFilePath: String,
    val durationSec: Float,
    val peakDb: Float,
    val defectName: String?,
    val createdAt: Long
)

@Entity(tableName = "pronunciation_entries")
data class PronunciationEntity(
    @PrimaryKey val id: String,
    val originalWord: String,
    val phoneticReplacement: String
)

@Entity(tableName = "custom_ambiences")
data class CustomAmbienceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pcmFilePath: String,
    val durationSec: Float,
    val createdAt: Long
)

@Entity(tableName = "generations")
data class GenerationEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val title: String,
    val sourceMode: String,
    val annotatedScript: String,
    val directorExplanation: String,
    val selectedVoice: String,
    val modelId: String,
    val mixedPcmPath: String,
    val voiceOnlyPcmPath: String,
    val durationSec: Float,
    val peakDb: Float,
    val integratedLufs: Float,
    val waveformPeaksCsv: String,
    val ambiencesSummary: String,
    val segmentCount: Int,
    val createdAt: Long
)

@Dao
interface VoxaDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: String)

    @Query("SELECT * FROM script_versions WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 25")
    fun observeVersionsForProject(projectId: String): Flow<List<ScriptVersionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVersion(version: ScriptVersionEntity)

    @Query("DELETE FROM script_versions WHERE projectId = :projectId")
    suspend fun deleteVersionsForProject(projectId: String)

    @Query("SELECT * FROM cached_segments WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun getCachedSegment(fingerprint: String): CachedSegmentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedSegment(segment: CachedSegmentEntity)

    @Query("DELETE FROM cached_segments WHERE fingerprint = :fingerprint")
    suspend fun deleteCachedSegment(fingerprint: String)

    @Query("SELECT * FROM cached_segments ORDER BY createdAt ASC")
    suspend fun getAllCachedSegments(): List<CachedSegmentEntity>

    @Query("SELECT * FROM pronunciation_entries ORDER BY originalWord COLLATE NOCASE ASC")
    fun observeDictionary(): Flow<List<PronunciationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPronunciation(entry: PronunciationEntity)

    @Query("DELETE FROM pronunciation_entries WHERE id = :id")
    suspend fun deletePronunciation(id: String)

    @Query("SELECT * FROM custom_ambiences ORDER BY createdAt DESC")
    fun observeCustomAmbiences(): Flow<List<CustomAmbienceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomAmbience(ambience: CustomAmbienceEntity)

    @Query("DELETE FROM custom_ambiences WHERE id = :id")
    suspend fun deleteCustomAmbience(id: String)

    @Query("SELECT * FROM generations WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 30")
    fun observeGenerationsForProject(projectId: String): Flow<List<GenerationEntity>>

    @Query("SELECT * FROM generations WHERE projectId = :projectId")
    suspend fun getGenerationsForProjectOnce(projectId: String): List<GenerationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeneration(generation: GenerationEntity)

    @Query("DELETE FROM generations WHERE id = :id")
    suspend fun deleteGenerationById(id: String)

    @Query("DELETE FROM generations WHERE projectId = :projectId")
    suspend fun deleteGenerationsForProject(projectId: String)
}

@Database(
    entities = [
        ProjectEntity::class,
        ScriptVersionEntity::class,
        CachedSegmentEntity::class,
        PronunciationEntity::class,
        CustomAmbienceEntity::class,
        GenerationEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class VoxaDatabase : RoomDatabase() {
    abstract fun voxaDao(): VoxaDao

    companion object {
        @Volatile
        private var INSTANCE: VoxaDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `generations` (
                        `id` TEXT NOT NULL,
                        `projectId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `sourceMode` TEXT NOT NULL,
                        `annotatedScript` TEXT NOT NULL,
                        `directorExplanation` TEXT NOT NULL,
                        `selectedVoice` TEXT NOT NULL,
                        `modelId` TEXT NOT NULL,
                        `mixedPcmPath` TEXT NOT NULL,
                        `voiceOnlyPcmPath` TEXT NOT NULL,
                        `durationSec` REAL NOT NULL,
                        `peakDb` REAL NOT NULL,
                        `integratedLufs` REAL NOT NULL,
                        `waveformPeaksCsv` TEXT NOT NULL,
                        `ambiencesSummary` TEXT NOT NULL,
                        `segmentCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): VoxaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VoxaDatabase::class.java,
                    "voxa_studio.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
