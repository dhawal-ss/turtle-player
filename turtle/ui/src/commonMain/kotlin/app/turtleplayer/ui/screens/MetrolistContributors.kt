package app.turtleplayer.ui.screens

import app.turtleplayer.ui.generated.resources.shell_credit_android_lead
import app.turtleplayer.ui.generated.resources.shell_credit_collections
import app.turtleplayer.ui.generated.resources.shell_credit_creator
import app.turtleplayer.ui.generated.resources.shell_credit_design
import app.turtleplayer.ui.generated.resources.shell_credit_desktop
import app.turtleplayer.ui.generated.resources.shell_credit_desktop_lead
import app.turtleplayer.ui.generated.resources.shell_credit_interface
import app.turtleplayer.ui.generated.resources.shell_credit_lyrics
import app.turtleplayer.ui.generated.resources.shell_credit_macos
import app.turtleplayer.ui.generated.resources.shell_credit_multiplatform
import app.turtleplayer.ui.generated.resources.shell_credit_packaging
import app.turtleplayer.ui.generated.resources.shell_credit_playlists
import app.turtleplayer.ui.generated.resources.shell_credit_recommendations
import app.turtleplayer.ui.generated.resources.shell_credit_settings
import app.turtleplayer.ui.generated.resources.shell_credit_sign_in
import app.turtleplayer.ui.generated.resources.shell_credit_translations
import app.turtleplayer.ui.generated.resources.shell_credit_watch
import app.turtleplayer.ui.generated.resources.Res
import org.jetbrains.compose.resources.StringResource

/**
 * Everyone who helped build Metrolist, which Turtle is built on: the GitHub contributors of
 * MetrolistGroup/Metrolist (bots left out) plus the team upstream's About credits, each once.
 * Names are the ones upstream credits, else the GitHub display name, else the GitHub handle. Roles are
 * upstream's, or a word or two on what their commits did.
 * Taken on 22 Nov 2026; refresh it by hand when Metrolist's contributors change.
 */
internal object MetrolistContributors {
    class Person(val name: String, val github: String, val role: StringResource)

    val all: List<Person> = listOf(
        Person("Mo Agamy", "mostafaalagamy", Res.string.shell_credit_creator),
        Person("Nyx", "nyxiereal", Res.string.shell_credit_android_lead),
        Person("Adriel O'Connel", "adrielGGmotion", Res.string.shell_credit_multiplatform),
        Person("Alessio Attilio", "kairosci", Res.string.shell_credit_multiplatform),
        Person("Gwocha", "Gwocha", Res.string.shell_credit_translations),
        Person("Ibra Al Tabian", "ibratabian17", Res.string.shell_credit_lyrics),
        Person("YouGamesYT", "YouGamesYT", Res.string.shell_credit_translations),
        Person("Lamp", "l6t9", Res.string.shell_credit_desktop_lead),
        Person("Valer", "Valer100", Res.string.shell_credit_translations),
        Person("Mayur Naik", "mayuur-naik", Res.string.shell_credit_interface),
        Person("Kehribar", "dpentx", Res.string.shell_credit_translations),
        Person("Faturahman Alfareza", "alfa-reza", Res.string.shell_credit_lyrics),
        Person("CrackedOnTiti", "CrackedOnTiti", Res.string.shell_credit_settings),
        Person("David Emanuel Bieregger", "BierDav", Res.string.shell_credit_packaging),
        Person("Mohd. Ibrahim Iqbal", "ibrahim-iqbal", Res.string.shell_credit_playlists),
        Person("Muhammad Isyan Maulana", "isyanpcr", Res.string.shell_credit_collections),
        Person("Revilo0509", "Revilo0509", Res.string.shell_credit_sign_in),
        Person("Ryly Dou", "rylydou", Res.string.shell_credit_watch),
        Person("f6466553-lab", "f6466553-lab", Res.string.shell_credit_desktop),
        Person("Applxcake", "applxcake", Res.string.shell_credit_macos),
        Person("noelpatata", "noelpatata", Res.string.shell_credit_recommendations),
        Person("Show", "showtc", Res.string.shell_credit_design),
        Person("Ismail Tiryaki", "isotjs", Res.string.shell_credit_multiplatform),
        Person("AlexxBytes", "alexxbytes", Res.string.shell_credit_desktop),
        Person("Tryfle", "Tryflle", Res.string.shell_credit_macos),
    )

    const val CONTRIBUTORS_URL = "https://github.com/MetrolistGroup/Metrolist/graphs/contributors"
}

/** Turtle's creator on GitHub. */
internal const val TURTLE_CREATOR_GITHUB = "dhawal-ss"
