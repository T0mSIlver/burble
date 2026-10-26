package dev.silver.burble.ui

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import dev.silver.burble.R

/** Where the onboarding and home screens send the user to grant what's missing. */
class AccessActions(
    val requestMicrophone: () -> Unit,
    val openAccessibility: () -> Unit,
    val openAppInfo: () -> Unit,
    val openBattery: () -> Unit,
) {
    companion object {
        val None = AccessActions({}, {}, {}, {})
    }
}

/** Two screens: what Burble does with the microphone, then the accessibility service. */
enum class Step { WELCOME, INSERTION }

/**
 * Ends by itself once the accessibility service is on: the bubble then shows over home. A file
 * install gets Android's restricted-settings unlock on the second screen, decided when
 * onboarding opens so it doesn't vanish midway.
 */
@Composable
fun Onboarding(
    access: Access,
    actions: AccessActions,
    onDone: () -> Unit,
    initial: Int = 0,
    initialUnlockStage: Int = 0,
) {
    val steps = Step.entries
    var index by rememberSaveable { mutableIntStateOf(initial) }
    val unlocking = rememberSaveable { access.restricted }
    // Android shows "Allow restricted settings" only after the user tried to turn the service on
    // and saw it refused, and apps can't read either fact: each button of the unlock moves the
    // step on to the next one.
    var unlockStage by rememberSaveable { mutableIntStateOf(initialUnlockStage) }
    BackHandler(enabled = index > 0) { index-- }
    // The microphone granted on the first screen moves on; one granted before needs Continue.
    val micOnArrival = remember(index) { access.microphone }
    LaunchedEffect(index, access.microphone) {
        if (index == 0 && access.microphone && !micOnArrival) index = 1
    }
    LaunchedEffect(index, access.insertion) {
        if (index == 1 && access.insertion) onDone()
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
                transitionSpec = { pageTransition(forward = targetState > initialState) },
                modifier = Modifier.weight(1f),
                label = "step",
            ) { i ->
                StepPage(steps[i], access, actions, unlocking, unlockStage, { unlockStage = it }, onNext = { index = 1 })
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
    unlocking: Boolean,
    unlockStage: Int,
    setUnlockStage: (Int) -> Unit,
    onNext: () -> Unit,
) {
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
                Step.INSERTION -> Insertion(if (unlocking) unlockStage else null, unlock)
            }
            Spacer(Modifier.height(24.dp))
        }
        if (step == Step.WELCOME) Disclosure()
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Spacer(Modifier.weight(1f))
            val (label, onClick) = when {
                step == Step.WELCOME && access.microphone -> R.string.onb_continue to onNext
                step == Step.WELCOME -> R.string.onb_allow_mic to actions.requestMicrophone
                unlocking -> (if (unlockStage == 1) R.string.open_app_info else R.string.open_settings) to unlock[unlockStage]
                else -> R.string.open_settings to actions.openAccessibility
            }
            Button(
                onClick = onClick,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.heightIn(min = ButtonDefaults.MediumContainerHeight),
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
    ShapeBadge(Icons.Rounded.GraphicEq, MaterialShapes.Cookie9Sided, size = BADGE)
    Title(R.string.onb_welcome_title)
    Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge)
}

/** The disclosure in one line above the button; the full text is behind Privacy. */
@Composable
private fun Disclosure() {
    val colors = MaterialTheme.colorScheme
    val text = buildAnnotatedString {
        append(stringResource(R.string.onb_disclosure))
        append(" ")
        withLink(LinkAnnotation.Url("$SOURCE#what-leaves-your-phone", TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)))) {
            append(stringResource(R.string.privacy))
        }
    }
    Text(text, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
}

private val BADGE = 88.dp

@Composable
private fun Title(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(top = 32.dp, bottom = 16.dp).semantics { heading() },
    )
}

/**
 * The accessibility service. For a file install, [stage] is where the user is in Android's
 * unlock: tried the service once, then allowed restricted settings in App info; tapping an
 * earlier step does it again. Null when Android doesn't block it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Insertion(stage: Int?, actions: List<() -> Unit>) {
    ShapeBadge(Icons.Rounded.Keyboard, MaterialShapes.SoftBurst, size = if (stage == null) BADGE else 64.dp)
    Title(R.string.onb_insert_title)
    Text(stringResource(R.string.onb_insert_why), style = MaterialTheme.typography.bodyLarge)
    if (stage == null) return
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.onb_unlock_why), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(R.string.onb_unlock_1, R.string.onb_unlock_2, R.string.onb_unlock_3).forEachIndexed { i, title ->
            val past = i < stage
            val current = i == stage
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .clickable(enabled = i <= stage, onClick = actions[i]).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
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
                Text(
                    stringResource(title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (current) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}
