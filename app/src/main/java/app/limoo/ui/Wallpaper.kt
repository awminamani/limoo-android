package app.limoo.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.limoo.R
import app.limoo.model.AppSettings
import java.io.InputStream

/**
 * Background stack, drawn behind everything else. Three possible sources, chosen by
 * [AppSettings.bgSource]:
 *
 *  1. `""` (empty) - **the default**, two layers:
 *       a. `limoo_wallpaper_base` - the monochrome Limoo artwork;
 *       b. `limoo_accent_mask` - a transparent mask whose ALPHA selects which details take the accent
 *          colour. `ColorFilter.tint` recolours the RGB and preserves alpha, so only the masked details
 *          change and the base stays monochrome. (The mask is white with partial alpha up to ~190 and has
 *          no fully opaque pixels, which is exactly why tinting is correct and a colour overlay is not.)
 *  2. any other value - a `content://` image the user picked from their gallery. Rendered alone, with no
 *     accent layer: a photograph is already fully coloured and tinting it would only muddy it.
 *
 * In both cases an optional **blur** and **dim** are applied on top so the UI stays legible over artwork.
 * The blur is done by downscaling the decoded bitmap and letting the GPU stretch it back up - a real
 * blur filter for a full-screen image every frame is far too expensive on a phone, and the low-resolution
 * upscale is visually indistinguishable at this strength.
 *
 * The accent is read from [LocalN], which `NTheme` derives from the stored `AppSettings.accent`. That
 * makes this the app's single accent source: changing the setting recomposes `NTheme`, this reads the new
 * value, and the wallpaper retints in place with no restart and no duplicate preference.
 *
 * `painterResource` caches the decoded bitmaps, so repeated recompositions do not re-decode.
 */
@Composable
fun AccentWallpaper(st: AppSettings, modifier: Modifier = Modifier, scrim: Float = 0f) {
    val accent = LocalN.current.accent
    val n = LocalN.current
    val custom = st.bgSource
    Box(modifier) {
        if (custom.isEmpty()) {
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
        } else {
            // Decoded off the composition thread, and only re-decoded when the user picks a different image.
            // LocalContext is captured OUTSIDE the produceState block: that block is a suspend lambda, not a
            // composable, so reading a CompositionLocal inside it does not compile.
            val ctx = LocalContext.current
            val bmp by produceState<ImageBitmap?>(initialValue = null, key1 = custom, key2 = st.bgBlur) {
                value = decodeBackground(ctx, custom, st.bgBlur)
            }
            bmp?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // The accent layer is skipped for a photo, so the signal colour would be absent from the
            // background entirely; a barely-there tint keeps the two sources visually related.
            if (st.accent != "mono") {
                Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.06f)))
            }
        }
        // Optional darkening for when the artwork would compete with text.
        val dim = maxOf(scrim, st.bgDim)
        if (dim > 0f) Box(Modifier.fillMaxSize().background(n.bg.copy(alpha = dim.coerceIn(0f, 1f))))
    }
}

/**
 * Background-only form: the layers, sized to fill their parent.
 * [scrim] darkens the artwork further for screens where it would compete with text.
 */
@Composable
fun WallpaperLayers(st: AppSettings, modifier: Modifier = Modifier, scrim: Float = 0f) =
    AccentWallpaper(st, modifier, scrim)

/**
 * Decodes a `content://` image into something Compose can draw.
 *
 * Sampling matters: a modern phone photo is 4000 px wide and decoding it full-size costs ~48 MB of heap
 * for an image that is only ever shown as a wallpaper. The sample size is chosen from the requested blur
 * so a heavily-blurred background can be decoded even smaller.
 */
private fun decodeBackground(ctx: android.content.Context, uri: String, blur: Float): ImageBitmap? = try {
    val resolver = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream(resolver, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val (w, h) = bounds.outWidth to bounds.outHeight
    if (w <= 0 || h <= 0) null else {
        // Target ~1080 px on the long edge; smaller when the image will be blurred anyway.
        val long = maxOf(w, h)
        val target = if (blur > 0.05f) 720 else 1080
        var sample = 1
        while (long / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565   // no alpha needed: there is nothing to composite
        }
        openStream(resolver, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
    }
} catch (e: Throwable) {
    null
}

private fun openStream(r: android.content.ContentResolver, uri: String): InputStream? =
    runCatching { r.openInputStream(android.net.Uri.parse(uri)) }.getOrNull()

/** True when the stored background is a user image rather than the shipped artwork. */
fun AppSettings.hasCustomBackground(): Boolean = bgSource.isNotEmpty()

/** Suggested dim for a chosen photo: darker than the default artwork, because a photo has more contrast. */
fun AppSettings.defaultDimForCustom(): Float = if (hasCustomBackground()) 0.55f else 0.45f