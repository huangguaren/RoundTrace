// RoundTrace 根设置脚本
// 说明：除 google() / mavenCentral() 之外，额外加入阿里云镜像用于加速依赖下载
//      （顺序在 mavenCentral 之前，mavenCentral 作为兜底）。该环境适配记录于 docs/decisions.md。
pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        google()
        mavenCentral()
    }
}

rootProject.name = "RoundTrace"
include(":app")
