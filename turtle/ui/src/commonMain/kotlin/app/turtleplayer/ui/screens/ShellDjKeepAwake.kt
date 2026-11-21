package app.turtleplayer.ui.screens

import androidx.compose.runtime.Composable

/** Keeps the screen on while composed (the DJ booth at a party). No-op where the platform has no such switch. */
@Composable
internal expect fun DjKeepScreenOn()
