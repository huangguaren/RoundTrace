package com.roundtrace.app.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.roundtrace.app.R
import com.roundtrace.app.core.model.ProposalDecision
import com.roundtrace.app.core.ui.component.ProposalCard
import com.roundtrace.app.core.ui.component.QuestStepper
import com.roundtrace.app.core.ui.component.SaveStatusBar
import com.roundtrace.app.core.ui.issueText
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens

/** 记录页当前打开的弹层。 */
private enum class RecordSheet {
    NONE,
    PROPOSAL,
    VOTE,
    MISSION
}

/**
 * 现场记录页（TASKS.md T45④）。
 *
 * 结构：任务头 → 任务进度条 → 保存状态 → 当前提案卡 → 四个主操作 → 问题提示 → 时间线。
 *
 * **全程不需要键盘**：选队长/选队员/投票/失败牌数全部是点选控件，
 * 只有「标题」在创建页可留空自动生成。
 *
 * 本组件是纯展示 + 回调，所有判定结果来自 [RecordUiState]（由重放派生）。
 */
@Composable
fun RecordScreen(
    state: RecordUiState,
    onAction: (RecordAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenClaim: () -> Unit = {},
    onOpenReveal: () -> Unit = {},
    onOpenReplay: () -> Unit = {}
) {
    var sheet by rememberSaveable { mutableStateOf(RecordSheet.NONE) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 撤销反馈 → Snackbar（T49②）
    val undoDone = stringResource(R.string.record_undo_done)
    val undoEmpty = stringResource(R.string.record_undo_empty)
    val undoFailed = stringResource(R.string.record_undo_failed_short)
    LaunchedEffect(state.undoFeedback) {
        when (state.undoFeedback) {
            UndoFeedback.DONE -> {
                snackbarHostState.showSnackbar(undoDone)
                onAction(RecordAction.ClearUndoFeedback)
            }

            UndoFeedback.NOTHING_TO_UNDO -> {
                snackbarHostState.showSnackbar(undoEmpty)
                onAction(RecordAction.ClearUndoFeedback)
            }

            UndoFeedback.FAILED -> {
                snackbarHostState.showSnackbar(undoFailed)
                onAction(RecordAction.ClearUndoFeedback)
            }

            null -> Unit
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        when {
            state.isLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            state.errorMessage != null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
            }

            else -> {
                val quest = state.quest
                if (quest == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.record_not_found),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Box
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.PageHorizontalPadding,
                        end = Dimens.PageHorizontalPadding,
                        top = Dimens.Spacing8,
                        bottom = Dimens.Spacing32
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.Spacing12)
                ) {
                    item {
                        Text(
                            text = stringResource(
                                R.string.record_quest_header,
                                quest.questNo,
                                quest.expectedTeamSize,
                                quest.failThreshold
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    item {
                        QuestStepper(
                            outcomes = quest.slots,
                            currentQuestNo = quest.questNo
                        )
                    }
                    item {
                        SaveStatusBar(
                            status = state.saveStatus,
                            onRetry = { onAction(RecordAction.SaveDraft) }
                        )
                    }
                    state.currentProposal?.let { proposal ->
                        item {
                            ProposalCard(
                                questNo = proposal.questNo,
                                proposalNo = proposal.proposalNo,
                                leaderSeat = proposal.leaderSeat,
                                teamSeats = proposal.teamSeats,
                                approveCount = proposal.approveCount,
                                rejectCount = proposal.rejectCount,
                                decision = proposal.decision.takeIf {
                                    proposal.decision != ProposalDecision.UNKNOWN
                                },
                                failCount = proposal.failCount,
                                missionOutcome = proposal.missionOutcome,
                                missionVerified = proposal.verified
                            )
                        }
                    }
                    item {
                        StartQuestRow(
                            state = state,
                            onStartQuest = { onAction(RecordAction.StartQuest) }
                        )
                    }
                    item {
                        ActionRow(
                            state = state,
                            onOpenProposal = { sheet = RecordSheet.PROPOSAL },
                            onOpenVote = { sheet = RecordSheet.VOTE },
                            onOpenMission = { sheet = RecordSheet.MISSION },
                            onUndo = { onAction(RecordAction.Undo) }
                        )
                    }
                    item {
                        SecondaryActionRow(
                            onOpenClaim = onOpenClaim,
                            onOpenReveal = onOpenReveal,
                            onOpenReplay = onOpenReplay
                        )
                    }
                    if (state.issues.isNotEmpty()) {
                        item {
                            IssueHints(state = state)
                        }
                    }
                    item {
                        RecordTimelineSection(entries = state.timeline)
                    }
                }

                // —— 弹层 ——
                when (sheet) {
                    RecordSheet.PROPOSAL -> ProposalSheet(
                        questNo = quest.questNo,
                        expectedTeamSize = quest.expectedTeamSize,
                        playerCount = state.playerCount,
                        leaderSeat = state.draft.leaderSeat,
                        teamSeats = state.draft.teamSeats,
                        canSubmit = state.draft.teamSeats.size == quest.expectedTeamSize &&
                            state.draft.leaderSeat != null,
                        onSelectLeader = { onAction(RecordAction.SelectLeader(it)) },
                        onToggleTeamSeat = { onAction(RecordAction.ToggleTeamSeat(it)) },
                        onSaveDraft = { onAction(RecordAction.SaveDraft) },
                        onSubmit = {
                            onAction(RecordAction.ConfirmProposal)
                            sheet = RecordSheet.NONE
                        },
                        onDismiss = { sheet = RecordSheet.NONE }
                    )

                    RecordSheet.VOTE -> {
                        val proposalNo = (state.currentProposal?.proposalNo
                            ?: (quest.nextProposalNo - 1)).coerceAtLeast(1)
                        VoteSheet(
                            questNo = quest.questNo,
                            proposalNo = proposalNo,
                            playerCount = state.playerCount,
                            votesBySeat = state.draft.votesBySeat,
                            approveCount = state.draft.approveCount,
                            rejectCount = state.draft.rejectCount,
                            decision = state.voteDecision,
                            halfCount = state.halfCount,
                            exceedsPlayers = state.voteExceedsPlayers,
                            onSeatVote = { seat, vote -> onAction(RecordAction.SetVote(seat, vote)) },
                            onSummaryChange = { approve, reject ->
                                onAction(RecordAction.SetVoteSummary(approve, reject))
                            },
                            onSaveVotes = { onAction(RecordAction.SaveVotes) },
                            onDecision = { decision ->
                                onAction(RecordAction.ConfirmVoteDecision(decision))
                                sheet = RecordSheet.NONE
                            },
                            onDismiss = { sheet = RecordSheet.NONE }
                        )
                    }

                    RecordSheet.MISSION -> MissionSheet(
                        questNo = quest.questNo,
                        failThreshold = quest.failThreshold,
                        failCount = state.draft.failCount,
                        observedOutcome = state.draft.observedOutcome,
                        derivedOutcome = state.missionDerivedOutcome,
                        onFailCountChange = { onAction(RecordAction.SetMissionFailCount(it)) },
                        onObservedOutcomeChange = {
                            onAction(RecordAction.SetMissionObservedOutcome(it))
                        },
                        onSave = {
                            onAction(RecordAction.SaveMission)
                            sheet = RecordSheet.NONE
                        },
                        onDismiss = { sheet = RecordSheet.NONE }
                    )

                    RecordSheet.NONE -> Unit
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Dimens.Spacing16)
        )
    }
}

/**
 * 现场记录主操作区（R1 修复：拆两行）。
 *
 * 原本一行 4 控件（提案/投票/任务牌/撤销）每按钮仅 ~37dp 文案区，
 * 「记录组队提案」6 字必裁切。改为：
 *   行 1：主操作「记录组队提案」 + 撤销图标；
 *   行 2：「记录组队投票」+「记录任务牌」两个 outlined，文案 13sp。
 */
@Composable
private fun ActionRow(
    state: RecordUiState,
    onOpenProposal: () -> Unit,
    onOpenVote: () -> Unit,
    onOpenMission: () -> Unit,
    onUndo: () -> Unit
) {
    val decision = state.currentProposal?.decision
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing8)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onOpenProposal,
                shape = ButtonShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(text = stringResource(R.string.record_action_propose))
            }
            OutlinedButton(
                onClick = onUndo,
                enabled = state.timeline.isNotEmpty(),
                shape = ButtonShape,
                modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Undo,
                    contentDescription = stringResource(R.string.record_action_undo)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onOpenVote,
                enabled = state.currentProposal != null,
                shape = ButtonShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(
                    text = stringResource(R.string.record_action_vote),
                    fontSize = 13.sp
                )
            }
            OutlinedButton(
                onClick = onOpenMission,
                enabled = decision == ProposalDecision.APPROVED,
                shape = ButtonShape,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(
                    text = stringResource(R.string.record_action_mission),
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * 「开始第 N 任务」入口（主理人 M4 要求）。
 *
 * 存在意义：时间线需要在提案**之前**就有任务节点，否则起点会莫名其妙从提案开始。
 * 幂等：`quest.questStarted == true` 时按钮禁用，且 ViewModel 侧也会二次拦截，
 * 因此不会出现两条 `QUEST_STARTED`。
 */
@Composable
private fun StartQuestRow(state: RecordUiState, onStartQuest: () -> Unit) {
    val quest = state.quest ?: return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8)
    ) {
        Button(
            onClick = onStartQuest,
            enabled = !quest.questStarted && !quest.isFinished,
            shape = ButtonShape,
            modifier = Modifier.heightIn(min = Dimens.MinTouchTarget)
        ) {
            Text(text = stringResource(R.string.record_action_start_quest, quest.questNo))
        }
        Text(
            text = if (quest.questStarted) {
                stringResource(R.string.record_quest_started)
            } else {
                stringResource(R.string.record_quest_not_started)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 发言 / 揭晓 / 复盘 三个入口（M4 新增，均在记录页可达）。 */
@Composable
private fun SecondaryActionRow(
    onOpenClaim: () -> Unit,
    onOpenReveal: () -> Unit,
    onOpenReplay: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Spacing8),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onOpenClaim,
            shape = ButtonShape,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.MinTouchTarget)
        ) { Text(text = stringResource(R.string.claim_title)) }
        OutlinedButton(
            onClick = onOpenReveal,
            shape = ButtonShape,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.MinTouchTarget)
        ) { Text(text = stringResource(R.string.reveal_title)) }
        OutlinedButton(
            onClick = onOpenReplay,
            shape = ButtonShape,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Dimens.MinTouchTarget)
        ) { Text(text = stringResource(R.string.replay_title)) }
    }
}

@Composable
private fun IssueHints(state: RecordUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.Spacing4)) {
        state.issues.forEach { issue ->
            Text(
                text = issueText(issue),
                style = MaterialTheme.typography.bodySmall,
                color = if (issue.isBlocking) {
                    MaterialTheme.colorScheme.error
                } else {
                    com.roundtrace.app.core.ui.theme.LocalExtendedColors.current.accent
                }
            )
        }
    }
}
