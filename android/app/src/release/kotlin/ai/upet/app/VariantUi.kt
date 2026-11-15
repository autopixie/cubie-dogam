package ai.upet.app

import android.view.View

/** Release has no gesture registration or debug diagnostic class references. */
internal object VariantUi {
    @Suppress("UNUSED_PARAMETER")
    fun bindTitle(view: View, open: () -> Unit) = Unit
    @Suppress("UNUSED_PARAMETER")
    fun diagnose(context: android.content.Context) = Unit
    @Suppress("UNUSED_PARAMETER")
    fun panel(ui: DesignUi, host: PreviewHost): View? = null
}
