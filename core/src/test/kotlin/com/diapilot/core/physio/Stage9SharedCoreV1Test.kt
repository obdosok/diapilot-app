package com.diapilot.core.physio

import com.diapilot.core.collector.Annotation
import com.diapilot.core.collector.BolusPoint
import com.diapilot.core.collector.GlucosePoint
import com.diapilot.core.twin.HypoAction
import org.junit.Assert.*
import org.junit.Test

class Stage9SharedCoreV1Test {
    private val minute=60_000L

    @Test fun `evaluator wrapper has byte-for-byte episode and joint parity`() {
        val model=VersionedEpisodeKernelModelV1(listOf(EpisodeKernelPriorsV1.PHYSIOLOGICAL_V1))
        val direct=model.kernelForEpisode(0,emptyList(),0,2.0)
        val shared=Stage9SharedCoreV1.episodeKernel(model,0,emptyList(),0,2.0)
        assertEquals(direct.provenanceHash,shared.provenanceHash)

        val notes=listOf(
            Annotation(0,"food","coldnik with potatoes",id=1,estCarbs=35.0,carbsSource="manual",carbsKnownAtMs=0),
            Annotation(60*minute,"food","ice cream",id=2,estCarbs=20.0,carbsSource="manual",carbsKnownAtMs=60*minute),
        )
        val readings=(0..300 step 5).map { t -> GlucosePoint(t*minute,5.5+when { t<45->t/45.0; t<100->1+(t-45)/35.0; else->2.6 }) }
        val cs=PosteriorV1(.165,.117,.217,0,0,0)
        val kernel:(BolusPoint)->EpisodeKernelResultV1={b->model.kernelForEpisode(b.tsMs,emptyList(),b.tsMs,b.units)}
        val a=jointMealAttributionV1(notes,readings,emptyList(),cs,300*minute,kernel,appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        val b=Stage9SharedCoreV1.jointReceipt(notes,readings,emptyList(),cs,300*minute,kernel,appearance=com.diapilot.core.hybrid.CarbAppearancePolicyV1.PHYSIO_SHIPPED)!!
        assertEquals(a.cacheIdentity,b.cacheIdentity)
        assertEquals(2,b.allocations.size)
        assertTrue(b.allocations.any{it.status==AttributionResolutionV1.UNRESOLVED})
    }

    @Test fun `current low invariant is computed by production state machine`() {
        val realisticEpoch=1_700_000_000_000L
        assertNotEquals(HypoAction.NONE,Stage9SharedCoreV1.currentLowAction(realisticEpoch,3.5))
        assertEquals(HypoAction.NONE,Stage9SharedCoreV1.currentLowAction(realisticEpoch,4.5))
    }
}
