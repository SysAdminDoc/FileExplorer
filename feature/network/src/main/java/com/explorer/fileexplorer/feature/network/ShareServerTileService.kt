package com.explorer.fileexplorer.feature.network

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.explorer.fileexplorer.core.designsystem.R as DesignSystemR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ShareServerTileService : TileService() {

    @Inject
    lateinit var controller: ShareServerController

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (controller.status.value.isRunning) {
            ShareServerService.stop(this)
        } else {
            ShareServerService.start(this)
        }
        updateTile()
    }

    private fun updateTile() {
        qsTile?.state = if (controller.status.value.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.label = getString(DesignSystemR.string.share_server)
        qsTile?.updateTile()
    }
}
