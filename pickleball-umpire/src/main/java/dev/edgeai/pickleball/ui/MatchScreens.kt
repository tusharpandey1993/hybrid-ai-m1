package dev.edgeai.pickleball.ui

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.edgeai.pickleball.R
import dev.edgeai.pickleball.match.CameraQuality
import dev.edgeai.pickleball.match.ActiveCourtUiStatus
import dev.edgeai.pickleball.match.MatchAction
import dev.edgeai.pickleball.match.MatchDraft
import dev.edgeai.pickleball.match.MatchHistoryEvent
import dev.edgeai.pickleball.match.MatchScreen
import dev.edgeai.pickleball.match.MatchSummary
import dev.edgeai.pickleball.match.MatchType
import dev.edgeai.pickleball.match.MatchUiState
import dev.edgeai.pickleball.match.Team
import kotlinx.coroutines.delay
import java.util.Locale

private val CourtGreen = Color(0xFF123B2D)
private val CourtMint = Color(0xFFB6E4C7)
private val CourtCoral = Color(0xFFFF765B)
private val Ink = Color(0xFF102019)

@Composable
fun PickleballMatchApp(
    state: MatchUiState,
    cameraView: View,
    onAction: (MatchAction) -> Unit,
    onCalibrate: () -> Unit,
    onExport: (String?) -> Unit,
    onReviewVideo: (String) -> Unit,
    landscapeLayout: Boolean,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MaterialTheme(colorScheme = lightColorScheme(
        primary = CourtGreen,
        onPrimary = Color.White,
        secondary = CourtCoral,
        onSecondary = Ink,
        background = Color(0xFFF2F5F1),
        surface = Color.White,
        onSurface = Ink,
        error = Color(0xFFB3261E),
    )) {
        if (state.screen == MatchScreen.SETUP || state.screen == MatchScreen.LIVE) {
            Box(modifier.fillMaxSize()) {
                AndroidView(factory = { cameraView }, modifier = Modifier.fillMaxSize())
                when (state.screen) {
                    MatchScreen.SETUP -> SetupScreen(state, onAction, onCalibrate, landscapeLayout, onToggleOrientation)
                    MatchScreen.LIVE -> LiveMatchScreen(state, onAction, landscapeLayout, onToggleOrientation)
                    else -> Unit
                }
            }
        } else {
            when (state.screen) {
                MatchScreen.SUMMARY -> SummaryScreen(state, onAction, onExport, onReviewVideo, modifier)
                MatchScreen.HISTORY -> HistoryScreen(state, onAction, modifier)
                MatchScreen.EVENTS -> EventsScreen(state.selectedEvents, state.selectedSummary?.recordingPaths.orEmpty(), onAction, onReviewVideo, modifier)
                else -> Unit
            }
        }
    }
}

@Composable
private fun SetupScreen(
    state: MatchUiState,
    onAction: (MatchAction) -> Unit,
    onCalibrate: () -> Unit,
    landscapeLayout: Boolean,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Surface(
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.52f).align(Alignment.CenterEnd)
                    .statusBarsPadding().navigationBarsPadding().padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xF2F2F5F1),
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SetupHeader(state, onAction, landscapeLayout, onToggleOrientation)
                    TextButton(onClick = onCalibrate, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.calibrate_court))
                    }
                    SetupFields(state, onAction, Modifier.weight(1f).fillMaxWidth())
                    StartMatchButton(state, onAction)
                }
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.48f).align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = Color(0xF2F2F5F1),
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onCalibrate, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.calibrate_court))
                    }
                    SetupFields(state, onAction, Modifier.weight(1f).fillMaxWidth())
                    StartMatchButton(state, onAction)
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(10.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xF2F2F5F1),
            ) {
                SetupHeader(state, onAction, landscapeLayout, onToggleOrientation, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun SetupHeader(
    state: MatchUiState,
    onAction: (MatchAction) -> Unit,
    landscapeLayout: Boolean,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val orientationLabel = stringResource(if (landscapeLayout) R.string.portrait_short else R.string.landscape_short)
    val orientationDescription = stringResource(if (landscapeLayout) R.string.switch_to_portrait else R.string.switch_to_landscape)
    val historyDescription = stringResource(R.string.match_history)
    val cameraStatus = if (state.cameraStatus.contains("ready", ignoreCase = true)) {
        stringResource(R.string.camera_ready_short)
    } else {
        state.cameraStatus
    }
    val courtStatusLabel = stringResource(when (state.activeCourtStatus) {
        ActiveCourtUiStatus.NOT_SELECTED -> R.string.court_not_selected
        ActiveCourtUiStatus.SELECTED -> R.string.court_selected_players_unavailable
        ActiveCourtUiStatus.LOCKED -> R.string.court_locked
        ActiveCourtUiStatus.CALIBRATION_UNCERTAIN -> R.string.court_calibration_uncertain
    })
    val courtStatus = state.activeCourtId?.let { courtId ->
        stringResource(
            R.string.active_court_confidence,
            courtId,
            state.activeCourtConfidence * 100f,
            courtStatusLabel,
        )
    } ?: stringResource(R.string.court_not_selected)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.match_setup), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(cameraStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFF52645B), maxLines = 1)
            Text(courtStatus, style = MaterialTheme.typography.labelSmall, color = if (state.activeCourtLocked) CourtGreen else Color(0xFF6E4035), maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onToggleOrientation,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    orientationLabel,
                    maxLines = 1,
                    modifier = Modifier.semantics {
                        contentDescription = orientationDescription
                    },
                )
            }
            TextButton(
                onClick = { onAction(MatchAction.ShowHistory) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.history_short),
                    maxLines = 1,
                    modifier = Modifier.semantics { contentDescription = historyDescription },
                )
            }
        }
    }
}

@Composable
private fun SetupFields(state: MatchUiState, onAction: (MatchAction) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { MatchTypePicker(state.draft, onAction) }
        item { TargetPicker(state.draft, onAction) }
        item { NameFields(state.draft, onAction) }
        item { ServingTeamPicker(state.draft, onAction) }
        item { CameraOptions(state, onAction) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.scoring_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = true, onClick = {}, label = { Text(stringResource(R.string.traditional_side_out)) })
                    FilterChip(selected = false, enabled = false, onClick = {}, label = { Text(stringResource(R.string.rally_scoring_future)) })
                }
                Text(stringResource(R.string.winning_margin), style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.draft.recordVideo, onCheckedChange = { onAction(MatchAction.RecordingChanged(it)) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.record_video), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.recording_local_only), style = MaterialTheme.typography.bodySmall, color = Color(0xFF52645B))
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.automatic_awards_disabled), color = Color(0xFF6E4035), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StartMatchButton(state: MatchUiState, onAction: (MatchAction) -> Unit) {
    Button(
        onClick = { onAction(MatchAction.StartMatch) },
        enabled = state.cameraStatus.contains("ready", ignoreCase = true) ||
            state.cameraStatus.contains("stopped", ignoreCase = true),
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(8.dp),
    ) { Text(stringResource(R.string.start_match), style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun MatchTypePicker(draft: MatchDraft, onAction: (MatchAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.match_type), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(draft.matchType == MatchType.SINGLES, { onAction(MatchAction.MatchTypeChanged(MatchType.SINGLES)) }, label = { Text(stringResource(R.string.singles)) })
            FilterChip(draft.matchType == MatchType.DOUBLES, { onAction(MatchAction.MatchTypeChanged(MatchType.DOUBLES)) }, label = { Text(stringResource(R.string.doubles)) })
        }
    }
}

@Composable
private fun TargetPicker(draft: MatchDraft, onAction: (MatchAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.target_score), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(11, 21).forEach { target ->
                FilterChip(draft.targetScore == target, { onAction(MatchAction.TargetChanged(target)) }, label = { Text(target.toString()) })
            }
        }
    }
}

@Composable
private fun NameFields(draft: MatchDraft, onAction: (MatchAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.teamAName,
            onValueChange = { onAction(MatchAction.TeamANameChanged(it)) },
            label = { Text(stringResource(R.string.team_a_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.teamBName,
            onValueChange = { onAction(MatchAction.TeamBNameChanged(it)) },
            label = { Text(stringResource(R.string.team_b_name)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
private fun ServingTeamPicker(draft: MatchDraft, onAction: (MatchAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.starting_server), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(draft.startingServingTeam == Team.A, { onAction(MatchAction.StartingTeamChanged(Team.A)) })
            Text(stringResource(R.string.team_a))
            Spacer(Modifier.width(20.dp))
            RadioButton(draft.startingServingTeam == Team.B, { onAction(MatchAction.StartingTeamChanged(Team.B)) })
            Text(stringResource(R.string.team_b))
        }
    }
}

@Composable
private fun CameraOptions(state: MatchUiState, onAction: (MatchAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.camera_quality), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.cameraQualityOptions.forEach { quality ->
                FilterChip(
                    selected = state.draft.cameraQuality == quality,
                    onClick = { onAction(MatchAction.CameraQualityChanged(quality)) },
                    label = { Text(qualityLabel(quality)) },
                )
            }
            if (state.cameraQualityOptions.isEmpty()) Text(stringResource(R.string.no_fps_options), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.camera_fps), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.cameraFpsOptions.forEach { fps ->
                FilterChip(
                    selected = state.draft.cameraFps == fps,
                    onClick = { onAction(MatchAction.CameraFpsChanged(fps)) },
                    label = { Text("$fps FPS") },
                )
            }
            if (state.cameraFpsOptions.isEmpty()) Text(stringResource(R.string.no_fps_options), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LiveMatchScreen(
    state: MatchUiState,
    onAction: (MatchAction) -> Unit,
    landscapeLayout: Boolean,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.feedback) {
        if (state.feedback != null) {
            delay(2_500L)
            onAction(MatchAction.ClearFeedback)
        }
    }
    val score = state.score
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val compact = landscape || maxHeight < 850.dp
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = if (landscape) 12.dp else 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            score?.let { current ->
                Scoreboard(
                    state,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).align(Alignment.CenterHorizontally),
                    compact = compact,
                    landscapeLayout = landscapeLayout,
                    onToggleOrientation = onToggleOrientation,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(visible = state.feedback != null, enter = fadeIn(), exit = fadeOut()) {
                    Surface(
                        color = if (state.reviewRequired) CourtCoral else CourtMint,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text(
                            text = state.feedback.orEmpty(),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            color = Ink,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                LiveControls(state, onAction, compact, onReset = { confirmReset = true })
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.confirm_reset_title)) },
            text = { Text(stringResource(R.string.confirm_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onAction(MatchAction.ResetConfirmed)
                }) { Text(stringResource(R.string.confirm_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Scoreboard(
    state: MatchUiState,
    modifier: Modifier = Modifier,
    compact: Boolean,
    landscapeLayout: Boolean,
    onToggleOrientation: () -> Unit,
) {
    val score = state.score ?: return
    val courtStatusLabel = stringResource(when (state.activeCourtStatus) {
        ActiveCourtUiStatus.NOT_SELECTED -> R.string.court_not_selected
        ActiveCourtUiStatus.SELECTED -> R.string.court_selected_players_unavailable
        ActiveCourtUiStatus.LOCKED -> R.string.court_locked
        ActiveCourtUiStatus.CALIBRATION_UNCERTAIN -> R.string.court_calibration_uncertain
    })
    val orientationLabel = stringResource(if (landscapeLayout) R.string.portrait_short else R.string.landscape_short)
    val orientationDescription = stringResource(if (landscapeLayout) R.string.switch_to_portrait else R.string.switch_to_landscape)
    val cameraLabel = state.cameraFps?.let { String.format(Locale.getDefault(), "%.1f FPS", it) }
        ?: if (state.cameraStatus.contains("ready", ignoreCase = true)) stringResource(R.string.camera_ready_short)
        else state.cameraStatus.take(24)
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = CourtGreen.copy(alpha = if (compact) 0.78f else 0.88f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.live_match).uppercase(), color = CourtMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = onToggleOrientation,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(orientationLabel, color = CourtMint, maxLines = 1, modifier = Modifier.semantics {
                        contentDescription = orientationDescription
                    })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TeamScore(score.settings.teamAName, score.scoreA, score.servingTeam == Team.A, Modifier.weight(1f), compact)
                Text(":", color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 26.sp else 32.sp)
                TeamScore(score.settings.teamBName, score.scoreB, score.servingTeam == Team.B, Modifier.weight(1f), compact)
            }
            if (compact) {
                Text(
                    state.activeCourtId?.let { "$it · $courtStatusLabel" } ?: courtStatusLabel,
                    color = if (state.activeCourtLocked) CourtMint else CourtCoral,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.game_to, score.settings.targetScore, score.settings.winningMargin), color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.serving_team, score.servingTeam.name, score.serverNumber), color = CourtMint, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.match_duration, formatDuration(state.elapsedMs)), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Text(cameraLabel, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Text(
                    if (state.activeBallTrackId == null) stringResource(R.string.active_ball_uncertain) else "${state.trackingStatus} · ${state.activeBallTrackId}",
                    color = if (state.activeBallTrackId == null) CourtCoral else CourtMint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            } else {
                Text(
                    stringResource(R.string.game_to, score.settings.targetScore, score.settings.winningMargin),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.serving_team, score.servingTeam.name, score.serverNumber), color = CourtMint, style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.match_duration, formatDuration(state.elapsedMs)), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("● ${state.trackingStatus}", color = CourtMint, style = MaterialTheme.typography.labelSmall)
                    Text(cameraLabel, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Text(state.modelStatus, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(stringResource(R.string.player_tracking_unavailable), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            score.gamePointTeam?.let { team ->
                val teamName = if (team == Team.A) score.settings.teamAName else score.settings.teamBName
                Text(stringResource(R.string.game_point, teamName), color = CourtCoral, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "${stringResource(R.string.last_decision)}: ${state.lastDecision} · ${stringResource(if (state.lastDecisionSource == "MANUAL") R.string.manual_decision else R.string.ai_decision)}",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            if (state.recordingStatus != "OFF") {
                Text(state.recordingStatus, color = CourtCoral, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            if (state.reviewRequired) {
                Text(stringResource(R.string.review_required).uppercase(), color = CourtCoral, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TeamScore(name: String, score: Int, serving: Boolean, modifier: Modifier, compact: Boolean) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (serving) Text("●", color = CourtMint, style = MaterialTheme.typography.labelSmall)
            Text(score.toString().padStart(2, '0'), color = Color.White, fontSize = if (compact) 32.sp else 42.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LiveControls(
    state: MatchUiState,
    onAction: (MatchAction) -> Unit,
    compact: Boolean,
    onReset: () -> Unit,
) {
    val score = state.score ?: return
    var serveCorrectionExpanded by rememberSaveable(score.settings.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Ink.copy(alpha = if (compact) 0.84f else 0.92f)),
    ) {
        Column(Modifier.padding(if (compact) 8.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(
                    text = stringResource(if (score.phase == dev.edgeai.pickleball.match.MatchPhase.PAUSED) R.string.resume_match else R.string.pause_match),
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(MatchAction.PauseResume) },
                    enabled = score.phase == dev.edgeai.pickleball.match.MatchPhase.LIVE ||
                        score.phase == dev.edgeai.pickleball.match.MatchPhase.PAUSED,
                )
                ActionButton(stringResource(R.string.stop_match), Modifier.weight(1f), onClick = {
                    onAction(MatchAction.StopMatch(state.logPath))
                }, danger = true)
                ActionButton(stringResource(R.string.reset_match), Modifier.weight(1f), onClick = onReset)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionButton(
                    text = stringResource(if (compact) R.string.team_a else R.string.award_team_a),
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(MatchAction.AwardRally(Team.A)) },
                    enabled = score.phase == dev.edgeai.pickleball.match.MatchPhase.LIVE,
                    contentDescription = stringResource(R.string.award_team_a),
                )
                ActionButton(
                    text = stringResource(if (compact) R.string.team_b else R.string.award_team_b),
                    modifier = Modifier.weight(1f),
                    onClick = { onAction(MatchAction.AwardRally(Team.B)) },
                    enabled = score.phase == dev.edgeai.pickleball.match.MatchPhase.LIVE,
                    contentDescription = stringResource(R.string.award_team_b),
                )
                ActionButton(
                    text = stringResource(if (compact) R.string.undo_score_short else R.string.undo_score),
                    modifier = Modifier.weight(0.8f),
                    onClick = { onAction(MatchAction.UndoScore) },
                    enabled = score.undoStack.isNotEmpty(),
                    contentDescription = stringResource(R.string.undo_score),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { serveCorrectionExpanded = !serveCorrectionExpanded },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.correct_serve), maxLines = 1)
                }
                Text(
                    stringResource(R.string.serving_team, score.servingTeam.name, score.serverNumber),
                    color = CourtMint,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            if (serveCorrectionExpanded) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(score.servingTeam == Team.A, {
                            onAction(MatchAction.CorrectServe(Team.A, score.serverNumber))
                            serveCorrectionExpanded = false
                        }, label = { Text("A") })
                        FilterChip(score.servingTeam == Team.B, {
                            onAction(MatchAction.CorrectServe(Team.B, score.serverNumber))
                            serveCorrectionExpanded = false
                        }, label = { Text("B") })
                        if (score.settings.matchType == MatchType.DOUBLES) {
                            FilterChip(score.serverNumber == 1, {
                                onAction(MatchAction.CorrectServe(score.servingTeam, 1))
                                serveCorrectionExpanded = false
                            }, label = { Text("1") })
                            FilterChip(score.serverNumber == 2, {
                                onAction(MatchAction.CorrectServe(score.servingTeam, 2))
                                serveCorrectionExpanded = false
                            }, label = { Text("2") })
                        }
                    }
                }
            }
            if (!compact) {
                Text(stringResource(R.string.automatic_awards_disabled), color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true,
    danger: Boolean = false,
    contentDescription: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) CourtCoral else CourtMint,
            contentColor = Ink,
            disabledContainerColor = Color(0xFF65746D),
            disabledContentColor = Color.White.copy(alpha = 0.65f),
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = if (contentDescription == null) Modifier else Modifier.semantics {
                this.contentDescription = contentDescription
            },
        )
    }
}

@Composable
private fun SummaryScreen(
    state: MatchUiState,
    onAction: (MatchAction) -> Unit,
    onExport: (String?) -> Unit,
    onReviewVideo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = state.selectedSummary ?: state.score?.let { score ->
        MatchSummary(
            id = score.settings.id,
            dateEpochMs = state.matchStartedEpochMs,
            matchType = score.settings.matchType,
            targetScore = score.settings.targetScore,
            teamAName = score.settings.teamAName,
            teamBName = score.settings.teamBName,
            scoreA = score.scoreA,
            scoreB = score.scoreB,
            winner = score.winner,
            durationMs = state.elapsedMs,
            logPath = state.logPath,
            recordingPath = state.recordingPath,
            recordingPaths = state.recordingPaths,
            ballDetectionCount = state.ballDetectionCount,
            lineEventCount = state.lineEventCount,
            uncertainEventCount = state.uncertainEventCount,
        )
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.match_summary), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        summary?.let { result ->
            Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = CourtGreen)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        SummaryScore(result.teamAName, result.scoreA)
                        Text(":", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        SummaryScore(result.teamBName, result.scoreB)
                    }
                    Text(
                        result.winner?.let { winner ->
                            stringResource(R.string.winner_team, if (winner == Team.A) result.teamAName else result.teamBName)
                        } ?: stringResource(R.string.no_winner),
                        color = CourtMint,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            SummaryStat(stringResource(R.string.match_duration, formatDuration(result.durationMs)))
            SummaryStat("${stringResource(R.string.ball_detections)}: ${result.ballDetectionCount}")
            SummaryStat("${stringResource(R.string.line_events)}: ${result.lineEventCount}")
            SummaryStat("${stringResource(R.string.uncertain_calls)}: ${result.uncertainEventCount}")
            result.recordingPaths.forEachIndexed { index, path ->
                SummaryStat("${stringResource(R.string.review_video)} ${index + 1}")
                Button(onClick = { onReviewVideo(path) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.review_video))
                }
            }
        }
        Button(onClick = { onAction(MatchAction.ShowEvents) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.view_events))
        }
        Button(onClick = { onExport(summary?.logPath) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.export_logs))
        }
        TextButton(onClick = { onAction(MatchAction.NewMatch) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.new_match))
        }
        TextButton(onClick = { onAction(MatchAction.ShowHistory) }) { Text(stringResource(R.string.match_history)) }
    }
}

@Composable
private fun SummaryScore(name: String, score: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = Color.White, style = MaterialTheme.typography.labelLarge)
        Text(score.toString().padStart(2, '0'), color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SummaryStat(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = Color.White, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HistoryScreen(state: MatchUiState, onAction: (MatchAction) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.match_history), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onAction(MatchAction.NewMatch) }) { Text(stringResource(R.string.back)) }
        }
        if (state.history.isEmpty()) {
            Text(stringResource(R.string.no_match_history), modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.history, key = MatchSummary::id) { summary ->
                    TextButton(onClick = { onAction(MatchAction.OpenSummary(summary)) }, modifier = Modifier.fillMaxWidth().height(62.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("${summary.teamAName} vs ${summary.teamBName}", fontWeight = FontWeight.SemiBold)
                                Text("${summary.matchType} · ${summary.targetScore} · ${formatDuration(summary.durationMs)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("${summary.scoreA} : ${summary.scoreB}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventsScreen(
    events: List<MatchHistoryEvent>,
    recordingPaths: List<String>,
    onAction: (MatchAction) -> Unit,
    onReviewVideo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.view_events), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onAction(MatchAction.BackToSummary) }) { Text(stringResource(R.string.back)) }
        }
        if (events.isEmpty()) {
            Text(stringResource(R.string.no_events), modifier = Modifier.padding(top = 24.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(events, key = { "${it.timestamp}-${it.type}" }) { event ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(event.type, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(event.summary, style = MaterialTheme.typography.bodySmall, color = Color(0xFF52645B))
                    }
                }
            }
        }
        recordingPaths.forEachIndexed { index, path ->
            Button(onClick = { onReviewVideo(path) }, modifier = Modifier.fillMaxWidth()) {
                Text("${stringResource(R.string.review_video)} ${index + 1}")
            }
        }
    }
}

@Composable
private fun CameraQuality.label(): String = when (this) {
    CameraQuality.SD -> stringResource(R.string.quality_sd)
    CameraQuality.HD -> stringResource(R.string.quality_hd)
    CameraQuality.FHD -> stringResource(R.string.quality_fhd)
}

@Composable
private fun qualityLabel(quality: CameraQuality): String = quality.label()

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000L
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
}