package com.paper2binary.core.model

import kotlinx.serialization.Serializable

/** One multiple-choice question as the survey author wrote it. */
@Serializable
data class Question(
    val text: String,
    val options: List<String>,
    /** When true a respondent may tick several boxes without it being flagged. */
    val multiSelect: Boolean = false,
)

/**
 * The full, human-readable definition of a survey.
 *
 * The wording lives only on the phone. What is printed in the QR code is the much smaller
 * [SheetSpec], which is enough to read a sheet even on a phone that has never seen the survey.
 */
@Serializable
data class Survey(
    val id: String,
    val title: String,
    val instructions: String = DEFAULT_INSTRUCTIONS,
    val questions: List<Question>,
    val hasComment: Boolean = true,
    val commentPrompt: String = DEFAULT_COMMENT_PROMPT,
    /** True for surveys auto-created from a QR code, whose wording is still unknown. */
    val placeholder: Boolean = false,
) {
    /** The machine-readable structure that decides where every box sits on paper. */
    fun toSpec(): SheetSpec = SheetSpec(
        surveyId = id,
        questions = questions.map { QuestionSpec(it.options.size, it.multiSelect) },
        hasComment = hasComment,
    )

    companion object {
        const val DEFAULT_INSTRUCTIONS = "Put a clear tick inside one box per question. Use a dark pen."
        const val DEFAULT_COMMENT_PROMPT = "Comments or suggestions (optional)"

        /** Builds a survey with generic wording when only the printed QR code is known. */
        fun placeholderFrom(spec: SheetSpec): Survey = Survey(
            id = spec.surveyId,
            title = "Survey ${spec.surveyId}",
            questions = spec.questions.mapIndexed { i, q ->
                Question(
                    text = "Question ${i + 1}",
                    options = List(q.optionCount) { optionLetter(it) },
                    multiSelect = q.multiSelect,
                )
            },
            hasComment = spec.hasComment,
            placeholder = true,
        )

        /** A, B, C, ... used wherever an option needs a short code. */
        fun optionLetter(index: Int): String = ('A' + index).toString()
    }
}
