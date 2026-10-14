// buildSrc：纯 Java 实现自动版本计算（GitVersion.java）
// 不使用 kotlin-dsl，避免离线环境拉取 kotlin-gradle-plugin 失败。
plugins {
    java
}