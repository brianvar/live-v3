package org.icpclive.cds

import org.icpclive.cds.noop.NoopDataSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.icpclive.api.*
import org.icpclive.cds.adapters.*
import org.icpclive.cds.cats.CATSDataSource
import org.icpclive.cds.clics.ClicsDataSource
import org.icpclive.cds.codeforces.CFDataSource
import org.icpclive.cds.common.RunsBufferService
import org.icpclive.cds.ejudge.EjudgeDataSource
import org.icpclive.cds.krsu.KRSUDataSource
import org.icpclive.cds.pcms.PCMSDataSource
import org.icpclive.cds.yandex.YandexDataSource
import org.icpclive.util.completeOrThrow
import org.icpclive.util.getLogger
import org.icpclive.util.guessDatetimeFormat
import org.icpclive.util.loopFlow
import java.util.*
import kotlin.time.Duration

data class ContestParseResult(
    val contestInfo: ContestInfo,
    val runs: List<RunInfo>,
    val analyticsMessages: List<AnalyticsMessage>
)

interface ContestDataSource {
    suspend fun run(
        contestInfoDeferred: CompletableDeferred<StateFlow<ContestInfo>>,
        runsDeferred: CompletableDeferred<Flow<RunInfo>>,
        analyticsMessagesDeferred: CompletableDeferred<Flow<AnalyticsMessage>>
    )
}

interface RawContestDataSource : ContestDataSource {
    public val resultType: ContestResultType
        get() = ContestResultType.ICPC

    suspend fun loadOnce(): ContestParseResult
}

abstract class FullReloadContestDataSource(val interval: Duration) : RawContestDataSource {
    override suspend fun run(
        contestInfoDeferred: CompletableDeferred<StateFlow<ContestInfo>>,
        runsDeferred: CompletableDeferred<Flow<RunInfo>>,
        analyticsMessagesDeferred: CompletableDeferred<Flow<AnalyticsMessage>>
    ) {
        coroutineScope {
            val reloadFlow = loopFlow(
                interval,
                { getLogger(ContestDataSource::class).error("Failed to reload data, retrying", it) }
            ) {
                loadOnce()
            }.flowOn(Dispatchers.IO)
                .stateIn(this)
            launch { RunsBufferService(reloadFlow.map { it.runs }, runsDeferred).run() }
            analyticsMessagesDeferred.completeOrThrow(emptyFlow())
            contestInfoDeferred.completeOrThrow(reloadFlow.map { it.contestInfo }.stateIn(this))
        }
    }
}

fun getContestDataSource(
    properties: Properties,
    creds: Map<String, String> = emptyMap(),
    calculateFTS: Boolean,
    calculateDifference: Boolean,
    removeFrozenResults: Boolean,
    advancedPropertiesDeferred: CompletableDeferred<Flow<AdvancedProperties>>?,
) : ContestDataSource {
    val loader = when (val standingsType = properties.getProperty("standings.type")) {
        "CLICS" -> ClicsDataSource(properties, creds)
        "PCMS" -> PCMSDataSource(properties, creds)
        "CF" -> CFDataSource(properties, creds)
        "YANDEX" -> YandexDataSource(properties, creds)
        "EJUDGE" -> EjudgeDataSource(properties)
        "KRSU" -> KRSUDataSource(properties)
        "CATS" -> CATSDataSource(properties, creds)
        "NOOP" -> NoopDataSource()
        else -> throw IllegalArgumentException("Unknown standings.type $standingsType")
    }

    val adapters = listOfNotNull(
        properties.getProperty("emulation.speed")?.let {
            val emulationSpeed = it.toDouble()
            val emulationStartTime = guessDatetimeFormat(properties.getProperty("emulation.startTime"));
            { source: ContestDataSource -> EmulationAdapter(emulationStartTime, emulationSpeed, source as RawContestDataSource) }
        },
        advancedPropertiesDeferred?.let { { source : ContestDataSource -> AdvancedPropertiesAdapter(source, it) } },
        ::RemoveFrozenSubmissionsAdapter.takeIf { removeFrozenResults },
        ::ICPCFirstToSolveAdapter.takeIf { calculateFTS && loader.resultType == ContestResultType.ICPC },
        ::IOIFirstToSolveAdapter.takeIf { calculateFTS && loader.resultType == ContestResultType.IOI },
        ::DifferenceAdapter.takeIf { calculateDifference }
    )

    return adapters.fold(loader as ContestDataSource) { acc, function -> function(acc) }
}