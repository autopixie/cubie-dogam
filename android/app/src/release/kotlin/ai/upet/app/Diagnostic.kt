package ai.upet.app

internal inline fun diagnostic(block: () -> Unit) {
    // Intentionally do not evaluate operational diagnostics in release.

}
