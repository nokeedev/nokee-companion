# Changelog

## [1.0] - in development

We're excited to share our first public release of the Native Companion plugin—a distillation of our experience from years of developing Nokee native plugins and working closely with clients on real-world native builds.
While this is an early release aimed at quick iteration and feedback, it represents carefully selected enhancements that have proven valuable in practice.

This plugin packages battle-tested solutions into a Swiss Army knife for Gradle's core native plugins, making years of native build expertise readily available to every developer.
As we move towards stability, we're particularly keen to hear how these enhancements work in your build environments.

The Native Companion plugin works seamlessly with Gradle's core native plugins, providing carefully crafted enhancements for C++ ecosystem.
It brings sensible defaults and workflow optimizations without requiring changes to your existing build scripts—all backed by real-world experience.

Below are the key features included in this preview release:

- Fix to [gradle/gradle#29492](https://github.com/gradle/gradle/issues/29492)
- Fix to [gradle/gradle#29744](https://github.com/gradle/gradle/issues/29744)
- Support version catalogue dependencies
- Fix [gradle/gradle-native#994](https://github.com/gradle/gradle-native/issues/994) depends on public header generation
- Fix gradle/gradle#??? multiple public header directories
- Exact object files mapping from native compile tasks
- Per-source options for compile task [gradle/gradle-native#974](https://github.com/gradle/gradle-native/issues/974)
- Incremental after failure
- Binary task extensions
- Compile tasks extension [gradle/gradle-native#974](https://github.com/gradle/gradle-native/issues/974)
