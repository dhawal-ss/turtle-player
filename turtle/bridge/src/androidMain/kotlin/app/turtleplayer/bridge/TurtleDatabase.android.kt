package app.turtleplayer.bridge

import android.content.Context
import androidx.sqlite.driver.AndroidSQLiteDriver
import org.koin.core.scope.Scope

internal actual fun Scope.turtleDatabaseSpec(): TurtleDatabaseSpec =
    TurtleDatabaseSpec(AndroidSQLiteDriver(), get<Context>().getDatabasePath("turtle.db").absolutePath)
