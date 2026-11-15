package ai.upet.core

import kotlin.math.min
import kotlin.math.roundToInt

/** Shared measured geometry: no child is scaled to manufacture movement room. */
data class PetGroupBounds(val travelX: Int, val travelY: Int, val baseX: Float, val baseY: Float, val fits: Boolean) {
    companion object {
        fun measure(innerWidth: Int, innerHeight: Int, groupWidth: Int, groupHeight: Int,
            horizontal: Boolean, verticalLimit: Int, minimumTravel: Int): PetGroupBounds {
            val iw=innerWidth.coerceAtLeast(0);val ih=innerHeight.coerceAtLeast(0)
            val gw=groupWidth.coerceAtLeast(0);val gh=groupHeight.coerceAtLeast(0)
            val fits=gw<=iw && gh<=ih
            val roomX=(iw-gw).coerceAtLeast(0);val roomY=(ih-gh).coerceAtLeast(0)
            val proposedX=if(horizontal)roomX else min(roomX,(gw*.16f).roundToInt())
            val proposedY=min(roomY,verticalLimit.coerceAtLeast(0))
            val x=if(fits && proposedX>=minimumTravel.coerceAtLeast(1))proposedX else 0
            val y=if(fits && proposedY>=minimumTravel.coerceAtLeast(1))proposedY else 0
            return PetGroupBounds(x,y,(roomX-x)/2f,(roomY-y)/2f,fits)
        }
    }
}
