package io.github.obdosok.diapilot

/**
 * Every name the app shares with the rest of the phone, derived from the
 * applicationId instead of written as a literal.
 *
 * WHY IT MATTERS. Two builds of this source can be installed on one phone —
 * the public one and a private one with a different applicationId. Any name
 * that stayed a literal would collide: two apps declaring the same
 * FileProvider authority cannot both be installed at all, two apps using the
 * same broadcast action can receive each other's intents, and two apps
 * writing the same file name into a shared folder overwrite (through SAF) or
 * shadow (through MediaStore) each other's backups. Deriving every such name
 * from [APPLICATION_ID] makes each installed copy's names its own.
 *
 * The Kotlin package and the Gradle `namespace` are NOT the applicationId;
 * nothing here depends on them.
 */
object AppIdentity {
    const val APPLICATION_ID: String = BuildConfig.APPLICATION_ID

    /** Must match `${applicationId}.fileprovider` in the manifest. */
    const val FILE_PROVIDER_AUTHORITY: String = "$APPLICATION_ID.fileprovider"

    /** Meal-notification label action (explicit intent to LabelActionReceiver). */
    const val ACTION_LABEL: String = "$APPLICATION_ID.ACTION_LABEL"

    /** Doze-proof BLE stall check alarm (package-scoped broadcast). */
    const val ACTION_BLE_WAKEUP: String = "$APPLICATION_ID.BLE_WAKEUP"

    /**
     * Prefix of the daily automatic backups in the public Downloads folder
     * (and in a user-picked cloud folder). Carries the applicationId so the
     * rotation, the reuse lookup and the prune of one installed copy can never
     * match another copy's files.
     */
    fun autoBackupPrefix(applicationId: String = APPLICATION_ID): String =
        "$applicationId-auto-backup-"

    /** One rotating file per weekday: [dayOfWeek] is `Calendar.DAY_OF_WEEK`. */
    fun autoBackupName(dayOfWeek: Int, applicationId: String = APPLICATION_ID): String =
        "${autoBackupPrefix(applicationId)}$dayOfWeek.sqlite"

    /** The Settings "Export database" file; [stamp] is `yyyyMMdd-HHmm`. */
    fun manualExportName(stamp: String, applicationId: String = APPLICATION_ID): String =
        "$applicationId-backup-$stamp.sqlite"
}
