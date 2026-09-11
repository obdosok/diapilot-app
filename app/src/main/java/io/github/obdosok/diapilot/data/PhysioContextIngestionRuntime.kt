package io.github.obdosok.diapilot.data

import android.content.Context
import com.diapilot.core.analysis.InsulinProductDefault
import com.diapilot.core.analysis.NoteTag
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.ContextExposureV1
import org.json.JSONObject
import java.security.MessageDigest

/** Exact structured ingestion only; arbitrary prose is never classified. */
object PhysioContextIngestionRuntime {
    private const val DAY=86_400_000L
    private fun id(type:String,knownAt:Long,value:String)=MessageDigest.getInstance("SHA-256")
        .digest("$type|$knownAt|$value".toByteArray()).take(10).joinToString(""){"%02x".format(it)}.let{"ctx-$it"}

    /** A heat note older builds accepted besides the cartridge-warming tag (data, not UI text). */
    private const val LEGACY_HEAT_NOTE = "жара / инсулин"

    fun fromCanonicalAnnotation(annotation:Annotation,knownAt:Long,settingsContext:Context?=null):ContextExposureV1? {
        val text=annotation.content.trim().lowercase()
        // The tag in any stored form (the key, or a word in any supported language).
        val tag=NoteTag.of(text)
        val route=when{
            tag==NoteTag.NEW_CARTRIDGE->Triple("cartridge","changed",Long.MAX_VALUE)
            tag==NoteTag.NEW_SENSOR->Triple("sensor_epoch",settingsContext?.let(Settings::libreSensorSerial)?:"changed",Long.MAX_VALUE)
            tag==NoteTag.INJECTION_BELLY->Triple("injection_site","belly",Long.MAX_VALUE)
            tag==NoteTag.INJECTION_THIGH->Triple("injection_site","thigh",Long.MAX_VALUE)
            tag==NoteTag.INJECTION_ARM->Triple("injection_site","arm",Long.MAX_VALUE)
            tag==NoteTag.ILL||tag==NoteTag.STRESS->Triple("stress_illness",tag.key,annotation.tsMs+24*3_600_000L)
            tag==NoteTag.CARTRIDGE_WARMING||text==LEGACY_HEAT_NOTE->Triple("weather_heat","manual_heat",annotation.tsMs+2*3_600_000L)
            else->return null
        }
        return ContextExposureV1(id(route.first,knownAt,route.second),route.first,annotation.tsMs,route.third,knownAt,knownAt,
            contextJson=JSONObject().put("value",route.second).put("canonical_tag",tag?.key?:text).put("annotation_id",annotation.id).toString())
    }

    /** Materialize current Settings values as causal epochs at first observation/change. */
    fun syncSettings(context:Context,store:SqliteCollectorStore,asOfMs:Long):Int {
        var inserted=0
        fun sync(type:String,value:String?,eventStart:Long=asOfMs,same:(String?,String)->Boolean={a,b->a==b}){
            if(value.isNullOrBlank())return
            val latest=store.contextExposures(type,FoodEraSettings.current().startMs,asOfMs,asOfMs).maxByOrNull{it.knownAtMs}
            val old=latest?.contextJson?.let{runCatching{JSONObject(it).optString("value")}.getOrNull()}
            if(same(old,value))return
            val row=ContextExposureV1(id(type,asOfMs,value),type,eventStart.coerceAtMost(asOfMs),Long.MAX_VALUE,asOfMs,asOfMs,
                contextJson=JSONObject().put("value",value).put("source","settings_epoch").put("supersedes",latest?.exposureId).toString())
            if(store.appendContextExposure(row))inserted++
        }
        // The ledger keeps what it recorded; an older Russian placeholder
        // and its key ("rapid") are the same product, not a change.
        val sameProduct={a:String?,b:String->InsulinProductDefault.canonical(a)==InsulinProductDefault.canonical(b)}
        sync("insulin_product",Settings.bolusProduct(context),same=sameProduct)
        sync("basal_product",Settings.basalProduct(context),same=sameProduct)
        Settings.libreSensorSerial(context)?.let{sync("sensor_epoch",it,Settings.libreSensorStartMs(context).takeIf{t->t>0}?:asOfMs)}
        return inserted
    }

}
