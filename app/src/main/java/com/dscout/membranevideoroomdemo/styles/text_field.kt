package com.dscout.membranevideoroomdemo.styles

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.material.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color

fun AppTextFieldColors(): TextFieldColors {
    return DefaultTextFieldColors(
        textColor = Color.White.darker(0.1f),
        disabledTextColor = Color.White.darker(0.4f),
        cursorColor = Color.White,
        errorCursorColor = Color.Red,
        focusedIndicatorColor = Color.White,
        unfocusedIndicatorColor = Color.White.darker(0.1f),
        errorIndicatorColor = Color.Red,
        disabledIndicatorColor = Color.Gray.darker(0.4f),
        leadingIconColor = Color.White,
        disabledLeadingIconColor = Color.Gray,
        errorLeadingIconColor = Color.Red,
        trailingIconColor = Color.White,
        disabledTrailingIconColor = Color.Gray,
        errorTrailingIconColor = Color.Red,
        focusedLabelColor = Color.White,
        unfocusedLabelColor = Color.White.darker(0.1f),
        disabledLabelColor = Color.Gray.darker(0.4f),
        errorLabelColor = Color.Red,
        backgroundColor = Blue.darker(0.4f),
        placeholderColor = Color.White.darker(0.3f),
        disabledPlaceholderColor = Color.Gray.darker(0.5f)
    )
}

internal data class DefaultTextFieldColors(
    val textColor: Color,
    val disabledTextColor: Color,
    val cursorColor: Color,
    val errorCursorColor: Color,
    val focusedIndicatorColor: Color,
    val unfocusedIndicatorColor: Color,
    val errorIndicatorColor: Color,
    val disabledIndicatorColor: Color,
    val leadingIconColor: Color,
    val disabledLeadingIconColor: Color,
    val errorLeadingIconColor: Color,
    val trailingIconColor: Color,
    val disabledTrailingIconColor: Color,
    val errorTrailingIconColor: Color,
    val backgroundColor: Color,
    val focusedLabelColor: Color,
    val unfocusedLabelColor: Color,
    val disabledLabelColor: Color,
    val errorLabelColor: Color,
    val placeholderColor: Color,
    val disabledPlaceholderColor: Color
) : TextFieldColors {
    @Composable
    override fun leadingIconColor(
        enabled: Boolean,
        isError: Boolean
    ): State<Color> {
        return rememberUpdatedState(
            when {
                !enabled -> disabledLeadingIconColor
                isError -> errorLeadingIconColor
                else -> leadingIconColor
            }
        )
    }

    @Composable
    override fun trailingIconColor(
        enabled: Boolean,
        isError: Boolean
    ): State<Color> {
        return rememberUpdatedState(
            when {
                !enabled -> disabledTrailingIconColor
                isError -> errorTrailingIconColor
                else -> trailingIconColor
            }
        )
    }

    @Composable
    override fun indicatorColor(
        enabled: Boolean,
        isError: Boolean,
        interactionSource: InteractionSource
    ): State<Color> {
        val focused by interactionSource.collectIsFocusedAsState()

        val targetValue =
            when {
                !enabled -> disabledIndicatorColor
                isError -> errorIndicatorColor
                focused -> focusedIndicatorColor
                else -> unfocusedIndicatorColor
            }
        return if (enabled) {
            animateColorAsState(targetValue, tween(durationMillis = 150))
        } else {
            rememberUpdatedState(targetValue)
        }
    }

    @Composable
    override fun backgroundColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(backgroundColor)
    }

    @Composable
    override fun placeholderColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(if (enabled) placeholderColor else disabledPlaceholderColor)
    }

    @Composable
    override fun labelColor(
        enabled: Boolean,
        error: Boolean,
        interactionSource: InteractionSource
    ): State<Color> {
        val focused by interactionSource.collectIsFocusedAsState()

        val targetValue =
            when {
                !enabled -> disabledLabelColor
                error -> errorLabelColor
                focused -> focusedLabelColor
                else -> unfocusedLabelColor
            }
        return rememberUpdatedState(targetValue)
    }

    @Composable
    override fun textColor(enabled: Boolean): State<Color> {
        return rememberUpdatedState(if (enabled) textColor else disabledTextColor)
    }

    @Composable
    override fun cursorColor(isError: Boolean): State<Color> {
        return rememberUpdatedState(if (isError) errorCursorColor else cursorColor)
    }
}
