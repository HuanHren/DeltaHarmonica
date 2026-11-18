package com.huanhren.deltaharmonica.input

private fun canonicalComponent(component: String): String {
    val parts = component.split('/', limit = 2)
    if (parts.size != 2) return component
    val className = if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
    return "${parts[0]}/$className"
}

internal fun serviceComponents(value: String?): List<String> =
    if (value.isNullOrBlank() || value == "null") emptyList() else value.split(':').filter { it.isNotEmpty() }

internal fun containsAccessibilityService(value: String?, component: String): Boolean =
    serviceComponents(value).any { canonicalComponent(it) == canonicalComponent(component) }

internal fun mergeAccessibilityServices(value: String?, component: String): String {
    val services = serviceComponents(value)
    return (if (containsAccessibilityService(value, component)) services else services + component).joinToString(":")
}

internal fun withoutAccessibilityService(value: String?, component: String): String =
    serviceComponents(value).filterNot { canonicalComponent(it) == canonicalComponent(component) }.joinToString(":")

/** Quote a single argument for the POSIX shell; values read from system settings are not trusted. */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
