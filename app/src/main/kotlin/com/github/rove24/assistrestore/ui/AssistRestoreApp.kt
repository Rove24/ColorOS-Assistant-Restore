package com.github.rove24.assistrestore.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Help
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.github.rove24.assistrestore.AssistConfig
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/* --------------------------------------------------------------------------------------------- */
/* Preview data                                                                                   */
/* --------------------------------------------------------------------------------------------- */

private val ENTRY_NAMES = listOf("长按电源键", "长按手势条", "滑动唤醒")

/** Entry identifiers as stored in the configuration, in the same order as [ENTRY_NAMES]. */
/** The OEM assistant: it declares no voice interaction service, so it is started as an activity. */
private const val BREENO_PACKAGE = "com.heytap.speechassist"

private val ENTRY_IDS = listOf(
    AssistConfig.ENTRY_POWER,
    AssistConfig.ENTRY_HANDLE,
    AssistConfig.ENTRY_CORNER,
)

/** What one entry point wakes. Exactly one of these is active per entry. */
/** Reads the three entries' targets out of the stored configuration. */
private fun loadChoices(store: SettingsStore): List<TargetChoice> = ENTRY_IDS.map { entry ->
    when (store.mode(entry)) {
        AssistConfig.MODE_CTS -> TargetChoice.CircleToSearch
        AssistConfig.MODE_APP -> TargetChoice.App(store.targetPackage(entry))
        AssistConfig.MODE_CUSTOM -> TargetChoice.Custom(store.targetPackage(entry))
        AssistConfig.MODE_OEM -> TargetChoice.Oem
        AssistConfig.MODE_NONE -> TargetChoice.None
        else -> TargetChoice.FollowDefault
    }
}

/**
 * Single choice that also allows "everything off": turning the active row off means the entry wakes
 * nothing, the OEM invocation included.
 */
private fun toggleChoice(
    on: Boolean,
    target: TargetChoice,
    onSelectChoice: (TargetChoice) -> Unit,
) {
    onSelectChoice(if (on) target else TargetChoice.None)
}

/** The four things the paste box understands. */
private data class PastedIntent(
    val action: String = "",
    val packageName: String = "",
    val className: String = "",
    val category: String = "",
    val extra: String = "",
)

/**
 * Reads the {@code {"action": "...", "packageName": "...", "className": "..."}} form used by app
 * inspection tools. Deliberately lenient: unknown keys are ignored and missing values are dropped.
 */
private fun parseIntentJson(text: String): PastedIntent? {
    val values = LinkedHashMap<String, String>()
    var index = 0
    while (true) {
        val keyStart = text.indexOf('"', index)
        if (keyStart < 0) break
        val keyEnd = text.indexOf('"', keyStart + 1)
        if (keyEnd < 0) break
        val key = text.substring(keyStart + 1, keyEnd)
        val colon = text.indexOf(':', keyEnd)
        if (colon < 0) break
        var valueStart = colon + 1
        while (valueStart < text.length && text[valueStart] == ' ') valueStart++
        if (valueStart < text.length && text[valueStart] == '"') {
            val valueEnd = text.indexOf('"', valueStart + 1)
            if (valueEnd < 0) break
            values[key] = text.substring(valueStart + 1, valueEnd)
            index = valueEnd + 1
        } else {
            var valueEnd = text.indexOf(',', valueStart)
            if (valueEnd < 0) valueEnd = text.length
            values[key] = text.substring(valueStart, valueEnd).trim()
            index = valueEnd
        }
    }
    if (values.isEmpty()) return null
    return PastedIntent(
        action = values["action"].orEmpty(),
        packageName = values["packageName"].orEmpty(),
        className = values["className"].orEmpty(),
        category = values["category"].orEmpty(),
        extra = values["extra"].orEmpty(),
    )
}

private sealed interface TargetChoice {
    data object FollowDefault : TargetChoice
    data object CircleToSearch : TargetChoice
    data class App(val packageName: String) : TargetChoice
    /** Target configured on the custom screen: explicit package / component / intent. */
    data class Custom(val packageName: String) : TargetChoice
    /** Leave the entry to ColorOS: the gesture handle stays 小布识屏. */
    data object Oem : TargetChoice
    /** Wake nothing: the entry is switched off, OEM call included. */
    data object None : TargetChoice
}

private const val ROUTE_ENTRIES = "entries"
private const val ROUTE_CUSTOM = "custom"

/** LSPosed's manager, which is where this module is actually switched on and off. */
private const val LSPOSED_PACKAGE = "org.lsposed.manager"
private const val LSPOSED_ACTIVITY = "org.lsposed.manager.ui.activity.MainActivity"

/** Where the module lives; the 关于 rows open these. */
private const val GITHUB_URL = "https://github.com/Rove24"
private const val COOLAPK_HANDLE = "@Rove24"
private const val COOLAPK_URL = "http://www.coolapk.com/u/Rove24"

/* --------------------------------------------------------------------------------------------- */
/* Root                                                                                           */
/* --------------------------------------------------------------------------------------------- */

@Composable
fun AssistRestoreApp() {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(AssistantSnapshot.load(context)) }
    val store = remember { SettingsStore(context) }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var route by remember { mutableStateOf(ROUTE_ENTRIES) }
    var entryIndex by remember { mutableIntStateOf(1) }
    var frameworkConnected by remember { mutableStateOf(App.service != null) }
    var choices by remember { mutableStateOf(loadChoices(store)) }
    var skipOcrPreload by remember { mutableStateOf(store.skipOcrPreload()) }
    var unblockPageFlags by remember { mutableStateOf(store.unblockPageFlags()) }
    var fakeGoogleBuild by remember { mutableStateOf(store.spoofGoogleBuild()) }
    var handleWhenBarHidden by remember { mutableStateOf(store.handleWhenBarHidden()) }
    var pixelLights by remember { mutableStateOf(store.pixelLights()) }
    // Not a preference: the switch reports the launcher alias' component state, which is what the
    // package manager keeps, so it also stays correct after a reinstall.
    var hideLauncherIcon by remember { mutableStateOf(LauncherIcon.isHidden(context)) }

    // The framework bridge arrives asynchronously: once it is bound the real configuration is
    // readable (and writable) instead of the local fallback.
    // The default assistant and the installed assistant apps live outside this app, so they are
    // re-read every time the screen comes back to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                snapshot = AssistantSnapshot.load(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        val listener = object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                store.bind(service)
                frameworkConnected = service != null
                choices = loadChoices(store)
                skipOcrPreload = store.skipOcrPreload()
                unblockPageFlags = store.unblockPageFlags()
                fakeGoogleBuild = store.spoofGoogleBuild()
                handleWhenBarHidden = store.handleWhenBarHidden()
                pixelLights = store.pixelLights()
            }
        }
        App.addServiceStateListener(listener, true)
        onDispose { App.removeServiceStateListener(listener) }
    }

    fun persistChoice(index: Int, choice: TargetChoice) {
        val entry = ENTRY_IDS[index]
        when (choice) {
            // Only the mode changes here; the custom fields stay untouched as history.
            TargetChoice.FollowDefault -> store.setTarget(entry, AssistConfig.MODE_DEFAULT)
            TargetChoice.CircleToSearch -> store.setTarget(entry, AssistConfig.MODE_CTS)
            is TargetChoice.App -> store.setTarget(
                entry, AssistConfig.MODE_APP, packageName = choice.packageName,
            )
            is TargetChoice.Custom -> store.setTarget(entry, AssistConfig.MODE_CUSTOM)
            TargetChoice.Oem -> store.setTarget(entry, AssistConfig.MODE_OEM)
            TargetChoice.None -> store.setTarget(entry, AssistConfig.MODE_NONE)
        }
    }


    val notify: (String) -> Unit = { message ->
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    val openSystemAssistantSettings: () -> Unit = {
        val candidates = listOf(
            // What ColorOS' own "默认语音助手" row opens; gated on some builds, so try it first.
            Intent("android.intent.action.MANAGE_DEFAULT_APP")
                .putExtra("android.intent.extra.ROLE_NAME", "android.app.role.ASSISTANT"),
            // Settings' assistant page (ManageAssistActivity) — the page the card is meant to open.
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            // Last resort: the default-apps list, where 数字助理应用 is one row.
            Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS"),
        )
        // First candidate that starts wins; the rest are never tried.
        candidates.any { intent -> runCatching { context.startActivity(intent) }.isSuccess }
    }

    // The module's own on/off lives in LSPosed, so the status card hands the user straight there
    // rather than carrying a switch of its own.
    val openLsposedManager: () -> Unit = {
        val direct = Intent()
            .setClassName(LSPOSED_PACKAGE, LSPOSED_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(direct) }.isSuccess
        if (!opened) {
            // Older managers keep the activity name private; the launcher entry always works.
            context.packageManager.getLaunchIntentForPackage(LSPOSED_PACKAGE)?.let { launch ->
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(launch) }
            }
        }
    }

    // The home page is the whole settings surface now: every entry point has its own category and
    // the page simply scrolls. The only thing that still leaves it is the custom-target editor.
    // Each screen keeps its own `rememberSaveable` state while the other one is on top — the
    // home page's scroll position in particular, so returning from the custom-target editor
    // lands where the user left off instead of jumping back to the top.
    val saveableStateHolder = rememberSaveableStateHolder()

    saveableStateHolder.SaveableStateProvider(route) {
        when (route) {
            ROUTE_CUSTOM -> {
                BackHandler { route = ROUTE_ENTRIES }
                CustomTargetScreen(
                    entry = ENTRY_IDS[entryIndex],
                    store = store,
                    onBack = {
                        route = ROUTE_ENTRIES
                        // The custom screen writes straight to the store, so the entry list has to be
                        // re-read or the home page keeps showing the old selection.
                        choices = loadChoices(store)
                    },
                    snackbarHost = snackbarHost,
                    notify = notify,
                )
            }
    
            else -> EntriesScreen(
                snapshot = snapshot,
                choices = choices,
                onSelectChoice = { index, picked ->
                    choices = choices.toMutableList().also { it[index] = picked }
                    persistChoice(index, picked)
                },
                onOpenCustom = { index ->
                    entryIndex = index
                    route = ROUTE_CUSTOM
                },
                onOpenAssistantSettings = openSystemAssistantSettings,
                frameworkConnected = frameworkConnected,
                skipOcrPreload = skipOcrPreload,
                onSkipOcrPreloadChange = { skipOcrPreload = it; store.setSkipOcrPreload(it) },
                unblockPageFlags = unblockPageFlags,
                onUnblockPageFlagsChange = { unblockPageFlags = it; store.setUnblockPageFlags(it) },
                fakeGoogleBuild = fakeGoogleBuild,
                onFakeGoogleBuildChange = { fakeGoogleBuild = it; store.setSpoofGoogleBuild(it) },
                handleWhenBarHidden = handleWhenBarHidden,
                onHandleWhenBarHiddenChange = {
                    handleWhenBarHidden = it
                    store.setHandleWhenBarHidden(it)
                },
                hideLauncherIcon = hideLauncherIcon,
                onHideLauncherIconChange = {
                    hideLauncherIcon = it
                    LauncherIcon.setHidden(context, it)
                },
                onOpenLsposed = openLsposedManager,
                pixelLights = pixelLights,
                onPixelLightsChange = { pixelLights = it; store.setPixelLights(it) },
                snackbarHost = snackbarHost,
            )
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Shared building blocks                                                                         */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun AppScreen(
    title: String,
    snackbarHost: SnackbarHostState? = null,
    onBack: (() -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { AppTopBar(title, onBack, actions) },
        bottomBar = { bottomBar?.invoke() },
        snackbarHost = { if (snackbarHost != null) SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { insets ->
        val scrollState = rememberScrollState()
        Box(
            Modifier
                .padding(insets)
                .fillMaxSize()
        ) {
            content(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            )
            ScrollBar(
                state = scrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = 6.dp, end = 2.dp, bottom = 6.dp)
                    .width(4.dp),
            )
        }
    }
}

/**
 * The thin fading scroll indicator the platform's own scrolling views draw. Compose has no scrollbar
 * slot, and the sibling modules get one for free from their `RecyclerView`, so this reproduces the
 * same look: a 4dp rounded thumb hugging the right edge that fades in while the list moves and
 * fades out shortly after it settles. Nothing is drawn when the content already fits.
 */
@Composable
private fun ScrollBar(
    state: ScrollState,
    modifier: Modifier = Modifier,
) {
    var engaged by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            engaged = true
        } else {
            delay(700)
            engaged = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (engaged) 1f else 0f,
        animationSpec = tween(durationMillis = if (engaged) 90 else 400),
        label = "scrollBarAlpha",
    )
    val thumbColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier) {
        val scrollable = state.maxValue
        if (scrollable <= 0) return@Canvas
        val viewport = size.height
        val thumb = (viewport * viewport / (viewport + scrollable)).coerceAtLeast(28.dp.toPx())
        val top = (viewport - thumb) * (state.value.toFloat() / scrollable)
        drawRoundRect(
            color = thumbColor.copy(alpha = 0.35f * alpha),
            topLeft = Offset(0f, top),
            size = Size(size.width, thumb),
            cornerRadius = CornerRadius(size.width / 2f),
        )
    }
}

@Composable
private fun AppTopBar(
    title: String,
    onBack: (() -> Unit)?,
    actions: (@Composable () -> Unit)?,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(64.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            actions?.invoke()
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * The home page's top card: whether the framework actually loaded the module, as a row card that
 * matches {@link AssistantRowCard} line for line - same padding, same 40dp leading icon, same
 * two-line text block - so the two read as a matched pair.
 *
 * <p>Tapping it opens LSPosed, which is where the module is actually switched on and off; the card
 * only reports the result. The master switch that used to live here is its own row again, as it was
 * before the two were merged.</p>
 */
@Composable
private fun ModuleStatusCard(
    active: Boolean,
    onClick: () -> Unit,
) {
    val badgeColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val badgeContent = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Cards.Corner))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Cards.Corner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (active) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    tint = badgeContent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (active) "模块已生效" else "模块未生效",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (active) "4 个作用域已挂载" else "未连接 Xposed 服务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Chevron()
        }
    }
}

/** The default assistant as a row card: its own launcher icon, the label and a chevron. */
@Composable
private fun AssistantRowCard(
    snapshot: AssistantSnapshot,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Cards.Corner))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Cards.Corner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val icon = snapshot.defaultIcon
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SettingsVoice,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "默认助理", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = snapshot.defaultLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Chevron()
        }
    }
}

@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Cards.Corner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
    ) {
        Column(content = content)
    }
}

/**
 * A group heading in the settings list, matching the sibling modules' `layout/preference_category`
 * TextView one-for-one: `13sp`, `sans-serif-medium`, `@color/m3_category_title` (which carries the
 * same two values as `m3_primary`, so it is read straight off the colour scheme), inset `32dp` from
 * the screen edge, `10dp` above and `4dp` below, single line.
 *
 * The `16dp` here is on top of the screen's own `16dp` gutter — the callers already sit inside a
 * `Column(Modifier.padding(horizontal = 16.dp))`, so the two add up to the reference's `32dp`.
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconBitmap: ImageBitmap? = null,
    /**
     * Reserve the 40dp leading slot even without an icon, so titles stay in one column. The 关于
     * rows turn it off to sit at the card's own 16dp inset, exactly like the sibling modules.
     */
    reserveIconSpace: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            iconBitmap != null -> Image(
                bitmap = iconBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(40.dp),
            )

            icon != null -> Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }

            else -> if (reserveIconSpace) Spacer(Modifier.width(40.dp)) else Unit
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        trailing?.invoke()
    }
}

/**
 * The 1dp line a row draws along its own bottom edge, exactly as the reference modules do it: full
 * width with no inset, in `m3_divider`. It is not a Material divider — the reference kit paints it
 * into each row's background drawable, so it spans the whole card and never stops short of the
 * content.
 */
@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(cardDivider)
    )
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onChange)
}

/* --------------------------------------------------------------------------------------------- */
/* Home: the whole settings surface                                                               */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun EntriesScreen(
    snapshot: AssistantSnapshot,
    choices: List<TargetChoice>,
    onSelectChoice: (Int, TargetChoice) -> Unit,
    onOpenCustom: (Int) -> Unit,
    onOpenAssistantSettings: () -> Unit,
    frameworkConnected: Boolean,
    skipOcrPreload: Boolean,
    onSkipOcrPreloadChange: (Boolean) -> Unit,
    unblockPageFlags: Boolean,
    onUnblockPageFlagsChange: (Boolean) -> Unit,
    fakeGoogleBuild: Boolean,
    onFakeGoogleBuildChange: (Boolean) -> Unit,
    handleWhenBarHidden: Boolean,
    onHandleWhenBarHiddenChange: (Boolean) -> Unit,
    hideLauncherIcon: Boolean,
    onHideLauncherIconChange: (Boolean) -> Unit,
    onOpenLsposed: () -> Unit,
    pixelLights: Boolean,
    onPixelLightsChange: (Boolean) -> Unit,
    snackbarHost: SnackbarHostState,
) {
    val context = LocalContext.current
    val versionLabel = remember(context) { installedVersionLabel(context) }
    AppScreen(
        title = "ColorOS 唤语",
        snackbarHost = snackbarHost,
    ) { modifier ->
        Column(modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))

            ModuleStatusCard(
                active = frameworkConnected,
                onClick = onOpenLsposed,
            )

            Spacer(Modifier.height(12.dp))

            AssistantRowCard(snapshot = snapshot, onClick = onOpenAssistantSettings)

            // Explains the switch semantics before the first category, so the three entry groups
            // below read as "one target each" rather than as three independent on/off settings.
            // Inset to the same 32dp the section headers sit at, and indented like Chinese body
            // copy (two characters on the first line).
            Spacer(Modifier.height(12.dp))
            Text(
                text = "三个入口各存一份目标；全部关掉 = 这个入口不唤醒任何助理（ColorOS 原生调用也一并关掉）。" +
                    "滑动唤醒那项改动要重启手机后桌面才会重新判断。",
                style = MaterialTheme.typography.bodySmall.copy(
                    textIndent = TextIndent(firstLine = 2.em),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 「（ColorOS」 is unbreakable — a line-ending parenthesis is forbidden and a Latin
                // word cannot be split — so the line breaker pushes the whole run down and leaves a
                // gap at the end of the line above. Justifying spreads that gap out instead, which
                // is how Chinese body text is set anyway.
                textAlign = TextAlign.Justify,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            )

            // One category per entry point, holding that entry's own target options. The page just
            // scrolls; there is no separate target screen any more.
            ENTRY_NAMES.forEachIndexed { index, name ->
                // The 4dp mirrors `m3_card_margin_bottom` on the last row of a group in the sibling
                // modules; the rest of the gap is the header's own 10dp top padding.
                Spacer(Modifier.height(4.dp))
                SectionHeader(name)

                SectionCard {
                    val choice = choices[index]
                    val pick: (TargetChoice) -> Unit = { onSelectChoice(index, it) }

                    TargetOptionRow(
                        title = "跟随系统默认",
                        subtitle = "当前系统默认：${snapshot.defaultLabel}",
                        icon = Icons.Rounded.SettingsVoice,
                        checked = choice is TargetChoice.FollowDefault,
                        onCheckedChange = { toggleChoice(it, TargetChoice.FollowDefault, pick) },
                    )
                    RowDivider()
                    TargetOptionRow(
                        title = "即圈即搜",
                        subtitle = "走系统 CTS 服务，不跟随默认助理设置",
                        icon = Icons.Rounded.Search,
                        checked = choice is TargetChoice.CircleToSearch,
                        onCheckedChange = { toggleChoice(it, TargetChoice.CircleToSearch, pick) },
                    )
                    RowDivider()
                    if (ENTRY_IDS[index] == AssistConfig.ENTRY_HANDLE) {
                        // The handle long press is ColorOS' own screen-recognition entry, so
                        // "leave it to the OEM" only makes sense here.
                        TargetOptionRow(
                            title = "小布识屏",
                            subtitle = "不接管：长按手势条仍由 ColorOS 自己处理",
                            icon = Icons.Rounded.Tune,
                            checked = choice is TargetChoice.Oem,
                            onCheckedChange = { toggleChoice(it, TargetChoice.Oem, pick) },
                        )
                    } else {
                        // The OEM assistant ships an ACTION_ASSIST activity but no voice
                        // interaction service, so it is offered as a plain app target.
                        TargetOptionRow(
                            title = "小布助手",
                            subtitle = "只有 ACTION_ASSIST 活动，直接唤起小布助手本体",
                            icon = Icons.Rounded.SettingsVoice,
                            checked = choice is TargetChoice.App &&
                                choice.packageName == BREENO_PACKAGE,
                            onCheckedChange = {
                                toggleChoice(it, TargetChoice.App(BREENO_PACKAGE), pick)
                            },
                        )
                    }
                    RowDivider()
                    ListRow(
                        title = "其他应用…",
                        subtitle = "手动填包名或服务组件，用该应用自己的助理入口",
                        icon = Icons.Rounded.Add,
                        trailing = { Chevron() },
                        onClick = { onOpenCustom(index) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            SectionHeader("其他")

            SectionCard {
                ListRow(
                    title = "跳过识屏服务预绑定",
                    subtitle = "长按手势条时不再白唤醒一次小布识屏服务",
                    icon = Icons.Rounded.Block,
                    trailing = { Toggle(skipOcrPreload, onSkipOcrPreloadChange) },
                )
                RowDivider()
                ListRow(
                    title = "解除页面级手势限制",
                    subtitle = "设置这类页面也能用滑动唤醒",
                    icon = Icons.Rounded.LockOpen,
                    trailing = { Toggle(unblockPageFlags, onUnblockPageFlagsChange) },
                )
                RowDivider()
                ListRow(
                    title = "Google 应用机型伪装",
                    subtitle = "伪装为 Pixel 11 Pro XL，解锁即圈即搜",
                    icon = Icons.Rounded.Smartphone,
                    trailing = { Toggle(fakeGoogleBuild, onFakeGoogleBuildChange) },
                )
                RowDivider()
                ListRow(
                    title = "Pixel 四色光弧",
                    subtitle = "把滑动唤醒的光弧换成 Google 四色光流与聚拢动效",
                    icon = Icons.Rounded.Palette,
                    trailing = { Toggle(pixelLights, onPixelLightsChange) },
                )
                RowDivider()
                ListRow(
                    title = "隐藏手势条时保持长按",
                    subtitle = "手势条隐藏后，底部原位置的长按仍能召唤助理",
                    icon = Icons.Rounded.TouchApp,
                    trailing = { Toggle(handleWhenBarHidden, onHandleWhenBarHiddenChange) },
                )
                RowDivider()
                ListRow(
                    title = "隐藏桌面图标",
                    subtitle = "隐藏后可从 LSPosed 模块页或系统设置的应用详情打开",
                    icon = Icons.Rounded.VisibilityOff,
                    trailing = { Toggle(hideLauncherIcon, onHideLauncherIconChange) },
                )
            }

            Spacer(Modifier.height(4.dp))
            SectionHeader("关于")

            SectionCard {
                ListRow(
                    title = "版本信息",
                    subtitle = versionLabel,
                    reserveIconSpace = false,
                )
                RowDivider()
                ListRow(
                    title = "GitHub 主页",
                    subtitle = GITHUB_URL,
                    reserveIconSpace = false,
                    onClick = { openUrl(context, GITHUB_URL) },
                )
                RowDivider()
                ListRow(
                    title = "酷安主页",
                    subtitle = COOLAPK_HANDLE,
                    reserveIconSpace = false,
                    onClick = { openUrl(context, COOLAPK_URL) },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** {@code "1.2 (12)"} — the installed build, read straight from the package manager. */
private fun installedVersionLabel(context: Context): String {
    val info = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull() ?: return ""
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    return "${info.versionName} ($code)"
}

/** Opens a link in whichever app handles it; a missing handler is not worth interrupting for. */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** One selectable target inside an entry's category; exactly one row per category is on. */
@Composable
private fun TargetOptionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        trailing = { Toggle(checked, onCheckedChange) },
    )
}

/* --------------------------------------------------------------------------------------------- */
/* Custom target                                                                                  */
/* --------------------------------------------------------------------------------------------- */

@Composable
private fun CustomTargetScreen(
    entry: String,
    store: SettingsStore,
    onBack: () -> Unit,
    snackbarHost: SnackbarHostState,
    notify: (String) -> Unit,
) {
    var packageName by remember {
        mutableStateOf(store.targetPackage(entry).ifEmpty { "com.heytap.speechassist" })
    }
    var component by remember { mutableStateOf(store.targetComponent(entry)) }
    var intentArgs by remember {
        mutableStateOf(
            store.targetArgs(entry).ifEmpty {
                "action=heytap.intent.action.ACTIVATE_SPEECH_ASSIST&start_type=91"
            }
        )
    }

    val methods = listOf(
        AssistConfig.METHOD_AUTO,
        AssistConfig.METHOD_ASSIST,
        AssistConfig.METHOD_INTENT,
    )
    val methodLabels = listOf("自动", "ACTION_ASSIST", "显式 Intent")
    var methodIndex by remember {
        mutableIntStateOf(methods.indexOf(store.targetMethod(entry)).coerceAtLeast(0))
    }
    var pasted by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }

    AppScreen(title = "自定义目标", snackbarHost = snackbarHost, onBack = onBack) { modifier ->
        Column(modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text("粘贴 Intent JSON") },
                supportingText = { Text("把应用信息里那段 JSON 粘进来，点下面按钮自动填") },
                leadingIcon = { Icon(Icons.Rounded.DataObject, contentDescription = null) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val parsed = parseIntentJson(pasted)
                    if (parsed == null) {
                        notify("没认出 JSON，检查一下格式")
                    } else {
                        if (parsed.packageName.isNotEmpty()) {
                            packageName = parsed.packageName
                        }
                        if (parsed.className.isNotEmpty()) {
                            component = parsed.className
                        }
                        methodIndex =
                            methods.indexOf(AssistConfig.METHOD_INTENT).coerceAtLeast(0)
                        val builder = StringBuilder()
                        if (parsed.action.isNotEmpty()) {
                            builder.append("action=").append(parsed.action)
                        }
                        if (parsed.category.isNotEmpty()) {
                            if (builder.isNotEmpty()) builder.append("&")
                            builder.append("category=").append(parsed.category)
                        }
                        if (parsed.extra.isNotEmpty()) {
                            if (builder.isNotEmpty()) builder.append("&")
                            builder.append(parsed.extra)
                        }
                        intentArgs = builder.toString()
                        notify("已识别并填入，确认后按「保存目标」")
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("识别并填入")
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = packageName,
                onValueChange = { packageName = it },
                label = { Text("包名") },
                supportingText = { Text("例如 com.heytap.speechassist") },
                leadingIcon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = component,
                onValueChange = { component = it },
                label = { Text("服务组件") },
                supportingText = { Text("com.heytap.speechassist/.service.SpeechAssistService") },
                leadingIcon = { Icon(Icons.Rounded.Widgets, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Column {
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = MaterialTheme.shapes.extraSmall,
                            )
                            .clickable { menuOpen = true }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallSplit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            text = methodLabels[methodIndex],
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        methodLabels.forEachIndexed { index, name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    methodIndex = index
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "自动：优先该应用声明的助理活动；显式 Intent：按下面的组件与参数启动",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = intentArgs,
                onValueChange = { intentArgs = it },
                label = { Text("Intent 参数") },
                supportingText = {
                    Text("action=heytap.intent.action.ACTIVATE_SPEECH_ASSIST · start_type=91")
                },
                leadingIcon = { Icon(Icons.Rounded.DataObject, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    store.setTarget(
                        entry = entry,
                        mode = AssistConfig.MODE_CUSTOM,
                        packageName = packageName.trim(),
                        component = component.trim(),
                        method = methods[methodIndex],
                        args = intentArgs.trim(),
                    )
                    notify("已保存：该入口改为自定义目标")
                    notify(
                        "已保存：" + store.mode(entry) +
                            " · " + store.targetPackage(entry) +
                            " · " + store.targetComponent(entry) +
                            " · " + store.targetMethod(entry)
                    )
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("保存目标")
            }


            Spacer(Modifier.height(24.dp))
        }
    }
}

/* --------------------------------------------------------------------------------------------- */
/* Logs                                                                                           */
/* --------------------------------------------------------------------------------------------- */
