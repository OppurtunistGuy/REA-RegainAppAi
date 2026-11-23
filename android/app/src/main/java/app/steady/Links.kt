package app.steady

/** External URLs shown in the app. Placeholders until the site is live (see docs/release). */
object Links {
    const val PRIVACY_POLICY = "https://steady.app/privacy"
}

/** Build-type API settings (app/build.gradle.kts `buildConfigField`s). */
object ApiConfig {
    /** Debug: the emulator's host (`http://10.0.2.2:8787`); release: the https production API. */
    val DEFAULT_BASE_URL: String = BuildConfig.DEFAULT_API_BASE_URL

    /** Release builds only talk https; debug may use plain http to the local backend. */
    val REQUIRE_HTTPS: Boolean = BuildConfig.REQUIRE_HTTPS_API
}
