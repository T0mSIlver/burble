package dev.silver.burble.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
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
import dev.silver.burble.Dictation
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

/**
 * What Burble does with the microphone, then the accessibility service, then a first dictation
 * into a field of Burble's own.
 */
enum class Step { WELCOME, INSERTION, TRY }

/**
 * Android's restricted-settings unlock for a file install, in order. Android shows "Allow
 * restricted settings" only after the user tried to turn the service on and saw it refused, and
 * apps can't read either fact: each step's button opens what it says ([button]) and moves on to
 * the next step.
 */
enum class Unlock(@StringRes val title: Int, @StringRes val button: Int) {
    /** Try to turn the service on, so that Android refuses it. */
    REFUSED(R.string.onb_unlock_1, R.string.open_settings),
    /** Allow restricted settings in App info. */
    ALLOW(R.string.onb_unlock_2, R.string.open_app_info),
    /** Turn the service on, now allowed. */
    TURN_ON(R.string.onb_unlock_3, R.string.open_settings),
    ;

    fun open(actions: AccessActions) = if (this == ALLOW) actions.openAppInfo() else actions.openAccessibility()

    /** The step after this one's button; the last stays. */
    val next get() = entries.getOrElse(ordinal + 1) { this }
}

/**
 * Moves on to Try it by itself once the accessibility service is on: the bubble is then on
 * screen. A file install gets Android's restricted-settings unlock on the second screen, decided
 * when onboarding opens so it doesn't vanish midway. [tryField] holds what was dictated in Try it.
 */
@Composable
fun Onboarding(
    access: Access,
    actions: AccessActions,
    dictation: Dictation.State,
    onDone: () -> Unit,
    // Reopened after leaving Try it, onboarding starts there again.
    initial: Step = if (access.microphone && access.insertion) Step.TRY else Step.WELCOME,
    initialUnlock: Unlock = Unlock.REFUSED,
    tryField: TextFieldState = rememberTextFieldState(),
) {
    // Keys of their own: state saved by a build that kept these as numbers is never read back as
    // an enum.
    var step by rememberSaveable(key = "onboarding-step") { mutableStateOf(initial) }
    val unlocking = rememberSaveable { access.restricted }
    var unlock by rememberSaveable(key = "onboarding-unlock") { mutableStateOf(initialUnlock) }
    // The microphone granted on the first screen moves on; one granted before needs Continue.
    val micOnArrival = remember(step) { access.microphone }
    LaunchedEffect(step, access.microphone) {
        if (step == Step.WELCOME && access.microphone && !micOnArrival) step = Step.INSERTION
    }
    LaunchedEffect(step, access.insertion) {
        if (step == Step.INSERTION && access.insertion) step = Step.TRY
    }
    val progress by animateFloatAsState((step.ordinal + 1f) / Step.entries.size, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "progress")

    Surface(color = MaterialTheme.colorScheme.surface) {
        // Try it's field doesn't open the keyboard on arrival; if the user taps the field it comes up,
        // and the button stays above it.
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            )
            // Back from the second step returns to the first; from the first it leaves the app. Back
            // from Try it would only bounce off the granted service: it leaves the app too.
            val stack = when (step) {
                Step.WELCOME -> listOf(Step.WELCOME)
                Step.INSERTION -> listOf(Step.WELCOME, Step.INSERTION)
                Step.TRY -> listOf(Step.TRY)
            }
            PageStack(stack, onBack = { step = Step.WELCOME }, Modifier.weight(1f)) { shown ->
                StepPage(shown, access, actions, if (unlocking) unlock else null, { unlock = it }, dictation, tryField,
                    onNext = { step = Step.INSERTION }, onDone)
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
    /** Where the user is in Android's unlock; null when Android doesn't block the service. */
    unlock: Unlock?,
    setUnlock: (Unlock) -> Unit,
    dictation: Dictation.State,
    tryField: TextFieldState,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    val tapUnlock = { u: Unlock -> u.open(actions); setUnlock(u.next) }
    // On Try it: words landed in its field, or a dictation failed, which the bubble explains and
    // whose error may clear by itself.
    var landed by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(step, dictation) {
        if (step != Step.TRY) return@LaunchedEffect
        if (dictation is Dictation.State.Done && dictation.delivered) landed = true
        if (dictation is Dictation.State.Error) failed = true
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            when (step) {
                Step.WELCOME -> Welcome()
                Step.INSERTION -> Insertion(unlock, tapUnlock)
                Step.TRY -> TryIt(tryField, dictation, landed && tryField.text.isNotBlank())
            }
            Spacer(Modifier.height(24.dp))
        }
        if (step == Step.WELCOME) Disclosure()
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Spacer(Modifier.weight(1f))
            if (step == Step.TRY) {
                TryItButton(tryField.text.isNotBlank() || failed, onDone)
                return@Row
            }
            val (label, onClick) = when {
                step == Step.WELCOME && access.microphone -> R.string.onb_continue to onNext
                step == Step.WELCOME -> R.string.onb_allow_mic to actions.requestMicrophone
                unlock != null -> unlock.button to { tapUnlock(unlock) }
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

/**
 * Skip until the field has text, or a dictation failed (the bubble says why): then Done. Both
 * at the height of the other screens' button, so the row never moves.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TryItButton(done: Boolean, onDone: () -> Unit) {
    val height = ButtonDefaults.MediumContainerHeight
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = done,
        transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
        contentAlignment = Alignment.CenterEnd,
        label = "try",
    ) { finished ->
        val modifier = Modifier.heightIn(min = height)
        val padding = ButtonDefaults.contentPaddingFor(height)
        val label = stringResource(if (finished) R.string.onb_done else R.string.onb_skip)
        val style = ButtonDefaults.textStyleFor(height)
        if (finished) Button(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = modifier, contentPadding = padding) { Text(label, style = style) }
        else TextButton(onClick = onDone, shapes = ButtonDefaults.shapes(), modifier = modifier, contentPadding = padding) { Text(label, style = style) }
    }
}

/**
 * A first dictation, through the real bubble and insertion: the field has focus on arrival, as
 * in any app, so the bubble's text lands in it, but the keyboard stays down: dictating without
 * one is the point. The line under it follows the dictation, and the
 * badge turns into a check mark once [landed].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TryIt(field: TextFieldState, dictation: Dictation.State, landed: Boolean) {
    val focus = remember { FocusRequester() }
    // Opened straight on this screen, the window isn't focused yet and the field would stay
    // without focus: wait for it.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) { if (windowFocused) focus.requestFocus() }
    ShapeBadge(Icons.Rounded.Mic, MaterialShapes.Sunny, done = landed, size = BADGE)
    Title(R.string.onb_try_title)
    Text(stringResource(R.string.onb_try_body), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(24.dp))
    val status = when {
        dictation == Dictation.State.Listening -> R.string.onb_try_listening
        dictation == Dictation.State.Finishing -> R.string.mic_finishing
        landed -> R.string.onb_try_landed
        else -> null
    }
    // Whole, with the line under it, while the dictation moves on and after a rotation; matters
    // once a tap on the field has brought the keyboard up.
    val view = remember { BringIntoViewRequester() }
    val orientation = LocalConfiguration.current.orientation
    LaunchedEffect(dictation, orientation, status) { view.bringIntoView() }
    OutlinedTextField(
        state = field,
        modifier = Modifier.fillMaxWidth().focusRequester(focus).bringIntoViewRequester(view),
        placeholder = { Text(stringResource(R.string.onb_try_placeholder)) },
        supportingText = { Text(status?.let { stringResource(it) }.orEmpty()) },
        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),
        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

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
 * unlock; tapping an earlier step does it again. Null when Android doesn't block it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Insertion(stage: Unlock?, onTap: (Unlock) -> Unit) {
    ShapeBadge(Icons.Rounded.Keyboard, MaterialShapes.SoftBurst, size = if (stage == null) BADGE else 64.dp)
    Title(R.string.onb_insert_title)
    Text(stringResource(R.string.onb_insert_why), style = MaterialTheme.typography.bodyLarge)
    if (stage == null) return
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.onb_unlock_why), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(16.dp))
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Unlock.entries.forEach { u ->
            val past = u < stage
            val current = u == stage
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .clickable(enabled = u <= stage) { onTap(u) }.padding(vertical = 8.dp),
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
                        else Text("${u.ordinal + 1}", style = MaterialTheme.typography.labelLarge, color = if (current) colors.onPrimary else colors.onSurfaceVariant)
                    }
                }
                Text(
                    stringResource(u.title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (current) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}
