package com.example.diapilot.data

import android.content.Context
import android.util.AtomicFile
import com.diapilot.core.hybrid.HybridPersonModel
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Crash-safe local lifecycle for personalized v11 runtime artifacts.
 *
 * Model generations are immutable and addressed by their SHA-256. A single
 * AtomicFile pointer selects active/previous, so a process death can never
 * expose a half-written model. The bundled asset is always the final fallback.
 */
object HybridModelStore {
    private const val ASSET = "models/person_model_v11_runtime.json"
    private const val DIRECTORY = "hybrid-v11"
    private const val STATE_FILE = "state.json"

    data class Status(
        val activeSha256: String,
        val previousSha256: String?,
        val source: String,
        val personModelId: String,
        val modelVersion: String,
        val eligibleFrom: String,
        val eligibleTimezone: String,
        val trainedThrough: String?,
        val trainingDays: Int,
        val installedAt: String,
    )

    fun loadOrInstallBundled(context: Context): Status {
        val bundled = context.assets.open(ASSET).use(InputStream::readBytes)
        val bundledArtifact = parse(bundled)
        val current = readState(context)
        val currentArtifact =
            current?.let { readGeneration(context, it.activeSha256) }

        val selected = when {
            currentArtifact == null -> installBytes(
                context,
                bundled,
                source = "bundled",
                previous = null,
            )
            current.source.startsWith("bundled") &&
                current.activeSha256 != sha256(bundled) -> installBytes(
                    context,
                    bundled,
                    source = "bundled",
                    previous = current.activeSha256,
                )
            else -> current
        }
        val artifact = readGeneration(context, selected.activeSha256)
            ?: bundledArtifact
        // THE TUNING IS APPLIED AT INSTALL, NOT AT READ.
        //
        // `model()` is consulted on every forecast pass; applying the override
        // there would rebuild the model thousands of times a day and, worse,
        // let a settings change take effect mid-flight so two passes minutes
        // apart could run different models under the same artifact id. Here it
        // happens once, and `retune` re-enters this path explicitly.
        HybridShadowRegistry.install(
            PhysioTuning.apply(context, artifact.model),
            selected.activeSha256,
        )
        return selected
    }

    /**
     * Installs a trainer-approved runtime artifact. Gate evaluation belongs to
     * the trainer/lifecycle CLI; this boundary validates JSON before commit.
     */
    fun install(context: Context, input: InputStream, source: String): Status {
        require(source.isNotBlank()) { "model source must not be blank" }
        val bytes = input.readBytes()
        val incoming = parse(bytes)
        requireInsideFoodEra(incoming, FoodEraSettings.era(context))
        val current = readState(context)
        require(
            current == null ||
                current.personModelId == incoming.model.personModelId
        ) {
            "person_model_id mismatch: refusing another person's model"
        }
        val previous = current?.activeSha256
        val selected = installBytes(context, bytes, source, previous)
        HybridShadowRegistry.install(
            PhysioTuning.apply(context, incoming.model),
            selected.activeSha256,
        )
        return selected
    }

    fun rollback(context: Context): Status? {
        val current = readState(context) ?: return null
        val targetSha = current.previousSha256 ?: return null
        val target = readGeneration(context, targetSha) ?: return null
        val status = Status(
            activeSha256 = targetSha,
            previousSha256 = current.activeSha256,
            source = "rollback",
            personModelId = target.model.personModelId,
            modelVersion = target.model.modelVersion,
            eligibleFrom = target.eligibleFrom,
            eligibleTimezone = target.eligibleTimezone,
            trainedThrough = target.trainedThrough,
            trainingDays = target.trainingDays,
            installedAt = Instant.now().toString(),
        )
        writeState(context, status)
        HybridShadowRegistry.install(
            PhysioTuning.apply(context, target.model),
            status.activeSha256,
        )
        return status
    }

    fun status(context: Context): Status? = readState(context)

    /**
     * Re-installs the active generation so a changed [PhysioTuning] takes
     * effect. Called from the settings screen after any knob moves.
     *
     * It re-enters `loadOrInstallBundled` rather than patching the installed
     * instance, because that is the ONE path that knows which generation is
     * active — a second, shorter path here is how the two would drift apart.
     */
    fun retune(context: Context): Status = loadOrInstallBundled(context)

    /**
     * The active generation WITHOUT any [PhysioTuning] applied.
     *
     * The auto-fitter must search from the shipped model, not from whatever is
     * currently installed: fitting on top of an already-tuned model compounds
     * the two, and the answer would silently mean «on top of what you set
     * yesterday» rather than «this is the value».
     */
    fun untunedModel(context: Context): com.diapilot.core.hybrid.HybridPersonModel? =
        readState(context)?.let { readGeneration(context, it.activeSha256)?.model }

    private fun installBytes(
        context: Context,
        bytes: ByteArray,
        source: String,
        previous: String?,
    ): Status {
        val model = parse(bytes)
        val sha = sha256(bytes)
        val generation = generationFile(context, sha)
        if (!generation.exists() || sha256(generation.readBytes()) != sha) {
            atomicWrite(generation, bytes)
        }
        val status = Status(
            activeSha256 = sha,
            previousSha256 = previous?.takeUnless { it == sha },
            source = source,
            personModelId = model.model.personModelId,
            modelVersion = model.model.modelVersion,
            eligibleFrom = model.eligibleFrom,
            eligibleTimezone = model.eligibleTimezone,
            trainedThrough = model.trainedThrough,
            trainingDays = model.trainingDays,
            installedAt = Instant.now().toString(),
        )
        writeState(context, status)
        return status
    }

    private fun readGeneration(
        context: Context,
        sha: String,
    ): ParsedArtifact? = runCatching {
        val file = generationFile(context, sha)
        val bytes = file.readBytes()
        check(sha256(bytes) == sha) { "model generation SHA-256 mismatch" }
        parse(bytes)
    }.getOrNull()

    private data class ParsedArtifact(
        val model: HybridPersonModel,
        val eligibleFrom: String,
        val eligibleTimezone: String,
        val trainedThrough: String?,
        val trainingDays: Int,
    )

    /**
     * Every artifact, bundled or imported, must carry a well-formed training
     * window: `eligible_from` an ISO date, `eligible_timezone` a real zone,
     * `trained_through` (when present) an ISO date not before `eligible_from`
     * and not in the future, and `days` a count that fits inside that window
     * (0 when nothing was trained). A provenance that cannot say which days it
     * learned from cannot be checked against the food era, so it is refused.
     */
    private fun parse(bytes: ByteArray, today: LocalDate = LocalDate.now(ZoneOffset.UTC)): ParsedArtifact {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val provenance = root.getJSONObject("training_provenance")
        val eligibleFrom = provenance.getString("eligible_from")
        val eligibleTimezone = provenance.getString("eligible_timezone")
        val from = requireNotNull(runCatching { LocalDate.parse(eligibleFrom) }.getOrNull()) {
            "runtime artifact has a malformed eligible_from: $eligibleFrom"
        }
        requireNotNull(runCatching { ZoneId.of(eligibleTimezone) }.getOrNull()) {
            "runtime artifact uses an unknown training timezone: $eligibleTimezone"
        }
        val trainedThrough = provenance.optString("trained_through")
            .takeIf { it.isNotBlank() && it != "null" }
        val days = provenance.getInt("days")
        require(days >= 0) { "runtime artifact reports a negative training day count" }
        if (trainedThrough == null) {
            require(days == 0) { "runtime artifact reports training days without trained_through" }
        } else {
            val through = requireNotNull(runCatching { LocalDate.parse(trainedThrough) }.getOrNull()) {
                "runtime artifact has a malformed trained_through: $trainedThrough"
            }
            require(!through.isBefore(from)) { "runtime artifact training window ends before it starts" }
            // One day of slack: the window's own zone may already be a day ahead of UTC.
            require(!through.isAfter(today.plusDays(1))) { "runtime artifact was trained on future days" }
            require(days <= ChronoUnit.DAYS.between(from, through) + 1) {
                "runtime artifact reports more training days than its window holds"
            }
        }
        return ParsedArtifact(
            model = HybridPersonModelJson.fromJson(root),
            eligibleFrom = eligibleFrom,
            eligibleTimezone = eligibleTimezone,
            trainedThrough = trainedThrough,
            trainingDays = days,
        )
    }

    /**
     * An IMPORTED artifact must have been trained only on history inside the
     * user's food era: a training window that starts before the era start would
     * carry exactly the pre-era regime the era exists to keep out of learning.
     *
     * The bundled asset is exempt, and that is not a loophole: it is a
     * synthetic example person's prior shipped with the app, trained on none of
     * this user's history, so there is no era of this user it could overlap.
     */
    private fun requireInsideFoodEra(artifact: ParsedArtifact, era: com.diapilot.core.api.FoodEra) {
        val windowStartMs = LocalDate.parse(artifact.eligibleFrom)
            .atStartOfDay(ZoneId.of(artifact.eligibleTimezone)).toInstant().toEpochMilli()
        require(windowStartMs >= era.startMs) {
            "runtime artifact includes history before the food era " +
                "(${artifact.eligibleFrom} ${artifact.eligibleTimezone} < ${era.startDate} ${era.zone.id})"
        }
    }

    private fun generationFile(context: Context, sha: String): File =
        File(File(context.filesDir, "$DIRECTORY/generations"), "$sha.json")

    private fun stateFile(context: Context): File =
        File(context.filesDir, "$DIRECTORY/$STATE_FILE")

    private fun readState(context: Context): Status? = runCatching {
        val root = JSONObject(stateFile(context).readText(Charsets.UTF_8))
        Status(
            activeSha256 = root.getString("active_sha256"),
            previousSha256 = root.optString("previous_sha256")
                .takeIf(String::isNotBlank),
            source = root.getString("source"),
            personModelId = root.getString("person_model_id"),
            modelVersion = root.getString("model_version"),
            eligibleFrom = root.optString("eligible_from", ""),
            eligibleTimezone = root.optString("eligible_timezone", ""),
            trainedThrough = root.optString("trained_through")
                .takeIf { it.isNotBlank() && it != "null" },
            trainingDays = root.optInt("training_days", 0),
            installedAt = root.getString("installed_at"),
        )
    }.getOrNull()

    private fun writeState(context: Context, status: Status) {
        val root = JSONObject()
            .put("schema_version", 1)
            .put("active_sha256", status.activeSha256)
            .put("previous_sha256", status.previousSha256 ?: "")
            .put("source", status.source)
            .put("person_model_id", status.personModelId)
            .put("model_version", status.modelVersion)
            .put("eligible_from", status.eligibleFrom)
            .put("eligible_timezone", status.eligibleTimezone)
            .put("trained_through", status.trainedThrough ?: "")
            .put("training_days", status.trainingDays)
            .put("installed_at", status.installedAt)
        atomicWrite(
            stateFile(context),
            (root.toString(2) + "\n").toByteArray(Charsets.UTF_8),
        )
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.flush()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
