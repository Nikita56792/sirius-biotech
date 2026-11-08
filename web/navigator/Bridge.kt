@file:OptIn(ExperimentalJsExport::class)
package com.gmcnav.navi.core

private lateinit var engine: NavigatorEngine

@JsExport
fun initializeNavigation(texts: dynamic, grids: dynamic): String {
    engine = NavigatorEngine(Building(AssetSource(texts, grids)))
    return engine.command("{\"type\":\"info\"}")
}

@JsExport
fun navigationCommand(command: String): String = engine.command(command)
