package org.imunav.app.diagnostics

/** Remove credential-shaped values before diagnostic text leaves the app. */
internal object DiagnosticRedaction {
    private val assignments = Regex("(?i)(token|password|authorization|secret|api_key)=\\S+")
    private val bearer = Regex("(?i)Bearer\\s+\\S+")

    fun redact(line: String): String = line
        .replace(assignments, "$1=[redacted]")
        .replace(bearer, "Bearer [redacted]")
}
