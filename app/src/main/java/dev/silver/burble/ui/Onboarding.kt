package dev.silver.burble.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.delay
import dev.silver.burble.R

/** Where the onboarding and home screens send the user to grant what's missing. */
class AccessActions(
    val requestMicrophone: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAccessibility: () -> Unit,
    val openAppInfo: () -> Unit,
    val openBattery: () -> Unit,
    /** Asks Android 13+ to add the tile; [onAdded] runs when it is in Quick Settings. */
    val addTile: (onAdded: () -> Unit) -> Unit,
) {
    companion object {
        val None = AccessActions({}, {}, {}, {}, {}, {})
    }
}

enum class Step { WELCOME, MICROPHONE, NOTIFICATIONS, UNLOCK, INSERTION, BATTERY, TILE }

/**
 * The steps this phone needs, fixed when onboarding opens so that a step granted midway keeps its
 * place and shows its check mark instead of vanishing.
 */
fun onboardingSteps(access: Access, sdk: Int = Build.VERSION.SDK_INT) = buildList {
    add(Step.WELCOME)
    add(Step.MICROPHONE)
    if (sdk >= 33) add(Step.NOTIFICATIONS)
    // A file install gets the accessibility step with Android's unlock folded in.
    add(if (access.restricted) Step.UNLOCK else Step.INSERTION)
    add(Step.BATTERY)
    add(Step.TILE)
}

private fun Step.done(access: Access, tileAdded: Boolean) = when (this) {
    Step.WELCOME -> false
    Step.MICROPHONE -> access.microphone
    Step.NOTIFICATIONS -> access.notifications
    Step.UNLOCK, Step.INSERTION -> access.insertion
    Step.BATTERY -> access.battery
    Step.TILE -> tileAdded
}

/** Steps the app works without; the home screen lists the essential ones as missing. */
private val Step.optional get() = this != Step.WELCOME && this != Step.MICROPHONE

@Composable
fun Onboarding(
    access: Access,
    actions: AccessActions,
    steps: List<Step>,
    onDone: () -> Unit,
    initial: Int = 0,
    initialUnlockStage: Int = 0,
) {
    var index by rememberSaveable { mutableIntStateOf(initial) }
    // Android shows "Allow restricted settings" only after the user tried to turn the service on
    // and saw it refused, and apps can't read either fact: each button of the unlock moves the
    // step on to the next one.
    var unlockStage by rememberSaveable { mutableIntStateOf(initialUnlockStage) }
    // Android has no query for "the tile is in Quick Settings"; its answer to the request is all.
    var tileAdded by rememberSaveable { mutableStateOf(false) }
    val stepActions = AccessActions(
        actions.requestMicrophone, actions.requestNotifications, actions.openAccessibility,
        openAppInfo = actions.openAppInfo,
        openBattery = actions.openBattery,
        addTile = { actions.addTile { tileAdded = true } },
    )
    BackHandler(enabled = index > 0) { index-- }
    val next: () -> Unit = { if (index < steps.lastIndex) index++ else onDone() }
    // A step granted while the user is on it moves on by itself, after a beat to show the check.
    // One already granted on arrival (the user came back to it) waits for Continue.
    val done = steps[index].done(access, tileAdded)
    val doneOnArrival = remember(index) { done }
    LaunchedEffect(index, done) {
        if (done && !doneOnArrival) {
            delay(900)
            next()
        }
    }
    val progress by animateFloatAsState((index + 1f) / steps.size, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "progress")

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            )
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 4 else -it / 4 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 4 else it / 4 } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "step",
            ) { i ->
                val step = steps[i]
                StepPage(step, access, stepActions, unlockStage, { unlockStage = it }, tileAdded, last = i == steps.lastIndex, onNext = next)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepPage(
    step: Step,
    access: Access,
    actions: AccessActions,
    unlockStage: Int,
    setUnlockStage: (Int) -> Unit,
    tileAdded: Boolean,
    last: Boolean,
    onNext: () -> Unit,
    sdk: Int = Build.VERSION.SDK_INT,
) {
    val done = step.done(access, tileAdded)
    // Before Android 13 the app can't add its tile: the step only says how.
    val tileRequest = sdk >= 33
    val canGo = done
    val unlock = listOf(
        { actions.openAccessibility(); setUnlockStage(1) },
        { actions.openAppInfo(); setUnlockStage(2) },
        actions.openAccessibility,
    )
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            when (step) {
                Step.WELCOME -> Welcome()
                Step.MICROPHONE -> Permission(
                    Icons.Rounded.Mic, MaterialShapes.Cookie9Sided, done,
                    R.string.onb_mic_title, R.string.onb_mic_why, R.string.onb_mic_not,
                )
                Step.NOTIFICATIONS -> Permission(
                    Icons.Rounded.Notifications, MaterialShapes.Sunny, done,
                    R.string.onb_notif_title, R.string.onb_notif_why, R.string.onb_notif_not,
                )
                Step.UNLOCK -> Unlock(done, unlockStage, unlock)
                Step.INSERTION -> Permission(
                    Icons.Rounded.Keyboard, MaterialShapes.SoftBurst, done,
                    R.string.onb_insert_title, R.string.onb_insert_why, R.string.onb_insert_not,
                    how = R.string.onb_insert_how,
                )
                Step.BATTERY -> Permission(
                    Icons.Rounded.BatteryFull, MaterialShapes.Clover8Leaf, done,
                    R.string.onb_battery_title, R.string.onb_battery_why, R.string.onb_battery_not,
                )
                Step.TILE -> Permission(
                    Icons.Rounded.ToggleOn, MaterialShapes.Cookie4Sided, done,
                    R.string.onb_tile_title, R.string.onb_tile_why, null,
                    how = if (tileRequest) null else R.string.onb_tile_how,
                    doneLabel = R.string.onb_tile_added,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (step.optional && !canGo) TextButton(onClick = onNext) { Text(stringResource(R.string.onb_skip)) }
            Spacer(Modifier.weight(1f))
            val grant: (() -> Unit)? = when (step) {
                Step.WELCOME -> null
                Step.MICROPHONE -> actions.requestMicrophone
                Step.NOTIFICATIONS -> actions.requestNotifications
                Step.UNLOCK -> unlock[unlockStage]
                Step.INSERTION -> actions.openAccessibility
                Step.BATTERY -> actions.openBattery
                Step.TILE -> if (tileRequest) ({ actions.addTile {} }) else null
            }
            val label = when {
                grant != null && !canGo -> when (step) {
                    Step.MICROPHONE, Step.NOTIFICATIONS -> R.string.onb_allow
                    Step.UNLOCK -> if (unlockStage == 1) R.string.open_app_info else R.string.open_settings
                    Step.BATTERY -> R.string.onb_allow
                    Step.TILE -> R.string.onb_tile_add
                    else -> R.string.open_settings
                }
                last -> R.string.onb_finish
                else -> R.string.onb_continue
            }
            Button(
                onClick = if (grant != null && !canGo) grant else onNext,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            ) {
                Text(stringResource(label), style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Welcome() {
    ShapeBadge(Icons.Rounded.GraphicEq, MaterialShapes.Cookie9Sided)
    Title(R.string.onb_welcome_title)
    Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(32.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = segmentShape(0, 1),
    ) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.disclaimer_short),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Title(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(top = 32.dp, bottom = 16.dp).semantics { heading() },
    )
}

@Composable
private fun Permission(
    icon: ImageVector,
    shape: RoundedPolygon,
    done: Boolean,
    title: Int,
    why: Int,
    not: Int?,
    how: Int? = null,
    doneLabel: Int = R.string.onb_granted,
) {
    ShapeBadge(icon, shape, done = done)
    Title(title)
    Text(stringResource(why), style = MaterialTheme.typography.bodyLarge)
    if (not != null) {
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(stringResource(not), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (how != null && !done) {
        Spacer(Modifier.height(20.dp))
        Text(stringResource(how), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (done) Granted(doneLabel)
}

/**
 * The accessibility step for a file install, where Android refuses the service until the user
 * has tried it once, then allowed restricted settings in App info. [stage] is the step the user
 * is on; tapping an earlier one does it again.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Unlock(done: Boolean, stage: Int, actions: List<() -> Unit>) {
    ShapeBadge(Icons.Rounded.Keyboard, MaterialShapes.SoftBurst, done = done)
    Title(R.string.onb_insert_title)
    Text(stringResource(R.string.onb_insert_why), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.onb_insert_not), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (done) {
        Granted()
        return
    }
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.onb_unlock_why), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            R.string.onb_unlock_1 to R.string.onb_unlock_1_detail,
            R.string.onb_unlock_2 to R.string.onb_unlock_2_detail,
            R.string.onb_unlock_3 to R.string.onb_unlock_3_detail,
        ).forEachIndexed { i, (title, detail) ->
            val past = i < stage
            val current = i == stage
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .clickable(enabled = i <= stage, onClick = actions[i]).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        current -> colors.primary
                        past -> colors.secondaryContainer
                        else -> colors.surfaceContainerHigh
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (past) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = if (current) colors.onPrimary else colors.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (current) colors.onSurface else colors.onSurfaceVariant,
                    )
                    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Granted(label: Int = R.string.onb_granted) {
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    }
}
