plugins {
    java
}

group = "dev.local.docinspector"
version = "1.0.0"

base {
    archivesName.set("anyreader")
}

val ideaHomePath = providers.gradleProperty("ideaHome")
    .orElse(providers.environmentVariable("IDEA_HOME"))
    .orElse("C:/Program Files/JetBrains/IntelliJ IDEA 2025.3.2")
    .get()
val ideaHome = file(ideaHomePath)
val ideaJbrHome = ideaHome.resolve("jbr")
val isWindows = System.getProperty("os.name").lowercase().contains("win")
val localLibs = fileTree("libs") { include("*.jar") }

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    val javacExe = ideaJbrHome.resolve(if (isWindows) "bin/javac.exe" else "bin/javac")
    if (javacExe.exists()) {
        options.isFork = true
        options.forkOptions.javaHome = ideaJbrHome
    }
}

repositories {
    mavenCentral()
    maven("https://maven.aliyun.com/repository/public")
}

val ideaCompileLibs = files(
    fileTree(ideaHome.resolve("lib")) {
        include("*.jar")
        exclude("intellij.libraries.jsoup.jar")
    },
    fileTree(ideaHome.resolve("modules")) {
        include("*.jar")
    },
    fileTree(ideaHome.resolve("plugins/jcef-plugin/lib/modules")) {
        include("*.jar")
    }
)

dependencies {
    compileOnly(ideaCompileLibs)
    if (!localLibs.isEmpty) {
        implementation(localLibs)
    } else {
        implementation("org.apache.pdfbox:pdfbox:3.0.5")
        implementation("org.jsoup:jsoup:1.15.4")
    }

    testCompileOnly(ideaCompileLibs)
    testRuntimeOnly(ideaCompileLibs)
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}

tasks.test {
    useJUnitPlatform()
    val jbrJava = ideaJbrHome.resolve(if (isWindows) "bin/java.exe" else "bin/java")
    if (jbrJava.exists()) {
        executable = jbrJava.absolutePath
    }
}

val assemblePluginDir by tasks.registering(Sync::class) {
    dependsOn(tasks.jar)
    into(layout.buildDirectory.dir("plugin-dist/anyreader/lib"))
    from(tasks.jar.flatMap { it.archiveFile })
    from(if (!localLibs.isEmpty) localLibs else configurations.runtimeClasspath)
}

val buildPluginZip by tasks.registering(Zip::class) {
    dependsOn(assemblePluginDir)
    archiveBaseName.set("anyreader")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("plugin-dist"))
}
