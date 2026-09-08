package com.malik.lmai.presentation.ui.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malik.lmai.feature.reminder.HDeviceLocationProvider
import com.malik.lmai.feature.reminder.HLocationScope
import com.malik.lmai.feature.reminder.HLocationScopeStore
import com.malik.lmai.feature.reminder.HReminder
import com.malik.lmai.feature.reminder.HReminderRepository
import com.malik.lmai.feature.reminder.HReminderStatus
import com.malik.lmai.feature.reminder.HReminderType
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class HReminderFilter { ALL, ACTIVE, LOCATION, TIME, COMPLETED }

data class HRemindersUiState(
    val reminders: List<HReminder> = emptyList(),
    val filter: HReminderFilter = HReminderFilter.ALL,
    val selected: HReminder? = null,
    val editing: HReminder? = null,
    val deleteCandidate: HReminder? = null,
    val hasAnyReminder: Boolean = false,
    val hasLocationReminder: Boolean = false,
    val locationScope: HLocationScope = HLocationScope(),
    val locationScopeBusy: Boolean = false,
    val locationScopeMessage: String? = null,
)

@HiltViewModel
class HRemindersViewModel @Inject constructor(
    private val repository: HReminderRepository,
    private val locationScopeStore: HLocationScopeStore,
    private val deviceLocationProvider: HDeviceLocationProvider,
) : ViewModel() {
    private val filter = MutableStateFlow(HReminderFilter.ALL)
    private val selected = MutableStateFlow<HReminder?>(null)
    private val editing = MutableStateFlow<HReminder?>(null)
    private val deleteCandidate = MutableStateFlow<HReminder?>(null)
    private val locationScopeBusy = MutableStateFlow(false)
    private val locationScopeMessage = MutableStateFlow<String?>(null)

    private val reminderState = combine(
        repository.observePersonal(),
        filter,
        selected,
        editing,
        deleteCandidate,
    ) { reminders, currentFilter, currentSelected, currentEditing, currentDelete ->
        val filtered = when (currentFilter) {
            HReminderFilter.ALL -> reminders
            HReminderFilter.ACTIVE -> reminders.filter { it.status == HReminderStatus.ACTIVE || it.status == HReminderStatus.DEFERRED }
            HReminderFilter.LOCATION -> reminders.filter { it.type == HReminderType.LOCATION }
            HReminderFilter.TIME -> reminders.filter { it.type == HReminderType.TIME || it.type == HReminderType.RECURRING }
            HReminderFilter.COMPLETED -> reminders.filter { it.status == HReminderStatus.COMPLETED }
        }
        HRemindersUiState(
            reminders = filtered,
            filter = currentFilter,
            selected = currentSelected,
            editing = currentEditing,
            deleteCandidate = currentDelete,
            hasAnyReminder = reminders.isNotEmpty(),
            hasLocationReminder = reminders.any { it.location != null || it.type == HReminderType.LOCATION },
        )
    }

    val uiState = combine(
        reminderState,
        locationScopeStore.scope,
        locationScopeBusy,
        locationScopeMessage,
    ) { reminder, scope, busy, message ->
        reminder.copy(
            locationScope = scope,
            locationScopeBusy = busy,
            locationScopeMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HRemindersUiState(locationScope = locationScopeStore.current()),
    )

    init {
        locationScopeStore.refresh()
    }

    fun setFilter(value: HReminderFilter) { filter.value = value }
    fun open(reminder: HReminder) { selected.value = reminder }
    fun closeDetails() { selected.value = null }
    fun beginEdit(reminder: HReminder) {
        selected.value = null
        editing.value = reminder
    }
    fun closeEdit() { editing.value = null }
    fun requestDelete(reminder: HReminder) { deleteCandidate.value = reminder }
    fun cancelDelete() { deleteCandidate.value = null }

    fun pinCurrentLocation() = viewModelScope.launch {
        if (locationScopeBusy.value) return@launch
        locationScopeBusy.value = true
        locationScopeMessage.value = null
        try {
            if (!deviceLocationProvider.hasLocationPermission()) {
                locationScopeMessage.value = "اسمح بالموقع الدقيق أولًا لتثبيت نقطة الارتكاز."
                return@launch
            }
            val current = deviceLocationProvider.currentLocation()
            if (current == null) {
                locationScopeMessage.value = "تعذر الحصول على موقعك الحالي. تأكد من تشغيل الموقع ثم حاول مرة أخرى."
                return@launch
            }
            val radius = locationScopeStore.current().radiusKm
            locationScopeStore.pin(
                latitude = current.latitude,
                longitude = current.longitude,
                label = current.label ?: "موقعي المثبت",
                radiusKm = radius,
            )
            locationScopeMessage.value = "تم تثبيت نقطة الارتكاز. H سيبحث عن الأماكن داخل ${radius.toInt()} كم فقط."
        } finally {
            locationScopeBusy.value = false
        }
    }

    fun setLocationScopeRadius(radiusKm: Double) {
        val value = locationScopeStore.setRadiusKm(radiusKm)
        locationScopeMessage.value = if (value.isConfigured) {
            "تم تحديث نطاق البحث إلى ${value.radiusKm.toInt()} كم."
        } else {
            "تم اختيار ${value.radiusKm.toInt()} كم. ثبّت موقعك الحالي لتفعيل النطاق."
        }
    }

    fun clearLocationScope() {
        locationScopeStore.clear()
        locationScopeMessage.value = "تم إلغاء نقطة الارتكاز. لن ينشئ H تذكير مكان غامض حتى تثبّت نطاقًا جديدًا."
    }

    fun clearLocationScopeMessage() { locationScopeMessage.value = null }

    fun save(reminder: HReminder) = viewModelScope.launch {
        repository.update(reminder)
        editing.value = null
        selected.value = repository.get(reminder.id)
    }

    fun delete(reminder: HReminder) = viewModelScope.launch {
        repository.delete(reminder.id)
        deleteCandidate.value = null
        if (selected.value?.id == reminder.id) selected.value = null
        if (editing.value?.id == reminder.id) editing.value = null
    }

    fun complete(reminder: HReminder) = setStatus(reminder, HReminderStatus.COMPLETED)
    fun defer(reminder: HReminder) = setStatus(reminder, HReminderStatus.DEFERRED)
    fun toggleEnabled(reminder: HReminder) = setStatus(
        reminder,
        if (reminder.status == HReminderStatus.DISABLED) HReminderStatus.ACTIVE else HReminderStatus.DISABLED,
    )

    private fun setStatus(reminder: HReminder, status: HReminderStatus) = viewModelScope.launch {
        repository.setStatus(reminder.id, status)
        selected.value = repository.get(reminder.id)
    }

    fun reschedule() = viewModelScope.launch { repository.rescheduleAll() }
}
