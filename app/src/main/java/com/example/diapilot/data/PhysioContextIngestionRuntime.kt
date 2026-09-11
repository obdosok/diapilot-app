package com.example.diapilot.data

import android.content.Context
import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.ContextExposureV1
import org.json.JSONObject
import java.security.MessageDigest

/** Exact structured ingestion only; arbitrary prose is never classified. */
object PhysioContextIngestionRuntime {
    private const val DAY=86_400_000L
    private fun id(type:String,knownAt:Long,value:String)=MessageDigest.getInstance("SHA-256")
        .digest("$type|$knownAt|$value".toByteArray()).take(10).joinToString(""){"%02x".format(it)}.let{"ctx-$it"}

    fun fromCanonicalAnnotation(annotation:Annotation,knownAt:Long,settingsContext:Context?=null):ContextExposureV1? {
        val text=annotation.content.trim().lowercase()
        val route=when(text){
            "новая ампула"->Triple("cartridge","changed",Long.MAX_VALUE)
            "новый сенсор"->Triple("sensor_epoch",settingsContext?.let(Settings::libreSensorSerial)?:"changed",Long.MAX_VALUE)
            "укол в живот","укол в бедро","укол в руку"->Triple("injection_site",text.removePrefix("укол в "),Long.MAX_VALUE)
            "болею","стресс"->Triple("stress_illness",text,annotation.tsMs+24*3_600_000L)
            "нагрев ампулы","жара / инсулин"->Triple("weather_heat","manual_heat",annotation.tsMs+2*3_600_000L)
            else->return null
        }
        return ContextExposureV1(id(route.first,knownAt,route.second),route.first,annotation.tsMs,route.third,knownAt,knownAt,
            contextJson=JSONObject().put("value",route.second).put("canonical_tag",text).put("annotation_id",annotation.id).toString())
    }

    /** Materialize current Settings values as causal epochs at first observation/change. */
    fun syncSettings(context:Context,store:SqliteCollectorStore,asOfMs:Long):Int {
        var inserted=0
        fun sync(type:String,value:String?,eventStart:Long=asOfMs){
            if(value.isNullOrBlank())return
            val latest=store.contextExposures(type,FoodEraSettings.current().startMs,asOfMs,asOfMs).maxByOrNull{it.knownAtMs}
            val old=latest?.contextJson?.let{runCatching{JSONObject(it).optString("value")}.getOrNull()}
            if(old==value)return
            val row=ContextExposureV1(id(type,asOfMs,value),type,eventStart.coerceAtMost(asOfMs),Long.MAX_VALUE,asOfMs,asOfMs,
                contextJson=JSONObject().put("value",value).put("source","settings_epoch").put("supersedes",latest?.exposureId).toString())
            if(store.appendContextExposure(row))inserted++
        }
        sync("insulin_product",Settings.bolusProduct(context))
        sync("basal_product",Settings.basalProduct(context))
        Settings.libreSensorSerial(context)?.let{sync("sensor_epoch",it,Settings.libreSensorStartMs(context).takeIf{t->t>0}?:asOfMs)}
        return inserted
    }

}
