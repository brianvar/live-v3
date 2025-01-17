package org.icpclive.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.icpclive.api.*
import org.icpclive.data.DataBus
import org.icpclive.util.getLogger
import org.icpclive.util.intervalFlow
import kotlin.time.Duration.Companion.seconds


sealed class TeamAccent
class TeamRunAccent(val run: RunInfo) : TeamAccent()
class TeamScoreboardPlace(val rank: Int) : TeamAccent()
class ExternalScoreAddAccent(val score: Double) : TeamAccent()
object SocialEventAccent : TeamAccent()


private val RunInfo.isFirstSolvedRun get() = (result as? ICPCRunResult)?.isFirstToSolveRun == true
private val RunInfo.isAccepted get() = (result as? ICPCRunResult)?.isAccepted == true
private val RunInfo.isJudged get() = result != null

private fun RunInfo.coerceAtMost(other: RunInfo): RunInfo {
    if (interesting() <= other.interesting()) {
        return other
    }
    return this
}

private fun RunInfo.interesting() = when {
    isFirstSolvedRun -> 3
    isAccepted == true -> 2
    result != null -> 1
    else -> 0
}

private fun Double.takeIf(condition: Boolean?) = if (condition == true) this else 0.0
private fun TeamAccent.getScoreDelta(flowSettings: TeamSpotlightFlowSettings) = when (this) {
    is TeamScoreboardPlace -> flowSettings.rankScore(rank)
    is TeamRunAccent -> {
        when (val result = run.result) {
            is ICPCRunResult -> {
                flowSettings.firstToSolvedRunScore.takeIf(result.isFirstToSolveRun) +
                        flowSettings.acceptedRunScore.takeIf(result.isAccepted) +
                        flowSettings.judgedRunScore.takeIf(run.isFirstSolvedRun) +
                        flowSettings.notJudgedRunScore.takeIf(!run.isJudged)
            }

            is IOIRunResult -> {
                flowSettings.acceptedRunScore.takeIf(result.difference != 0.0) +
                        flowSettings.judgedRunScore.takeIf(run.isFirstSolvedRun) +
                        flowSettings.notJudgedRunScore.takeIf(!run.isJudged)
            }

            else -> 0.0
        }
    }

    is ExternalScoreAddAccent -> flowSettings.externalScoreScale * score
    is SocialEventAccent -> flowSettings.socialEventScore
}

@Serializable
class TeamState(val teamId: Int) : Comparable<TeamState> {
    var score = 0.0
        private set
    private var causedRun: RunInfo? = null
    val caused: KeyTeamCause
        get() = causedRun?.let { RunCause(it.id) } ?: ScoreSumCause

    fun addAccent(accent: TeamAccent, flowSettings: TeamSpotlightFlowSettings) {
        score += accent.getScoreDelta(flowSettings)
        if (accent is TeamRunAccent) {
            causedRun = causedRun?.coerceAtMost(accent.run) ?: accent.run
        }
    }

    override fun compareTo(other: TeamState): Int = when {
        other.score > score -> 1
        other.score < score -> -1
        else -> teamId.compareTo(other.teamId)
    }
}

class TeamSpotlightService(
    val settings: TeamSpotlightFlowSettings = TeamSpotlightFlowSettings(),
    private val teamInteresting: MutableStateFlow<List<CurrentTeamState>>? = null,
) {
    private val mutex = Mutex()
    private val queue = mutableSetOf<TeamState>()
    private fun getTeamInQueue(teamId: Int) =
        queue.find { it.teamId == teamId } ?: TeamState(teamId).also { queue += it }

    fun getFlow(): Flow<KeyTeam> {
        return flow {
            while (true) {
                val element = mutex.withLock {
                    queue.minOrNull()?.also { queue.remove(it) }
                }
                if (element == null) {
                    delay(1.seconds)
                    continue
                }
                emit(KeyTeam(element.teamId, element.caused))
                val teams = mutex.withLock { queue.map(::CurrentTeamState) }
                teamInteresting?.emit(teams)
            }
        }
    }

    private suspend fun TeamState.addAccent(accent: TeamAccent) {
        addAccent(accent, settings)
        teamInteresting?.emit(queue.map(::CurrentTeamState))
    }

    suspend fun run(
        info: StateFlow<ContestInfo>,
        runs: Flow<RunInfo>,
        scoreboard: Flow<Scoreboard>,
        // analyticsMessage: Flow<AnalyticsMessage>
        addScoreRequests: Flow<AddTeamScoreRequest>? = null,
    ) {
        val runIds = mutableSetOf<Int>()
        merge(
            intervalFlow(settings.scoreboardPushInterval).map { ScoreboardPushTrigger },
            runs.filter { !it.isHidden },
            addScoreRequests ?: emptyFlow(),
            DataBus.socialEvents.await(),
        ).collect { update ->
            when (update) {
                is RunInfo -> {
                    if (update.time + 60.seconds > info.value.currentContestTime) {
                        if (update.isJudged || update.id !in runIds) {
                            runIds += update.id
                            mutex.withLock {
                                getTeamInQueue(update.teamId).addAccent(TeamRunAccent(update))
                            }
                        }
                    }
                }

                is ScoreboardPushTrigger -> {
                    scoreboard.first().rows.filter { it.rank <= settings.scoreboardLowestRank }.forEach {
                        mutex.withLock {
                            getTeamInQueue(it.teamId).addAccent(TeamScoreboardPlace(it.rank))
                        }
                    }
                }

                is AddTeamScoreRequest -> {
                    mutex.withLock {
                        getTeamInQueue(update.teamId).addAccent(ExternalScoreAddAccent(update.score))
                    }
                }

                is SocialEvent -> {
                    update.teamIds.forEach { teamId ->
                        mutex.withLock {
                            getTeamInQueue(teamId).addAccent(SocialEventAccent)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private object ScoreboardPushTrigger

        val logger = getLogger(TeamSpotlightService::class)
    }
}
