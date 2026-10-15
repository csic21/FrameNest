package com.framenest.feature.listen_translate.recorded

import com.framenest.feature.listen_translate.RecordedHypothesis
import com.framenest.feature.listen_translate.RecordedToken
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

/** Replay captured real ASR/PCM evidence. No model is run and no MT accuracy is asserted. */
class RecordedCapturedFixtureTest {
    private val morphology = KuromojiJapaneseMorphology()
    private fun fixture(name: String): Map<Pair<String, Long>, RecordedWindowInput> {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("recorded/$name-captured-asr.tsv"))
        val results = linkedMapOf<Pair<String, Long>, RecordedWindowInput>()
        var header = emptyList<String>()
        val tokens = mutableListOf<RecordedToken>()
        val quiet = mutableListOf<RecordedQuietSpan>()
        stream.bufferedReader().useLines { lines -> lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val p = line.split('\t')
            when(p[0]) {
                "H", "X" -> { header=p; tokens.clear(); quiet.clear() }
                "T" -> tokens += RecordedToken(String(Base64.getDecoder().decode(p[2]),Charsets.UTF_8),p[1].toLong())
                "Q" -> quiet += RecordedQuietSpan(p[1].toLong(),p[2].toLong())
                "END" -> {
                    val h=RecordedHypothesis(header[1].toLong(),header[2].toLong(),header[3].toLong(),tokens.toList())
                    results[header[0] to h.startMs]=RecordedWindowInput(h,quiet.toList())
                }
            }
        } }
        return results
    }
    private fun anchors(h: RecordedHypothesis): List<RecordedCharacterAnchor> {
        var raw=0
        return h.tokens.map { t ->
            RecordedCharacterAnchor(raw,raw+t.text.length,h.startMs,h.endMs,raw,raw+t.text.length,t.startMs).also { raw+=t.text.length }
        }
    }
    private fun boundaries(input: RecordedWindowInput, decodedThroughMs: Long = input.hypothesis.endMs): List<RecordedBoundary> {
        val h=input.hypothesis
        val all=anchors(h)
        val cuts=mutableListOf<RecordedBoundary>()
        var raw=0
        while(raw<h.text.length) {
            val mapped=all.filter { it.unitRawEnd>raw }.map { it.copy(unitRawStart=maxOf(it.unitRawStart,raw)-raw,unitRawEnd=it.unitRawEnd-raw) }
            val cut=JapaneseSourceBoundary(morphology).first(h.text.substring(raw),mapped,input.quietSpans,decodedThroughMs) ?: break
            raw+=cut.rawEnd
            cuts+=cut.copy(rawEnd=raw)
        }
        return cuts
    }

    @Test fun actualTokenOnsetsSlightlyInsideMeasuredQuietDoNotEraseCompletePredicates() {
        val windows=fixture("kishida")
        val input36=windows.getValue("H" to 36000L)
        // The next captured window supplies the required >=750 ms PCM right context;
        // the 36–51 s window alone correctly withholds this near-edge candidate.
        assertTrue(boundaries(input36).isEmpty())
        val at36=boundaries(input36,windows.getValue("H" to 45000L).hypothesis.endMs)
        val at90=boundaries(windows.getValue("H" to 90000L))
        assertTrue("49.859 s onset is inside 49.800–50.420 s quiet: $at36",at36.any { it.mediaEndMs==50110L })
        assertTrue("97.919 s onset is inside 97.820–98.620 s quiet: $at90",at90.any { it.mediaEndMs==98220L })
    }

    @Test fun actualCommaThenIndependentMorphemeAllowsMeasuredSentenceBoundary() {
        val windows=fixture("kishida")
        val h=windows.getValue("H" to 54000L)
        val cuts=boundaries(h)
        val sentence=cuts.firstOrNull { it.mediaEndMs==64130L }
        assertNotNull("Complete predicate before comma/filler was discarded: $cuts",sentence)
        assertTrue(h.hypothesis.text.substring(0,sentence!!.rawEnd).endsWith("考えて、います、"))
    }

    @Test fun actualEmojiPlainVerbStaysWithItsAttributiveNounPhrase() {
        val h=fixture("emoji").getValue("H" to 36000L)
        val first=boundaries(h).first()
        val source=h.hypothesis.text.substring(0,first.rawEnd)
        assertTrue("must retain the modified noun phrase: $source",source.contains("新しい表現スタイルです"))
        assertEquals(42220L,first.mediaEndMs)
        assertFalse(source.trimEnd().endsWith("使う"))
    }

    @Test fun fullCapturedKishidaDoesNotCascadeIntoFalseLeftContextAfterTimeCap() {
        val windows=fixture("kishida")
        val c=RecordedSourceUnitCoordinator(morphology)
        val active=linkedMapOf<RecordedUnitId,RecordedSourceUnit>()
        val byRead=mutableMapOf<Long,MutableList<RecordedSourceUnit>>()
        fun record(r: RecordedCoordinatorResult) {
            assertTrue(r.error ?: "unexpected rejection",r.accepted)
            assertTrue(r.pendingText.length<=1024)
            r.records.forEach { e ->
                if(e.state==RecordedResultState.SUPERSEDED) active.remove(e.unit.id)
                else active[e.unit.id]=e.unit
            }
            r.readyUnits.forEach { byRead.getOrPut(r.readThroughMs) { mutableListOf() }+=it.unit }
        }
        var ended=false
        var steps=0
        while(!ended) {
            check(++steps<150) { "bounded replay stalled" }
            val action=c.snapshot().nextAction
            val r=when(action) {
                is RecordedNextAction.DecodeWindow -> {
                    val h=windows["H" to action.startMs]
                    if(h==null) c.finish(0,175787) else c.push(h)
                }
                is RecordedNextAction.DecodeUnion -> {
                    val union=windows["X" to action.request.startMs]
                    if(union?.hypothesis?.endMs==action.request.endMs) c.recoverUnion(union)
                    else c.failUnion(0,"requested_union_not_in_captured_fixture")
                }
                is RecordedNextAction.Translate -> {
                    if(action.ticket.unit.status==RecordedSourceStatus.FINAL)
                        c.translationFailed(action.ticket,"source_boundary_test_does_not_run_MT")
                    else c.deferUnconfirmed(action.ticket)
                }
                RecordedNextAction.Finished -> { ended=true; continue }
            }
            record(r)
        }
        val all=active.values.toList()
        println("Captured Kishida source statuses: ${all.groupingBy { it.status }.eachCount()}")
        println("Captured Kishida source intervals: ${all.map { Triple(it.status,it.coverageStartMs,it.coverageEndMs) }}")
        assertEquals(175787L,c.snapshot().readThroughMs)
        val around115=byRead[123000].orEmpty()
        assertTrue("credible 115.3 s boundary must survive cap inspection: $around115",
            around115.any { it.status==RecordedSourceStatus.FINAL && it.coverageEndMs==115300L })
        assertTrue("normal aligned context before 115.3 s must not be falsely reseeded",
            all.filter { it.coverageEndMs<=115300 }.none { it.reason==RecordedSourceReason.LEFT_CONTEXT_UNCONFIRMED })
        assertTrue("captured complete sentences should not cascade into cap snapshots",all.count { it.reason==RecordedSourceReason.NO_BOUNDARY_CONTEXT_LIMIT }<3)
    }
}
