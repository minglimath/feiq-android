// 纯 JVM 的「桥」模块。
//
// 目的：把飞秋协议 + 网页服务跑到桌面/机顶盒的 JVM 上（Linux 有完整原始 socket 能力，
// 没有 Android 的保活、分区存储、前台服务那些限制）。
//
// 关键点：这里**不复制**任何源码，而是用 srcDirs 直接复用 app 模块的 net/、core/、web/，
// 只 exclude 掉 Android 专属的文件。这样协议和网页服务都只有一份实现，
// 手机 App 与机顶盒桥共用同一套代码。详见 docs/09-网页桥接可行性分析.md。
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

// 网页前端只有一份，放在 app 的 assets 里；桥构建时把它拷进自己的资源目录，两端共用。
val webResources = layout.buildDirectory.dir("generated/web-resources")

val copyWebAssets by tasks.registering(Copy::class) {
    from("../app/src/main/assets") { include("web/**") }
    into(webResources)
}

kotlin {
    jvmToolchain(17)

    sourceSets["main"].kotlin.srcDirs(
        "../app/src/main/java/com/feiq/droid/net",
        "../app/src/main/java/com/feiq/droid/core",
        "../app/src/main/java/com/feiq/droid/web",
    )

    // core/ 里 Android 专属的文件，桥用不到（这些依赖 Context/Service/Notification 等）。
    // 注意：以后往 core/ 里新增 Android 专属文件时，这里要同步补上，否则桥会编译失败。
    sourceSets["main"].kotlin.exclude(
        "**/App.kt",
        "**/AvatarStore.kt",
        "**/ChatRecord.kt",
        "**/FeiqApp.kt",
        "**/FeiqService.kt",
        "**/MediaIndex.kt",
        "**/MessageRepository.kt",
        "**/MessageStore.kt",
        "**/NetworkInfo.kt",
        "**/Prefs.kt",
        "**/Storage.kt",
        "**/StoragePermission.kt",
    )

    sourceSets["main"].resources.srcDir(webResources)
}

tasks.named("processResources") {
    dependsOn(copyWebAssets)
}

application {
    mainClass.set("com.feiq.droid.bridge.MainKt")
    applicationName = "feiq-bridge"
}
