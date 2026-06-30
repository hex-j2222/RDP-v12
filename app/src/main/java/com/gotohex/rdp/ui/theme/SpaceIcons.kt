package com.gotohex.rdp.ui.theme

import android.content.Context
import androidx.annotation.DrawableRes

/**
 * SpaceIcons — resolves each icon to its theme-specific drawable resource ID.
 *
 * SVG files live in res/drawable/ following the pattern:
 *   ic_{name}_{theme}.svg
 * where theme is: space | nebula | aurora
 *
 * BUG-4 FIX: The original implementation returned a String resource name
 * (e.g. "ic_rdp_space"), but Compose's painterResource() requires a
 * @DrawableRes Int, not a String. Calling painterResource("ic_rdp_space")
 * would not compile.  Fix: resolve via Context.resources.getIdentifier() so
 * callers receive the actual resource ID.
 *
 * Usage in Compose:
 *   val iconRes = SpaceIcons.rdp(context, themeVariant)
 *   Image(painterResource(iconRes), contentDescription = null)
 *
 * Note: getIdentifier() is intentionally avoided in tight draw loops; cache
 * the result in a remember {} block at the call site.
 */
object SpaceIcons {

    /**
     * Returns the @DrawableRes Int for a given [iconBase] and [themeVariant].
     * Falls back to the "space" variant if the requested variant is not found,
     * then to 0 (no drawable) if neither exists.
     */
    @DrawableRes
    fun resolve(context: Context, iconBase: String, themeVariant: String): Int {
        val variant  = themeVariant.lowercase().let {
            if (it == "space" || it == "nebula" || it == "aurora") it else "space"
        }
        val resName  = "ic_${iconBase}_$variant"
        val id       = context.resources.getIdentifier(resName, "drawable", context.packageName)
        if (id != 0) return id
        // Fallback: try the "space" variant
        val fallback = "ic_${iconBase}_space"
        return context.resources.getIdentifier(fallback, "drawable", context.packageName)
    }

    // ── Protocol icons ────────────────────────────────────────────────────────
    @DrawableRes fun rdp(context: Context, themeVariant: String) = resolve(context, "rdp", themeVariant)
    @DrawableRes fun vnc(context: Context, themeVariant: String) = resolve(context, "vnc", themeVariant)
    @DrawableRes fun ssh(context: Context, themeVariant: String) = resolve(context, "ssh", themeVariant)

    // ── Navigation ────────────────────────────────────────────────────────────
    @DrawableRes fun grid(context: Context, themeVariant: String)     = resolve(context, "grid", themeVariant)
    @DrawableRes fun history(context: Context, themeVariant: String)  = resolve(context, "history", themeVariant)
    @DrawableRes fun transfer(context: Context, themeVariant: String) = resolve(context, "file_transfer", themeVariant)
    @DrawableRes fun settings(context: Context, themeVariant: String) = resolve(context, "settings", themeVariant)

    // ── Actions ───────────────────────────────────────────────────────────────
    @DrawableRes fun add(context: Context, themeVariant: String)         = resolve(context, "add", themeVariant)
    @DrawableRes fun search(context: Context, themeVariant: String)      = resolve(context, "search", themeVariant)
    @DrawableRes fun refresh(context: Context, themeVariant: String)     = resolve(context, "refresh", themeVariant)
    @DrawableRes fun close(context: Context, themeVariant: String)       = resolve(context, "close", themeVariant)
    @DrawableRes fun swap(context: Context, themeVariant: String)        = resolve(context, "swap", themeVariant)
    @DrawableRes fun folder(context: Context, themeVariant: String)      = resolve(context, "folder", themeVariant)
    @DrawableRes fun code(context: Context, themeVariant: String)        = resolve(context, "code", themeVariant)
    @DrawableRes fun flash(context: Context, themeVariant: String)       = resolve(context, "flash", themeVariant)

    // ── Security ──────────────────────────────────────────────────────────────
    @DrawableRes fun lock(context: Context, themeVariant: String)        = resolve(context, "lock", themeVariant)
    @DrawableRes fun fingerprint(context: Context, themeVariant: String) = resolve(context, "fingerprint", themeVariant)
    @DrawableRes fun security(context: Context, themeVariant: String)    = resolve(context, "security", themeVariant)

    // ── Status ────────────────────────────────────────────────────────────────
    @DrawableRes fun wifi(context: Context, themeVariant: String)   = resolve(context, "wifi", themeVariant)
    @DrawableRes fun phone(context: Context, themeVariant: String)  = resolve(context, "phone", themeVariant)
    @DrawableRes fun rocket(context: Context, themeVariant: String) = resolve(context, "rocket", themeVariant)
    @DrawableRes fun palette(context: Context, themeVariant: String)= resolve(context, "palette", themeVariant)
}
