package com.roundtrace.app.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.roundtrace.app.R
import com.roundtrace.app.core.model.MissionOutcome
import com.roundtrace.app.core.ui.component.MissionOutcomeSelector
import com.roundtrace.app.core.ui.theme.ButtonShape
import com.roundtrace.app.core.ui.theme.Dimens

/**
 * 任务结果录入 Sheet（TASKS.md T48）。
 *
 * 与 [VoteSheet] **完全分离**：这里没有「赞成/反对」，只有「失败牌数」。
 * 这是 PRD AC-02「反对票 ≠ 失败牌」在录入流程上的物理隔离。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionSheet(
    questNo: Int,
    failThreshold: Int,
    failCount: Int?,
    observedOutcome: MissionOutcome?,
    derivedOutcome: MissionOutcome?,
    onFailCountChange: (Int?) -> Unit,
    onObservedOutcomeChange: (MissionOutcome?) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.PageHorizontalPadding)
                .padding(bottom = Dimens.Spacing24),
            verticalArrangement = Arrangement.spacedBy(Dimens.Spacing16)
        ) {
            Text(
                text = stringResource(R.string.mission_sheet_title, questNo),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            MissionOutcomeSelector(
                questNo = questNo,
                failThreshold = failThreshold,
                failCount = failCount,
                observedOutcome = observedOutcome,
                derivedOutcome = derivedOutcome,
                onFailCountChange = onFailCountChange,
                onObservedOutcomeChange = onObservedOutcomeChange
            )

            Button(
                onClick = onSave,
                shape = ButtonShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget)
            ) {
                Text(text = stringResource(R.string.mission_save))
            }
        }
    }
}
