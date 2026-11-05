package com.paper2binary.core.model

import kotlin.random.Random

/** Structure of one question: everything the layout needs, nothing about its wording. */
data class QuestionSpec(val optionCount: Int, val multiSelect: Boolean = false)

/**
 * The compact description of a sheet that is printed in its QR code.
 *
 * Example payload: `P2B1:K7M2QX:4.4M.5.3.4.2:C`
 *  - `P2B1`   format tag; the digit is the layout generation, so old sheets stay readable
 *  - `K7M2QX` survey id
 *  - `4.4M.5` option count per question, `M` marks a multi-select question
 *  - `C`      the sheet has a comment box (`N` when it does not)
 *
 * Only characters from the QR "alphanumeric" set are used. That keeps the code at version 2
 * or 3, whose large modules survive cheap printing and repeated photocopying.
 */
data class SheetSpec(
    val surveyId: String,
    val questions: List<QuestionSpec>,
    val hasComment: Boolean,
) {
    fun encode(): String {
        val structure = questions.joinToString(".") { q ->
            q.optionCount.toString() + if (q.multiSelect) "M" else ""
        }
        return "$TAG:$surveyId:$structure:${if (hasComment) "C" else "N"}"
    }

    companion object {
        const val TAG = "P2B1"

        /** Returns null for anything that is not one of our sheets, e.g. a stray QR code. */
        fun decode(payload: String): SheetSpec? {
            val parts = payload.trim().split(':')
            if (parts.size != 4 || parts[0] != TAG) return null
            val id = parts[1]
            if (id.isEmpty() || !id.all { it in ID_ALPHABET }) return null
            val questions = parts[2].split('.').map { token ->
                val multi = token.endsWith("M")
                val count = token.removeSuffix("M").toIntOrNull() ?: return null
                if (count !in 2..12) return null
                QuestionSpec(count, multi)
            }
            val hasComment = when (parts[3]) {
                "C" -> true
                "N" -> false
                else -> return null
            }
            return SheetSpec(id, questions, hasComment)
        }

        // Excludes look-alikes (0/O, 1/I) so an id can also be read aloud or typed safely.
        private const val ID_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

        fun newSurveyId(random: Random = Random.Default): String =
            String(CharArray(6) { ID_ALPHABET[random.nextInt(ID_ALPHABET.length)] })
    }
}
