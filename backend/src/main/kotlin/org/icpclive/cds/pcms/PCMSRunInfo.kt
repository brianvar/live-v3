package org.icpclive.cds.pcms

import org.icpclive.cds.EventsLoader
import org.icpclive.cds.RunInfo

class PCMSRunInfo(
    override var isJudged: Boolean,
    override var result: String,
    override val problemId: Int,
    override val time: Long,
    override val teamId: Int
) : RunInfo {

    constructor(run: PCMSRunInfo): this(
        run.isJudged, run.result, run.problemId, run.time, run.teamId
    ) {
        lastUpdateTime = run.lastUpdateTime
    }

    override val isAccepted: Boolean
        get() = "AC" == result
    override val isAddingPenalty: Boolean
        get() = isJudged && !isAccepted && "CE" != result

    override val problem
        get() = EventsLoader.instance.contestData!!.problems[problemId]

    override val percentage: Double
        get() = 0.0
    override var id = 0
    override var lastUpdateTime: Long = 0
    override var isReallyUnknown = false
}