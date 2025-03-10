package eu.darken.sdmse.systemcleaner.core

import androidx.annotation.Keep
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import eu.darken.sdmse.common.areas.DataArea
import eu.darken.sdmse.common.debug.logging.Logging.Priority.WARN
import eu.darken.sdmse.common.debug.logging.log
import eu.darken.sdmse.common.debug.logging.logTag
import eu.darken.sdmse.common.files.APathLookup
import eu.darken.sdmse.common.files.Segments
import eu.darken.sdmse.common.files.containsSegments
import eu.darken.sdmse.common.files.isAncestorOf
import eu.darken.sdmse.common.files.isDirectory
import eu.darken.sdmse.common.files.isFile
import eu.darken.sdmse.common.files.startsWith
import eu.darken.sdmse.common.forensics.AreaInfo
import eu.darken.sdmse.common.forensics.FileForensics
import eu.darken.sdmse.common.forensics.identifyArea
import java.time.Duration

class BaseSieve @AssistedInject constructor(
    @Assisted private val config: Config,
    private val fileForensics: FileForensics,
) {

    @Keep
    enum class TargetType {
        FILE,
        DIRECTORY,
        ;
    }

    data class Result(
        val matches: Boolean,
        val areaInfo: AreaInfo? = null,
    )

    suspend fun match(subject: APathLookup<*>): Result {
        // Directory or file?
        config.targetTypes
            ?.takeIf { it.isNotEmpty() }
            ?.let { types ->
                if (subject.isFile && !types.contains(TargetType.FILE)) {
                    return Result(matches = false)
                } else if (subject.isDirectory && !types.contains(TargetType.DIRECTORY)) {
                    return Result(matches = false)
                }
            }

        config.isEmpty?.let {
            // Empty or not ?
            if (it && subject.size > 0 || !it && subject.size == 0L) return Result(matches = false)
        }

        config.maximumSize?.let {
            // Is our subject too large?
            if (subject.size > it) return Result(matches = false)
        }

        config.minimumSize?.let {
            // Maybe it's too small
            if (subject.size < it) return Result(matches = false)
        }

        config.maximumAge?.let {
            if (System.currentTimeMillis() - subject.modifiedAt.toEpochMilli() > it.toMillis()) {
                return Result(matches = false)
            }
        }

        config.minimumAge?.let {
            if (System.currentTimeMillis() - subject.modifiedAt.toEpochMilli() < it.toMillis()) {
                return Result(matches = false)
            }
        }

        config.pathContains
            ?.takeIf { it.isNotEmpty() }
            ?.let { pathContains ->
                val noMatch = pathContains.none {
                    subject.segments.containsSegments(
                        it,
                        allowPartial = true,
                        ignoreCase = config.ignoreCase
                    )
                }
                if (noMatch) return Result(matches = false)
            }

        config.nameContains
            ?.takeIf { it.isNotEmpty() }
            ?.let { namePartials ->
                if (namePartials.none { subject.name.contains(it, ignoreCase = config.ignoreCase) }) {
                    return Result(matches = false)
                }
            }

        config.nameSuffixes
            ?.takeIf { it.isNotEmpty() }
            ?.let { ends ->
                if (ends.none { subject.name.endsWith(it, ignoreCase = config.ignoreCase) }) {
                    return Result(matches = false)
                }
            }

        config.exclusions
            ?.takeIf { it.isNotEmpty() }
            ?.let { exclusions ->
                // Check what the path should not contain
                val match = exclusions.any {
                    subject.segments.containsSegments(
                        it.segments,
                        allowPartial = it.allowPartial,
                        ignoreCase = config.ignoreCase
                    )
                }
                if (match) return Result(matches = false)
            }

        config.regexes
            ?.takeIf { it.isNotEmpty() }
            ?.let { regexes ->
                if (regexes.none { it.matches(subject.path) }) return Result(matches = false)
            }

        val areaInfo = fileForensics.identifyArea(subject)
        if (areaInfo == null) {
            log(TAG, WARN) { "Couldn't identify area for $subject" }
            return Result(matches = false)
        }
        val subjectSegments = areaInfo.prefixFreePath

        config.areaTypes
            ?.takeIf { it.isNotEmpty() }
            ?.let { types ->
                if (!types.contains(areaInfo.type)) {
                    return Result(matches = false)
                }
            }

        config.pathAncestors
            ?.takeIf { it.isNotEmpty() }
            ?.let { basePaths ->
                // Check path starts with
                if (basePaths.none { it.isAncestorOf(subjectSegments, ignoreCase = config.ignoreCase) }) {
                    return Result(matches = false)
                }
            }

        config.pathPrefixes
            ?.takeIf { it.isNotEmpty() }
            ?.let { basePaths ->
                // Like basepath, but allows for partial matches
                if (basePaths.none { subjectSegments.startsWith(it, ignoreCase = config.ignoreCase) }) {
                    return Result(matches = false)
                }
            }

        return Result(
            matches = true,
            areaInfo = areaInfo
        )
    }

    data class Config(
        val maximumSize: Long? = null,
        val minimumSize: Long? = null,
        val maximumAge: Duration? = null,
        val minimumAge: Duration? = null,
        val targetTypes: Set<TargetType>? = null,
        val isEmpty: Boolean? = null,
        val areaTypes: Set<DataArea.Type>? = null,
        val pathAncestors: Set<Segments>? = null,
        val pathPrefixes: Set<Segments>? = null,
        val pathContains: Set<Segments>? = null,
        val regexes: Set<Regex>? = null,
        val exclusions: Set<Exclusion>? = null,
        val nameContains: Set<String>? = null,
        val nameSuffixes: Set<String>? = null,
        val ignoreCase: Boolean = true,
    )

    data class Exclusion(
        val segments: Segments,
        val allowPartial: Boolean = true,
    )

    @AssistedFactory
    interface Factory {
        fun create(config: Config): BaseSieve
    }

    companion object {
        private val TAG = logTag("SystemCleaner", "SystemCrawler", "BaseSieve")
    }
}