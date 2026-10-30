package com.roundtrace.app.core.export

import com.roundtrace.app.core.data.MatchBundle
import com.roundtrace.app.core.model.EventVisibility
import com.roundtrace.app.core.model.MatchEvent
import com.roundtrace.app.core.model.PrivateNoteAdded
import com.roundtrace.app.core.model.RevealState

/**
 * 导出脱敏（TASKS.md T60② / PRD FR-09）。
 *
 * 三条硬规则：
 * 1. **默认不外泄**：`includeSecrets = false` 时，`roles` 只保留
 *    `revealState == REVEALED` 的条目——未揭晓的秘密身份一个都不出去；
 * 2. `includePrivateNotes = false` 时，剔除 `PRIVATE_NOTE_ADDED` 与任何
 *    `visibility == PRIVATE_OBSERVER` 的事件（私人笔记属于「只有我记得的东西」）；
 * 3. **脱敏是「删」不是「遮」**：不生成 `"roleId": "***"` 之类的占位，避免导入方
 *    把占位当成事实。没有就干脆没有。
 *
 * 只读纯函数：不修改入参，返回 `copy`。
 */
object VisibilityFilter {

    /** 按 [options] 产出可安全导出的快照。 */
    fun apply(bundle: MatchBundle, options: ExportOptions): MatchBundle {
        val roles = if (options.includeSecrets) {
            bundle.roleAssignments
        } else {
            bundle.roleAssignments.filterValues { it.revealState == RevealState.REVEALED }
        }

        val events = if (options.includePrivateNotes) {
            bundle.events
        } else {
            bundle.events.filterNot { isPrivate(it.visibility, it) }
        }

        return bundle.copy(roleAssignments = roles, events = events)
    }

    /** 该事件是否属于「私人/秘密」范畴，需要在默认导出中剔除。 */
    fun isPrivate(visibility: EventVisibility, event: MatchEvent?): Boolean =
        visibility == EventVisibility.PRIVATE_OBSERVER || event is PrivateNoteAdded
}
