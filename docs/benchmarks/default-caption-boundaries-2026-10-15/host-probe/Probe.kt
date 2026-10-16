package com.framenest.feature.listen_translate
import com.framenest.feature.listen_translate.asr.sherpaTokensToWords
import java.util.Base64
fun main() {
  fun dec(t: String) = String(Base64.getDecoder().decode(t), Charsets.UTF_8)
  fun enc(t: String) = Base64.getEncoder().encodeToString(t.toByteArray(Charsets.UTF_8))
  generateSequence(::readLine).forEach { line ->
    val p = line.split('\t')
    val start = p[1].toLong(); val end = p[2].toLong(); val decodeStart = p[3].toLong()
    val tokens = p.drop(4).chunked(2)
    val words = sherpaTokensToWords(tokens.map { dec(it[0]) }.toTypedArray(),tokens.map { it[1].toFloat() }.toFloatArray())
    val selected = selectWordsForWindow(words,decodeStart,start,end)
    val cueStart = selected.firstOrNull()?.let { decodeStart+it.startMs }?.coerceIn(start,end)?:start
    val tokenEnd = selected.lastOrNull()?.let { decodeStart+it.endMs }?.coerceIn(cueStart,end)?:end
    val cueEnd = maxOf(tokenEnd,cueStart+600L).coerceAtMost(end)
    println(listOf(p[0],start,end,cueStart,cueEnd,enc(selected.joinToString("") { it.text }.trim()),
      enc(selected.joinToString(";") { "${it.text}=${decodeStart+it.startMs}:${decodeStart+it.endMs}" })).joinToString("\t"))
  }
}
