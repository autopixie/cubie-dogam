package ai.upet.app

import android.content.Context
import android.widget.ImageView

/** One explicit stage/state map; approved SVG/XML sources remain available for rollback. */
internal object PetArtwork {
    fun resource(stage: Int, face: PetFace): Int = when (stage.coerceIn(1, 3)) {
        1 -> when (face) {
            PetFace.SMILE -> R.drawable.s6_cubie_stage1_smile
            PetFace.JOY -> R.drawable.s6_cubie_stage1_joy
            PetFace.WORRIED -> R.drawable.s6_cubie_stage1_worried
            PetFace.REST -> R.drawable.s6_cubie_stage1_rest
            PetFace.WAITING -> R.drawable.s6_cubie_stage1_waiting
            PetFace.TALKING -> R.drawable.s6_cubie_stage1_talking
        }
        2 -> when (face) {
            PetFace.SMILE -> R.drawable.s6_cubie_stage2_smile
            PetFace.JOY -> R.drawable.s6_cubie_stage2_joy
            PetFace.WORRIED -> R.drawable.s6_cubie_stage2_worried
            PetFace.REST -> R.drawable.s6_cubie_stage2_rest
            PetFace.WAITING -> R.drawable.s6_cubie_stage2_waiting
            PetFace.TALKING -> R.drawable.s6_cubie_stage2_talking
        }
        3 -> when (face) {
            PetFace.SMILE -> R.drawable.s6_cubie_stage3_smile
            PetFace.JOY -> R.drawable.s6_cubie_stage3_joy
            PetFace.WORRIED -> R.drawable.s6_cubie_stage3_worried
            PetFace.REST -> R.drawable.s6_cubie_stage3_rest
            PetFace.WAITING -> R.drawable.s6_cubie_stage3_waiting
            PetFace.TALKING -> R.drawable.s6_cubie_stage3_talking
        }
        else -> error("Invalid stage")
    }
}
internal class PetView(context: Context, face: PetFace, private val stage: Int = 1) : ImageView(context) {
    init { scaleType = ScaleType.FIT_CENTER; showFace(face); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    fun showFace(face: PetFace) {
        setImageResource(PetArtwork.resource(stage, face))
        contentDescription = "${stage.coerceIn(1, 3)}단계 · " + when (face) {
            PetFace.SMILE -> "웃는 큐비"
            PetFace.JOY -> "기쁜 큐비"
            PetFace.WORRIED -> "걱정하는 큐비"
            PetFace.REST -> "쉬는 큐비"
            PetFace.WAITING -> "기다리는 큐비"
            PetFace.TALKING -> "말하는 큐비"
        }
    }
}
