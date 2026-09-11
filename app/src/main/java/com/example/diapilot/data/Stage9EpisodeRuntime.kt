package com.example.diapilot.data

import com.diapilot.core.analysis.IsfEpisode
import com.diapilot.core.analysis.isFoodNote
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.CarbEvidenceSourceV1
import com.diapilot.core.collector.CarbEvidenceV1
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.physio.*
import java.util.Locale
import kotlin.math.abs

/** Bounded diagnostic sidecar. It is never a dependency of forecast publication. */
object Stage9EpisodeRuntime {
    /**
     * v12, DERIVED rather than written down.
     *
     * The appearance policy is appended, so retuning the sieving or the
     * emptying rate changes this string on its own and every decomposition
     * stored under the old physiology is rejected on restore. Writing the
     * version by hand is what let a v6 receipt built on the flat 30 g/h come
     * back as «current» after the queue shipped.
     */
    val CACHE_VERSION="time-resolved-attribution-v13-physio-only+" +
        com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED.signature()
    private const val DAY=86_400_000L

    data class BuildResult(
        val receipts:Map<Long,EpisodeAttributionExplanationV1>,val complete:Boolean,
        val processedClusters:Int,val totalClusters:Int,val budgetLimited:Boolean,val nextOffset:Int=0,
    )

    /** Sorted once, then every cluster window is O(log n + window) instead of
     * filtering the complete month of CGM/boluses again. */
    internal class TimeRangeIndex<T>(rows:List<T>,private val timestamp:(T)->Long) {
        private val sorted=rows.sortedBy(timestamp)
        fun between(fromInclusive:Long,toInclusive:Long):List<T> {
            fun lower(value:Long,strict:Boolean):Int {
                var lo=0;var hi=sorted.size
                while(lo<hi){val mid=(lo+hi) ushr 1;val ts=timestamp(sorted[mid])
                    if(ts<value||strict&&ts==value)lo=mid+1 else hi=mid}
                return lo
            }
            return sorted.subList(lower(fromInclusive,false),lower(toInclusive,true))
        }
    }

    internal fun annotationAsOf(n:Annotation,asOf:Long,evidence:CarbEvidenceV1?):Annotation {
        if(evidence==null)return n
        require(evidence.annotationId==n.id&&evidence.knownAtMs<=asOf)
        if(evidence.deleted)return n.copy(estCarbs=null,carbsKnownAtMs=evidence.knownAtMs,analysisKnownAtMs=null)
        val source=when(evidence.input.source){
            CarbEvidenceSourceV1.LABEL_WEIGHT->"label"
            CarbEvidenceSourceV1.STANDARD_RECIPE_WEIGHT->"anchor"
            CarbEvidenceSourceV1.PRESET_TABLE->"preset"
            CarbEvidenceSourceV1.PHOTO_LLM->"photo"
            CarbEvidenceSourceV1.USER_ESTIMATE,CarbEvidenceSourceV1.LABEL_PORTION_ESTIMATED->"manual"
            CarbEvidenceSourceV1.LEGACY_UNKNOWN->"unknown"
        }
        // Title/analysis have no append-only revision journal. They remain display
        // facts only; the causal core deliberately ignores unversioned title shape.
        return n.copy(tsMs=evidence.intakeStartMs,estCarbs=evidence.input.totalCarbsG,
            carbsSource=source,carbsKnownAtMs=evidence.knownAtMs,
            analysis=evidence.input.kineticsV2,
            analysisKnownAtMs=evidence.input.kineticsV2?.let{evidence.knownAtMs})
    }

    fun build(
        notes:List<Annotation>, readings:List<GlucosePoint>, boluses:List<BolusPoint>,
        episodes:List<IsfEpisode>, foodEraStartMs:Long?, nowMs:Long, budgetMs:Long=2_000L,
        carbEvidenceAsOf:(Long,Long)->CarbEvidenceV1?={_,_->null},
    ):Map<Long,EpisodeAttributionExplanationV1> = buildDetailed(notes,readings,boluses,episodes,foodEraStartMs,nowMs,budgetMs,carbEvidenceAsOf).receipts

    fun buildDetailed(
        notes:List<Annotation>, readings:List<GlucosePoint>, boluses:List<BolusPoint>,
        episodes:List<IsfEpisode>, foodEraStartMs:Long?, nowMs:Long, budgetMs:Long=2_000L,
        carbEvidenceAsOf:(Long,Long)->CarbEvidenceV1?={_,_->null},
        lookbackMs:Long=30*DAY,
        personModelForBolus:((BolusPoint)->com.diapilot.core.hybrid.HybridPersonModel?)?=null,
        clusterOffset:Int=0,
    ):BuildResult {
        val started=android.os.SystemClock.elapsedRealtime()
        val causal=episodes.filter{foodEraStartMs!=null&&it.t0Ms>=foodEraStartMs&&!it.activityContaminated}
            .mapIndexed{i,e->IdentifiedIsfEvidenceV1(e.t0Ms,e.t0Ms+4*3_600_000L,e.isf.coerceAtLeast(.1),if(e.postActivityTail).25 else 1.0,i+1,"food-era correction candidate")}
        // Current Twin kernel is deliberately absent. Every historical bolus
        // selects a prior revision which was already known at that bolus.
        val model=VersionedEpisodeKernelModelV1(listOf(EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1),causal)
        val readingIndex=TimeRangeIndex(readings){it.tsMs}
        val bolusIndex=TimeRangeIndex(boluses){it.tsMs}
        val personModelCache=mutableMapOf<Pair<Long,Long>,com.diapilot.core.hybrid.HybridPersonModel?>()
        val recent=notes.filter{it.tsMs>=nowMs-lookbackMs&&it.tsMs<=nowMs&&isFoodNote(it)}.sortedBy{it.tsMs}
        val clusters=mutableListOf<MutableList<Annotation>>()
        recent.forEach{n->val c=clusters.lastOrNull();if(c!=null&&n.tsMs-c.last().tsMs<=6*3_600_000L&&n.tsMs-c.first().tsMs<=6*3_600_000L)c.add(n)else clusters.add(mutableListOf(n))}
        val out=mutableMapOf<Long,EpisodeAttributionExplanationV1>()
        val selected=clusters.asReversed().drop(clusterOffset.coerceIn(0,clusters.size));var processed=0;var budgetLimited=false
        for(cluster in selected) {
            if(android.os.SystemClock.elapsedRealtime()-started>budgetMs){budgetLimited=true;break}
            // The continuation cursor counts attempted clusters, not only
            // clusters which produced a receipt. A terminal cluster with no
            // causal grams/CGM previously hit `continue` below without moving
            // the cursor, so the final few in history were retried forever.
            processed++
            val from=cluster.first().tsMs-8*3_600_000L;val to=minOf(nowMs,cluster.first().tsMs+6*3_600_000L)
            val rr=readingIndex.between(from,to);val bb=bolusIndex.between(from,to)
            val causalCluster=cluster.mapNotNull{n->
                val e=carbEvidenceAsOf(n.id,to)
                val c=if(e!=null)annotationAsOf(n,to,e) else n
                c.takeIf{it.estCarbs!=null&&it.carbsKnownAtMs?.let{known->known<=to}==true}
            }
            val receipt=Stage9SharedCoreV1.jointReceipt(causalCluster,rr,bb,PosteriorV1(.165,.133,.20,0,0,0),to,{b->
                val facts=buildList{
                    val hour=java.util.Calendar.getInstance().apply{timeInMillis=b.tsMs}.get(java.util.Calendar.HOUR_OF_DAY)
                    add(EpisodeContextFactV1(KernelFactorKindV1.TOD,hour.toDouble(),"hour",b.tsMs,b.tsMs,1,"event clock"))
                    if(rr.any{it.tsMs in (b.tsMs-12*3_600_000L)..b.tsMs&&it.mmol<3.9})
                        add(EpisodeContextFactV1(KernelFactorKindV1.PRIOR_HYPO,1.0,"present",b.tsMs,b.tsMs,1,"CGM known before bolus"))
                }
                personModelForBolus?.let{provider->
                    val key=b.tsMs to java.lang.Double.doubleToLongBits(b.units)
                    if(personModelCache.containsKey(key))personModelCache[key]
                    else provider(b).also{personModelCache[key]=it}
                }?.let{hybridEpisodeKernelV1(it,b,to)}
                    ?:Stage9SharedCoreV1.episodeKernel(model,b.tsMs,facts,b.tsMs,b.units)
            },
                // THE SAME PHYSIOLOGY THE FORECAST DRAWS. Stage9 is what the
                // model learns from; decomposing a meal under the flat 30 g/h
                // while the forecast draws it under the caloric queue means the
                // corpus and the forecast disagree about the same food.
                appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED,
            )?:continue
            receipt.allocations.forEachIndexed{i,a->
                val neighbours=receipt.allocations.filterIndexed{j,_->j!=i}.joinToString{n->"${(n.startMs-a.startMs)/60_000} мин"}.ifBlank{"нет"}
                val kernelHash=receipt.insulinKernelHashes.values.firstOrNull()?.take(10)?:"без bolus"
                // TRIMMED TO COVERAGE. The solver zeroes a meal's basis beyond
                // its continuous CGM coverage, so the raw curve ended in a run
                // of zeros and "0.0 mmol/L by the end of the window" — the censoring
                // artifact printed as if the contribution had vanished. A
                // cumulative contribution cannot fall to zero; the meal does
                // not come back out. Fourth instance in two days of a bound
                // shown as a measurement (120-min insulin end, 360/360 series
                // timing, the 20-mismatch truncation, now this).
                val covered=a.contributionCurve.filter{(it.tsMs-a.startMs)/60_000.0<=a.coverageMin+1.0}
                val peak=covered.maxByOrNull{it.median}
                val modelPeakMin=peak?.let{(it.tsMs-a.startMs)/60_000.0}
                val censoredTail=a.contributionCurve.size>covered.size
                val curveText="Кривая вклада ${spark(covered.map{it.median})}"+
                    (if(censoredTail)" (покрытие до ~${fmt(a.coverageMin)} мин, дальше окно обрезано)" else "")+
                    ": самый ранний вклад по модельной кривой с учётом lag после ${fmt((a.causalOnsetMs-a.startMs)/60_000.0)} мин; " +
                    "модельный пик ${modelPeakMin?.let{fmt(it)+" мин"}?:"—"}, ${peak?.let{fmt(it.median)}?:"—"} ммоль/л; " +
                    "на последней покрытой точке ${covered.lastOrNull()?.let{fmt(it.median)}?:"—"}. " +
                    "До следующего приёма ${pct(a.fractionBeforeNext.median)}, после него ${pct(a.fractionAfterNext.median)}."
                val transferText=receipt.tailTransferAudit?.let{audit->
                    when {
                        audit.detected&&audit.resolution==AttributionResolutionV1.RESOLVED -> "У ранних приёмов дефицит ${fmt(audit.earlyDeficitMmol)}, у последнего избыток ${fmt(audit.lastSurplusMmol)} ммоль/л; вероятен перенос хвоста. После совместного перерасчёта ${pct(audit.previousMealsShareOfRecognizedFoodLate.p10)}–${pct(audit.previousMealsShareOfRecognizedFoodLate.p90)} распознанного пищевого вклада относится к предыдущим блюдам; нераспределённый остаток ${pct(audit.unassignedLateShare.median)}."
                        audit.detected -> "У ранних приёмов дефицит ${fmt(audit.earlyDeficitMmol)}, у последнего избыток ${fmt(audit.lastSurplusMmol)} ммоль/л; возможен перенос хвоста, но надёжно разделить нельзя. Допустимая доля предыдущих блюд в распознанном пищевом вкладе ${pct(audit.previousMealsShareOfRecognizedFoodLate.p10)}–${pct(audit.previousMealsShareOfRecognizedFoodLate.p90)}; нераспределённый остаток ${pct(audit.unassignedLateShare.median)}."
                        audit.resolution!=AttributionResolutionV1.RESOLVED -> "Перекрытие есть; надёжно разделить вклад блюд нельзя. Общий остаток оставлен нераспределённым."
                        else -> "Согласованный перенос хвоста не установлен."
                    }
                }.orEmpty()
                val portions=a.perNoteOnsetsMs.mapNotNull{ts->causalCluster.firstOrNull{abs(it.tsMs-ts)<1}}
                    .joinToString("; "){p->"${fmt(p.estCarbs?:0.0)} г в ${java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(java.util.Date(p.tsMs))}"}
                val explanation=EpisodeAttributionExplanationV1(
                    receipt.version,
                    // The CS is printed from the receipt that USED it, never as a literal:
                    // a hardcoded «0,248» would keep printing after the learner moves.
                    "${fmt(a.modelForecastMmol)} ммоль/л: ${fmt(a.recordedCarbsG)} г × общий CS ${"%.3f".format(receipt.globalCs.median).replace('.',',')}",
                    // The printed "allowed L-H" range reads as a free measurement; it is not — the
                    // per-meal amplitude is clamped to the prior (x1.35, or x1.60
                    // at weak provenance) and the group mass is bounded too. The
                    // clamp is now stated where the number is shown.
                    if(a.status==AttributionResolutionV1.RESOLVED)
                        "Этому приёму совместная модель отнесла ${fmt(a.bestAllocationMmol)} ммоль/л; допустимо ${fmt(a.allocationLowMmol)}–${fmt(a.allocationHighMmol)} (оценка удержана в границах приора)"
                    else "Приёмы перекрываются: отдельный подъём этого блюда не установлен. Диапазон ${fmt(a.allocationLowMmol)}–${fmt(a.allocationHighMmol)} ммоль/л (в границах приора); точка ${fmt(a.bestAllocationMmol)} — техническая медиана, не факт.",
                    phaseSummary(a,receipt.clusterTiming),
                    "Соседние приёмы: $neighbours. Неучтённая еда/остаток кластера: ${fmt(receipt.unloggedFoodResidualMmol.median)} ммоль/л",
                    "Лучшая доля ${pct(a.shareMedian)}, возможный диапазон ${pct(a.shareLow)}–${pct(a.shareHigh)}",
                    when(a.status){AttributionResolutionV1.RESOLVED->"разделение устойчиво";AttributionResolutionV1.UNRESOLVED->"неразрешимо: надёжно разделить соседние приёмы нельзя";AttributionResolutionV1.CENSORED->"окно этого приёма обрезано (${fmt(a.coverageMin)} мин); показан диапазон"},
                    if(personModelForBolus!=null) "$EPISODE_KERNEL_VERSION_V1; единая CDF выбранного PHYSIO-профиля; hash $kernelHash"
                    else "$EPISODE_KERNEL_VERSION_V1; causal prior ${EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1.identity}; hash $kernelHash",
                    if(personModelForBolus!=null) "Условное удаление укола на графике и эта деконволюция используют один ретроспективный CGM-профиль. ISF остаётся персональным для часа болюса; live-прогноз и гипо-алерты этим профилем не изменяются."
                    else "Медиану меняет только инерционное дневное ISF по прошлым эпизодам. TOD/гипо и другие контексты пока не promoted и лишь расширяют интервал.",
                    confidence(a.confidence),
                    (a.caveats.map(::caveatRu)+listOfNotNull("углеводы ${fmt(a.gramsLow)}–${fmt(a.gramsHigh)} г (${a.gramsProvenance})",if(portions.isNotBlank())"порции: $portions" else null,"датчик и фон остаются неопределёнными",if(receipt.inputTruncated)"кластер ограничен вычислительным лимитом" else null)).joinToString("; "),
                    causalProvenance="Causal: frozen prior + CarbEvidence/ISF с knownAt ≤ cutoff; текущий Twin kernel и неревизионируемый текст блюда не используются.",
                    diagnosticProvenance="Retrospective post-hoc: CGM после еды, fitted background, residual и allocation; в live forecast/alerts не входят.",
                    timeResolvedCurve=curveText,
                    tailTransfer=transferText,
                    compactSummary="Ожидалось +${fmt(a.modelForecastMmol)}; по эпизоду +${fmt(a.bestAllocationMmol)} (${fmt(a.allocationLowMmol)}–${fmt(a.allocationHighMmol)}) ммоль/л",
                    unloggedResidualMmol=receipt.unloggedFoodResidualMmol.median,
                    compactFinding=when(a.timingScope){
                        com.diapilot.core.hybrid.MealTimingScopeV1.COMPLETED_BEFORE_CLUSTER -> "Ранний вклад завершился до следующей еды и зафиксирован отдельно; в общую сумму углеводов он включён."
                        // A window-bound triplet on the user's card was the
                        // WINDOW BOUND printed as a measurement: mainEndMin is
                        // the last supported grid bin, and when the series is
                        // still active at the 6-hour horizon that bin IS the
                        // horizon (levelPeak lands there too). The same trap as
                        // an "end at 120 min" figure in the insulin doc — a bound must say
                        // it is a bound.
                        com.diapilot.core.hybrid.MealTimingScopeV1.CLUSTER_ONLY -> receipt.clusterTiming?.let{ct->
                            val bounded=ct.mainEndMin>=355.0
                            "Общий тайминг серии: старт ~${fmt(ct.onsetMin)} мин"+
                                (if(bounded)"; серия ещё активна на границе окна (6 ч), пик и конец не установлены."
                                else "; пик ~${fmt(ct.levelPeakMin)}, основная фаза до ~${fmt(ct.mainEndMin)} мин.")
                        } ?:"Блюда серии не разделились; амплитуда считается по сумме углеводов."
                        else->when(a.status){
                        AttributionResolutionV1.RESOLVED -> if(receipt.tailTransferAudit?.detected==true) "Обнаружен вероятный перенос хвоста между соседними блюдами." else "Вклад блюда удалось отделить от соседних событий."
                        AttributionResolutionV1.UNRESOLVED -> "Приёмы перекрываются: отдельно оценить это блюдо пока нельзя."
                        AttributionResolutionV1.CENSORED -> "Не хватает последующей истории CGM для полной оценки."
                    }},
                )
                // One session receipt belongs to its first portion. Per-portion
                // onsets/grams are listed inside it; copying the whole allocation
                // onto every bottle would falsely present session amplitude twice.
                a.perNoteOnsetsMs.minOrNull()?.let{ts->causalCluster.firstOrNull{abs(it.tsMs-ts)<1}?.let{out[it.id]=explanation}}
            }
        }
        val next=(clusterOffset+processed).coerceAtMost(clusters.size)
        val complete=!budgetLimited&&next>=clusters.size
        return BuildResult(out,complete,processed,clusters.size,!complete,next)
    }
    private fun fmt(v:Double)=String.format(Locale.US,"%.1f",v)
    private fun pct(v:Double)=String.format(Locale.US,"%.0f%%",100*v.coerceIn(0.0,1.0))
    private fun confidence(v:Double)=when{v>=.7->"высокая";v>=.4->"средняя";else->"низкая"}
    private fun spark(values:List<Double>):String {
        if(values.isEmpty())return "—"
        val blocks="▁▂▃▄▅▆▇█";val max=values.maxOrNull()?.coerceAtLeast(1e-9)?:return "—"
        val step=(values.size/8.0).coerceAtLeast(1.0)
        return (0 until minOf(8,values.size)).map{i->values[(i*step).toInt().coerceAtMost(values.lastIndex)]}
            .joinToString(""){blocks[((it/max)*(blocks.lastIndex)).toInt().coerceIn(0,blocks.lastIndex)].toString()}
    }
    private fun phaseName(p:ObservedPhaseV1)=when(p){ObservedPhaseV1.START->"старт";ObservedPhaseV1.LEVEL_MAXIMUM->"максимум уровня";ObservedPhaseV1.LATE_PHASE->"поздняя фаза";ObservedPhaseV1.PLATEAU->"плато"}
    private fun phaseSummary(a:MealAllocationV1,cluster:ClusterTimingV1?):String {
        if(a.timingScope==com.diapilot.core.hybrid.MealTimingScopeV1.CLUSTER_ONLY)
            return "Индивидуальные фазы не устанавливаются. "+(cluster?.let{
                // The same window-bound trap as compactFinding: when the series
                // is still active at the 6-hour horizon, mainEndMin (and often
                // the fitted peak) IS the horizon, not a measurement.
                if(it.mainEndMin>=355.0)
                    "Общий тайминг серии (~${fmt(a.clusterCarbsG)} г): старт ~${fmt(it.onsetMin)} мин; серия ещё активна на границе окна (6 ч), максимум и конец не установлены."
                else "Общий тайминг серии (~${fmt(a.clusterCarbsG)} г): старт ~${fmt(it.onsetMin)}, максимум уровня ~${fmt(it.levelPeakMin)}, основная фаза до ~${fmt(it.mainEndMin)} мин."
            }?:"Общий тайминг кластера пока тоже не выделен.")
        if(a.timingScope==com.diapilot.core.hybrid.MealTimingScopeV1.COMPLETED_BEFORE_CLUSTER)
            return "Не менее 90% раннего вклада завершилось до следующего приёма; его фаза зафиксирована отдельно и не растянута БЖУ последующей еды."
        val seen=a.observedPhases.joinToString(transform=::phaseName)
        val missing=(a.phaseWindowsAvailable-a.observedPhases).joinToString(transform=::phaseName)
        return buildString{if(seen.isNotBlank())append("Установлено: $seen. ");if(missing.isNotBlank())append("Окно доступно, но не установлено: $missing.");if(seen.isBlank()&&missing.isBlank())append("Данных для установления фазы пока недостаточно.")}
    }
    private fun caveatRu(s:String)=when(s){"overlap not identifiable"->"перекрытие неидентифицируемо";"individual timing replaced by aggregate meal-cluster timing"->"индивидуальный тайминг заменён общим таймингом пищевого кластера";"at least 90% completed before next intake; early contribution locked"->"не менее 90% раннего вклада завершилось до следующей еды и зафиксировано";"joint model mismatch; episode excluded from confident food learning"->"совместная модель не объяснила эпизод: он исключён из уверенного обучения еды";"meal-specific late window censored"->"позднее окно этого приёма обрезано";"meal-specific CGM window censored at gap or low density"->"окно CGM этого приёма обрезано разрывом или редкими точками";"input truncated; allocation unresolved"->"вход обрезан; разделение не установлено";"insulin uncertainty propagated"->"неопределённость инсулина учтена в интервале";"shared process residual retained"->"общий необъяснённый остаток сохранён";"phase window available but phase not established"->"окно фазы доступно, но фаза не установлена";"grams provenance is weak"->"слабое происхождение оценки углеводов";"unknown context widens insulin interval"->"неизвестный контекст расширяет интервал insulin";else->s}
}
