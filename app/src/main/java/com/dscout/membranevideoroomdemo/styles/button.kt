package com.dscout.membranevideoroomdemo.styles

import androidx.compose.material.ButtonColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color

fun AppButtonColors(): ButtonColors {
    return DefaultButtonColors(
        backgroundColor = Blue,
        contentColor = Color.White,
        disabledBackgroundColor = Blue.darker(0.2f),
        disabledContentColor = Color.White.darker(0.2f)
    )
}

private data class DefaultButtonColors(
    private val backgroundColor: Color,
    private val contentColor: Color,
    private val disabledBackgroundColor: Color,
    private val disabledContentColor: Color
) : ButtonColors {
    @Composable
    override fun backgroundColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(if (enabled) backgroundColor else disabledBackgroundColor)
    }

    @Composable
    override fun contentColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(if (enabled) contentColor else disabledContentColor)
    }
}
