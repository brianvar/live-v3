package org.icpclive.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.icpclive.DataBus
import org.icpclive.api.*
import org.icpclive.utils.*
import kotlin.time.Duration.Companion.seconds

private sealed class QueueProcessTrigger
private object Clean : QueueProcessTrigger()
private class Run(val run: RunInfo): QueueProcessTrigger()
private object Subscribe : QueueProcessTrigger()


class QueueService(val runsFlow: Flow<RunInfo>) {
    private val runs = mutableMapOf<Int, RunInfo>()
    private val seenRunsSet = mutableSetOf<Int>()

    private val resultFlow = MutableSharedFlow<QueueEvent>(
        extraBufferCapacity = 100000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val subscriberFlow = MutableStateFlow(0)

    init {
        DataBus.setQueueEvents(flow {
            var nothingSent = true
            resultFlow
                .onSubscription { subscriberFlow.update { it + 1 } }
                .collect {
                    val isSnapshot = it is QueueSnapshotEvent
                    if (nothingSent == isSnapshot) {
                        emit(it)
                        nothingSent = false
                    }
                }
        })
    }

    private val RunInfo.toOldAtTime get() = lastUpdateTime + if (isFirstSolvedRun) FIRST_TO_SOLVE_WAIT_TIME else WAIT_TIME

    private suspend fun removeRun(run: RunInfo) {
        runs.remove(run.id)
        resultFlow.emit(RemoveRunFromQueueEvent(run))
    }

    suspend fun run() {
        val removerFlowTrigger = tickerFlow(1.seconds).map { Clean }
        val runsFlowTrigger = runsFlow.map { Run(it) }
        val subscriberFlowTrigger = subscriberFlow.map { Subscribe }
        // it's important to have all side effects after merge, as part before merge will be executed concurrently
        merge(runsFlowTrigger, removerFlowTrigger, subscriberFlowTrigger).collect { event ->
            when (event) {
                is Clean -> {
                    val currentTime = DataBus.contestInfoUpdates.value.currentContestTimeMs
                    runs.values.filter { currentTime >= it.toOldAtTime }.forEach { removeRun(it) }
                }
                is Run -> {
                    val run = event.run
                    val currentTime = DataBus.contestInfoUpdates.value.currentContestTimeMs
                    logger.debug("Receive run $run")
                    if (run.toOldAtTime > currentTime) {
                        if (run.id !in seenRunsSet || (run.isFirstSolvedRun && run.id !in runs)) {
                            runs[run.id] = run
                            seenRunsSet.add(run.id)
                            resultFlow.emit(AddRunToQueueEvent(run))
                        } else if (run.id in runs) {
                            runs[run.id] = run
                            resultFlow.emit(ModifyRunInQueueEvent(run))
                        }
                    } else {
                        logger.debug("Ignore run ${run.id} in queue as too old (currentTime = ${currentTime}, run.time = ${run.lastUpdateTime}, diff = ${currentTime - run.lastUpdateTime}")
                    }
                }
                is Subscribe -> {
                    resultFlow.emit(QueueSnapshotEvent(runs.values.sortedBy { it.id }))
                }
            }
            while (runs.size >= MAX_QUEUE_SIZE) {
                runs.values.asSequence()
                    .filterNot { it.isFirstSolvedRun }
                    .minByOrNull { it.id }
                    ?.run { removeRun(this) }
            }
        }
    }

    companion object {
        val logger = getLogger(QueueService::class)

        private const val WAIT_TIME = 60000L
        private const val FIRST_TO_SOLVE_WAIT_TIME = 120000L
        private const val MAX_QUEUE_SIZE = 15
    }
}