package com.sertum.player.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LanguageOption { SYSTEM, ZH, EN }

data class SettingsState(
    val darkTheme: Boolean = true,
    val language: LanguageOption = LanguageOption.SYSTEM,
    val autoExclusiveOnUsbAttach: Boolean = true,
    val roundCover: Boolean = false,
)

object SettingsStateHolder {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun update(transform: (SettingsState) -> SettingsState) {
        _state.value = transform(_state.value)
    }
}
