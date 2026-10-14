package app.limoo

/**
 * Armio service build switches.
 *
 * This build is distributed to Armio customers only, so the generic-client surface -
 * importing arbitrary configs from anywhere, and exporting/sharing configs back out - is
 * switched OFF here: the app works with the one personal subscription the customer received
 * from the Armio bot, and nothing else. The gates live at the entry points (AddSheet,
 * SubsSheet, MainActivity.open, the server row menus, Settings backup) and once more in
 * Store.addSubscription, so no path can sneak a second subscription or a foreign config in.
 */
object Armio {
    /** Master switch for the customer lock-down described above. */
    const val SERVICE_MODE = true

    /** Remote service config (kill switch / minimum version / announcement), hosted on the Armio server. */
    const val CONFIG_URL = "https://eu.limoo.indevs.in:2083/armio/config.json"

    /** Where Renew / support actions send customers: the Armio bot. */
    const val SUPPORT_URL = "https://t.me/Armiovpn_bot"

    /** In service mode a customer has exactly one subscription: their own. */
    const val MAX_SUBSCRIPTIONS = 1
}
