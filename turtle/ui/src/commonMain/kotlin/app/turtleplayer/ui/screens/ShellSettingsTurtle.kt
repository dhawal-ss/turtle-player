package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.HomeYouTubeRows
import app.turtleplayer.bridge.InfinityGenres
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_inf_fresh
import app.turtleplayer.ui.generated.resources.shell_inf_fresh_body
import app.turtleplayer.ui.generated.resources.shell_inf_genres
import app.turtleplayer.ui.generated.resources.shell_inf_moods
import app.turtleplayer.ui.generated.resources.shell_inf_sheet_body
import app.turtleplayer.ui.generated.resources.shell_inf_tuned
import app.turtleplayer.ui.generated.resources.shell_infinity_title
import app.turtleplayer.ui.generated.resources.shell_set_about
import app.turtleplayer.ui.generated.resources.shell_set_about_sub
import app.turtleplayer.ui.generated.resources.shell_set_advanced
import app.turtleplayer.ui.generated.resources.shell_set_advanced_sub
import app.turtleplayer.ui.generated.resources.shell_set_app_name
import app.turtleplayer.ui.generated.resources.shell_set_built_on
import app.turtleplayer.ui.generated.resources.shell_set_built_on_body
import app.turtleplayer.ui.generated.resources.shell_set_content
import app.turtleplayer.ui.generated.resources.shell_set_devtools_on
import app.turtleplayer.ui.generated.resources.shell_set_display
import app.turtleplayer.ui.generated.resources.shell_set_gpl
import app.turtleplayer.ui.generated.resources.shell_set_gpl_link
import app.turtleplayer.ui.generated.resources.shell_set_group_app
import app.turtleplayer.ui.generated.resources.shell_set_group_library
import app.turtleplayer.ui.generated.resources.shell_set_group_listening
import app.turtleplayer.ui.generated.resources.shell_set_group_privacy
import app.turtleplayer.ui.generated.resources.shell_set_group_turtle
import app.turtleplayer.ui.generated.resources.shell_set_hidden
import app.turtleplayer.ui.generated.resources.shell_set_hidden_sub
import app.turtleplayer.ui.generated.resources.shell_set_home_youtube
import app.turtleplayer.ui.generated.resources.shell_set_home_youtube_all
import app.turtleplayer.ui.generated.resources.shell_set_home_youtube_body
import app.turtleplayer.ui.generated.resources.shell_set_home_youtube_matched
import app.turtleplayer.ui.generated.resources.shell_set_home_youtube_off
import app.turtleplayer.ui.generated.resources.shell_set_infinity_sub
import app.turtleplayer.ui.generated.resources.shell_set_integrations_sub
import app.turtleplayer.ui.generated.resources.shell_set_metrolist_source
import app.turtleplayer.ui.generated.resources.shell_set_more
import app.turtleplayer.ui.generated.resources.shell_set_navigation
import app.turtleplayer.ui.generated.resources.shell_set_palette
import app.turtleplayer.ui.generated.resources.shell_set_playback
import app.turtleplayer.ui.generated.resources.shell_set_playback_sub
import app.turtleplayer.ui.generated.resources.shell_set_recs
import app.turtleplayer.ui.generated.resources.shell_set_recs_dial
import app.turtleplayer.ui.generated.resources.shell_set_recs_dial_body
import app.turtleplayer.ui.generated.resources.shell_set_recs_familiar_body
import app.turtleplayer.ui.generated.resources.shell_set_recs_familiar_title
import app.turtleplayer.ui.generated.resources.shell_set_recs_how
import app.turtleplayer.ui.generated.resources.shell_set_recs_new_body
import app.turtleplayer.ui.generated.resources.shell_set_recs_new_title
import app.turtleplayer.ui.generated.resources.shell_set_recs_sub
import app.turtleplayer.ui.generated.resources.shell_set_recs_tune
import app.turtleplayer.ui.generated.resources.shell_set_recs_tuning_body
import app.turtleplayer.ui.generated.resources.shell_set_recs_tuning_title
import app.turtleplayer.ui.generated.resources.shell_set_spoken
import app.turtleplayer.ui.generated.resources.shell_set_spoken_speed_body
import app.turtleplayer.ui.generated.resources.shell_set_spoken_sub
import app.turtleplayer.ui.generated.resources.shell_set_theme_sub
import app.turtleplayer.ui.generated.resources.shell_set_tuning_none
import app.turtleplayer.ui.generated.resources.shell_set_version
import app.turtleplayer.ui.generated.resources.shell_set_window
import app.turtleplayer.ui.generated.resources.shell_sp_speed
import app.turtleplayer.ui.generated.resources.shell_sp_speed_value
import app.turtleplayer.ui.generated.resources.shell_steer_title
import app.turtleplayer.ui.generated.resources.shell_theme_body
import app.turtleplayer.ui.generated.resources.shell_theme_title
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPalettes
import app.turtleplayer.ui.theme.ShellPlatform
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.generated.resources.credits_license_desc
import com.metrolist.shared.generated.resources.credits_license_name
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.integrations
import com.metrolist.shared.generated.resources.logo
import com.metrolist.shared.generated.resources.settings_artist_sources
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.screens.AboutCommunityLinkKind
import com.metrolist.shared.ui.screens.AboutContributor
import com.metrolist.shared.ui.screens.AboutContributors
import com.metrolist.shared.ui.screens.aboutCommunityLinks
import com.metrolist.shared.ui.screens.rememberAboutScreenText
import com.metrolist.shared.ui.settings.SettingsChoice
import com.metrolist.shared.ui.settings.SettingsControl
import com.metrolist.shared.ui.settings.SettingsGroupRegistration
import com.metrolist.shared.ui.settings.SettingsItemRegistration
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPageRegistration
import com.metrolist.shared.ui.settings.SettingsPlatform
import com.metrolist.shared.ui.settings.SettingsRootGroupRegistration
import com.metrolist.shared.ui.settings.SettingsTone
import com.metrolist.shared.ui.settings.SettingsUiRegistry
import com.metrolist.shared.ui.settings.SharedSettingsHost
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import com.metrolist.shared.generated.resources.Res as SharedRes

/*
 * Turtle's own Settings (product owner, 20 Nov 2026: "only specific to Turtle, not to Metrolist").
 * Upstream's registry still owns every setting that changes behaviour; this file reshapes it before
 * Shell draws it: Metrolist-only and Shell-overridden settings are left out (see the KEEP lists below,
 * each checked against the code that reads the setting), the kept ones are regrouped under plain
 * names, and Turtle's own pages come first: Theme, Infinity Mix, Recommendations and Spoken
 * word, plus About Turtle with the GPL notice and the Metrolist credits.
 *
 * SettingsPageId is upstream's enum, so Turtle pages reuse ids of upstream pages Turtle hides
 * (APPEARANCE, PLAYER_STYLE, PLAYER_ARTWORK, MINI_PLAYER_STYLE, FONTS, UPDATES, ABOUT, DISCORD); no
 * upstream page with those ids is shown. Search only sees this reshaped registry, so it never finds
 * a hidden setting. The Integrations screen (its own registry, without PLAYER) passes through as is.
 */

/** The registry Shell draws, plus pages that open elsewhere (Integrations on Android). */
internal class TurtleSettingsModel(
    val registry: SettingsUiRegistry,
    val openers: Map<SettingsPageId, () -> Unit>,
)

private val settingsScope = MainScope()

private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/** Upstream Appearance items that still work under Shell: display size (Android and desktop density). */
private val KEEP_DISPLAY = listOf("densityScaleFactor", "enableLandscapeScaling")

/** Desktop window behaviour (frame, Wayland scaling, tray) and the search shortcut. */
private val KEEP_WINDOW = listOf("systemWindowFrame", "disableWaylandScaleFix", "minimizeToTrayOnClose", "desktopTrayToggleOnClick", "floatingSearch")

/** Tabs Shell's navigation shows, the start tab, tablet bottom bar, song-row swipes and haptics. */
private val KEEP_NAVIGATION =
    listOf("defaultOpenTab", "showHomeTab", "showSearchTab", "showLibraryTab", "forceBottomNavBar", "swipeToSong", "swipeToRemoveSong", "haptics")

/**
 * Lyrics: Shell draws its own lyrics, so only fetching settings and the text size and line spacing it
 * reads apply (plus the sources, AI and romanization pages).
 */
private val KEEP_LYRICS_BEHAVIOR = setOf("preferWordSyncedLyrics")
private val KEEP_LYRICS_DISPLAY = setOf("lyricsTextSize", "lyricsLineSpacing")
private val HIDE_LYRICS_GROUPS = setOf("alignment", "accessibility")

/** Metrolist's Sentry crash reports go to Metrolist's servers. */
private val HIDE_PRIVACY = setOf("sentry")

/** Upstream pages kept as they are (reached from the pages above). */
private val KEEP_SUBPAGES =
    listOf(
        SettingsPageId.PLAYER_QUEUE,
        SettingsPageId.CONTENT_HOME,
        SettingsPageId.EQUALIZER,
        SettingsPageId.LYRICS_SOURCES,
        SettingsPageId.LYRICS_AI_TRANSLATION,
        SettingsPageId.LYRICS_AI_SETUP,
        SettingsPageId.LYRICS_ROMANIZATION,
        SettingsPageId.HIDDEN_HOME_ITEMS,
        SettingsPageId.HIDDEN_LIBRARY_ITEMS,
        SettingsPageId.BLOCKED_ARTISTS,
        SettingsPageId.BLOCKED_SONGS,
        SettingsPageId.ANDROID_AUTO,
        SettingsPageId.SUPPORTED_LINKS,
        SettingsPageId.DEVTOOLS,
        SettingsPageId.PLAYBACK_BENCHMARK,
    )

private fun group(id: String, title: String, items: List<SettingsItemRegistration>, description: String? = null) =
    SettingsGroupRegistration(id = id, title = title, description = description, items = items)

private fun customItem(
    id: String,
    title: String,
    description: String? = null,
    content: @Composable (onSettingValueChanged: () -> Unit) -> Unit,
) = SettingsItemRegistration(id = id, title = title, description = description, control = SettingsControl.CUSTOM, customContent = content)

private fun actionItem(
    id: String,
    title: String,
    description: String? = null,
    valueLabel: String? = null,
    confirmationMessage: String? = null,
    onClick: () -> Unit,
) = SettingsItemRegistration(
    id = id,
    title = title,
    description = description,
    control = SettingsControl.ACTION,
    valueLabel = valueLabel,
    confirmationMessage = confirmationMessage,
    onClick = onClick,
)

private fun navigationItem(id: String, title: String, destination: SettingsPageId, description: String? = null) =
    SettingsItemRegistration(id = id, title = title, description = description, control = SettingsControl.NAVIGATION, destination = destination)

private fun turtlePage(id: SettingsPageId, title: String, subtitle: String?, icon: ImageVector, groups: List<SettingsGroupRegistration>) =
    SettingsPageRegistration(id = id, title = title, subtitle = subtitle, icon = icon, tone = SettingsTone.NEUTRAL, groups = groups.filter { it.items.isNotEmpty() })

/** Items with these ids from [pages], in the order given; a disabled group's items stay disabled. */
private fun SettingsUiRegistry.pick(ids: List<String>, vararg pages: SettingsPageId): List<SettingsItemRegistration> {
    val found = LinkedHashMap<String, SettingsItemRegistration>()
    pages.forEach { pageId ->
        get(pageId)?.groups?.forEach { group ->
            group.items.forEach { item ->
                if (item.id in ids && item.id !in found) found[item.id] = if (group.enabled) item else item.copy(enabled = false)
            }
        }
    }
    return ids.mapNotNull { found[it] }
}

/** Reshapes upstream's main settings registry into Turtle's; any other registry (Integrations) is returned as is. */
@Composable
internal fun rememberTurtleSettings(raw: SettingsUiRegistry, host: SharedSettingsHost): TurtleSettingsModel {
    if (raw[SettingsPageId.PLAYER] == null && raw[SettingsPageId.APPEARANCE] == null) return TurtleSettingsModel(raw, emptyMap())
    val controller = LocalPlaybackController.current
    LaunchedEffect(controller) { ShellSpoken.track(controller) }
    val navigator = if (host.platform == SettingsPlatform.ANDROID) LocalNavigator.current else null
    val uriHandler = LocalUriHandler.current
    val infinity by TurtleBridge.infinity.collectAsState()

    // ---- Turtle --------------------------------------------------------------------------------
    val theme =
        turtlePage(
            SettingsPageId.APPEARANCE,
            stringResource(Res.string.shell_theme_title),
            stringResource(Res.string.shell_set_theme_sub),
            Glyphs.Palette,
            listOf(
                group(
                    "palette",
                    stringResource(Res.string.shell_set_palette),
                    listOf(
                        customItem("palette", stringResource(Res.string.shell_set_palette), ShellPalettes.all.map { stringResource(it.name) }.joinToString(", ")) { PaletteGrid() },
                    ),
                    description = stringResource(Res.string.shell_theme_body),
                ),
                group("display", stringResource(Res.string.shell_set_display), raw.pick(KEEP_DISPLAY, SettingsPageId.APPEARANCE)),
            ),
        )

    val tuned =
        if (infinity.likes + infinity.passes > 0) {
            stringResource(Res.string.shell_inf_tuned, infinity.likes, infinity.passes)
        } else {
            stringResource(Res.string.shell_set_tuning_none)
        }
    val genresTitle = stringResource(Res.string.shell_inf_genres)
    val moodsTitle = stringResource(Res.string.shell_inf_moods)
    val infinityPage =
        turtlePage(
            SettingsPageId.PLAYER_STYLE,
            stringResource(Res.string.shell_infinity_title),
            stringResource(Res.string.shell_set_infinity_sub),
            Glyphs.Infinity,
            listOf(
                group(
                    "genres",
                    genresTitle,
                    listOf(customItem("infinityGenres", genresTitle) { InfinityChips(mood = false) }),
                    description = stringResource(Res.string.shell_inf_sheet_body),
                ),
                group("moods", moodsTitle, listOf(customItem("infinityMoods", moodsTitle) { InfinityChips(mood = true) })),
                group(
                    "steer",
                    stringResource(Res.string.shell_steer_title),
                    listOf(customItem("infinitySteer", stringResource(Res.string.shell_steer_title)) { SteerItem() }),
                ),
                group(
                    "tuning",
                    "",
                    listOf(
                        actionItem(
                            id = "infinityStartFresh",
                            title = stringResource(Res.string.shell_inf_fresh),
                            description = tuned,
                            valueLabel = stringResource(Res.string.shell_inf_fresh),
                            confirmationMessage = stringResource(Res.string.shell_inf_fresh_body),
                        ) { settingsScope.launch { TurtleBridge.infinityStartFresh() } },
                    ),
                ),
            ),
        )

    val familiarTitle = stringResource(Res.string.shell_set_recs_familiar_title)
    val familiarBody = stringResource(Res.string.shell_set_recs_familiar_body)
    val newTitle = stringResource(Res.string.shell_set_recs_new_title)
    val newBody = stringResource(Res.string.shell_set_recs_new_body)
    val tuningTitle = stringResource(Res.string.shell_set_recs_tuning_title)
    val tuningBody = stringResource(Res.string.shell_set_recs_tuning_body)
    val homeYouTubeLabels =
        HomeYouTubeRows.entries.associateWith { m ->
            stringResource(
                when (m) {
                    HomeYouTubeRows.MATCHED -> Res.string.shell_set_home_youtube_matched
                    HomeYouTubeRows.ALL -> Res.string.shell_set_home_youtube_all
                    HomeYouTubeRows.OFF -> Res.string.shell_set_home_youtube_off
                },
            )
        }
    val homeYouTube = ShellHomeRows.mode ?: HomeYouTubeRows.MATCHED
    val recommendations =
        turtlePage(
            SettingsPageId.PLAYER_ARTWORK,
            stringResource(Res.string.shell_set_recs),
            stringResource(Res.string.shell_set_recs_sub),
            Glyphs.Discover,
            listOf(
                group(
                    "how",
                    stringResource(Res.string.shell_set_recs_how),
                    listOf(
                        customItem("recsExplained", newTitle, "$familiarBody $newBody $tuningBody") {
                            ExplainList(
                                listOf(
                                    Triple(Glyphs.HeartLine, familiarTitle, familiarBody),
                                    Triple(Glyphs.Discover, newTitle, newBody),
                                    Triple(Glyphs.ThumbUp, tuningTitle, tuningBody),
                                ),
                            )
                        },
                    ),
                ),
                group(
                    "dial",
                    stringResource(Res.string.shell_set_recs_dial),
                    listOf(customItem("discoveryDial", stringResource(Res.string.shell_set_recs_dial)) { SteerItem() }),
                    description = stringResource(Res.string.shell_set_recs_dial_body),
                ),
                group(
                    "homeYouTube",
                    "",
                    listOf(
                        SettingsItemRegistration(
                            id = "homeYouTubeRows",
                            title = stringResource(Res.string.shell_set_home_youtube),
                            description = stringResource(Res.string.shell_set_home_youtube_body),
                            control = SettingsControl.CHOICE,
                            valueLabel = homeYouTubeLabels.getValue(homeYouTube),
                            choices = HomeYouTubeRows.entries.map { SettingsChoice(it.prefValue, homeYouTubeLabels.getValue(it)) },
                            selectedChoiceId = homeYouTube.prefValue,
                            onChoiceSelected = { id -> ShellHomeRows.choose(HomeYouTubeRows.fromPref(id)) },
                        ),
                    ),
                ),
                group(
                    "tune",
                    "",
                    listOf(
                        navigationItem(
                            "recsInfinity",
                            stringResource(Res.string.shell_set_recs_tune),
                            SettingsPageId.PLAYER_STYLE,
                            stringResource(Res.string.shell_set_infinity_sub),
                        ),
                    ),
                ),
                group(
                    "hidden",
                    "",
                    listOf(
                        navigationItem(
                            "recsHidden",
                            stringResource(Res.string.shell_set_hidden),
                            SettingsPageId.APPEARANCE_NAVIGATION,
                            stringResource(Res.string.shell_set_hidden_sub),
                        ),
                    ),
                ),
            ),
        )

    // Reached only from Recommendations. Upstream's APPEARANCE_NAVIGATION id is free in Turtle's registry
    // (its items moved to Advanced), and adding an id of our own would be an upstream edit.
    val hiddenTitle = stringResource(Res.string.shell_set_hidden)
    val hiddenPage =
        turtlePage(
            SettingsPageId.APPEARANCE_NAVIGATION,
            hiddenTitle,
            stringResource(Res.string.shell_set_hidden_sub),
            Glyphs.Less,
            listOf(group("hidden", "", listOf(customItem("hiddenList", hiddenTitle) { HiddenList() }))),
        )

    val speedChoices = ShellSpoken.SPEEDS.map { SettingsChoice(it.toString(), stringResource(Res.string.shell_sp_speed_value, speedText(it))) }
    val speed = ShellSpoken.speed
    val spoken =
        turtlePage(
            SettingsPageId.FONTS,
            stringResource(Res.string.shell_set_spoken),
            stringResource(Res.string.shell_set_spoken_sub),
            Glyphs.Podcast,
            listOf(
                group(
                    "speed",
                    "",
                    listOf(
                        SettingsItemRegistration(
                            id = "spokenSpeed",
                            title = stringResource(Res.string.shell_sp_speed),
                            description = stringResource(Res.string.shell_set_spoken_speed_body),
                            control = SettingsControl.CHOICE,
                            valueLabel = stringResource(Res.string.shell_sp_speed_value, speedText(speed)),
                            choices = speedChoices,
                            selectedChoiceId = speed.toString(),
                            onChoiceSelected = { id -> id.toFloatOrNull()?.let { ShellSpoken.setDefaultSpeed(controller, it) } },
                        ),
                    ),
                ),
            ),
        )

    // ---- Upstream pages, kept and renamed ------------------------------------------------------
    val equalizerTitle = stringResource(SharedRes.string.equalizer)
    val playback =
        raw[SettingsPageId.PLAYER]?.let { page ->
            page.copy(
                title = stringResource(Res.string.shell_set_playback),
                subtitle = stringResource(Res.string.shell_set_playback_sub),
                icon = Glyphs.Volume,
                groups = page.groups.map { g -> g.copy(items = g.items.map { if (it.id == "equalizer") it.copy(title = equalizerTitle) else it }) },
            )
        }
    val lyrics =
        raw[SettingsPageId.LYRICS]?.let { page ->
            page.copy(
                icon = Glyphs.Lyrics,
                groups =
                    page.groups
                        .filter { it.id !in HIDE_LYRICS_GROUPS }
                        .map { g ->
                            when (g.id) {
                                "behavior" -> g.copy(items = g.items.filter { it.id in KEEP_LYRICS_BEHAVIOR })
                                // A new id keeps pinsLyricsPreview() off: upstream's preview is not what Shell draws.
                                "display" -> g.copy(id = "textSize", items = g.items.filter { it.id in KEEP_LYRICS_DISPLAY })
                                else -> g
                            }
                        }
                        .filter { it.items.isNotEmpty() },
            )
        }
    val artistImages = raw.pick(listOf("appleMusicArtistImages"), SettingsPageId.PLAYER_ARTWORK)
    val artistSourcesTitle = stringResource(SharedRes.string.settings_artist_sources)
    val content =
        raw[SettingsPageId.CONTENT]?.let { page ->
            val groups =
                if (artistImages.isEmpty()) {
                    page.groups
                } else if (page.groups.any { it.id == "artistSources" }) {
                    page.groups.map { if (it.id == "artistSources") it.copy(items = it.items + artistImages) else it }
                } else {
                    page.groups + group("artistSources", artistSourcesTitle, artistImages)
                }
            page.copy(title = stringResource(Res.string.shell_set_content), icon = Glyphs.Explore, groups = groups)
        }
    val privacy =
        raw[SettingsPageId.PRIVACY]?.let { page ->
            page.copy(
                icon = Glyphs.Lock,
                groups = page.groups.map { g -> g.copy(items = g.items.filter { it.id !in HIDE_PRIVACY }) }.filter { it.items.isNotEmpty() },
            )
        }
    val storage = raw[SettingsPageId.STORAGE]?.copy(icon = Glyphs.Download)
    val backup = raw[SettingsPageId.BACKUP_RESTORE]?.copy(icon = Glyphs.History)
    val equalizerPage = raw[SettingsPageId.EQUALIZER]?.copy(title = equalizerTitle)

    val integrations =
        navigator?.let {
            SettingsPageRegistration(
                id = SettingsPageId.DISCORD,
                title = stringResource(SharedRes.string.integrations),
                subtitle = stringResource(Res.string.shell_set_integrations_sub),
                icon = Glyphs.Devices,
                tone = SettingsTone.NEUTRAL,
                groups = emptyList(),
                custom = true,
                searchTerms = listOf("Discord", "Last.fm", "ListenBrainz"),
            )
        }

    val moreLinks =
        listOf(SettingsPageId.ANDROID_AUTO, SettingsPageId.SUPPORTED_LINKS, SettingsPageId.DEVTOOLS).mapNotNull { id ->
            raw[id]?.let { navigationItem("more:${id.name}", it.title, id, it.subtitle) }
        }
    val advanced =
        turtlePage(
            SettingsPageId.UPDATES,
            stringResource(Res.string.shell_set_advanced),
            stringResource(Res.string.shell_set_advanced_sub),
            Glyphs.Sliders,
            listOf(
                group(
                    "navigation",
                    stringResource(Res.string.shell_set_navigation),
                    raw.pick(KEEP_NAVIGATION, SettingsPageId.APPEARANCE_NAVIGATION, SettingsPageId.APPEARANCE),
                ),
                group("window", stringResource(Res.string.shell_set_window), raw.pick(KEEP_WINDOW, SettingsPageId.APPEARANCE)),
                group("more", stringResource(Res.string.shell_set_more), moreLinks),
            ),
        )

    // ---- About Turtle --------------------------------------------------------------------------
    val appName = stringResource(Res.string.shell_set_app_name)
    val version = stringResource(Res.string.shell_set_version, BuildConfig.APP_VERSION)
    val builtOn = stringResource(Res.string.shell_set_built_on)
    val repository = aboutCommunityLinks.firstOrNull { it.kind == AboutCommunityLinkKind.REPOSITORY }?.url
    val about =
        turtlePage(
            SettingsPageId.ABOUT,
            stringResource(Res.string.shell_set_about),
            stringResource(Res.string.shell_set_about_sub),
            Glyphs.Info,
            listOf(
                group("turtle", "", listOf(customItem("aboutTurtle", appName, version) { changed -> AboutHeader(appName, version, changed) })),
                group(
                    "license",
                    stringResource(SharedRes.string.credits_license_name),
                    listOf(
                        actionItem("gpl", stringResource(Res.string.shell_set_gpl_link), stringResource(Res.string.shell_set_gpl)) {
                            runCatching { uriHandler.openUri(GPL_URL) }
                        },
                    ),
                ),
                group(
                    "builtOn",
                    builtOn,
                    buildList {
                        add(customItem("metrolistCredits", builtOn, AboutContributors.all.joinToString(", ") { it.name }) { MetrolistCredits() })
                        repository?.let { url ->
                            add(
                                actionItem("metrolistSource", stringResource(Res.string.shell_set_metrolist_source), stringResource(SharedRes.string.credits_license_desc)) {
                                    runCatching { uriHandler.openUri(url) }
                                },
                            )
                        }
                    },
                    description = stringResource(Res.string.shell_set_built_on_body),
                ),
            ),
        )

    val pages =
        listOfNotNull(theme, infinityPage, recommendations, hiddenPage, spoken, playback, lyrics, content, storage, backup, privacy, integrations, advanced, about) +
            KEEP_SUBPAGES.mapNotNull { id -> if (id == SettingsPageId.EQUALIZER) equalizerPage else raw[id] }
    val rootGroups =
        listOf(
            SettingsRootGroupRegistration(
                stringResource(Res.string.shell_set_group_turtle),
                listOf(SettingsPageId.APPEARANCE, SettingsPageId.PLAYER_STYLE, SettingsPageId.PLAYER_ARTWORK, SettingsPageId.MINI_PLAYER_STYLE, SettingsPageId.FONTS),
            ),
            SettingsRootGroupRegistration(
                stringResource(Res.string.shell_set_group_listening),
                listOfNotNull(playback?.id, lyrics?.id, content?.id),
            ),
            SettingsRootGroupRegistration(stringResource(Res.string.shell_set_group_library), listOfNotNull(storage?.id, backup?.id)),
            SettingsRootGroupRegistration(stringResource(Res.string.shell_set_group_privacy), listOfNotNull(privacy?.id, integrations?.id)),
            SettingsRootGroupRegistration(stringResource(Res.string.shell_set_group_app), listOf(SettingsPageId.UPDATES, SettingsPageId.ABOUT)),
        ).filter { it.pages.isNotEmpty() }
    val openers: Map<SettingsPageId, () -> Unit> =
        if (navigator != null) mapOf(SettingsPageId.DISCORD to { navigator.navigate(Screen.Integrations) }) else emptyMap()
    return TurtleSettingsModel(SettingsUiRegistry(pages, rootGroups), openers)
}

// ---- Custom items ----------------------------------------------------------------------------

/** Turtle's palettes as swatches; choosing one re-skins the app at once (and is saved). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PaletteGrid() {
    FlowRow(
        Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val selected = ShellPalettes.selected.id
        ShellPalettes.all.forEach { p -> Swatch(p, p.id == selected) { ShellPalettes.choose(p) } }
    }
}

/** The Infinity Mix's genres or moods as chips; a change is saved and re-mixes Up Next if the mix is playing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfinityChips(mood: Boolean) {
    val controller = LocalPlaybackController.current
    val state by TurtleBridge.infinity.collectAsState()
    var picks by remember(state.genres) { mutableStateOf(state.genres.toSet()) }
    FlowRow(
        Modifier.fillMaxWidth().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InfinityGenres.all.filter { it.mood == mood }.forEach { genre ->
            val on = genre.id in picks
            SettingsGenreChip(genreName(genre.id), on) {
                val next = if (on) picks - genre.id else picks + genre.id
                picks = next
                val ordered = InfinityGenres.all.map { it.id }.filter { it in next }
                settingsScope.launch {
                    TurtleBridge.setInfinityGenres(ordered)
                    ShellInfinity.resteer(controller)
                }
            }
        }
    }
}

@Composable
private fun SettingsGenreChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Shell.colors
    val bg by animateColorAsState(if (on) c.accent else c.surface2, ShellMotion.emphasized())
    val fg by animateColorAsState(if (on) c.onAccent else c.ink, ShellMotion.emphasized())
    Row(
        Modifier.height(36.dp).clip(CircleShape).background(bg).border(1.dp, if (on) Color.Transparent else c.hairline, CircleShape)
            .shellPressable(onClick, label = label, focusShape = CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) {
            Glyph(Glyphs.Check, fg, 14.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = Shell.type.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = fg)
    }
}

/** The discovery dial (the same one as the player's Steer card). */
@Composable
private fun SteerItem() {
    Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp)) { SteerCard(Shell.colors.surface2) }
}

@Composable
private fun ExplainList(rows: List<Triple<ImageVector, String, String>>) {
    val c = Shell.colors
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { (glyph, title, body) ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(c.surface3), contentAlignment = Alignment.Center) {
                    Glyph(glyph, c.accent, 18.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                    Spacer(Modifier.height(2.dp))
                    Text(body, style = Shell.type.bodySmall, color = c.ink2)
                }
            }
        }
    }
}

/** Turtle's logo, name and version. Three taps on the version turn on developer tools, as upstream's logo did. */
@Composable
private fun AboutHeader(appName: String, version: String, onSettingValueChanged: () -> Unit) {
    val c = Shell.colors
    var taps by remember { mutableIntStateOf(0) }
    var unlocked by remember { mutableStateOf(Settings.shared.devtoolsUnlocked) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painterResource(SharedRes.drawable.logo),
            contentDescription = null,
            modifier = Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Text(appName, style = Shell.type.display.copy(fontSize = 30.sp, lineHeight = 32.sp), color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            version,
            style = Shell.type.label,
            color = c.ink2,
            modifier = Modifier.clip(CircleShape).shellPressable({
                if (!unlocked) {
                    taps += 1
                    if (taps >= 3) {
                        unlocked = true
                        Settings.shared.setDevtoolsEnabled(true)
                        onSettingValueChanged()
                    }
                }
            }, label = version, focusShape = CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        )
        if (unlocked) {
            Spacer(Modifier.height(6.dp))
            IndexLabel(stringResource(Res.string.shell_set_devtools_on), color = c.accent)
        }
    }
}

/** Metrolist's creator, leads and collaborators with their roles, as upstream's About credits them. */
@Composable
private fun MetrolistCredits() {
    val text = rememberAboutScreenText()
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        listOf(
            text.creatorSection to listOf(AboutContributors.lead),
            text.leadsSection to AboutContributors.leads,
            text.collaborators to AboutContributors.collaborators,
        ).forEach { (section, people) ->
            IndexLabel(section, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp))
            people.forEach { person ->
                CreditRow(person, text.roleLabel(person.role)) {
                    (person.githubUrl ?: person.websiteUrl)?.let { url -> runCatching { uriHandler.openUri(url) } }
                }
            }
        }
    }
}

@Composable
private fun CreditRow(person: AboutContributor, role: String, onClick: () -> Unit) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg = shellRowBackground(hovered)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .hoverable(source).shellPressable(onClick, label = person.name, focusShape = RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellArt(person.avatarUrl, Modifier.size(36.dp), shape = CircleShape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(person.name, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(role, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
