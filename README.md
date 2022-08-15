# Steelwool

A compatibility tool to run (some) Fabric mods on Forge.

## Project structure

Built jar location: `./ModLocator/build/libs/`

### ModLocator

The main subproject, containing our implementation of `IModLocator` that finds Fabric mod jars in the mods folder and converts them to jars that can be loaded by Forge.

### Mod

This subproject contains a Forge mod that is nested inside the main Steelwool jar, that handles various runtime behavior normally handled by FabricLoader (entrypoints, etc.).

### DummyProject

Forge allows for loading mod locators as dependencies in development environments, but does not support loading them directly in their own project.

Because of this, we use a dummy mod to load Steelwool in development environments.
