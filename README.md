# QuPath <> Concentriq extension
The Concentriq-QuPath extension allows users to seamlessly leverage QuPath’s open and versatile ecosystem with Concentriq LS 
This repo contains code for a QuPath extension that  template and instructions to help create a new extension for [QuPath](https://qupath.github.io).

## Build the extension
Open a command prompt, navigate to where the code lives, and use
```bash
gradlew build
```

Tests can be run with
```bash
gradlew test
```

The built extension should be found inside `build/libs`.
You can drag this onto QuPath to install it.
You'll be prompted to create a user directory if you don't already have one.

## Configure the extension
See the User Guide for details on how to configure your extension.