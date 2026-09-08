package com.malik.lmai.feature.agent.tool

import android.content.Context
import android.location.Geocoder
import com.malik.lmai.feature.agent.AgentTool
import com.malik.lmai.feature.agent.AgentToolCall
import com.malik.lmai.feature.agent.AgentToolContext
import com.malik.lmai.feature.agent.AgentToolDefinition
import com.malik.lmai.feature.agent.AgentToolResult
import com.malik.lmai.feature.reminder.HDeviceLocation
import com.malik.lmai.feature.reminder.HDeviceLocationProvider
import com.malik.lmai.feature.reminder.HLocationScope
import com.malik.lmai.feature.reminder.HLocationScopeException
import com.malik.lmai.feature.reminder.HLocationScopePolicy
import com.malik.lmai.feature.reminder.HLocationScopeSource
import com.malik.lmai.feature.reminder.HLocationScopeStore
import com.malik.lmai.feature.reminder.HLocationTriggerMode
import com.malik.lmai.feature.reminder.HReminder
import com.malik.lmai.feature.reminder.HReminderDomain
import com.malik.lmai.feature.reminder.HReminderLocation
import com.malik.lmai.feature.reminder.HReminderRepository
import com.malik.lmai.feature.reminder.HReminderSource
import com.malik.lmai.feature.reminder.HReminderStatus
import com.malik.lmai.feature.reminder.HReminderType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Singleton
class HReminderTool @Inject constructor(
    private val repository: HReminderRepository,
    private val locationScopeStore: HLocationScopeStore,
    private val deviceLocationProvider: HDeviceLocationProvider,
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "h_reminders",
        description = "Create, list, inspect, edit, complete, defer, disable, or delete H reminders. " +
            "Use this whenever the user asks H to remember something at a time, place, person, or recurring context. " +
            "Use domain PERSONAL for normal-life reminders. Use PROGRAMMING only for coding/development reminders. " +
            "For a place request like 'إذا رحت حلي', prefer triggerMode DWELL with dwellMinutes=1. " +
            "Generic place names must obey the geographic search scope pinned in the H app. If travel mode is active, use its temporary anchor instead of moving the base anchor. " +
            "Set location.explicitAreaOverride=true ONLY when the user explicitly names an external city/region/area (for example: 'إذا رحت الرياض'). Never set it merely because a shop/place name was mentioned. " +
            "The large search radius is only for place discovery/disambiguation; the actual reminder geofence stays small around the selected place. " +
            "For location reminders, placeNameAr is required but latitude/longitude are optional. Preserve originalText.",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("action", stringProp("CREATE, LIST, GET, UPDATE, COMPLETE, DEFER, DISABLE, or DELETE"))
                put("id", stringProp("Reminder id for GET/UPDATE/status/delete actions"))
                put("title", stringProp("Short human-friendly Arabic reminder title"))
                put("originalText", stringProp("Exact original user request"))
                put("interpretedText", stringProp("What H understood and will execute"))
                put("type", stringProp("TIME, LOCATION, PERSON, RECURRING, or CONTEXTUAL"))
                put("domain", stringProp("PERSONAL or PROGRAMMING"))
                put("scheduledAtIso", stringProp("Absolute ISO-8601 datetime, preferably with UTC offset"))
                put("scheduledAtMs", intProp("Epoch milliseconds; use only when already known exactly"))
                put("recurrenceRule", stringProp("DAILY, WEEKLY, or a human-readable future recurrence rule"))
                put("personName", stringProp("Related person's name if any"))
                put("location", buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put("properties", buildJsonObject {
                        put("placeNameAr", stringProp("Arabic display/search name for the place. Required for a location reminder."))
                        put("addressAr", stringProp("Arabic address or extra disambiguation when known"))
                        put("placeId", stringProp("Google Maps/Places place id when known"))
                        put("latitude", buildJsonObject { put("type", JsonPrimitive("number")) })
                        put("longitude", buildJsonObject { put("type", JsonPrimitive("number")) })
                        put("radiusMeters", buildJsonObject { put("type", JsonPrimitive("number")) })
                        put("dwellMinutes", intProp("Minutes inside the area before a DWELL reminder fires; default 1"))
                        put("triggerMode", stringProp("ARRIVE, DWELL, DEPART, or NEARBY"))
                        put("explicitAreaOverride", buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("True only when the user explicitly names an external city, region, or area; false for generic store/place names."))
                        })
                    })
                })
            })
            put("required", requiredFields("action"))
        },
    )

    override suspend fun execute(call: AgentToolCall, context: AgentToolContext): AgentToolResult {
        val args = call.arguments.jsonObject
        return runCatching {
            when (args["action"]?.jsonPrimitive?.content?.uppercase()) {
                "CREATE" -> create(call, args)
                "LIST" -> list(call, args)
                "GET" -> get(call, args)
                "UPDATE" -> update(call, args)
                "COMPLETE" -> status(call, args, HReminderStatus.COMPLETED)
                "DEFER" -> status(call, args, HReminderStatus.DEFERRED)
                "DISABLE" -> status(call, args, HReminderStatus.DISABLED)
                "DELETE" -> delete(call, args)
                else -> call.errorResult("Unsupported reminder action")
            }
        }.getOrElse { error -> call.errorResult(error.message ?: "Reminder operation failed") }
    }

    private suspend fun create(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val original = args.string("originalText") ?: args.string("title") ?: ""
        val interpreted = args.string("interpretedText") ?: original
        val type = enumOrDefault(args.string("type"), HReminderType.CONTEXTUAL)
        val location = parseLocation(args["location"] as? JsonObject)
        if (type == HReminderType.LOCATION && location == null) {
            return call.errorResult("تعذر تحديد المكان داخل نطاق البحث. افتح إعدادات التذكيرات في H وثبّت نطاقك أو وضّح المكان.")
        }
        val reminder = repository.create(
            title = args.string("title") ?: interpreted.take(80),
            originalText = original,
            interpretedText = interpreted,
            type = type,
            source = HReminderSource.APP_CHAT,
            domain = enumOrDefault(args.string("domain"), HReminderDomain.PERSONAL),
            scheduledAtMs = parseScheduledAt(args),
            recurrenceRule = args.string("recurrenceRule"),
            personName = args.string("personName"),
            location = location,
        )
        return call.result(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("reminder", reminderJson(reminder))
            if (location != null) put("locationScope", scopeJson(locationScopeStore.current()))
        })
    }

    private suspend fun list(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val domain = args.string("domain")?.let { enumOrDefault(it, HReminderDomain.PERSONAL) }
        val reminders = repository.list(domain).take(100)
        return call.result(buildJsonObject {
            put("ok", JsonPrimitive(true))
            put("count", JsonPrimitive(reminders.size))
            put("reminders", buildJsonArray { reminders.forEach { add(reminderJson(it)) } })
        })
    }

    private suspend fun get(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val reminder = repository.get(args.requiredId()) ?: return call.errorResult("Reminder not found")
        return call.result(buildJsonObject { put("reminder", reminderJson(reminder)) })
    }

    private suspend fun update(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val old = repository.get(args.requiredId()) ?: return call.errorResult("Reminder not found")
        val hasLocation = args.containsKey("location")
        val updated = old.copy(
            title = args.string("title") ?: old.title,
            originalText = args.string("originalText") ?: old.originalText,
            interpretedText = args.string("interpretedText") ?: old.interpretedText,
            type = args.string("type")?.let { enumOrDefault(it, old.type) } ?: old.type,
            domain = args.string("domain")?.let { enumOrDefault(it, old.domain) } ?: old.domain,
            scheduledAtMs = if (args.containsKey("scheduledAtIso") || args.containsKey("scheduledAtMs")) parseScheduledAt(args) else old.scheduledAtMs,
            recurrenceRule = args.string("recurrenceRule") ?: old.recurrenceRule,
            personName = args.string("personName") ?: old.personName,
            location = if (hasLocation) parseLocation(args["location"] as? JsonObject) else old.location,
        )
        if (hasLocation && updated.type == HReminderType.LOCATION && updated.location == null) {
            return call.errorResult("تعذر تحديد مكان التذكير المحدّث داخل نطاق البحث")
        }
        if (!repository.update(updated)) return call.errorResult("Reminder update rejected")
        return call.result(buildJsonObject { put("ok", JsonPrimitive(true)); put("reminder", reminderJson(updated)) })
    }

    private suspend fun status(call: AgentToolCall, args: JsonObject, status: HReminderStatus): AgentToolResult {
        val ok = repository.setStatus(args.requiredId(), status)
        return if (ok) call.result(buildJsonObject { put("ok", JsonPrimitive(true)); put("status", JsonPrimitive(status.name)) })
        else call.errorResult("Reminder not found")
    }

    private suspend fun delete(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val ok = repository.delete(args.requiredId())
        return if (ok) call.okResult() else call.errorResult("Reminder not found")
    }

    private fun parseScheduledAt(args: JsonObject): Long? {
        args["scheduledAtMs"]?.jsonPrimitive?.content?.toLongOrNull()?.let { return it }
        val iso = args.string("scheduledAtIso") ?: return null
        return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private suspend fun parseLocation(json: JsonObject?): HReminderLocation? {
        if (json == null) return null
        val name = json.string("placeNameAr") ?: return null
        val address = json.string("addressAr")
        val explicitOverride = json.boolean("explicitAreaOverride") == true
        val scope = requireConfiguredScope()
        val current = deviceLocationProvider.currentLocation()
        rejectIfDeviceOutsideEffectiveScope(scope, current, explicitOverride)

        val providedLat = json["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val providedLng = json["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val coordinates = if (providedLat != null && providedLng != null) {
            if (!explicitOverride) requireCandidateInsideEffectiveScope(scope, current, name, providedLat, providedLng)
            providedLat to providedLng
        } else {
            resolvePlace(scope, current, name, address, explicitOverride)
                ?: throwNoPlace(scope, current, name, explicitOverride)
        }

        return HReminderLocation(
            placeNameAr = name,
            addressAr = address,
            placeId = json.string("placeId"),
            latitude = coordinates.first,
            longitude = coordinates.second,
            radiusMeters = json["radiusMeters"]?.jsonPrimitive?.content?.toFloatOrNull()?.coerceIn(50f, 5_000f) ?: 180f,
            dwellMinutes = json["dwellMinutes"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceIn(1, 60) ?: 1,
            triggerMode = enumOrDefault(json.string("triggerMode"), HLocationTriggerMode.DWELL),
        )
    }

    private fun requireConfiguredScope(): HLocationScope {
        val scope = locationScopeStore.current()
        if (!scope.isConfigured) {
            throw HLocationScopeException("حدد نقطة الارتكاز من شاشة التذكيرات في تطبيق H أولًا. النطاق الافتراضي 100 كم.")
        }
        return scope
    }

    private fun rejectIfDeviceOutsideEffectiveScope(scope: HLocationScope, current: HDeviceLocation?, explicitOverride: Boolean) {
        if (current == null || explicitOverride) return
        val effective = HLocationScopePolicy.effectiveScope(scope, current.latitude, current.longitude)
        val evaluation = HLocationScopePolicy.evaluateEffective(
            scope, current.latitude, current.longitude, current.latitude, current.longitude,
        )
        if (evaluation.configured && !evaluation.inside) {
            val distance = evaluation.distanceKm?.toInt()
            throw HLocationScopeException(
                "أنت الآن خارج نطاق البحث الأساسي${distance?.let { " بحوالي $it كم" } ?: ""}. " +
                    "فعّل وضع السفر من التطبيق أو عدّل نقطة الارتكاز. إذا كنت تقصد مدينة أخرى صراحةً فاذكرها في الطلب."
            )
        }
        if (effective.source == HLocationScopeSource.BASE && scope.travelEnabled && scope.isTravelConfigured) {
            // Travel expired or UNTIL_RETURN has completed. The base scope is intentionally authoritative again.
        }
    }

    private fun requireCandidateInsideEffectiveScope(
        scope: HLocationScope,
        current: HDeviceLocation?,
        name: String,
        latitude: Double,
        longitude: Double,
    ) {
        val evaluation = HLocationScopePolicy.evaluateEffective(
            scope, latitude, longitude, current?.latitude, current?.longitude,
        )
        if (!evaluation.inside) {
            val effective = HLocationScopePolicy.effectiveScope(scope, current?.latitude, current?.longitude)
            val distance = evaluation.distanceKm?.toInt()
            val sourceLabel = if (evaluation.source == HLocationScopeSource.TRAVEL) "نطاق السفر" else "النطاق الأساسي"
            throw HLocationScopeException(
                "المكان «$name» خارج $sourceLabel (${effective.radiusKm.toInt()} كم)" +
                    (distance?.let { " ويبعد نحو $it كم عن نقطة الارتكاز الفعالة" } ?: "") +
                    ". لن أختار مكانًا بعيدًا يحمل الاسم نفسه تلقائيًا."
            )
        }
    }

    private suspend fun resolvePlace(
        scope: HLocationScope,
        current: HDeviceLocation?,
        placeName: String,
        address: String?,
        explicitOverride: Boolean,
    ): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val query = listOfNotNull(placeName, address).joinToString("، ")
        runCatching {
            @Suppress("DEPRECATION")
            val results = Geocoder(context, Locale("ar", "SA")).getFromLocationName(query, 10).orEmpty()
            if (explicitOverride) {
                results.minByOrNull { item ->
                    if (current == null) 0.0 else HLocationScopePolicy.distanceKm(current.latitude, current.longitude, item.latitude, item.longitude)
                }?.let { it.latitude to it.longitude }
            } else {
                results.map { item ->
                    val evaluation = HLocationScopePolicy.evaluateEffective(
                        scope, item.latitude, item.longitude, current?.latitude, current?.longitude,
                    )
                    Triple(item.latitude, item.longitude, evaluation)
                }.filter { it.third.inside }
                    .minByOrNull { it.third.distanceKm ?: Double.MAX_VALUE }
                    ?.let { it.first to it.second }
            }
        }.getOrNull()
    }

    private fun throwNoPlace(scope: HLocationScope, current: HDeviceLocation?, name: String, explicitOverride: Boolean): Nothing {
        if (explicitOverride) throw HLocationScopeException("تعذر تحديد «$name» بالوصف الصريح. أضف اسم المدينة أو الحي بشكل أوضح.")
        val effective = HLocationScopePolicy.effectiveScope(scope, current?.latitude, current?.longitude)
        val source = if (effective.source == HLocationScopeSource.TRAVEL) "نطاق السفر" else "نطاقك الأساسي"
        throw HLocationScopeException(
            "لم أجد «$name» داخل $source (${effective.radiusKm.toInt()} كم من ${effective.label ?: "نقطة الارتكاز"}). لن أختار نتيجة أبعد تلقائيًا."
        )
    }

    private fun reminderJson(reminder: HReminder) = buildJsonObject {
        put("id", JsonPrimitive(reminder.id))
        put("title", JsonPrimitive(reminder.title))
        put("originalText", JsonPrimitive(reminder.originalText))
        put("interpretedText", JsonPrimitive(reminder.interpretedText))
        put("type", JsonPrimitive(reminder.type.name))
        put("status", JsonPrimitive(reminder.status.name))
        put("domain", JsonPrimitive(reminder.domain.name))
        reminder.scheduledAtMs?.let { put("scheduledAtMs", JsonPrimitive(it)) }
        reminder.recurrenceRule?.let { put("recurrenceRule", JsonPrimitive(it)) }
        reminder.personName?.let { put("personName", JsonPrimitive(it)) }
        reminder.location?.let { location ->
            put("location", buildJsonObject {
                put("placeNameAr", JsonPrimitive(location.placeNameAr))
                location.addressAr?.let { put("addressAr", JsonPrimitive(it)) }
                location.placeId?.let { put("placeId", JsonPrimitive(it)) }
                put("latitude", JsonPrimitive(location.latitude))
                put("longitude", JsonPrimitive(location.longitude))
                put("radiusMeters", JsonPrimitive(location.radiusMeters))
                put("dwellMinutes", JsonPrimitive(location.dwellMinutes))
                put("triggerMode", JsonPrimitive(location.triggerMode.name))
            })
        }
    }

    private fun scopeJson(scope: HLocationScope) = buildJsonObject {
        put("configured", JsonPrimitive(scope.isConfigured))
        put("radiusKm", JsonPrimitive(scope.radiusKm))
        scope.anchorLabel?.let { put("anchorLabel", JsonPrimitive(it)) }
        put("travelEnabled", JsonPrimitive(scope.isTravelConfigured))
        if (scope.isTravelConfigured) {
            put("travelRadiusKm", JsonPrimitive(scope.travelRadiusKm))
            put("travelMode", JsonPrimitive(scope.travelExpiryMode.name))
            scope.travelLabel?.let { put("travelLabel", JsonPrimitive(it)) }
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }
    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
    private fun JsonObject.requiredId(): String = string("id") ?: throw IllegalArgumentException("Reminder id is required")
    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty().uppercase()) }.getOrDefault(fallback)
}
