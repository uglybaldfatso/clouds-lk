rootProject.name = "clouds-lk"

// Automatic plugin modules: any directory containing a build.gradle.kts is
// included. New plugin modules therefore only need their own directory.
val disabled = listOf<String>()

File(rootDir, ".").eachDir { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach(block)
}
