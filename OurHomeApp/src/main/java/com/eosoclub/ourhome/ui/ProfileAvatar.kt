package com.eosoclub.ourhome.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eosoclub.ourhome.data.ProfileStyle

/** A member's chosen emoji (or initials) on their chosen colour, like the web's ProfileAvatar. */
@Composable
internal fun ProfileAvatar(name: String, emoji: String?, colorKey: String?, size: Dp = 40.dp) {
    val color = Color(ProfileStyle.color(colorKey))
    Box(
        Modifier
            .size(size)
            // Emoji sit on a soft tint of the colour; initials on the solid colour.
            .background(if (emoji != null) color.copy(alpha = 0.2f) else color, CircleShape)
            .border(2.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            emoji ?: ProfileStyle.initials(name),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * if (emoji != null) 0.5f else 0.4f).sp,
        )
    }
}
