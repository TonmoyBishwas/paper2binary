package com.paper2binary.app.data

import android.content.Context
import com.paper2binary.app.util.ImageConversions.saveJpeg
import com.paper2binary.core.analysis.CsvExporter
import com.paper2binary.core.analysis.ResponseRecord
import com.paper2binary.core.analysis.SurveyBundle
import com.paper2binary.core.dedupe.SheetFingerprint
import com.paper2binary.core.model.SheetSpec
import com.paper2binary.core.model.Survey
import com.paper2binary.core.pipeline.SheetScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SurveySummary(val survey: Survey, val responseCount: Int)

/** A previously saved sheet that the current sheet appears to be a rescan of. */
data class DuplicateMatch(val responseId: String, val scannedAt: Long, val sheetImage: File?)

/** Single source of truth for surveys, responses and their image files. */
class SurveyRepository(context: Context, db: AppDatabase) {
    private val appContext = context.applicationContext
    private val surveys = db.surveys()
    private val responses = db.responses()
    private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val answersSerializer = ListSerializer(ListSerializer(Int.serializer()))

    private fun surveyDir(surveyId: String) = File(appContext.filesDir, "surveys/$surveyId")

    fun imageFile(surveyId: String, name: String): File = File(surveyDir(surveyId), name)

    private fun SurveyEntity.toSurvey(): Survey = codec.decodeFromString(Survey.serializer(), this.json)

    // ---------------------------------------------------------------- surveys

    fun observeSummaries(): Flow<List<SurveySummary>> =
        surveys.observeAll().combine(surveys.observeCounts()) { list, counts ->
            val byId = counts.associate { it.id to it.count }
            list.map { SurveySummary(it.toSurvey(), byId[it.id] ?: 0) }
        }

    fun observeSurvey(id: String): Flow<Survey?> = surveys.observe(id).map { it?.toSurvey() }

    suspend fun getSurvey(id: String): Survey? = surveys.get(id)?.toSurvey()

    /** Creates or updates; existing responses of the survey are left untouched. */
    suspend fun saveSurvey(survey: Survey) {
        val encoded = codec.encodeToString(Survey.serializer(), survey)
        val existing = surveys.get(survey.id)
        if (existing == null) {
            surveys.insertNew(SurveyEntity(survey.id, encoded, System.currentTimeMillis()))
        } else {
            surveys.update(existing.copy(json = encoded))
        }
    }

    suspend fun deleteSurvey(id: String) {
        surveys.delete(id)
        withContext(Dispatchers.IO) { surveyDir(id).deleteRecursively() }
    }

    /** Makes sure a survey exists for a sheet from elsewhere, so it can be scanned regardless. */
    suspend fun ensureSurveyFor(spec: SheetSpec): Survey {
        getSurvey(spec.surveyId)?.let { return it }
        val placeholder = Survey.placeholderFrom(spec)
        saveSurvey(placeholder)
        return placeholder
    }

    // -------------------------------------------------------------- responses

    fun observeResponses(surveyId: String): Flow<List<ResponseEntity>> =
        responses.observeForSurvey(surveyId)

    fun observeResponseCount(surveyId: String): Flow<Int> = responses.observeCount(surveyId)

    suspend fun getResponse(id: String): ResponseEntity? = responses.get(id)

    fun decodeAnswers(entity: ResponseEntity): List<List<Int>> =
        codec.decodeFromString(answersSerializer, entity.answersJson)

    private fun encodeAnswers(answers: List<List<Int>>): String =
        codec.encodeToString(answersSerializer, answers)

    /** Looks for an already saved sheet with the same answers and the same handwriting. */
    suspend fun findDuplicate(surveyId: String, answers: List<List<Int>>, fingerprint: ByteArray): DuplicateMatch? {
        val candidates = responses.withSameAnswers(surveyId, SheetFingerprint.answerKey(answers))
        return withContext(Dispatchers.Default) {
            candidates
                .mapNotNull { row ->
                    val stored = row.fingerprint ?: return@mapNotNull null
                    val score = SheetFingerprint.similarity(fingerprint, stored)
                    if (score >= SheetFingerprint.DUPLICATE_THRESHOLD) row to score else null
                }
                .maxByOrNull { it.second }
                ?.first
                ?.let { row ->
                    DuplicateMatch(row.id, row.scannedAt, row.sheetImage?.let { imageFile(surveyId, it) })
                }
        }
    }

    /** Stores a scanned sheet with its images. Returns the new response id. */
    suspend fun saveScan(scan: SheetScan, answers: List<List<Int>>, reviewed: Boolean, fingerprint: ByteArray): String {
        val id = UUID.randomUUID().toString()
        val surveyId = scan.spec.surveyId
        val sheetName = "${id}_sheet.jpg"
        val commentName = scan.commentImage?.let { "${id}_comment.jpg" }
        withContext(Dispatchers.IO) {
            scan.sheet.saveJpeg(imageFile(surveyId, sheetName), SHEET_JPEG_QUALITY)
            commentName?.let { scan.commentImage!!.saveJpeg(imageFile(surveyId, it), COMMENT_JPEG_QUALITY) }
        }
        responses.insert(
            ResponseEntity(
                id = id,
                surveyId = surveyId,
                scannedAt = System.currentTimeMillis(),
                answersJson = encodeAnswers(answers),
                answerKey = SheetFingerprint.answerKey(answers),
                commentText = null,
                commentConfirmed = false,
                commentImage = commentName,
                sheetImage = sheetName,
                fingerprint = fingerprint,
                reviewed = reviewed,
            ),
        )
        return id
    }

    suspend fun updateAnswers(id: String, answers: List<List<Int>>) {
        val current = responses.get(id) ?: return
        responses.update(
            current.copy(
                answersJson = encodeAnswers(answers),
                answerKey = SheetFingerprint.answerKey(answers),
                reviewed = true,
            ),
        )
    }

    /** Saves comment text. An unconfirmed draft never overwrites what a person has confirmed. */
    suspend fun updateComment(id: String, text: String, confirmed: Boolean) {
        val current = responses.get(id) ?: return
        if (!confirmed && current.commentConfirmed) return
        responses.update(current.copy(commentText = text, commentConfirmed = confirmed))
    }

    suspend fun deleteResponse(id: String) {
        val current = responses.get(id) ?: return
        responses.delete(current)
        withContext(Dispatchers.IO) {
            current.sheetImage?.let { imageFile(current.surveyId, it).delete() }
            current.commentImage?.let { imageFile(current.surveyId, it).delete() }
        }
    }

    // ----------------------------------------------------------- import/export

    private fun ResponseEntity.toRecord() = ResponseRecord(
        id = id,
        scannedAtMillis = scannedAt,
        answers = decodeAnswers(this),
        commentText = commentText,
        commentImageName = commentImage,
        reviewed = reviewed,
    )

    suspend fun records(surveyId: String): List<ResponseRecord> =
        responses.allForSurvey(surveyId).map { it.toRecord() }

    /** Builds a ZIP with the CSV tables and the comment images. Returns the file for sharing. */
    suspend fun exportZip(surveyId: String): File = withContext(Dispatchers.IO) {
        val survey = requireNotNull(getSurvey(surveyId)) { "unknown survey" }
        val rows = responses.allForSurvey(surveyId)
        val records = rows.map { it.toRecord() }
        val out = exportFile("${safeFileName(survey.title)}_results.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("responses.csv"))
            zip.write(CsvExporter.responses(survey, records).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("summary.csv"))
            zip.write(CsvExporter.summary(survey, records).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            rows.forEach { row ->
                val name = row.commentImage ?: return@forEach
                val image = imageFile(surveyId, name)
                if (image.exists()) {
                    zip.putNextEntry(ZipEntry("comments/$name"))
                    image.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        out
    }

    /** Writes a portable file for another phone: the survey, optionally with its responses. */
    suspend fun exportBundle(surveyId: String, includeResponses: Boolean): File = withContext(Dispatchers.IO) {
        val survey = requireNotNull(getSurvey(surveyId)) { "unknown survey" }
        val bundle = SurveyBundle(survey = survey, responses = if (includeResponses) records(surveyId) else emptyList())
        val suffix = if (includeResponses) "data" else "survey"
        exportFile("${safeFileName(survey.title)}_$suffix.${SurveyBundle.FILE_EXTENSION}").apply {
            writeText(bundle.toJson())
        }
    }

    data class ImportResult(val survey: Survey, val addedResponses: Int)

    /**
     * Merges a bundle into this phone. Real wording replaces a placeholder survey, but never the
     * other way round. Responses already present are skipped by id, so re-importing is harmless.
     */
    suspend fun importBundle(text: String): ImportResult {
        val bundle = SurveyBundle.fromJson(text)
        val existing = getSurvey(bundle.survey.id)
        val survey = when {
            existing == null -> bundle.survey.also { saveSurvey(it) }
            existing.placeholder && !bundle.survey.placeholder -> bundle.survey.also { saveSurvey(it) }
            else -> existing
        }
        var added = 0
        bundle.responses.forEach { r ->
            val inserted = responses.insert(
                ResponseEntity(
                    id = r.id,
                    surveyId = survey.id,
                    scannedAt = r.scannedAtMillis,
                    answersJson = encodeAnswers(r.answers),
                    answerKey = SheetFingerprint.answerKey(r.answers),
                    commentText = r.commentText,
                    commentConfirmed = r.commentText != null && r.reviewed,
                    commentImage = null,
                    sheetImage = null,
                    fingerprint = null,
                    reviewed = r.reviewed,
                ),
            )
            if (inserted != -1L) added++
        }
        return ImportResult(survey, added)
    }

    /** A file under the cache folder exposed by the FileProvider. */
    fun exportFile(name: String): File {
        val dir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        return File(dir, name)
    }

    private fun safeFileName(title: String): String =
        title.replace(Regex("""[^\p{L}\p{N}_-]+"""), "_").trim('_').ifEmpty { "survey" }.take(40)

    private companion object {
        const val SHEET_JPEG_QUALITY = 70
        const val COMMENT_JPEG_QUALITY = 82
    }
}
