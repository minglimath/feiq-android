// 纯 JVM 的「桥」模块。
//
// 目的：把飞秋协议跑到桌面/机顶盒的 JVM 上（Linux 有完整原始 socket 能力，
// 没有 Android 的保活、分区存储、前台服务那些限制）。
//
// 关键点：这里**不复制**协议源码，而是直接用 srcDirs 复用 app 模块的 net/ 和 core/，
// 只 exclude 掉 Android 专属的文件。这样协议实现只有一份，两边不会分裂。
// 详见 docs/09-网页桥接可行性分析.md。
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

kotlin {
    jvmToolchain(17)

    sourceSets["main"].kotlin.srcDirs(
        "../app/src/main/java/com/feiq/droid/net",
        "../app/src/main/java/com/feiq/droid/core",
    )

    // core/ 里 Android 专属的文件，桥用不到（这些文件依赖 Context/Service/Notification 等）。
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
}

application {
    mainClass.set("com.feiq.droid.bridge.MainKt")
    applicationName = "feiq-bridge"
}
