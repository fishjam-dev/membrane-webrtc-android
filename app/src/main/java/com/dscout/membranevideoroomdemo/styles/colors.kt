package com.dscout.membranevideoroomdemo.styles

import android.graphics.ColorSpace
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces


val Primary = Color(0xff1e3d80)
val Blue = Color(0xff447bfe)

fun Color.mix(with: Color, amount: Float): Color {
    val red1 = this.red
    val red2 = with.red
    val green1 = this.green
    val green2 = with.green
    val blue1 = this.blue
    val blue2 = with.blue
    val alpha1 = this.alpha
    val alpha2 = with.alpha

    return Color(
        (red1 * (1.0 - amount) + red2 * amount).toFloat(),
        (green1 * (1.0 - amount) + green2 * amount).toFloat(),
        (blue1 * (1.0 - amount) + blue2 * amount).toFloat(),
        (alpha1 * (1.0 - amount) + alpha2 * amount).toFloat(),
       ColorSpaces.Srgb
    )
}

fun Color.darker(by: Float): Color {
    return this.mix(Color.Black, by)
}

fun Color.lighter(by: Float): Color {
    return this.mix(Color.White, by)
}
