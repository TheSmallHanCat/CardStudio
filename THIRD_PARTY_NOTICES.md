# Third-party notices

The APK includes libxposed service/interface 101.0.0, Kotlin stdlib 2.2.10 and JetBrains Annotations 13.0 under Apache-2.0. The complete notices and license text are shipped in [the app asset](app/src/main/assets/third_party_licenses.txt).

The compile-only libxposed API 101.0.1 is Apache-2.0 and is supplied at runtime by the user's framework; its class definitions are not packaged in the APK.

The Gradle Wrapper 8.13 is distributed as build tooling under Apache-2.0, with its original copyright headers retained. It is not part of the APK. Project: https://github.com/gradle/gradle.

Development tests use JUnit 4.13.2 (EPL-1.0), Hamcrest Core 1.3 (BSD 3-Clause), and JSON-java 20240303 (public domain). They are not packaged in the APK.

- libxposed: https://github.com/libxposed/service
- Kotlin: https://github.com/JetBrains/kotlin
- JetBrains annotations: https://github.com/JetBrains/java-annotations
- JUnit: https://github.com/junit-team/junit4
- Hamcrest: https://github.com/hamcrest/JavaHamcrest
- JSON-java: https://github.com/stleary/JSON-java
