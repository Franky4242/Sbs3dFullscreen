import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

// Compose Multiplatform's resource system (stringResource) resolves the active language
// from Locale.getDefault() and has no public API yet to override it per-composition, so
// we track an explicit override here and mutate the JVM default Locale when it changes.
// See: https://kotlinlang.org/docs/multiplatform/compose-resource-environment.html
object LocalAppLocale {
    private var systemDefault: Locale? = null
    private val local = staticCompositionLocalOf { Locale.getDefault().toString() }

    val current: String
        @Composable get() = local.current

    @Composable
    infix fun provides(languageTag: String?): ProvidedValue<*> {
        if (systemDefault == null) {
            systemDefault = Locale.getDefault()
        }
        val resolved = languageTag?.let(Locale::of) ?: systemDefault!!
        Locale.setDefault(resolved)
        return local.provides(resolved.toString())
    }
}
