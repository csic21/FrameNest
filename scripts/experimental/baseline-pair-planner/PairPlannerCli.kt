package com.framenest.feature.listen_translate.experimental
import com.atilika.kuromoji.ipadic.Tokenizer
import java.util.Base64
class PairMorphology {
 private val tokenizer=Tokenizer()
 fun analyze(raw:String):List<PairMorpheme> {
  val offsets=ArrayList<Int>();val text=buildString {raw.forEachIndexed {i,c->if(!c.isWhitespace()){append(c);offsets+=i}}}
  if(text.isEmpty())return emptyList()
  return tokenizer.tokenize(text).map {t->PairMorpheme(offsets[t.position],offsets[t.position+t.surface.length-1]+1,t.surface,t.isKnown)}
 }
}
fun enc(s:String)=Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
fun main() {
 val morph=PairMorphology();val planner=BaselinePairPlanner(morph::analyze)
 val groups=linkedMapOf<String,MutableList<BaselineCue>>()
 generateSequence(::readLine).forEach {line->val p=line.split('\t');groups.getOrPut(p[0]){mutableListOf()}+=BaselineCue(p[1].toInt(),p[2].toLong(),p[3].toLong(),p[4].toLongOrNull(),p[5].toLongOrNull(),String(Base64.getDecoder().decode(p[6]),Charsets.UTF_8))}
 groups.forEach {(fixture,cues)->
  val units=planner.plan(cues)
  units.forEachIndexed {i,u->println(listOf("UNIT",fixture,i,u.cues.joinToString(","){it.index.toString()},u.windowStartMs,u.windowEndMs,u.speechStartMs?:"",u.speechEndMs?:"",u.reason,enc(u.crossingMorpheme?.surface?:""),enc(u.textSrc),u.crossingMorpheme?.known?:"",u.inspectedCueIndices.joinToString(","),u.requiredAudioHorizonMs).joinToString("\t"))}
  units.zipWithNext().forEachIndexed {i,(a,b)->val w=planner.crossing(a.textSrc,b.textSrc);if(w!=null)println(listOf("OUTER",fixture,i,i+1,a.windowEndMs,enc(w.surface),w.known).joinToString("\t"))}
 }
}
