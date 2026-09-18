package za.ac.tendertrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.*
import androidx.compose.ui.unit.dp
import za.ac.tendertrack.ui.theme.AppColor
import za.ac.tendertrack.ui.theme.AppType
import za.ac.tendertrack.ui.theme.Dimens

/** One row of the lifecycle stepper. */
data class StepItem(
    val title: String,
    val detail: String,
    val state: StepState
)

enum class StepState { Done, Current, Pending }

/**
 * Vertical stepper used for the tender lifecycle and the flag audit trail.
 *
 * The lifecycle vocabulary is fixed in [za.ac.tendertrack.data.model.TenderStatus];
 * this component only renders whatever states it is handed, so there is no
 * second place where status names could drift.
 */
@Composable
fun LifecycleStepper(
    steps: List<StepItem>,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            val isLast = index == steps.lastIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    // Lets the connector line stretch to the height of the text beside it.
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(Dimens.StepPip)
                ) {
                    StepPip(step.state)
                    if (!isLast) {
                        Box(
                            Modifier
                                .width(2.dp)
                                .heightIn(min = 24.dp)
                                .weight(1f)
                                .background(
                                    if (step.state == StepState.Done) AppColor.SuccessBar
                                    else AppColor.LineStrong
                                )
                        )
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(bottom = if (isLast) 0.dp else 16.dp)
                ) {
                    Text(
                        step.title,
                        style = AppType.Body.copy(
                            fontWeight = if (step.state == StepState.Pending)
                                androidx.compose.ui.text.font.FontWeight.SemiBold
                            else androidx.compose.ui.text.font.FontWeight.Bold,
                            color = if (step.state == StepState.Pending)
                                AppColor.MutedLight else AppColor.Ink
                        )
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(step.detail, style = AppType.Hint)
                }
            }
        }
    }
}

@Composable
private fun StepPip(state: StepState) {
    Box(
        Modifier
            .size(Dimens.StepPip)
            .clip(CircleShape)
            .background(
                when (state) {
                    StepState.Done -> AppColor.SuccessBar
                    StepState.Current -> AppColor.Ink
                    StepState.Pending -> AppColor.Surface
                }
            )
            .border(
                2.dp,
                when (state) {
                    StepState.Done -> AppColor.SuccessBar
                    StepState.Current -> AppColor.Ink
                    StepState.Pending -> AppColor.LineStrong
                },
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (state == StepState.Done) {
            AppIcon(Icons.Default.Check, tint = AppColor.OnInk, size = 13.dp)
        }
    }
}
