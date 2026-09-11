package com.example.diapilot.data

import com.diapilot.core.collector.InsulinEvent
import com.diapilot.core.collector.Reading
import java.security.MessageDigest

/** Revision identity for arrival evidence. Timestamp alone is not a fact:
 * corrected CGM/dose values must not inherit the first revision's live flag. */
object PhysioArrivalIdentity {
    fun glucose(r:Reading)=sha("g|${r.tsMs}|${r.mgdl}|${r.mmol}|${r.trend}|${r.source}")
    fun glucose(ts:Long,mgdl:Double,mmol:Double,trend:String?,source:String)=sha("g|$ts|$mgdl|$mmol|$trend|$source")
    fun bolus(e:InsulinEvent)=sha("b|${e.tsMs}|${e.units}|${e.insulinType}|${e.source}")
    fun bolus(ts:Long,units:Double,insulinType:String?,source:String)=sha("b|$ts|$units|$insulinType|$source")
    private fun sha(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}
}
