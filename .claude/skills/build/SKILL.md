# /build — Build, test, and verify

Build the project and report results.

## Steps

1. Run `./gradlew build` from the project root
2. If the build fails, read the error output and identify the issue
3. Fix any compilation errors
4. Re-run until the build succeeds
5. Run `./gradlew :server:shadowJar` to verify the fat JAR builds
6. Report: which modules compiled, any warnings, JAR size
