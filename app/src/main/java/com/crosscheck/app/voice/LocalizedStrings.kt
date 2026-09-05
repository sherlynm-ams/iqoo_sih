package com.crosscheck.app.voice

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * [StringProvider] that resolves resources in the *speech* locale (the one [Speaker] is actually
 * using), not the UI locale — same technique as [Speaker.localizedString], with plurals.
 */
class LocalizedStrings(context: Context, private val locale: () -> Locale) : StringProvider {
    private val appContext = context.applicationContext

    private fun localized(): Context {
        val config = Configuration(appContext.resources.configuration).apply { setLocale(locale()) }
        return appContext.createConfigurationContext(config)
    }

    override fun get(resId: Int, vararg args: Any): String = localized().getString(resId, *args)

    override fun quantity(resId: Int, quantity: Int, vararg args: Any): String =
        localized().resources.getQuantityString(resId, quantity, *args)
}
