package com.framenest.feature.listen_translate.experimental

data class BaselineCue(val index:Int, val windowStartMs:Long, val windowEndMs:Long,
    val speechStartMs:Long?, val speechEndMs:Long?, val textSrc:String)
data class PairMorpheme(val rawStart:Int,val rawEnd:Int,val surface:String,val known:Boolean)
data class PairedSourceUnit(val cues:List<BaselineCue>,val textSrc:String,val reason:String,
    val crossingMorpheme:PairMorpheme?=null,
    val inspectedCueIndices:List<Int> = cues.map { it.index },
    val requiredAudioHorizonMs:Long = cues.maxOf { it.windowEndMs }) {
    val windowStartMs get()=cues.first().windowStartMs
    val windowEndMs get()=cues.last().windowEndMs
    val speechStartMs get()=cues.mapNotNull { it.speechStartMs }.minOrNull()
    val speechEndMs get()=cues.mapNotNull { it.speechEndMs }.maxOrNull()
}
/** Pure batch planner. Source is exact baseline text, never a recognition hypothesis.
 * NORMAL morphology only decides whether a seam crosses a morpheme. No normalization of output.
 * Greedy non-overlapping pairs. A second member cannot merge with a third cue.
 */
class BaselinePairPlanner(private val analyze:(String)->List<PairMorpheme>) {
    fun crossing(left:String,right:String):PairMorpheme? {
        if(left.isEmpty() || right.isEmpty())return null
        val text=left+right
        val words=analyze(text)
        require(words.all { it.rawStart>=0 && it.rawStart<it.rawEnd && it.rawEnd<=text.length })
        require(words.zipWithNext().all { (a,b)->a.rawEnd<=b.rawStart })
        return words.firstOrNull { it.rawStart<left.length && left.length<it.rawEnd }
    }
    fun plan(cues:List<BaselineCue>):List<PairedSourceUnit> {
        require(cues.all { it.windowStartMs<=it.windowEndMs })
        val result=mutableListOf<PairedSourceUnit>();var i=0
        while(i<cues.size) {
            val a=cues[i];val b=cues.getOrNull(i+1)
            val reason=when {
                b==null->"eof_single"
                a.textSrc.isEmpty() || b.textSrc.isEmpty()->"empty_cue_unchanged"
                a.windowEndMs!=b.windowStartMs->"nonadjacent_windows"
                b.windowEndMs-a.windowStartMs>6000->"exceeds_6000ms"
                else->null
            }
            val word=if(reason==null)crossing(a.textSrc,b!!.textSrc)else null
            if(word!=null) {
                result+=PairedSourceUnit(listOf(a,b!!),a.textSrc+b.textSrc,"crossing_morpheme_pair",word);i+=2
            } else {
                result+=PairedSourceUnit(listOf(a),a.textSrc,reason?:"no_crossing_morpheme",
                    inspectedCueIndices=if(b==null)listOf(a.index)else listOf(a.index,b.index),
                    requiredAudioHorizonMs=b?.windowEndMs?:a.windowEndMs);i++
            }
        }
        check(result.joinToString(""){it.textSrc}==cues.joinToString(""){it.textSrc})
        check(result.flatMap{it.cues}==cues)
        check(result.all{it.cues.size<=2})
        return result
    }
}
