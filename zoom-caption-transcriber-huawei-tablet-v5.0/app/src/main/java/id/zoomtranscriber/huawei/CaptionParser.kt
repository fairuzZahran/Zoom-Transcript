package id.zoomtranscriber.huawei

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class OcrLine(
    val y: Int,
    val h: Int,
    val text: String
)

data class ParsedCaption(
    val speaker: String,
    val initials: String,
    val text: String
)

/**
 * Port 1:1 dari fungsi-fungsi parser & deduplikasi cerdas di `app_desktop.py`:
 * - `get_initials`
 * - `clean_ocr_line_text`
 * - `normalize_for_match`
 * - `is_same_evolving_utterance`
 * - `parse_ocr_lines_to_items`
 */
object CaptionParser {

    private val EXCLUDED_INITIAL_WORDS = setOf(
        "DI", "KE", "YA", "IA", "NI", "TU", "SI", "OH", "IN", "OK"
    )

    fun getInitials(name: String?): String {
        val clean = (name ?: "").trim()
        if (clean.isEmpty()) return "P"
        if (clean.length <= 3 && clean.all { it.isLetterOrDigit() }) {
            return clean.uppercase(Locale.getDefault())
        }
        val parts = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            return "${parts[0].first()}${parts[1].first()}".uppercase(Locale.getDefault())
        }
        return clean.take(2).uppercase(Locale.getDefault())
    }

    fun cleanOcrLineText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var cleaned = text.replace(Regex("^[^a-zA-Z0-9\"]+"), "").trim()
        cleaned = cleaned.replace(Regex("[^a-zA-Z0-9.?!,\"'\\)]+$"), "").trim()
        return cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun normalizeForMatch(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val cleaned = text.lowercase(Locale.getDefault()).replace(Regex("[^\\w\\s]"), " ")
        return cleaned.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Cek apakah `newText` adalah kelanjutan/perubahan real-time dari kalimat `oldText` yang sedang diucapkan.
     */
    fun isSameEvolvingUtterance(oldText: String, newText: String): Boolean {
        val nOld = normalizeForMatch(oldText)
        val nNew = normalizeForMatch(newText)
        if (nOld.isEmpty() || nNew.isEmpty()) return false
        if (nOld == nNew) return true
        if (nNew.startsWith(nOld) || nOld.startsWith(nNew)) return true

        val oldWords = nOld.split(" ")
        val newWords = nNew.split(" ")

        if (oldWords.size >= 2 && newWords.size >= 2 && oldWords.take(2) == newWords.take(2)) {
            return true
        }
        if (oldWords.size <= 2 && oldWords.first() == newWords.first()) {
            return true
        }

        if (similarityRatio(nOld, nNew) >= 0.55) {
            return true
        }

        // Cek sliding window overlap (kalimat bergeser di kotak caption Zoom)
        val maxK = min(min(oldWords.size, newWords.size), 7)
        for (k in maxK downTo 2) {
            if (oldWords.takeLast(k) == newWords.take(k)) {
                return true
            }
        }
        return false
    }

    private fun similarityRatio(s1: String, s2: String): Double {
        val maxLen = max(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return (maxLen - dist).toDouble() / maxLen.toDouble()
    }

    private fun levenshteinDistance(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLen = lhs.length
        val rhsLen = rhs.length
        var cost = IntArray(lhsLen + 1) { it }
        var newCost = IntArray(lhsLen + 1)
        for (i in 1..rhsLen) {
            newCost[0] = i
            for (j in 1..lhsLen) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLen]
    }

    /**
     * Mengubah daftar baris OCR menjadi daftar Item Caption lengkap dengan pemisahan Nama Pembicara.
     */
    fun parseOcrLinesToItems(ocrLines: List<OcrLine>): List<ParsedCaption> {
        if (ocrLines.isEmpty()) return emptyList()

        val validLines = ocrLines.mapNotNull { l ->
            val txt = cleanOcrLineText(l.text)
            if (txt.length >= 2 && txt.any { it.isLetter() }) {
                OcrLine(y = l.y, h = max(10, l.h), text = txt)
            } else null
        }.sortedBy { it.y }

        if (validLines.isEmpty()) return emptyList()

        val blocks = mutableListOf<MutableList<OcrLine>>()
        var curBlock = mutableListOf(validLines.first())

        for (line in validLines.drop(1)) {
            val prev = curBlock.last()
            val gap = line.y - (prev.y + prev.h)
            val avgH = max(14.0, (prev.h + line.h) / 2.0)

            val words = line.text.split(" ")
            val firstWord = words.firstOrNull() ?: ""
            val startsWithInitials = (
                words.size >= 2 &&
                firstWord.length == 2 &&
                firstWord.all { it.isUpperCase() && it.isLetter() } &&
                firstWord !in EXCLUDED_INITIAL_WORDS
            )

            if (gap > avgH * 0.85 || startsWithInitials) {
                blocks.add(curBlock)
                curBlock = mutableListOf(line)
            } else {
                curBlock.add(line)
            }
        }
        if (curBlock.isNotEmpty()) {
            blocks.add(curBlock)
        }

        val items = mutableListOf<ParsedCaption>()
        for (block in blocks) {
            var speaker = "Pembicara"
            var textLines: List<OcrLine> = block

            // Cek apakah baris pertama adalah Nama Pembicara (misal: "Fairuz Zahran")
            if (block.size >= 2) {
                val firstTxt = block[0].text
                val firstWords = firstTxt.split(" ")
                val hasPunct = firstTxt.any { it in charArrayOf('.', '?', '!', ',') }
                if (
                    firstWords.size in 1..4 &&
                    firstTxt.length <= 32 &&
                    !hasPunct &&
                    block[0].h <= block[1].h * 1.15
                ) {
                    speaker = firstTxt
                    textLines = block.drop(1)
                }
            }

            var fullText = cleanOcrLineText(textLines.joinToString(" ") { it.text })
            if (fullText.length < 2) continue
            if ("Zoom Caption Transcriber" in fullText || "Bidik Langsung" in fullText) continue

            // Cek pola "Nama: Kalimat"
            val colonIdx = fullText.take(30).indexOf(':')
            if (colonIdx > 1) {
                val spkCand = fullText.substring(0, colonIdx).trim()
                val rem = fullText.substring(colonIdx + 1).trim()
                if (spkCand.length in 2..28 && rem.isNotEmpty()) {
                    speaker = spkCand
                    fullText = rem
                }
            } else if (speaker == "Pembicara") {
                val tokens = fullText.split(" ", limit = 2)
                if (
                    tokens.size == 2 &&
                    tokens[0].length == 2 &&
                    tokens[0].all { it.isUpperCase() && it.isLetter() } &&
                    tokens[0] !in EXCLUDED_INITIAL_WORDS
                ) {
                    speaker = tokens[0]
                    fullText = tokens[1].trim()
                }
            }

            if (fullText.length >= 2) {
                items.add(
                    ParsedCaption(
                        speaker = speaker,
                        initials = getInitials(speaker),
                        text = fullText
                    )
                )
            }
        }
        return items
    }
}
