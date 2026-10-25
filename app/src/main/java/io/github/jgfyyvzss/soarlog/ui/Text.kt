package io.github.jgfyyvzss.soarlog.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.Role
import java.time.LocalDate

/** The string resource for each message key. Exhaustive, so a key without a string fails to compile. */
@StringRes
fun MsgKey.res(): Int = when (this) {
    MsgKey.LOG_NO_POSITIVE -> R.string.msg_log_no_positive
    MsgKey.LOG_NO_USED -> R.string.msg_log_no_used
    MsgKey.FLIGHT_ON -> R.string.msg_flight_on
    MsgKey.DATE_REQUIRED -> R.string.msg_date_required
    MsgKey.DURATION_FORMAT -> R.string.msg_duration_format
    MsgKey.DURATION_RANGE -> R.string.msg_duration_range
    MsgKey.GLIDER_REQUIRED -> R.string.msg_glider_required
    MsgKey.AIRFIELD_REQUIRED -> R.string.msg_airfield_required
    MsgKey.CLUB_REQUIRED -> R.string.msg_club_required
    MsgKey.LAUNCH_TYPE_REQUIRED -> R.string.msg_launch_type_required
    MsgKey.PIC_REQUIRED -> R.string.msg_pic_required
    MsgKey.PIC_OTHER_REQUIRED -> R.string.msg_pic_other_required
    MsgKey.TIME_FORMAT -> R.string.msg_time_format
    MsgKey.LANDING_AFTER_LAUNCH -> R.string.msg_landing_after_launch
    MsgKey.LANDING_AIRFIELD_REQUIRED -> R.string.msg_landing_airfield_required
    MsgKey.LANDING_PLACE_REQUIRED -> R.string.msg_landing_place_required
    MsgKey.TIMES_DISAGREE -> R.string.msg_times_disagree
    MsgKey.REGISTRATION_REQUIRED -> R.string.msg_registration_required
    MsgKey.GLIDER_EXISTS -> R.string.msg_glider_exists
    MsgKey.GLIDER_GONE -> R.string.msg_glider_gone
    MsgKey.LOGBOOK_HOURS_AND_DATE -> R.string.msg_logbook_hours_and_date
    MsgKey.FLIGHTS_NEGATIVE -> R.string.msg_flights_negative
    MsgKey.AIRFIELD_NAME_REQUIRED -> R.string.msg_airfield_name_required
    MsgKey.COORDINATES_BOTH -> R.string.msg_coordinates_both
    MsgKey.LATITUDE_RANGE -> R.string.msg_latitude_range
    MsgKey.LONGITUDE_RANGE -> R.string.msg_longitude_range
    MsgKey.RADIUS_POSITIVE -> R.string.msg_radius_positive
    MsgKey.AIRFIELD_EXISTS -> R.string.msg_airfield_exists
    MsgKey.CLUB_NAME_REQUIRED -> R.string.msg_club_name_required
    MsgKey.CLUB_EXISTS -> R.string.msg_club_exists
    MsgKey.LAUNCH_TYPE_NAME_REQUIRED -> R.string.msg_launch_type_name_required
    MsgKey.LAUNCH_TYPE_EXISTS -> R.string.msg_launch_type_exists
    MsgKey.BALANCES_NEGATIVE -> R.string.msg_balances_negative
    MsgKey.CSV_ERROR_FOUND -> R.string.msg_csv_error_found
    MsgKey.CSV_ERROR_COLUMN -> R.string.msg_csv_error_column
    MsgKey.CSV_ERROR_ROW -> R.string.msg_csv_error_row
    MsgKey.CSV_BLANK -> R.string.msg_csv_blank
    MsgKey.CSV_EXPECT_HEADER -> R.string.msg_csv_expect_header
    MsgKey.CSV_EXPECT_COLUMN -> R.string.msg_csv_expect_column
    MsgKey.CSV_EXPECT_POSITIVE -> R.string.msg_csv_expect_positive
    MsgKey.CSV_EXPECT_WHOLE -> R.string.msg_csv_expect_whole
    MsgKey.CSV_EXPECT_LOG_NO_FREE -> R.string.msg_csv_expect_log_no_free
    MsgKey.CSV_EXPECT_LOG_NO_UNIQUE -> R.string.msg_csv_expect_log_no_unique
    MsgKey.CSV_EXPECT_DATE -> R.string.msg_csv_expect_date
    MsgKey.CSV_EXPECT_DURATION -> R.string.msg_csv_expect_duration
    MsgKey.CSV_EXPECT_DURATION_RANGE -> R.string.msg_csv_expect_duration_range
    MsgKey.CSV_EXPECT_REGISTRATION -> R.string.msg_csv_expect_registration
    MsgKey.CSV_EXPECT_AIRFIELD -> R.string.msg_csv_expect_airfield
    MsgKey.CSV_EXPECT_ROLE -> R.string.msg_csv_expect_role
    MsgKey.CSV_EXPECT_LAUNCH_TYPE -> R.string.msg_csv_expect_launch_type
    MsgKey.CSV_EXPECT_PIC -> R.string.msg_csv_expect_pic
    MsgKey.CSV_EXPECT_TIME -> R.string.msg_csv_expect_time
    MsgKey.CSV_EXPECT_AFTER_LAUNCH -> R.string.msg_csv_expect_after_launch
    MsgKey.CSV_OVERWRITE_DIFFERS -> R.string.msg_csv_overwrite_differs
    MsgKey.CSV_EXPECT_ONE_LANDING -> R.string.msg_csv_expect_one_landing
    MsgKey.CSV_EXPECT_FLIGHT_ROW -> R.string.msg_csv_expect_flight_row
    MsgKey.CSV_EXPECT_GLIDER_TYPE -> R.string.msg_csv_expect_glider_type
    MsgKey.CSV_EXPECT_LATITUDE -> R.string.msg_csv_expect_latitude
    MsgKey.CSV_EXPECT_LONGITUDE -> R.string.msg_csv_expect_longitude
    MsgKey.CSV_EXPECT_BOTH_COORDINATES -> R.string.msg_csv_expect_both_coordinates
    MsgKey.CSV_EXPECT_YES_NO -> R.string.msg_csv_expect_yes_no
    MsgKey.CSV_UNUSED_COLUMN -> R.string.msg_csv_unused_column
    MsgKey.CSV_SKIP_GLIDER_EXISTS -> R.string.msg_csv_skip_glider_exists
    MsgKey.CSV_SKIP_AIRFIELD_EXISTS -> R.string.msg_csv_skip_airfield_exists
    MsgKey.CSV_SKIP_REPEAT -> R.string.msg_csv_skip_repeat
    MsgKey.BACKUP_NOT_JSON -> R.string.msg_backup_not_json
    MsgKey.BACKUP_NOT_BACKUP -> R.string.msg_backup_not_backup
    MsgKey.BACKUP_NO_VERSION -> R.string.msg_backup_no_version
    MsgKey.BACKUP_NEWER -> R.string.msg_backup_newer
    MsgKey.BACKUP_UNKNOWN_VERSION -> R.string.msg_backup_unknown_version
    MsgKey.BACKUP_NO_UPGRADE -> R.string.msg_backup_no_upgrade
    MsgKey.BACKUP_DAMAGED -> R.string.msg_backup_damaged
    MsgKey.BACKUP_NEEDS_EMPTY -> R.string.msg_backup_needs_empty
    MsgKey.BACKUP_FILE_UNREADABLE -> R.string.msg_backup_file_unreadable
    MsgKey.BACKUP_FOLDER_REFUSED -> R.string.msg_backup_folder_refused
    MsgKey.BACKUP_WRITE_FAILED -> R.string.msg_backup_write_failed
    MsgKey.BACKUP_FAILED -> R.string.msg_backup_failed
    MsgKey.BK_TABLE_LAUNCH_TYPES -> R.string.msg_bk_table_launch_types
    MsgKey.BK_TABLE_CLUBS -> R.string.msg_bk_table_clubs
    MsgKey.BK_TABLE_AIRFIELDS -> R.string.msg_bk_table_airfields
    MsgKey.BK_TABLE_GLIDERS -> R.string.msg_bk_table_gliders
    MsgKey.BK_TABLE_FLIGHTS -> R.string.msg_bk_table_flights
    MsgKey.BK_TABLE_WITNESS_SIGNOFFS -> R.string.msg_bk_table_witness_signoffs
    MsgKey.BK_WITNESS -> R.string.msg_bk_witness
    MsgKey.BK_WITNESS_NO_CONSENT -> R.string.msg_bk_witness_no_consent
    MsgKey.BK_WITNESS_DANGLING -> R.string.msg_bk_witness_dangling
    MsgKey.BK_PHOTO_NOT_BASE64 -> R.string.msg_bk_photo_not_base64
    MsgKey.BK_PHOTO_NOT_JPEG -> R.string.msg_bk_photo_not_jpeg
    MsgKey.BK_PHOTO_TOO_LARGE -> R.string.msg_bk_photo_too_large
    MsgKey.BK_PHOTO_HASH -> R.string.msg_bk_photo_hash
    MsgKey.BK_TABLE_IMPORT_BATCHES -> R.string.msg_bk_table_import_batches
    MsgKey.BK_LAUNCH_TYPE -> R.string.msg_bk_launch_type
    MsgKey.BK_CLUB -> R.string.msg_bk_club
    MsgKey.BK_AIRFIELD -> R.string.msg_bk_airfield
    MsgKey.BK_GLIDER -> R.string.msg_bk_glider
    MsgKey.BK_FLIGHT -> R.string.msg_bk_flight
    MsgKey.BK_BALANCE -> R.string.msg_bk_balance
    MsgKey.BK_BATCH -> R.string.msg_bk_batch
    MsgKey.BK_DUPLICATE_ID -> R.string.msg_bk_duplicate_id
    MsgKey.BK_NO_TIME -> R.string.msg_bk_no_time
    MsgKey.BK_BAD_TIME -> R.string.msg_bk_bad_time
    MsgKey.BK_NO_NAME -> R.string.msg_bk_no_name
    MsgKey.BK_NO_REGISTRATION -> R.string.msg_bk_no_registration
    MsgKey.BK_MISSING_CLUB -> R.string.msg_bk_missing_club
    MsgKey.BK_DEFAULT_CLUB -> R.string.msg_bk_default_club
    MsgKey.BK_MISSING_LAUNCH_TYPE -> R.string.msg_bk_missing_launch_type
    MsgKey.BK_ONE_COORDINATE -> R.string.msg_bk_one_coordinate
    MsgKey.BK_LATITUDE_RANGE -> R.string.msg_bk_latitude_range
    MsgKey.BK_LONGITUDE_RANGE -> R.string.msg_bk_longitude_range
    MsgKey.BK_BAD_LOGBOOK_DATE -> R.string.msg_bk_bad_logbook_date
    MsgKey.BK_LOG_NO_POSITIVE -> R.string.msg_bk_log_no_positive
    MsgKey.BK_BAD_DATE -> R.string.msg_bk_bad_date
    MsgKey.BK_DURATION_RANGE -> R.string.msg_bk_duration_range
    MsgKey.BK_MISSING_GLIDER -> R.string.msg_bk_missing_glider
    MsgKey.BK_MISSING_AIRFIELD -> R.string.msg_bk_missing_airfield
    MsgKey.BK_MISSING_LANDING_AIRFIELD -> R.string.msg_bk_missing_landing_airfield
    MsgKey.BK_BOTH_LANDING -> R.string.msg_bk_both_landing
    MsgKey.BK_UNKNOWN_ROLE -> R.string.msg_bk_unknown_role
    MsgKey.BK_UNKNOWN_SOURCE -> R.string.msg_bk_unknown_source
    MsgKey.BK_NO_PIC -> R.string.msg_bk_no_pic
    MsgKey.BK_BAD_LAUNCH_TIME -> R.string.msg_bk_bad_launch_time
    MsgKey.BK_BAD_LANDING_TIME -> R.string.msg_bk_bad_landing_time
    MsgKey.BK_LANDING_BEFORE_LAUNCH -> R.string.msg_bk_landing_before_launch
    MsgKey.BK_BALANCE_ROLE -> R.string.msg_bk_balance_role
    MsgKey.BK_BALANCE_NEGATIVE -> R.string.msg_bk_balance_negative
    MsgKey.BK_BALANCE_DATE -> R.string.msg_bk_balance_date
    MsgKey.BK_BALANCE_DUPLICATE -> R.string.msg_bk_balance_duplicate
    MsgKey.BK_LOG_NO_COLLISION -> R.string.msg_bk_log_no_collision
    MsgKey.BK_DUPLICATE_KEY -> R.string.msg_bk_duplicate_key
    MsgKey.WEGLIDE_STATUS -> R.string.msg_weglide_status
    MsgKey.WEGLIDE_NO_HOST -> R.string.msg_weglide_no_host
    MsgKey.WEGLIDE_UNREACHABLE -> R.string.msg_weglide_unreachable
    MsgKey.WEGLIDE_NO_LIST -> R.string.msg_weglide_no_list
    MsgKey.AIRCRAFT_SAVE_FAILED -> R.string.msg_aircraft_save_failed
    MsgKey.IGC_NO_DATE -> R.string.msg_igc_no_date
    MsgKey.IGC_NO_FIXES -> R.string.msg_igc_no_fixes
    MsgKey.IGC_NO_FLIGHT -> R.string.msg_igc_no_flight
    MsgKey.IGC_UNREADABLE -> R.string.msg_igc_unreadable
    MsgKey.LXNAV_CONNECT_FAILED -> R.string.msg_lxnav_connect_failed
    MsgKey.LXNAV_NO_LOGBOOK -> R.string.msg_lxnav_no_logbook
    MsgKey.LXNAV_GAVE_UP -> R.string.msg_lxnav_gave_up
    MsgKey.LXNAV_SIZE_MISMATCH -> R.string.msg_lxnav_size_mismatch
    MsgKey.PDF_TEMPLATE_UNREADABLE -> R.string.msg_pdf_template_unreadable
    MsgKey.PDF_TEMPLATE_NEWER -> R.string.msg_pdf_template_newer
    MsgKey.PDF_TEMPLATE_PROBLEM -> R.string.msg_pdf_template_problem
    MsgKey.WITNESS_NAME_REQUIRED -> R.string.msg_witness_name_required
    MsgKey.WITNESS_NO_FLIGHTS -> R.string.msg_witness_no_flights
    MsgKey.WITNESS_IS_PILOT -> R.string.msg_witness_is_pilot
    MsgKey.WITNESS_NOT_PIC -> R.string.msg_witness_not_pic
    MsgKey.WITNESS_PHOTO_REQUIRED -> R.string.msg_witness_photo_required
}

/** A message in the pilot's language. Arguments that are messages, roles or dates are shown in that language too. */
fun Msg.text(context: Context): String = context.getString(key.res(), *shownArgs(args, context))

/** Message, role and date arguments shown in the pilot's language; anything else as it is. */
private fun shownArgs(args: List<Any?>, context: Context): Array<Any> = args.map { a ->
    when (a) {
        is Msg -> a.text(context)
        is Role -> context.getString(a.labelRes())
        is LocalDate -> a.shortText()
        null -> ""
        else -> a
    }
}.toTypedArray()

@Composable
fun Msg.text(): String {
    LocalConfiguration.current // read so the text follows a language change
    return text(LocalContext.current)
}

/** Text a view model wants shown: a string resource with arguments, a [Msg] from the plain-Kotlin code, or as-is. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Message(val msg: Msg) : UiText
    data class Plain(val text: String) : UiText

    fun text(context: Context): String = when (this) {
        is Res -> context.getString(id, *shownArgs(args, context))
        is Message -> msg.text(context)
        is Plain -> text
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

@Composable
fun UiText.text(): String {
    LocalConfiguration.current
    return text(LocalContext.current)
}

@StringRes
fun Role.labelRes(): Int = when (this) {
    Role.PIC -> R.string.role_pic
    Role.STUDENT -> R.string.role_student
    Role.INSTRUCTOR -> R.string.role_instructor
    Role.COACH -> R.string.role_coach
    Role.MUTUAL -> R.string.role_mutual
}

@Composable
fun Role.label(): String = stringResource(labelRes())

/**
 * A launch type's name for display. The four seeded types show in the pilot's language until the pilot renames
 * them; anything the pilot typed is shown as typed. The stored name, used in CSV and backups, never changes.
 */
@Composable
fun launchTypeName(id: String, storedName: String): String {
    val seeded = Keys.seededLaunchTypes.firstOrNull { Keys.seededLaunchTypeId(it) == id && it == storedName }
    return when (seeded) {
        "Aero" -> stringResource(R.string.launch_type_aero)
        "Winch" -> stringResource(R.string.launch_type_winch)
        "Self" -> stringResource(R.string.launch_type_self)
        "Auto" -> stringResource(R.string.launch_type_auto)
        else -> storedName
    }
}