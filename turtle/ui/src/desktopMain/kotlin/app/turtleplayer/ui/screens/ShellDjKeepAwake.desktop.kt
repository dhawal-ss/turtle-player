package app.turtleplayer.ui.screens

import androidx.compose.runtime.Composable

// Desktop: the OS decides display sleep; playing audio already keeps most systems awake.
@Composable
internal actual fun DjKeepScreenOn() = Unit
