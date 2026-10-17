package com.framenest.feature.listen_translate.experimental
fun main() {
 val m=PairMorphology();val p=BaselinePairPlanner(m::analyze)
 fun cues(vararg texts:String)=texts.mapIndexed {i,t->BaselineCue(i,i*3000L,(i+1)*3000L,i*3000L+10,(i+1)*3000L-10,t)}
 fun checkCase(cs:List<BaselineCue>):List<PairedSourceUnit> {val out=p.plan(cs);check(out.flatMap{it.cues}==cs);check(out.joinToString(""){it.textSrc}.toByteArray().contentEquals(cs.joinToString(""){it.textSrc}.toByteArray()));check(out.all{it.cues.size<=2});return out}
 check(checkCase(cues("こ","の")).single().textSrc=="この")
 check(checkCase(cues("中","国語")).single().crossingMorpheme?.surface=="中国")
 check(checkCase(cues("こ")).single().reason=="eof_single")
 check(checkCase(emptyList()).isEmpty())
 check(checkCase(cues("こ","","の")).size==3)
 checkCase(cues(" こ","の\t！","\n"));checkCase(cues("😀こ","の！"))
 val repeated=checkCase(cues("ス","マ","イ","ル","コ","マ","リ"));check(repeated.all{it.cues.size<=2});check(repeated.last().cues.size==1)
 checkCase(cues("は","は","は"));checkCase(cues("とい","い う"));checkCase(cues("。","！"))
 val nonadj=cues("こ","の").mapIndexed{i,c->if(i==1)c.copy(windowStartMs=4000,windowEndMs=7000)else c};check(checkCase(nonadj).size==2)
 val long=cues("こ","の").mapIndexed{i,c->if(i==1)c.copy(windowEndMs=6001)else c};check(checkCase(long).size==2)
 check(p.plan(cues("こ")).single().textSrc=="こ");check(p.plan(cues("の")).single().textSrc=="の") // stateless reset/generation isolation
 val looked=checkCase(cues("。","！"));check(looked[0].inspectedCueIndices==listOf(0,1));check(looked[0].requiredAudioHorizonMs==6000L);check(looked[1].inspectedCueIndices==listOf(1));check(looked[1].requiredAudioHorizonMs==6000L)
 println("PairPlannerTests: all 14 scenario groups passed")
}
