package com.malik.lmai.feature.agent.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.malik.lmai.feature.agent.AgentTool
import com.malik.lmai.feature.agent.AgentToolCall
import com.malik.lmai.feature.agent.AgentToolContext
import com.malik.lmai.feature.agent.AgentToolDefinition
import com.malik.lmai.feature.agent.AgentToolResult
import com.malik.lmai.feature.reminder.HGeoPoint
import com.malik.lmai.feature.reminder.HLocationScopeOutcome
import com.malik.lmai.feature.reminder.HLocationScopePolicy
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
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
    @ApplicationContext private val context: Context,
) : AgentTool {

    override val definition = AgentToolDefinition(
        name = "h_reminders",
        description = "Create, list, inspect, edit, complete, defer, disable, or delete H reminders. " +
            "Use this whenever the user asks H to remember something at a time, place, person, or recurring context. " +
            "Use domain PERSONAL for normal-life reminders. Use PROGRAMMING only when the reminder is specifically about coding, " +
            "software development, repositories, builds, or developing an app; programming reminders are intentionally hidden from the personal Reminders settings screen. " +
            "For a place request like 'إذا رحت حلي', prefer triggerMode DWELL with dwellMinutes=1 so merely passing through does not count as a visit. " +
            "For location reminders, placeNameAr is required but latitude/longitude are optional: H resolves a named place on the device when coordinates are omitted. " +
            "H may have a user-pinned place-search anchor and radius (default 100 km). Ambiguous/local place names MUST resolve inside that scope. " +
            "Set location.explicitDistantPlace=true ONLY when the user's wording explicitly identifies a distant city/place (for example 'جرير في الرياض'); never set it merely to bypass the scope. " +
            "If the tool reports OUTSIDE_BASE_SCOPE, tell the user they are outside the configured search radius and ask them to update the anchor or enable Travel mode from the Reminders screen. " +
            "The large search radius is never the reminder geofence; the actual target keeps its small POI radius. " +
            "Preserve the user's original wording in originalText. scheduledAtIso should be an absolute ISO-8601 time with offset when possible.",
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
                        put("explicitDistantPlace", buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put("description", JsonPrimitive("True only when the user explicitly named/disambiguated a place outside their normal area; never infer this just to bypass H's configured radius."))
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
            return call.errorResult(
                "Could not resolve the requested place inside H's configured place-search scope. Ask the user for a clearer place/address or let them choose the point from the Reminders map."
            )
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
            return call.errorResult("Could not resolve the updated reminder location inside H's configured place-search scope")
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
            .recoverCatching {
                LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
            .getOrNull()
    }

    private suspend fun parseLocation(json: JsonObject?): HReminderLocation? {
        if (json == null) return null
        val name = json.string("placeNameAr") ?: return null
        val address = json.string("addressAr")
        val explicitDistant = json.boolean("explicitDistantPlace") == true
        val config = locationScopeStore.load()

        if (!explicitDistant && config.baseAnchor != null && config.travelAnchor == null) {
            currentDevicePoint()?.let { current ->
                val currentDecision = HLocationScopePolicy.evaluateCurrentPosition(config, current)
                if (currentDecision.outcome == HLocationScopeOutcome.OUTSIDE_BASE_SCOPE) {
                    val distance = currentDecision.distanceKm?.toInt()
                    throw IllegalArgumentException(
                        "OUTSIDE_BASE_SCOPE: Device is ${distance?.let { "$it km" } ?: "outside"} from the pinned anchor, beyond the ${config.radiusKm.toInt()} km search radius. Ask the user to update the anchor or enable Travel mode in H Reminders."
                    )
                }
            }
        }

        val providedLat = json["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val providedLng = json["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val coordinates = if (providedLat != null && providedLng != null) {
            val candidate = HGeoPoint(providedLat, providedLng, name)
            val decision = HLocationScopePolicy.evaluateCandidate(config, candidate, explicitDistant)
            if (!decision.allowed) {
                throw IllegalArgumentException(
                    "OUTSIDE_SCOPE: '$name' resolved ${decision.distanceKm?.toInt() ?: "more than"} km from H's anchor, outside the ${config.radiusKm.toInt()} km search radius. Do not create this reminder unless the user explicitly identifies that distant place or changes the scope in the app."
                )
            }
            providedLat to providedLng
        } else {
            resolvePlace(name, address, explicitDistant) ?: return null
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

    private suspend fun resolvePlace(
        placeName: String,
        address: String?,
        explicitDistant: Boolean,
    ): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val query = listOfNotNull(placeName, address).joinToString("، ")
        val config = locationScopeStore.load()
        val active = config.activeAnchor()
        runCatching {
            val geocoder = Geocoder(context, Locale("ar", "SA"))
            @Suppress("DEPRECATION")
            val candidates = if (active != null && !explicitDistant) {
                val anchor = active.second
                val bounds = searchBounds(anchor, config.radiusKm)
                geocoder.getFromLocationName(
                    query,
                    8,
                    bounds.lowerLat,
                    bounds.lowerLon,
                    bounds.upperLat,
                    bounds.upperLon,
                ).orEmpty()
            } else {
                geocoder.getFromLocationName(query, 8).orEmpty()
            }

            candidates
                .asSequence()
                .map { HGeoPoint(it.latitude, it.longitude, it.featureName ?: placeName) }
                .filter { candidate ->
                    HLocationScopePolicy.evaluateCandidate(config, candidate, explicitDistant).allowed
                }
                .minByOrNull { candidate ->
                    active?.second?.let { HLocationScopePolicy.distanceKm(it, candidate) } ?: 0.0
                }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    private suspend fun currentDevicePoint(): HGeoPoint? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null

        val client = LocationServices.getFusedLocationProviderClient(context)
        val last = suspendCancellableCoroutine<android.location.Location?> { continuation ->
            client.lastLocation
                .addOnSuccessListener { location -> if (continuation.isActive) continuation.resume(location) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
        }
        val location = last ?: suspendCancellableCoroutine<android.location.Location?> { continuation ->
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { current -> if (continuation.isActive) continuation.resume(current) }
                .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
        }
        return location?.let { HGeoPoint(it.latitude, it.longitude, "current") }
    }

    private fun searchBounds(anchor: HGeoPoint, radiusKm: Double): SearchBounds {
        val latDelta = radiusKm / 110.574
        val cosLat = cos(Math.toRadians(anchor.latitude)).coerceAtLeast(0.05)
        val lonDelta = radiusKm / (111.320 * cosLat)
        return SearchBounds(
            lowerLat = (anchor.latitude - latDelta).coerceAtLeast(-90.0),
            lowerLon = normalizeLongitude(anchor.longitude - lonDelta),
            upperLat = (anchor.latitude + latDelta).coerceAtMost(90.0),
            upperLon = normalizeLongitude(anchor.longitude + lonDelta),
        )
    }

    private fun normalizeLongitude(value: Double): Double {
        var normalized = value
        while (normalized < -180.0) normalized += 360.0
        while (normalized > 180.0) normalized -= 360.0
        return normalized
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

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

    private fun JsonObject.requiredId(): String =
        string("id") ?: throw IllegalArgumentException("Reminder id is required")

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
        runCatching { enumValueOf<T>(value.orEmpty().uppercase()) }.getOrDefault(fallback)

    private data class SearchBounds(
        val lowerLat: Double,
        val lowerLon: Double,
        val upperLat: Double,
        val upperLon: Double,
    )
}
