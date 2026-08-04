package com.explorer.fileexplorer.plugin;

import android.os.Bundle;

/**
 * Versioned IPC boundary implemented by third-party FileExplorer plugins.
 * Implementations must keep calls short and do expensive work off the main thread.
 */
interface IFileExplorerPlugin {
    int getProtocolVersion();
    boolean canHandle(String path);
    Bundle execute(in Bundle request);
}
