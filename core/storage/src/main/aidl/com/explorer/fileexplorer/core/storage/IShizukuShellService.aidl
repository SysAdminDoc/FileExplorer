package com.explorer.fileexplorer.core.storage;

interface IShizukuShellService {
    /** Runs [command] and returns "exitCode\noutput". A [timeoutMs] of 0 or less means no limit. */
    String execute(String command, long timeoutMs);
}
