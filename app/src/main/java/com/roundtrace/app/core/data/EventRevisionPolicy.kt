package com.roundtrace.app.core.data

import com.roundtrace.app.core.model.MatchEvent

/**
 * 事件修订策略（TASKS.md T26 / ARCHITECTURE §9.3-12，**纯逻辑**，无任何 Android/Room 依赖）。
 *
 * 规则（缺一不可）：
 * 1. 必须**同一对局**（不可跨对局修订）；
 * 2. 必须**同 EventType**（因此 `QUEST_STARTED` 不可能被 `MISSION_RESOLVED` 替代）；
 * 3. 目标事件必须 `retracted == false`（不可修订已被修订/撤销的事件，保证审计链是单链）；
 * 4. 目标与替代事件的任务序号必须一致（不可把第 2 任务的事件替换成第 4 任务的）；
 * 5. 不可自我替代（`target.eventId == replacement.eventId`）；
 * 6. 替代事件若已声明 `supersedesId`，必须指向 target（防止一次修订挂两个目标）。
 *
 * 仓储层（M2/T35）在事务里先调用 [check]，不通过则返回
 * `RepositoryError.INVALID_SUPERSEDE_TARGET`，不做任何写入。
 */
object EventRevisionPolicy {

    /** 校验结果。 */
    data class RevisionCheck(
        val allowed: Boolean,
        val failure: RevisionFailure? = null
    ) {
        /** 失败原因的可读说明（用于日志，不用于 UI 文案）。 */
        val reasonOrEmpty: String
            get() = failure?.description ?: ""
    }

    /** 修订失败原因。 */
    enum class RevisionFailure(val description: String) {
        /** 跨对局修订 */
        NOT_SAME_MATCH("不能跨对局修订事件"),

        /** 事件类型不同 */
        TYPE_MISMATCH("替代事件的事件类型必须与被替代事件一致"),

        /** 目标已被撤销/已修订 */
        TARGET_RETRACTED("目标事件已失效（retracted=true），不能再被修订"),

        /** 目标与替代事件任务序号不同 */
        QUEST_MISMATCH("替代事件必须与被替代事件属于同一任务"),

        /** 自我替代 */
        SELF_SUPERSEDE("不能用事件替代它自己"),

        /** supersedesId 指向了别的目标 */
        SUPERSEDE_TARGET_MISMATCH("替代事件的 supersedesId 必须指向被替代事件")
    }

    /**
     * 判断能否用 [replacement] 替代 [target]。
     *
     * @param sameMatch 两个事件是否属于同一对局（由调用方从仓储上下文提供）
     */
    fun check(
        target: MatchEvent,
        replacement: MatchEvent,
        sameMatch: Boolean
    ): RevisionCheck {
        if (!sameMatch) return RevisionCheck(false, RevisionFailure.NOT_SAME_MATCH)
        if (target.eventId == replacement.eventId) {
            return RevisionCheck(false, RevisionFailure.SELF_SUPERSEDE)
        }
        if (target.retracted) return RevisionCheck(false, RevisionFailure.TARGET_RETRACTED)
        if (target.type != replacement.type) {
            return RevisionCheck(false, RevisionFailure.TYPE_MISMATCH)
        }
        if (target.questNo != replacement.questNo) {
            return RevisionCheck(false, RevisionFailure.QUEST_MISMATCH)
        }
        val declaredSupersede = replacement.supersedesId
        if (declaredSupersede != null && declaredSupersede != target.eventId) {
            return RevisionCheck(false, RevisionFailure.SUPERSEDE_TARGET_MISMATCH)
        }
        return RevisionCheck(true, null)
    }

    /** 便捷布尔入口：能否替代。 */
    fun canSupersede(
        target: MatchEvent,
        replacement: MatchEvent,
        sameMatch: Boolean
    ): Boolean = check(target, replacement, sameMatch).allowed

    /** 递归判定：目标链上是否已出现过某个事件（防止 A→B→A 的循环修订）。 */
    fun isInSupersedeChain(
        target: MatchEvent,
        history: List<MatchEvent>,
        maxDepth: Int = 32
    ): Boolean {
        var current: MatchEvent? = target
        var depth = 0
        while (current != null && depth < maxDepth) {
            if (!history.contains(current)) return false
            current = history.firstOrNull { it.eventId == current?.supersedesId }
            depth += 1
        }
        return true
    }
}
