package com.sertum.player.ui.settings

import com.sertum.player.ui.playback.OutputMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LanguageOption { SYSTEM, ZH, EN }

data class SettingsState(
    val darkTheme: Boolean = true,
    val language: LanguageOption = LanguageOption.SYSTEM,
    val outputMode: OutputMode = OutputMode.STANDARD,
    val roundCover: Boolean = false,
)

object SettingsStateHolder {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun update(transform: (SettingsState) -> SettingsState) {
        _state.value = transform(_state.value)
    }
}
