rootProject.name = "FlipperJ"

// Working on C64GfxLib at the same time: check it out next to this project and start Gradle with
// -Pc64gfxLocal (or put c64gfxLocal=true into gradle.properties). Then ../C64GfxLib is used directly
// instead of the published package, so library changes take effect without publishing.
if (providers.gradleProperty("c64gfxLocal").isPresent && file("../C64GfxLib").isDirectory) {
    includeBuild("../C64GfxLib")
}
