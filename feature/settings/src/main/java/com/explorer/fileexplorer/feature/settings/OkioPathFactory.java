package com.explorer.fileexplorer.feature.settings;

import java.io.File;

import okio.Path;

final class OkioPathFactory {
    private OkioPathFactory() {
    }

    static Path fromFile(File file) {
        return Path.Companion.get(file);
    }
}
