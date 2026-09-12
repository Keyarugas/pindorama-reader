package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal object PindoramaColorScheme : BaseColorScheme() {
    override val lightScheme = lightColorScheme(
        primary = Color(0xFF155D46), onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB8D5C7), onPrimaryContainer = Color(0xFF0B3023),
        inversePrimary = Color(0xFF8FC9AA), secondary = Color(0xFFA33A2B),
        onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFF2C8BE),
        onSecondaryContainer = Color(0xFF4A1008), tertiary = Color(0xFFA36B00),
        onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFF4D994),
        onTertiaryContainer = Color(0xFF342000), background = Color(0xFFF7F1E3),
        onBackground = Color(0xFF25231F), surface = Color(0xFFFBF7EE),
        onSurface = Color(0xFF25231F), surfaceVariant = Color(0xFFE7DDCB),
        onSurfaceVariant = Color(0xFF4A4035), surfaceTint = Color(0xFF155D46),
        inverseSurface = Color(0xFF302F2A), inverseOnSurface = Color(0xFFF2EEE4),
        error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF756657), outlineVariant = Color(0xFFCBBEAA),
        surfaceContainerLowest = Color(0xFFF5EEE0), surfaceContainerLow = Color(0xFFF9F3E8),
        surfaceContainer = Color(0xFFF0EADD), surfaceContainerHigh = Color(0xFFEAE4D7),
        surfaceContainerHighest = Color(0xFFE4DED2),
    )

    override val darkScheme = darkColorScheme(
        primary = Color(0xFF8FC9AA), onPrimary = Color(0xFF073524),
        primaryContainer = Color(0xFF1E5A43), onPrimaryContainer = Color(0xFFD9F2E4),
        inversePrimary = Color(0xFF155D46), secondary = Color(0xFFF08A77),
        onSecondary = Color(0xFF4A0B05), secondaryContainer = Color(0xFF713226),
        onSecondaryContainer = Color(0xFFFFDAD2), tertiary = Color(0xFFE8B84B),
        onTertiary = Color(0xFF3C2800), tertiaryContainer = Color(0xFF624A0D),
        onTertiaryContainer = Color(0xFFFBE4A3), background = Color(0xFF151A17),
        onBackground = Color(0xFFE7EAE2), surface = Color(0xFF151A17),
        onSurface = Color(0xFFE7EAE2), surfaceVariant = Color(0xFF3C463F),
        onSurfaceVariant = Color(0xFFD5DED6), surfaceTint = Color(0xFF8FC9AA),
        inverseSurface = Color(0xFFE7EAE2), inverseOnSurface = Color(0xFF25231F),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFFA8B5AA), outlineVariant = Color(0xFF536158),
        surfaceContainerLowest = Color(0xFF101411), surfaceContainerLow = Color(0xFF191E1B),
        surfaceContainer = Color(0xFF1D231F), surfaceContainerHigh = Color(0xFF272D29),
        surfaceContainerHighest = Color(0xFF323934),
    )
}
