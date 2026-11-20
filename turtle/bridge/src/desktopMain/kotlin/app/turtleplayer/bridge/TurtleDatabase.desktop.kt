package app.turtleplayer.bridge

import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.utils.XdgUtils
import org.koin.core.scope.Scope
import java.io.File

internal actual fun Scope.turtleDatabaseSpec(): TurtleDatabaseSpec {
    val file = File(XdgUtils.getXdgDataHome(), "turtleplayer/turtle.db")
    file.parentFile.mkdirs()
    return TurtleDatabaseSpec(JdbcSQLiteDriver(), file.absolutePath)
}

internal actual val warmStreamResolverOnStart: Boolean = true
