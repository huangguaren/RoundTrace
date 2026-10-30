package com.roundtrace.app.core.knowledge

import com.roundtrace.app.core.model.ReconstructedMatch
import com.roundtrace.app.core.model.EvidenceFinding
import com.roundtrace.app.core.model.KnowledgeSnapshot

/**
 * 证据分析接口（ARCHITECTURE §3 / TASKS.md T52）。
 *
 * **纯函数**：给定快照与历史事件，产出确定性的结论列表。
 * 不输出数值概率（TDD §6.1），每条结论必须带 `ruleId` 与 `sourceEventIds`。
 */
interface ReplayAnalyzer {

    /**
     * @param snapshot 已按视角/时间裁剪过的快照（不要绕过它去读全量事件）
     * @param history 与 [snapshot] 同一时间边界内的公开事件
     */
    fun analyze(snapshot: KnowledgeSnapshot, history: List<com.roundtrace.app.core.model.MatchEvent>): List<EvidenceFinding>

    /**
     * 便捷入口：直接从重放输入 + 视角构建并分析。
     *
     * 存在的意义：避免调用方（ViewModel）自己拼 `snapshot` 与 `history` 时
     * 不小心用了全量事件（那会直接击穿时间边界）。
     */
    fun analyzeAt(
        match: ReconstructedMatch,
        observer: com.roundtrace.app.core.model.SeatId?,
        at: com.roundtrace.app.core.model.GamePosition,
        mode: com.roundtrace.app.core.model.Perspective,
        knowledgeProvider: KnowledgeProvider
    ): List<EvidenceFinding>
}
