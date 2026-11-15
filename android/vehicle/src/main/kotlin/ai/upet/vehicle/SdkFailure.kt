package ai.upet.vehicle

/**
 * SDK 2.3.2 exposes Exception but obfuscates its concrete exception/accessor name.
 * Read a zero-argument enum accessor on that exception only. No vehicle property,
 * hidden Car API, arbitrary method name, message text or setter is invoked.
 */
internal object SdkFailure {
    private const val ENUM_TYPE = "ai.pleos.playground.vehicle.constant.ErrorCode"
    private val codes = setOf(
        "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA", "ERROR_NOT_SUPPORTED_VALUE_TYPE",
        "ERROR_SECURITY_PERMISSION", "ERROR_SERVICE_CONNECTION_FAILURE", "ERROR_INTERNAL_SERVICE",
        "ERROR_DRIVING_RESTRICTED", "ERROR_EMPTY_RESULT", "ERROR_UNDEFINED",
    )
    fun code(error: Exception): String? = runCatching {
        error.javaClass.methods.firstOrNull {
            it.parameterCount == 0 && it.returnType.name == ENUM_TYPE && it.returnType.isEnum
        }?.invoke(error)?.let { (it as? Enum<*>)?.name }?.takeIf { it in codes }
    }.getOrNull()
    fun health(code: String?): ReadHealth = when (code) {
        "ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA" -> ReadHealth.UNSUPPORTED
        "ERROR_EMPTY_RESULT" -> ReadHealth.UNKNOWN
        "ERROR_DRIVING_RESTRICTED" -> ReadHealth.RESTRICTED
        else -> ReadHealth.FAILED
    }
}
