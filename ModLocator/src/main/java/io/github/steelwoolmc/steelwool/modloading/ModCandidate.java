package io.github.steelwoolmc.steelwool.modloading;

import java.nio.file.Path;

public record ModCandidate(Path path, FabricModData metadata) {}
