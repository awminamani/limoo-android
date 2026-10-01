package app.limoo.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import app.limoo.R

/**
 * Accent-reactive wallpaper: two layers, drawn behind everything else.
 *
 *  1. `limoo_wallpaper_base` - the monochrome artwork.
 *  2. `limoo_accent_mask` - a transparent mask whose alpha selects which details take the accent colour.
 *     `ColorFilter.tint` recolours the RGB and preserves alpha, so only the masked details change and the
 *     base stays monochrome. (The mask is 100% white with partial alpha up to ~190; it has no fully
 *     opaque pixels, which is why tinting is the correct approach rather than a colour overlay.)
 *
 * Both layers use `ContentScale.Crop`, so the artwork fills any screen without distortion and the two
 * layers stay aligned.
 *
 * The accent is read from [LocalN], which `NTheme` derives from the stored `AppSettings.accent`. That
 * makes this the app's single accent source: changing the setting recomposes `NTheme`, this reads the new
 * value, and the wallpaper retints in place with no restart and no duplicate preference.
 *
 * `painterResource` caches the decoded bitmaps, so repeated recompositions do not re-decode.
 *
 * Applied on Home only - it has the most negative space, and dense rows over artwork (Servers, Settings)
 * cost legibility, so those tabs keep the flat canvas. See `Root.kt`.
 */
@Composable
fun AccentWallpaper(modifier: Modifier = Modifier, scrim: Float = 0f) {
    val accent = LocalN.current.accent
    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.limoo_wallpaper_base),
            contentDescription = null,           // decorative
            contentScale = ContentScale.Crop,    // aspect preserved, no stretching
            modifier = Modifier.fillMaxSize(),
        )
        Image(
            painter = painterResource(R.drawable.limoo_accent_mask),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = ColorFilter.tint(accent),
            modifier = Modifier.fillMaxSize(),
        )
        // Optional darkening for when the artwork would compete with text.
        if (scrim > 0f) {
            val n = LocalN.current
            Box(Modifier.fillMaxSize().background(n.bg.copy(alpha = scrim.coerceIn(0f, 1f))))
        }
    }
}

/**
 * Background-only form: the layers, sized to fill their parent.
 * [scrim] darkens the artwork further for screens where it would compete with text.
 */
@Composable
fun WallpaperLayers(modifier: Modifier = Modifier, scrim: Float = 0f) = AccentWallpaper(modifier, scrim)
