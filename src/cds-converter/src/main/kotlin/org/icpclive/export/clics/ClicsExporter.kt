import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import org.icpclive.api.*

import org.icpclive.cds.clics.api.*
import org.icpclive.util.defaultJsonSettings
import org.icpclive.util.intervalFlow
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.minutes

typealias EventProducer = (String) -> Event

object ClicsExporter  {
    private val judgementOk = JudgementType("AC", "ok", true, false)
    private val judgementWrongWithPenalty = JudgementType("RJ", "rejected", false, true)
    private val judgementWrongWithOutPenalty = JudgementType("CE", "compilation-error", false, false)
    private val unknownLanguage = Language(
        "unknown",
        "unknown",
        false,
        emptyList()
    )
    private fun <T> updateEvent(id: String, data: T, block : (String, String, T?) -> Event) : EventProducer = { block(id, it, data) }
    private fun <T> updateEvent(data: T, block : (String, T?) -> Event) : EventProducer = { block(it, data) }


    private suspend fun awaitContest() { }

    private fun contestFlow(info: StateFlow<ContestInfo>) = info.map {
        Contest(
            id = "contest",
            start_time = it.startTime.takeIf { it != kotlinx.datetime.Instant.fromEpochSeconds(0) },
            name = it.name,
            formal_name = it.name,
            duration = it.contestLength,
            scoreboard_freeze_duration = it.freezeTime,
            countdown_pause_time = it.holdBeforeStartTime,
            penalty_time = it.penaltyPerWrongAttempt,
            scoreboard_type = "pass-fail"
        )
    }.distinctUntilChanged().map { updateEvent(it, Event::ContestEventNamedNonWithSpec) }

    private fun judgementTypesFlow() = flow {
        awaitContest()
        for (type in listOf(judgementOk, judgementWrongWithPenalty, judgementWrongWithOutPenalty)) {
            emit(type)
        }
    }.map { updateEvent(it.id, it, Event::JudgementTypeEvent) }

    private fun languagesFlow() = flow {
        awaitContest()
        for (type in listOf(unknownLanguage)) {
            emit(type)
        }
    }.map { updateEvent(it.id, it, Event::LanguageEvent) }


    private suspend fun <E, K, T> FlowCollector<E>.diff(
        old: MutableMap<K, T>,
        new: List<T>,
        id: T.() -> K,
        onChange: suspend FlowCollector<E>.(T) -> Unit,
        onRemove: suspend FlowCollector<E>.(K) -> Unit,
    ) {
        for (n in new) {
            if (old[n.id()] != n) {
                onChange(n)
                old[n.id()] = n
            }
        }
        val values = new.map { it.id() }.toSet()
        for (k in old.keys) {
            if (k !in values) {
                onRemove(k)
            }
        }
    }

    private fun problemsFlow(info: StateFlow<ContestInfo>) = flow {
        awaitContest()
        val current = mutableMapOf<String, ProblemInfo>()
        info.collect {
            diff(
                current,
                it.problems,
                ProblemInfo::cdsId,
                {
                    emit(
                        updateEvent(it.cdsId, Problem(
                        id = it.cdsId,
                        ordinal = it.ordinal,
                        label = it.letter,
                        name = it.name,
                        rgb = it.color,
                        test_data_count = 1,
                    ), Event::ProblemEvent))
                },
                { emit(updateEvent(it, null, Event::ProblemEvent)) }
            )
        }
    }

    private fun teamsFlow(info: StateFlow<ContestInfo>) = flow {
        awaitContest()
        val current = mutableMapOf<String, TeamInfo>()
        val groups = mutableSetOf<String>()
        info.collect {
            diff(
                current,
                it.teams,
                TeamInfo::contestSystemId,
                {
                    it.groups.forEach { group ->
                        if (group !in groups) {
                            groups.add(group)
                            emit(updateEvent(group, Group(id = group, name = group), Event::GroupsEvent))
                        }
                    }
                    emit(
                        updateEvent(
                            it.contestSystemId,
                            Team(
                                id = it.contestSystemId,
                                name = it.name,
                                hidden = it.isHidden,
                                group_ids = it.groups
                            ),
                            Event::TeamEvent
                        )
                    )
                },
                { emit(updateEvent(it, null, Event::TeamEvent)) }
            )
        }
    }

    private fun stateFlow(info: StateFlow<ContestInfo>) = info.map {
        when (it.status) {
            ContestStatus.BEFORE -> State(ended = null, frozen = null, started = null, unfrozen = null, finalized = null, end_of_updates = null)
            ContestStatus.RUNNING -> State(ended = null, frozen = if (it.currentContestTime >= it.freezeTime) it.startTime + it.freezeTime else null, started = it.startTime, unfrozen = null, finalized = null, end_of_updates = null)
            ContestStatus.OVER -> State(ended = it.startTime + it.contestLength, frozen = it.startTime + it.freezeTime, started = it.startTime, unfrozen = null, finalized = null, end_of_updates = null)
        }
    }.distinctUntilChanged().map { updateEvent(it, Event::StateEvent) }

    private fun runsFlow(info: StateFlow<ContestInfo>, runs: Flow<RunInfo>) = flow {
        val submissionsCreated = mutableSetOf<Int>()
        runs.collect {run ->
            if (run.id !in submissionsCreated) {
                submissionsCreated.add(run.id)
                emit(updateEvent(
                    run.id.toString(),
                    Submission(
                        id = run.id.toString(),
                        language_id = unknownLanguage.id,
                        problem_id = info.mapNotNull { it.problems.getOrNull(run.problemId) }.first().cdsId,
                        team_id = info.mapNotNull { it.teams.getOrNull(run.teamId) }.first().contestSystemId,
                        time = info.value.startTime + run.time,
                        contest_time = run.time,
                    ),
                    Event::SubmissionEvent
                ))
            }
            val result = run.result
            if (result is ICPCRunResult) {
                emit(updateEvent(
                    run.id.toString(),
                    Judgement(
                        id = run.id.toString(),
                        submission_id = run.id.toString(),
                        judgement_type_id = when {
                            result.isAccepted -> judgementOk
                            result.isAddingPenalty -> judgementWrongWithPenalty
                            else -> judgementWrongWithOutPenalty
                        }.id,
                        start_time = info.value.startTime + run.time,
                        start_contest_time = run.time,
                        end_time = info.value.startTime + run.time,
                        end_contest_time = run.time
                    ),
                    Event::JudgementEvent
                ))
            }
        }
    }


    private suspend fun FlowCollector<Event>.generateEventFeed(info: StateFlow<ContestInfo>, runs: Flow<RunInfo>) {
        runs.let { }
        var eventCounter = 1
        merge(
            contestFlow(info),
            judgementTypesFlow(),
            languagesFlow(),
            problemsFlow(info),
            teamsFlow(info),
            stateFlow(info),
            runsFlow(info, runs)
        ).collect {
            emit(it("live-cds-${eventCounter++}"))
        }
    }

    fun Route.setUp(scope: CoroutineScope, contestInfo: CompletableDeferred<StateFlow<ContestInfo>>, runs: CompletableDeferred<Flow<RunInfo>>) {
        val eventFeed = flow {
            generateEventFeed(contestInfo.await(), runs.await())
        }.shareIn(scope, SharingStarted.Eagerly, replay = Int.MAX_VALUE)
        get("/event-feed") {
            val json = defaultJsonSettings()
            call.respondBytesWriter {
                merge(eventFeed.map { json.encodeToString(it) }, intervalFlow(2.minutes).map { "" }).collect {
                    writeFully(ByteBuffer.wrap("$it\n".toByteArray()))
                    flush()
                }
            }
        }
    }
}