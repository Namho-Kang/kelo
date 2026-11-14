package chat.kelo.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * What this phone itself can share with the server (calendar, contacts, messages, dialer, notifications, call log, Health Connect):
 * not services to sign in to, one switch each. Null in [chat.kelo.app.AppServices] = this device has none and nothing is shown.
 */
interface PhoneDataSection {
    /** The rows (call it through the [Section] extension to leave out arguments). [openCalendar]: scroll to the calendar row and open its sheet (Google Calendar's "connect with the phone's calendar"); [onCalendarOpened] once it did. */
    @Composable
    fun Section(modifier: Modifier, openCalendar: Boolean, onCalendarOpened: () -> Unit)
}

/**
 * [PhoneDataSection.Section] with its usual defaults. The defaults are not on the interface method on purpose: a composable interface
 * method with default values makes the Compose compiler give an implementing class in another module an extra parameter, and the call
 * through the interface then fails with an AbstractMethodError when the Connectors tab opens.
 */
@Composable
fun PhoneDataSection.Section(modifier: Modifier = Modifier, openCalendar: Boolean = false, onCalendarOpened: () -> Unit = {}) =
    Section(modifier, openCalendar, onCalendarOpened)
