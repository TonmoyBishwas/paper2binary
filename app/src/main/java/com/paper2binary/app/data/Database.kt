package com.paper2binary.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A survey, with its full definition stored as JSON since it is only ever read as a whole. */
@Entity(tableName = "surveys")
data class SurveyEntity(
    @PrimaryKey val id: String,
    val json: String,
    val createdAt: Long,
)

/** One scanned paper. Images are kept as files, referenced here by name. */
@Entity(
    tableName = "responses",
    foreignKeys = [
        ForeignKey(
            entity = SurveyEntity::class,
            parentColumns = ["id"],
            childColumns = ["surveyId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("surveyId"), Index("surveyId", "answerKey")],
)
data class ResponseEntity(
    @PrimaryKey val id: String,
    val surveyId: String,
    val scannedAt: Long,
    /** JSON list of ticked option indexes per question. */
    val answersJson: String,
    /** Normalised answers; duplicates are only searched among sheets with the same key. */
    val answerKey: String,
    val commentText: String?,
    /** True once a person has confirmed the comment text, rather than it being an OCR draft. */
    val commentConfirmed: Boolean,
    val commentImage: String?,
    val sheetImage: String?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val fingerprint: ByteArray?,
    val reviewed: Boolean,
)

/** The few columns needed when searching for a suspected rescan. */
data class FingerprintRow(
    val id: String,
    val scannedAt: Long,
    val sheetImage: String?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val fingerprint: ByteArray?,
)

@Dao
interface SurveyDao {
    @Query("SELECT * FROM surveys ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SurveyEntity>>

    @Query("SELECT * FROM surveys WHERE id = :id")
    fun observe(id: String): Flow<SurveyEntity?>

    @Query("SELECT * FROM surveys WHERE id = :id")
    suspend fun get(id: String): SurveyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNew(survey: SurveyEntity)

    // Update rather than replace: replacing would cascade-delete all of the survey's responses.
    @Update
    suspend fun update(survey: SurveyEntity)

    @Query("DELETE FROM surveys WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT surveyId AS id, COUNT(*) AS count FROM responses GROUP BY surveyId")
    fun observeCounts(): Flow<List<SurveyCount>>
}

data class SurveyCount(val id: String, val count: Int)

@Dao
interface ResponseDao {
    @Query("SELECT * FROM responses WHERE surveyId = :surveyId ORDER BY scannedAt DESC")
    fun observeForSurvey(surveyId: String): Flow<List<ResponseEntity>>

    @Query("SELECT * FROM responses WHERE surveyId = :surveyId ORDER BY scannedAt ASC")
    suspend fun allForSurvey(surveyId: String): List<ResponseEntity>

    @Query("SELECT COUNT(*) FROM responses WHERE surveyId = :surveyId")
    fun observeCount(surveyId: String): Flow<Int>

    @Query("SELECT * FROM responses WHERE id = :id")
    suspend fun get(id: String): ResponseEntity?

    @Query(
        "SELECT id, scannedAt, sheetImage, fingerprint FROM responses " +
            "WHERE surveyId = :surveyId AND answerKey = :answerKey",
    )
    suspend fun withSameAnswers(surveyId: String, answerKey: String): List<FingerprintRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(response: ResponseEntity): Long

    @Update
    suspend fun update(response: ResponseEntity)

    @Delete
    suspend fun delete(response: ResponseEntity)
}

@Database(entities = [SurveyEntity::class, ResponseEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun surveys(): SurveyDao
    abstract fun responses(): ResponseDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "paper2binary.db").build()
    }
}
