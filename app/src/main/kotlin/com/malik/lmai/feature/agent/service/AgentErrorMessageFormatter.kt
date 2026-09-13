package com.malik.lmai.feature.agent.service

import androidx.appcompat.app.AppCompatDelegate
import com.malik.lmai.R
import com.malik.lmai.data.preferences.AppText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Converts provider/network errors into concise messages in the selected app language. */
object AgentErrorMessageFormatter {

    fun format(message: String): String {
        val raw = message.trim()
        if (raw.isBlank()) return AppText.get(R.string.agent_error_generic)
        val n = raw.lowercase(Locale.US)

        if (n.contains("free-models-per-day") || n.contains("openrouter_free_tier_daily")) {
            val resetAt = extractRateLimitReset(raw)
            return if (resetAt != null) {
                AppText.get(R.string.agent_free_quota_reset_at, formatResetDate(resetAt))
            } else {
                AppText.get(R.string.agent_free_quota_ended)
            }
        }

        return when {
            hasAny(
                n,
                "h_cloud_ai_offline",
                "cloud_ai_offline",
                "free ai uses lightweight cloud inference",
            ) -> AppText.get(R.string.agent_cloud_ai_offline)

            hasAny(
                n,
                "h_no_route",
                "cloud_ai_not_connected",
                "connect openrouter free in settings",
            ) -> AppText.get(R.string.agent_cloud_ai_not_connected)

            hasAny(
                n,
                "h_openrouter_credential_missing",
                "openrouter_oauth_credential_missing",
                "openrouter free is configured but its oauth credential is unavailable",
            ) -> AppText.get(R.string.agent_openrouter_credential_missing)

            hasAny(n, "insufficient credits", "insufficient balance", "credit balance", "credits exhausted", "balance exhausted", "not enough credits", "payment required", "billing") ->
                AppText.get(R.string.agent_insufficient_balance)

            hasAny(n, "resource_exhausted", "resource exhausted", "quota exceeded") ||
                (n.contains("generativelanguage.googleapis.com") && n.contains("quota")) ->
                AppText.get(R.string.agent_google_quota_exceeded)

            containsHttpCode(n, 401) || hasAny(n, "unauthorized", "invalid api key", "invalid_api_key", "api key not valid", "api_key_invalid", "authentication failed") ->
                AppText.get(R.string.agent_invalid_api_key)

            containsHttpCode(n, 403) || hasAny(n, "forbidden", "permission denied", "permission_denied", "access denied") ->
                AppText.get(R.string.agent_permission_denied)

            containsHttpCode(n, 404) || hasAny(n, "model not found", "model_not_found", "endpoint not found", "not found for api version", "is not found") ->
                AppText.get(R.string.agent_model_not_found)

            hasAny(n, "no endpoints found that support tool use", "no endpoint found that supports tool use", "does not support tool use", "doesn't support tool use", "tool use is not supported", "tool calling is not supported", "tools are not supported", "function calling is not supported") ->
                AppText.get(R.string.agent_tools_not_supported)

            hasAny(n, "tool schema", "function schema", "invalid schema", "invalid_function_parameters", "invalid function parameters", "parameters schema") ||
                (n.contains("additionalproperties") && n.contains("schema")) ->
                AppText.get(R.string.agent_tool_schema_incompatible)

            n.contains("tool_call_id") || n.contains("tool call id") ||
                (n.contains("tool_calls") && hasAny(n, "must", "missing", "required")) ->
                AppText.get(R.string.agent_tool_call_sequence_invalid)

            containsHttpCode(n, 400) || hasAny(n, "bad request", "invalid_request_error", "invalid argument", "invalid_argument") ->
                AppText.get(R.string.agent_provider_rejected_request)

            containsHttpCode(n, 429) || hasAny(n, "rate limit", "rate_limit", "too many requests") ->
                AppText.get(R.string.agent_rate_limit_reached)

            hasAny(n, "context length", "context_length_exceeded", "maximum context", "max context", "too many tokens", "token limit") ->
                AppText.get(R.string.agent_context_limit_reached)

            hasAny(n, "only available on agentic harnesses", "only available on agentic") ->
                AppText.get(R.string.agent_chat_completions_unavailable)

            listOf(500, 502, 503, 504).any { containsHttpCode(n, it) } ||
                hasAny(n, "temporarily overloaded", "temporarily unavailable", "upstream error", "service unavailable", "gateway timeout", "bad gateway", "server error") ->
                AppText.get(R.string.agent_provider_temporarily_unavailable)

            hasAny(n, "unable to resolve host", "unknownhostexception", "connectexception", "connection refused", "failed to connect", "network is unreachable") ->
                AppText.get(R.string.agent_server_connection_failed)

            hasAny(n, "timeout", "timed out", "sockettimeoutexception") ->
                AppText.get(R.string.agent_provider_timeout)

            hasAny(n, "invalid_stream_chunk", "failed parsing sse") || (n.contains("sse") && n.contains("parse")) ->
                AppText.get(R.string.agent_incompatible_stream)

            else -> AppText.get(R.string.agent_connection_error)
        }
    }

    private fun hasAny(value: String, vararg needles: String): Boolean = needles.any(value::contains)

    private fun containsHttpCode(normalized: String, code: Int): Boolean =
        normalized.contains("\"code\":$code") ||
            normalized.contains("\"code\": $code") ||
            normalized.contains("http $code") ||
            normalized.contains("http_$code") ||
            normalized.contains("status $code") ||
            normalized.contains("status=$code") ||
            normalized.contains("status: $code")

    private fun currentAppLocale(): Locale {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        return if (!appLocales.isEmpty) appLocales[0] ?: Locale.getDefault() else Locale.getDefault()
    }

    private fun extractRateLimitReset(message: String): Long? {
        val match = Regex(
            pattern = """X-RateLimit-Reset["']?\s*[:=]\s*["']?(\d{10,13})""",
            option = RegexOption.IGNORE_CASE,
        ).find(message) ?: return null
        val raw = match.groupValues.getOrNull(1)?.toLongOrNull() ?: return null
        return if (raw < 10_000_000_000L) raw * 1000L else raw
    }

    private fun formatResetDate(timestampMillis: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", currentAppLocale()).format(Date(timestampMillis))
}
