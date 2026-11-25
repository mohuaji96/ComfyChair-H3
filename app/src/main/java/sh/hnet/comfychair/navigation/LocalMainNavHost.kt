package sh.hnet.comfychair.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController

/**
 * The main NavHost, so a screen's own chrome (the source switch at the top) can read the current
 * route and move between sources without every screen taking a navigation callback.
 */
val LocalMainNavHost = staticCompositionLocalOf<NavHostController?> { null }
