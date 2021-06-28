val kotlinxCoroutinesVersion: String by project
val kotlinVersion: String by project

dependencies {
  implementation(kotlin("stdlib", kotlinVersion))
  implementation(kotlin("reflect", kotlinVersion))

  implementation("org.jetbrains.kotlin:kotlin-stdlib")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutinesVersion")
}
