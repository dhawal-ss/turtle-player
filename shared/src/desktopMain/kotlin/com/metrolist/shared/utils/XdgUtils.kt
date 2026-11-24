/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.utils

import java.io.File

object XdgUtils {
    fun getXdgDataHome(): File {
        val env = System.getenv("XDG_DATA_HOME")
        return if (env != null && File(env).isAbsolute) {
            File(env)
        } else {
            com.metrolist.shared.turtle.TurtleDesktopPaths.dataHome // Turtle: HOOKS.md #67
        }
    }

    fun getXdgCacheHome(): File {
        val env = System.getenv("XDG_CACHE_HOME")
        return if (env != null && File(env).isAbsolute) {
            File(env)
        } else {
            com.metrolist.shared.turtle.TurtleDesktopPaths.cacheHome // Turtle: HOOKS.md #67
        }
    }

    fun getXdgStateHome(): File {
        val env = System.getenv("XDG_STATE_HOME")
        return if (env != null && File(env).isAbsolute) {
            File(env)
        } else {
            com.metrolist.shared.turtle.TurtleDesktopPaths.stateHome // Turtle: HOOKS.md #67
        }
    }

    fun getXdgConfigHome(): File {
        val env = System.getenv("XDG_CONFIG_HOME")
        return if (env != null && File(env).isAbsolute) {
            File(env)
        } else {
            com.metrolist.shared.turtle.TurtleDesktopPaths.configHome // Turtle: HOOKS.md #67
        }
    }
}
