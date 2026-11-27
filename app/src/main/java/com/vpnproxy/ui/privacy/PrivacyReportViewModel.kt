package com.vpnproxy.ui.privacy

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.privacy.AndroidPrivacyInputsProvider
import com.vpnproxy.core.privacy.PrivacyReport
import com.vpnproxy.core.privacy.PrivacyReportBuilder
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PrivacyReportUiState(
    val report: PrivacyReport? = null,
    val loading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class PrivacyReportViewModel @Inject constructor(
    @ApplicationContext context: Context,
    subscriptions: SubscriptionRepository
) : ViewModel() {
    private val provider = AndroidPrivacyInputsProvider(context.applicationContext, subscriptions)
    private val builder = PrivacyReportBuilder()
    private val _state = MutableStateFlow(PrivacyReportUiState())
    val state: StateFlow<PrivacyReportUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val report = withContext(Dispatchers.IO) {
                    builder.build(provider.read())
                }
                _state.value = PrivacyReportUiState(report = report)
            } catch (_: Exception) {
                _state.value = PrivacyReportUiState(error = "Не удалось сформировать отчёт. Повторите попытку.")
            }
        }
    }

    fun exportText(): String {
        val report = _state.value.report ?: return "Отчёт конфиденциальности пока не готов."
        return buildString {
            appendLine("vladossVPN — отчёт конфиденциальности")
            appendLine()
            PrivacyKind.entries.forEach { kind ->
                val facts = report.facts.filter { it.kind == kind }
                if (facts.isNotEmpty()) {
                    appendLine(kind.title)
                    facts.forEach { fact ->
                        appendLine("• ${fact.title}")
                        appendLine("  ${fact.detail}")
                        appendLine("  Источник: ${fact.source.title}")
                        appendLine("  Передача с устройства: ${if (fact.dataLeavesDevice) "да" else "нет"}")
                        if (fact.requiresOptIn) appendLine("  Требуется согласие: да")
                    }
                    appendLine()
                }
            }
            if (report.warnings.isNotEmpty()) {
                appendLine("Предупреждения")
                report.warnings.forEach { appendLine("• $it") }
            }
        }.trim()
    }
}
