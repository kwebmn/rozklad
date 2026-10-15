# Rozklad Android — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android-приложение, которое запоминает группу/преподавателя УКД и показывает расписание с сайта ПС-Розклад: офлайн-кэш, виджет, напоминания, уведомления об изменениях, самообновление из GitHub Releases.

**Architecture:** Один Gradle-модуль `app`. Сеть (`ScheduleApi` + Jsoup-парсер) пишет только в Room; UI, виджет и напоминания читают Room. Чистая логика (diff, выбор дня, напоминания, форматирование, версии) — в `domain/` без Android-зависимостей и покрыта JVM unit-тестами. Ручной DI через `AppContainer`, навигация — `enum Screen` в `MainActivity`.

**Tech Stack:** Kotlin 2.1.0, AGP 8.7.3, Gradle 8.11.1, Jetpack Compose (BOM 2024.12.01, Material 3), Room 2.6.1 + KSP, WorkManager 2.10.0, Glance 1.1.1, DataStore 1.1.1, OkHttp 4.12.0, Jsoup 1.18.3, kotlinx.serialization 1.7.3, JUnit 4 + kotlinx-coroutines-test + MockWebServer.

**Spec:** `docs/superpowers/specs/2026-10-15-rozklad-android-design.md`

## Global Constraints

- Package и applicationId: `io.github.kwebmn.rozklad`.
- `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`; Gradle запускается на JDK 21, байткод `jvmTarget = "17"`.
- Все строки интерфейса — на украинском.
- Сайт: `http://195.162.83.28/cgi-bin/timetable.cgi`. Ответы — `windows-1251`. POST-форма расписания кодируется в `windows-1251`; `query` автокомплита — в UTF-8.
- Cleartext HTTP разрешён только для `195.162.83.28` (`network_security_config`).
- Room — единственный источник данных для UI, виджета и напоминаний; сеть только пишет в Room.
- Сетевые таймауты — 15 с. Синхронизация — раз в 4 ч. Окно синхронизации — с понедельника текущей недели, 14 дней.
- Напоминания: по умолчанию включены, за 10 мин; варианты 5/10/15/30.
- Релиз: тег `vX.Y.Z` → `versionName = X.Y.Z`, `versionCode = X*10000 + Y*100 + Z`; подпись из secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- Источник обновлений: `https://api.github.com/repos/kwebmn/rozklad/releases/latest`.
- Без DI-фреймворков и без navigation-библиотеки.
- Все команды Gradle запускаются из `/root/rozklad`.

## Review Focus

1. Поиск с символами вне cp1251 (эмодзи, латиница с диакритикой) не должен падать — тест в Task 3 (`formEncode` с эмодзи).
2. Занятие без аудитории или без преподавателя (бывает на сайте) → поле `null`, без падения — тест в Task 4 (16.09 пара 6).
3. Приложение открыли за 5 минут до пары при настройке «за 10 мин» — напоминание не должно сработать задним числом/мгновенно — тест в Task 7.
4. Удаление основной подписки — другая становится основной, иначе виджет и уведомления «замолкают» — тест в Task 9.
5. Релиз на GitHub без `.apk` или с тегом не вида `vX.Y.Z` — никакого диалога обновления и падения — тесты в Task 13.

---

## File Structure

```
.github/workflows/ci.yml, release.yml
README.md
settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradlew (+wrapper)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/  values/strings.xml, values/themes.xml, values-night/themes.xml,
                   xml/network_security_config.xml, xml/file_paths.xml, xml/widget_info.xml,
                   drawable/ic_notification.xml, drawable/ic_launcher_foreground.xml,
                   mipmap-anydpi-v26/ic_launcher.xml, values/colors.xml
app/src/main/java/io/github/kwebmn/rozklad/
  RozkladApp.kt, AppContainer.kt, MainActivity.kt
  domain/Models.kt        Subscription, SubscriptionType, Lesson, DateRange, lessonOrder
  domain/Week.kt          weekStart, syncWindow
  domain/Format.kt        dayShort/dayFull/shortDate/formatTime/shortRoom/shortTeacher/formatSyncStatus
  domain/LessonDiff.kt    LessonChange, diffLessons, describe, changeSummary
  domain/DayLogic.kt      chooseStartDay, currentOrNext, DayRow, buildDayRows
  domain/Reminders.kt     Reminder, upcomingReminders, reminderId
  domain/SemVer.kt        SemVer, isNewer
  data/remote/SiteCodec.kt, ScheduleParser.kt, SuggestParser.kt, ScheduleApi.kt, UpdateApi.kt
  data/local/Entities.kt, ScheduleDao.kt, AppDatabase.kt, RoomLessonStore.kt
  data/LessonStore.kt, ScheduleRepository.kt, SettingsStore.kt, UpdateChecker.kt
  ui/Messages.kt, ui/theme/Theme.kt
  ui/add/AddViewModel.kt, AddScreen.kt
  ui/schedule/ScheduleViewModel.kt, ScheduleScreen.kt
  ui/settings/SettingsViewModel.kt, SettingsScreen.kt
  ui/update/UpdateViewModel.kt, UpdateDialog.kt
  update/ApkInstaller.kt
  work/Notifier.kt, ReminderScheduler.kt, ReminderReceiver.kt, BootReceiver.kt, ScheduleEffects.kt, SyncRunner.kt, SyncWorker.kt
  widget/WidgetData.kt, RozkladWidget.kt
app/src/test/java/io/github/kwebmn/rozklad/...   (зеркально + fakes/)
app/src/test/resources/fixtures/                  (копия docs/superpowers/plans/fixtures)
```

---

### Task 1: Toolchain и каркас проекта

**Files:**
- Create: `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/io/github/kwebmn/rozklad/MainActivity.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values-night/themes.xml`, `app/src/main/res/values/colors.xml`, `app/src/main/res/xml/network_security_config.xml`, `app/src/main/res/drawable/ic_launcher_foreground.xml`, `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `local.properties` (не коммитится)
- Generated: `gradlew`, `gradlew.bat`, `gradle/wrapper/*`

**Interfaces:**
- Produces: собираемый проект; команда `./gradlew :app:testDebugUnitTest assembleDebug`; Gradle-свойство `-PappVersion=X.Y.Z`; задача `printVersion`.

- [ ] **Step 1: Установить JDK 21 и Android SDK**

```bash
apt-get update && apt-get install -y openjdk-21-jdk-headless
S=/tmp/rozklad-setup && mkdir -p $S /opt/android-sdk/cmdline-tools
curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o $S/clt.zip
unzip -q $S/clt.zip -d $S/clt && mv $S/clt/cmdline-tools /opt/android-sdk/cmdline-tools/latest
yes | /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
/opt/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
curl -fsSL https://services.gradle.org/distributions/gradle-8.11.1-bin.zip -o $S/gradle.zip
unzip -q $S/gradle.zip -d /opt/gradle
java -version && /opt/gradle/gradle-8.11.1/bin/gradle --version | head -3
```
Expected: `openjdk version "21...` и `Gradle 8.11.1`.

- [ ] **Step 2: Корневые файлы Gradle**

`.gitignore`:
```
.gradle/
build/
local.properties
*.jks
*.keystore
keystore.b64
.idea/
*.iml
captures/
.kotlin/
```

`local.properties`:
```
sdk.dir=/opt/android-sdk
```

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "rozklad"
include(":app")
```

Сгенерировать wrapper (каталога `app` ещё нет — Gradle разрешает `wrapper` и так; если ругнётся на `include(":app")`, временно создать пустой `app/`):
```bash
mkdir -p app && /opt/gradle/gradle-8.11.1/bin/gradle wrapper --gradle-version 8.11.1 --distribution-type bin
```
Expected: появились `gradlew`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`.

`gradle.properties`:
```
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

`gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"
composeBom = "2024.12.01"
activityCompose = "1.9.3"
coreKtx = "1.15.0"
lifecycle = "2.8.7"
room = "2.6.1"
work = "2.10.0"
glance = "1.1.1"
datastore = "1.1.1"
okhttp = "4.12.0"
jsoup = "1.18.3"
serialization = "1.7.3"
coroutines = "1.9.0"
junit = "4.13.2"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-core" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
glance-appwidget = { module = "androidx.glance:glance-appwidget", version.ref = "glance" }
glance-material3 = { module = "androidx.glance:glance-material3", version.ref = "glance" }
datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
junit = { module = "junit:junit", version.ref = "junit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

`build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 3: Модуль app**

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val appVersion: String = (findProperty("appVersion") as String?) ?: "0.1.0"

fun versionCodeOf(version: String): Int {
    val parts = version.split(".").map { it.toInt() }
    require(parts.size == 3) { "appVersion must be X.Y.Z, got $version" }
    return parts[0] * 10000 + parts[1] * 100 + parts[2]
}

android {
    namespace = "io.github.kwebmn.rozklad"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.kwebmn.rozklad"
        minSdk = 26
        targetSdk = 35
        versionName = appVersion
        versionCode = versionCodeOf(appVersion)
    }

    signingConfigs {
        create("release") {
            val keystore = System.getenv("KEYSTORE_FILE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.register("printVersion") {
    doLast { println("versionName=$appVersion versionCode=${versionCodeOf(appVersion)}") }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
```

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/Theme.Rozklad">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Розклад</string>
</resources>
```

`app/src/main/res/values/themes.xml`:
```xml
<resources>
    <style name="Theme.Rozklad" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`app/src/main/res/values-night/themes.xml`:
```xml
<resources>
    <style name="Theme.Rozklad" parent="android:Theme.Material.NoActionBar" />
</resources>
```

`app/src/main/res/values/colors.xml`:
```xml
<resources>
    <color name="ic_launcher_background">#1E5AA8</color>
</resources>
```

`app/src/main/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">195.162.83.28</domain>
    </domain-config>
</network-security-config>
```

`app/src/main/res/drawable/ic_launcher_foreground.xml` (иконка календаря, отцентрована в safe zone 108dp):
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="2.5"
        android:scaleY="2.5"
        android:translateX="24"
        android:translateY="24">
        <path
            android:fillColor="#FFFFFF"
            android:pathData="M17,12h-5v5h5v-5zM16,1v2L8,3L8,1L6,1v2L5,3c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2h-1L18,1h-2zM19,19L5,19L5,8h14v11z" />
    </group>
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`app/src/main/java/io/github/kwebmn/rozklad/MainActivity.kt` (временная, заменяется в Task 14):
```kotlin
package io.github.kwebmn.rozklad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Text("Розклад") } }
    }
}
```

- [ ] **Step 4: Проверить сборку и версию**

Run: `./gradlew :app:testDebugUnitTest assembleDebug && ./gradlew -q -PappVersion=1.2.3 :app:printVersion`
Expected: `BUILD SUCCESSFUL`, файл `app/build/outputs/apk/debug/app-debug.apk`, вывод `versionName=1.2.3 versionCode=10203`.

Run: `./gradlew -q -PappVersion=1.2 :app:printVersion`
Expected: FAIL с `appVersion must be X.Y.Z, got 1.2`.

- [ ] **Step 5: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle gradlew gradlew.bat app
git commit -m "chore: Android project skeleton"
```

---

### Task 2: GitHub Actions (CI и релиз) + README

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `README.md`

**Interfaces:**
- Consumes: `-PappVersion`, env `KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` из Task 1.
- Produces: релиз с ассетом `rozklad-vX.Y.Z.apk` (на него опирается Task 13 — ищется первый ассет с суффиксом `.apk`).

- [ ] **Step 1: ci.yml**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Test and build
        run: ./gradlew testDebugUnitTest assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: rozklad-debug
          path: app/build/outputs/apk/debug/*.apk
```

- [ ] **Step 2: release.yml**

```yaml
name: Release

on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
      - name: Decode keystore
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}
        run: |
          test -n "$KEYSTORE_BASE64" || { echo "KEYSTORE_BASE64 secret is missing"; exit 1; }
          echo "$KEYSTORE_BASE64" | base64 -d > "$RUNNER_TEMP/release.jks"
      - name: Build signed APK
        env:
          KEYSTORE_FILE: ${{ runner.temp }}/release.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          ./gradlew testDebugUnitTest assembleRelease -PappVersion="$VERSION"
          cp app/build/outputs/apk/release/app-release.apk "rozklad-$GITHUB_REF_NAME.apk"
      - uses: softprops/action-gh-release@v2
        with:
          files: rozklad-*.apk
          generate_release_notes: true
```

- [ ] **Step 3: README.md**

````markdown
# Розклад УКД

Android-застосунок для розкладу занять Університету Короля Данила
(дані з http://195.162.83.28/cgi-bin/timetable.cgi).

## Встановлення

Завантажте `rozklad-vX.Y.Z.apk` з [Releases](https://github.com/kwebmn/rozklad/releases/latest)
і встановіть. Далі застосунок сам пропонує оновлення.

> APK з CI-артефактів (debug) підписаний іншим ключем — поверх нього релізна версія не встановиться.

## Збірка

```bash
./gradlew testDebugUnitTest assembleDebug
```

## Реліз

Один раз — створити ключ підпису і додати секрети репозиторію
(Settings → Secrets and variables → Actions):

```bash
keytool -genkeypair -v -keystore rozklad-release.jks -alias rozklad \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 rozklad-release.jks > keystore.b64
```

| Secret | Значення |
|---|---|
| `KEYSTORE_BASE64` | вміст `keystore.b64` |
| `KEYSTORE_PASSWORD` | пароль keystore |
| `KEY_ALIAS` | `rozklad` |
| `KEY_PASSWORD` | пароль ключа |

Збережіть `rozklad-release.jks` в надійному місці: без нього оновлення поверх встановленої версії неможливе.

Випуск версії:

```bash
git tag v0.1.0 && git push origin v0.1.0
```
````

- [ ] **Step 4: Проверить YAML**

Run: `python3 -c "import yaml,sys; [yaml.safe_load(open(f)) for f in ['.github/workflows/ci.yml','.github/workflows/release.yml']]; print('ok')"`
Expected: `ok` (если нет модуля yaml: `apt-get install -y python3-yaml`).

- [ ] **Step 5: Commit**

```bash
git add .github README.md
git commit -m "ci: build on push, signed release on tag"
```

---

### Task 3: Доменные модели, кодировки сайта, недели

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/domain/Models.kt`, `domain/Week.kt`, `data/remote/SiteCodec.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/data/remote/SiteCodecTest.kt`, `app/src/test/java/io/github/kwebmn/rozklad/domain/WeekTest.kt`

**Interfaces:**
- Produces:
  - `enum class SubscriptionType { GROUP, TEACHER }`
  - `data class Subscription(id: Long = 0, type, name: String, siteId: String?, isPrimary: Boolean = false, lastSyncedAt: Long? = null)`
  - `data class Lesson(date, pairNo, slot, start, end, subject, kind, teacher: String? = null, room: String? = null, stream: String? = null, note: String? = null, cancelled: Boolean = false, remote: Boolean = false, links: List<String> = emptyList())`
  - `data class DateRange(from: LocalDate, to: LocalDate)` с `operator fun contains(date)`; `val lessonOrder: Comparator<Lesson>`
  - `fun weekStart(date): LocalDate`, `fun syncWindow(today): DateRange`
  - `object SiteCodec { CHARSET; formEncode(String); queryEncode(String); decode(ByteArray); formatDate(LocalDate) }`

- [ ] **Step 1: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/data/remote/SiteCodecTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SiteCodecTest {
    @Test
    fun `form values are encoded in windows-1251`() {
        assertEquals("%B2%CF%C7%F1-24-1", SiteCodec.formEncode("ІПЗс-24-1"))
    }

    @Test
    fun `suggest query is encoded in utf-8`() {
        assertEquals("%D0%86%D0%9F%D0%97", SiteCodec.queryEncode("ІПЗ"))
    }

    @Test
    fun `characters outside windows-1251 do not crash form encoding`() {
        val encoded = SiteCodec.formEncode("Група 😀")
        assertTrue(encoded.startsWith("%C3%F0%F3%EF%E0+"))
        assertFalse(encoded.contains("😀"))
    }

    @Test
    fun `decodes windows-1251 bytes`() {
        assertEquals("ІПЗ", SiteCodec.decode(byteArrayOf(0xB2.toByte(), 0xCF.toByte(), 0xC7.toByte())))
    }

    @Test
    fun `formats dates like the site`() {
        assertEquals("05.10.2026", SiteCodec.formatDate(LocalDate.of(2026, 10, 5)))
    }
}
```

`app/src/test/java/io/github/kwebmn/rozklad/domain/WeekTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeekTest {
    @Test
    fun `week starts on monday`() {
        assertEquals(LocalDate.of(2026, 10, 12), weekStart(LocalDate.of(2026, 10, 14)))
        assertEquals(LocalDate.of(2026, 10, 12), weekStart(LocalDate.of(2026, 10, 12)))
        assertEquals(LocalDate.of(2026, 10, 12), weekStart(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `sync window covers two weeks from monday`() {
        assertEquals(
            DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25)),
            syncWindow(LocalDate.of(2026, 10, 15)),
        )
    }

    @Test
    fun `date range contains is inclusive`() {
        val range = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
        assertTrue(LocalDate.of(2026, 10, 12) in range)
        assertTrue(LocalDate.of(2026, 10, 18) in range)
        assertFalse(LocalDate.of(2026, 10, 19) in range)
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SiteCodecTest' --tests '*WeekTest'`
Expected: FAIL — compilation errors `Unresolved reference: SiteCodec`, `weekStart`.

- [ ] **Step 3: Implement**

`domain/Models.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import java.time.LocalDate
import java.time.LocalTime

enum class SubscriptionType { GROUP, TEACHER }

data class Subscription(
    val id: Long = 0,
    val type: SubscriptionType,
    val name: String,
    val siteId: String?,
    val isPrimary: Boolean = false,
    val lastSyncedAt: Long? = null,
)

data class Lesson(
    val date: LocalDate,
    val pairNo: Int,
    val slot: Int,
    val start: LocalTime,
    val end: LocalTime,
    val subject: String,
    val kind: String,
    val teacher: String? = null,
    val room: String? = null,
    val stream: String? = null,
    val note: String? = null,
    val cancelled: Boolean = false,
    val remote: Boolean = false,
    val links: List<String> = emptyList(),
)

data class DateRange(val from: LocalDate, val to: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
}

val lessonOrder: Comparator<Lesson> = compareBy({ it.date }, { it.pairNo }, { it.slot })
```

`domain/Week.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun syncWindow(today: LocalDate): DateRange {
    val start = weekStart(today)
    return DateRange(start, start.plusDays(13))
}
```

`data/remote/SiteCodec.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import java.net.URLEncoder
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object SiteCodec {
    val CHARSET: Charset = Charset.forName("windows-1251")
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Значение POST-формы расписания: сайт ждёт windows-1251. */
    fun formEncode(value: String): String = URLEncoder.encode(value, "windows-1251")

    /** Параметр query автокомплита: браузер шлёт UTF-8 (URLSearchParams). */
    fun queryEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun decode(bytes: ByteArray): String = String(bytes, CHARSET)

    fun formatDate(date: LocalDate): String = date.format(DATE)
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SiteCodecTest' --tests '*WeekTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: domain models, site encoding, week helpers"
```

---

### Task 4: Парсер HTML расписания

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/data/remote/ScheduleParser.kt`
- Create: `app/src/test/resources/fixtures/*` (копия `docs/superpowers/plans/fixtures/`)
- Test: `app/src/test/java/io/github/kwebmn/rozklad/data/remote/ScheduleParserTest.kt`, `app/src/test/java/io/github/kwebmn/rozklad/fakes/Fixtures.kt`

**Interfaces:**
- Consumes: `Lesson`, `SubscriptionType`, `SiteCodec.decode` (Task 3).
- Produces:
  - `class SiteFormatException(message: String) : Exception(message)`
  - `data class ParsedSchedule(type: SubscriptionType?, name: String?, siteId: String?, lessons: List<Lesson>)` — пустой период: всё `null`, `lessons` пуст.
  - `object ScheduleParser { fun parse(html: String): ParsedSchedule }` — бросает `SiteFormatException`.
  - test helper `fun fixtureBytes(name: String): ByteArray`, `fun fixture(name: String): String`.

Фикстуры — реальные страницы сайта (windows-1251), снятые 15.10.2026: `group_week.html` (ІПЗс-24-1, id -607, 13–19.10), `teacher_week.html` (Демчина, id 66), `group_markers.html` (Жс-24-1, id -604, 01.09–31.10: отмены, дистанционные, две пары в ячейке), `empty_period.html` (июль), `form_only.html` (страница без запроса), `suggest_groups.json` (автокомплит «ІПЗ»). **Не перекачивать** — данные на сайте меняются, ожидания в тестах привязаны к этим копиям.

- [ ] **Step 1: Скопировать фикстуры и helper**

```bash
mkdir -p app/src/test/resources/fixtures
cp docs/superpowers/plans/fixtures/* app/src/test/resources/fixtures/
```

`app/src/test/java/io/github/kwebmn/rozklad/fakes/Fixtures.kt`:
```kotlin
package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.data.remote.SiteCodec

fun fixtureBytes(name: String): ByteArray =
    requireNotNull(object {}.javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
        .use { it.readBytes() }

fun fixture(name: String): String = SiteCodec.decode(fixtureBytes(name))
```

- [ ] **Step 2: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/data/remote/ScheduleParserTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ScheduleParserTest {
    private fun day(d: Int, m: Int) = LocalDate.of(2026, m, d)

    @Test
    fun `parses group week`() {
        val r = ScheduleParser.parse(fixture("group_week.html"))
        assertEquals(SubscriptionType.GROUP, r.type)
        assertEquals("ІПЗс-24-1", r.name)
        assertEquals("-607", r.siteId)
        assertEquals(14, r.lessons.size)
        assertEquals(5, r.lessons.map { it.date }.distinct().size)
        assertEquals(
            Lesson(
                date = day(13, 10), pairNo = 1, slot = 0,
                start = LocalTime.of(8, 30), end = LocalTime.of(9, 50),
                subject = "Prompt-інжиніринг", kind = "Л",
                teacher = "Демчина Микола Миколайович", room = "к.1ауд.410",
                stream = "потік ІПЗс-24-1, ІПЗс-24-2",
            ),
            r.lessons[0],
        )
    }

    @Test
    fun `parses teacher week with groups instead of teacher`() {
        val r = ScheduleParser.parse(fixture("teacher_week.html"))
        assertEquals(SubscriptionType.TEACHER, r.type)
        assertEquals("Демчина Микола Миколайович", r.name)
        assertEquals("66", r.siteId)
        assertEquals(9, r.lessons.size)
        val second = r.lessons[1]
        assertEquals(day(13, 10), second.date)
        assertEquals(3, second.pairNo)
        assertEquals("ІПЗс-24-1", second.stream)
        assertNull(second.teacher)
        assertEquals("к.1ауд.330/5к", second.room)
    }

    @Test
    fun `cancelled lesson and its replacement share one pair`() {
        val r = ScheduleParser.parse(fixture("group_markers.html"))
        assertEquals(106, r.lessons.size)
        assertEquals(5, r.lessons.count { it.cancelled })
        val pair = r.lessons.filter { it.date == day(1, 9) && it.pairNo == 3 }
        assertEquals(2, pair.size)
        assertEquals("Радіовиробництво і подкасти", pair[0].subject)
        assertEquals(0, pair[0].slot)
        assertTrue(pair[0].cancelled)
        assertEquals("Увага! Заняття відмінено!", pair[0].note)
        assertEquals("Брендинг", pair[1].subject)
        assertEquals(1, pair[1].slot)
        assertFalse(pair[1].cancelled)
        assertNull(pair[1].note)
    }

    @Test
    fun `remote lesson keeps meeting links`() {
        val r = ScheduleParser.parse(fixture("group_markers.html"))
        val l = r.lessons.single { it.date == day(8, 10) && it.pairNo == 2 }
        assertTrue(l.remote)
        assertNull(l.room)
        assertEquals(
            listOf("https://meet.google.com/irq-qsnc-jwu", "https://meet.google.com/pdy-xnvx-xqj"),
            l.links,
        )
    }

    @Test
    fun `lesson without room has null room`() {
        val r = ScheduleParser.parse(fixture("group_markers.html"))
        val l = r.lessons.single { it.date == day(16, 9) && it.pairNo == 6 }
        assertEquals("Практика PR-діяльності", l.subject)
        assertEquals("Лашків Софія Олегівна", l.teacher)
        assertNull(l.room)
    }

    @Test
    fun `late pairs have their own times`() {
        val r = ScheduleParser.parse(fixture("group_markers.html"))
        val fifth = r.lessons.single { it.date == day(2, 10) && it.pairNo == 5 }
        assertEquals(LocalTime.of(15, 10), fifth.start)
        assertEquals(LocalTime.of(16, 30), fifth.end)
        assertEquals("Сем", fifth.kind)
    }

    @Test
    fun `nothing found page is a valid empty period`() {
        val r = ScheduleParser.parse(fixture("empty_period.html"))
        assertTrue(r.lessons.isEmpty())
        assertNull(r.name)
        assertNull(r.siteId)
        assertNull(r.type)
    }

    @Test
    fun `form page without schedule is a format error`() {
        assertThrows(SiteFormatException::class.java) { ScheduleParser.parse(fixture("form_only.html")) }
    }

    @Test
    fun `garbage is a format error`() {
        assertThrows(SiteFormatException::class.java) {
            ScheduleParser.parse("<html><body>Internal Server Error</body></html>")
        }
    }
}
```

- [ ] **Step 3: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleParserTest'`
Expected: FAIL — `Unresolved reference: ScheduleParser`.

- [ ] **Step 4: Implement**

`data/remote/ScheduleParser.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.SubscriptionType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SiteFormatException(message: String) : Exception(message)

data class ParsedSchedule(
    val type: SubscriptionType?,
    val name: String?,
    val siteId: String?,
    val lessons: List<Lesson>,
)

object ScheduleParser {
    private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val DAY_HEADER = Regex("""^(\d{2}\.\d{2}\.\d{4})""")
    private val TIME = Regex("""\d{1,2}:\d{2}""")
    private val SITE_ID = Regex("""[?&](?:group|teacher)=(-?\d+)""")
    private const val NOTHING_FOUND = "записів не знайдено"
    private const val GROUP_TITLE = "Розклад групи"
    private const val TEACHER_TITLE = "Розклад викладача"

    fun parse(html: String): ParsedSchedule {
        val doc = Jsoup.parse(html)
        if (doc.select("div.alert").any { it.text().contains(NOTHING_FOUND) }) {
            return ParsedSchedule(null, null, null, emptyList())
        }
        val title = doc.select("h4").firstOrNull {
            val t = it.text()
            t.startsWith(GROUP_TITLE) || t.startsWith(TEACHER_TITLE)
        } ?: throw SiteFormatException("Schedule title not found")
        val type = if (title.text().startsWith(GROUP_TITLE)) SubscriptionType.GROUP else SubscriptionType.TEACHER
        val link = title.selectFirst("a")

        val lessons = doc.select("h4").flatMap { header ->
            val dateText = DAY_HEADER.find(header.text().trim())?.groupValues?.get(1)
                ?: return@flatMap emptyList()
            val date = LocalDate.parse(dateText, DATE)
            val table = header.parent()?.selectFirst("table") ?: return@flatMap emptyList()
            table.select("tr").flatMap { row -> parseRow(row, date) }
        }
        return ParsedSchedule(
            type = type,
            name = link?.text()?.trim(),
            siteId = link?.attr("href")?.let { SITE_ID.find(it)?.groupValues?.get(1) },
            lessons = lessons,
        )
    }

    private fun parseRow(row: Element, date: LocalDate): List<Lesson> {
        val cells = row.select("td")
        if (cells.size < 3) return emptyList()
        val pairNo = cells[0].text().trim().toIntOrNull() ?: return emptyList()
        val times = TIME.findAll(cells[1].text()).map { LocalTime.parse(it.value.padStart(5, '0')) }.toList()
        if (times.size < 2) throw SiteFormatException("Bad time cell on $date pair $pairNo")
        return parseCell(cells[2]).mapIndexed { slot, draft ->
            draft.toLesson(date, pairNo, slot, times[0], times[1])
        }
    }

    private class Draft(val subject: String, val note: String?, val remote: Boolean) {
        var kind = ""
        var teacher: String? = null
        var room: String? = null
        var stream: String? = null
        val links = mutableListOf<String>()

        fun toLesson(date: LocalDate, pairNo: Int, slot: Int, start: LocalTime, end: LocalTime) = Lesson(
            date = date, pairNo = pairNo, slot = slot, start = start, end = end,
            subject = subject, kind = kind, teacher = teacher, room = room, stream = stream,
            note = note, cancelled = note?.contains("відмінено") == true, remote = remote,
            links = links.distinct(),
        )
    }

    /** Пометки (zamena, remote_work*) стоят перед p_name и относятся к следующему занятию ячейки. */
    private fun parseCell(cell: Element): List<Draft> {
        val drafts = mutableListOf<Draft>()
        var pendingNote: String? = null
        var pendingRemote = false
        for (el in cell.select("span, a")) {
            val text = el.text().trim().ifEmpty { null }
            val current = drafts.lastOrNull()
            when {
                el.tagName() == "a" ->
                    el.attr("href").trim().takeIf { it.startsWith("http") }?.let { current?.links?.add(it) }
                el.hasClass("zamena") -> pendingNote = text
                el.classNames().any { it.startsWith("remote_work") } -> pendingRemote = true
                el.hasClass("p_name") -> {
                    drafts += Draft(text.orEmpty(), pendingNote, pendingRemote)
                    pendingNote = null
                    pendingRemote = false
                }
                current == null -> Unit
                el.hasClass("p_type_name") -> current.kind = text.orEmpty()
                el.hasClass("t_name") -> current.teacher = text
                el.hasClass("room_name") -> current.room = text
                el.hasClass("gr_name") || el.hasClass("gr2_name") -> current.stream = text
            }
        }
        return drafts
    }
}
```

- [ ] **Step 5: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleParserTest'`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat: parse PS-Rozklad schedule HTML"
```

---

### Task 5: Автокомплит и HTTP-клиент сайта

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/data/remote/SuggestParser.kt`, `data/remote/ScheduleApi.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/data/remote/ScheduleApiTest.kt`

**Interfaces:**
- Consumes: `ScheduleParser`, `ParsedSchedule`, `SiteFormatException`, `SiteCodec`, `DateRange` (Tasks 3–4).
- Produces:
  - `class NetworkException(cause: Throwable) : Exception(cause)`
  - `interface ScheduleSource { suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String>; suspend fun fetch(type: SubscriptionType, siteId: String?, name: String, range: DateRange): ParsedSchedule }`
  - `class ScheduleApi(client: OkHttpClient, baseUrl: String = ScheduleApi.BASE_URL) : ScheduleSource`
  - `object SuggestParser { fun parse(text: String): List<String> }`

- [ ] **Step 1: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/data/remote/ScheduleApiTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.fixtureBytes
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ScheduleApiTest {
    private val server = MockWebServer()
    private lateinit var api: ScheduleApi
    private val week = DateRange(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 19))

    @Before
    fun setUp() {
        server.start()
        api = ScheduleApi(OkHttpClient(), server.url("/cgi-bin/timetable.cgi").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(fixture: String) =
        server.enqueue(MockResponse().setBody(Buffer().write(fixtureBytes(fixture))))

    @Test
    fun `suggest sends utf-8 query and decodes windows-1251 json`() = runTest {
        respond("suggest_groups.json")
        val result = api.suggest(SubscriptionType.GROUP, "ІПЗ", faculty = 1001, course = 2)
        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("n=701&lev=142&faculty=1001&course=2&query=%D0%86%D0%9F%D0%97"))
        assertEquals(38, result.size)
        assertTrue(result.contains("ІПЗс-24-1"))
    }

    @Test
    fun `teacher suggest uses lev 141 without course`() = runTest {
        server.enqueue(MockResponse().setBody("""{"query":"x","suggestions":[]}"""))
        api.suggest(SubscriptionType.TEACHER, "Дем", faculty = 0, course = 3)
        val path = server.takeRequest().path!!
        assertTrue(path, path.contains("lev=141&faculty=0&query="))
        assertTrue(path, !path.contains("course="))
    }

    @Test
    fun `fetch by site id uses permalink GET`() = runTest {
        respond("group_week.html")
        val r = api.fetch(SubscriptionType.GROUP, "-607", "ІПЗс-24-1", week)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.path!!, req.path!!.endsWith("?n=700&group=-607&sdate=13.10.2026&edate=19.10.2026"))
        assertEquals(14, r.lessons.size)
    }

    @Test
    fun `fetch teacher by site id`() = runTest {
        respond("teacher_week.html")
        api.fetch(SubscriptionType.TEACHER, "66", "Демчина Микола Миколайович", week)
        assertTrue(server.takeRequest().path!!.contains("&teacher=66&"))
    }

    @Test
    fun `fetch by name posts windows-1251 form`() = runTest {
        respond("group_week.html")
        val r = api.fetch(SubscriptionType.GROUP, null, "ІПЗс-24-1", week)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals(
            "faculty=0&teacher=&course=0&group=%B2%CF%C7%F1-24-1&sdate=13.10.2026&edate=19.10.2026&n=700",
            req.body.readUtf8(),
        )
        assertEquals("-607", r.siteId)
    }

    @Test
    fun `http error becomes NetworkException`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(NetworkException::class.java) {
            kotlinx.coroutines.runBlocking { api.fetch(SubscriptionType.GROUP, "-607", "x", week) }
        }
    }

    @Test
    fun `unreachable server becomes NetworkException`() {
        server.shutdown()
        assertThrows(NetworkException::class.java) {
            kotlinx.coroutines.runBlocking { api.fetch(SubscriptionType.GROUP, "-607", "x", week) }
        }
    }

    @Test
    fun `broken suggest json is a format error`() {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertThrows(SiteFormatException::class.java) {
            kotlinx.coroutines.runBlocking { api.suggest(SubscriptionType.GROUP, "ІП", 0, 0) }
        }
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleApiTest'`
Expected: FAIL — `Unresolved reference: ScheduleApi`.

- [ ] **Step 3: Implement**

`data/remote/SuggestParser.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object SuggestParser {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Response(val suggestions: List<String> = emptyList())

    fun parse(text: String): List<String> = try {
        json.decodeFromString<Response>(text).suggestions
    } catch (e: IllegalArgumentException) {
        throw SiteFormatException("Bad suggest response: ${e.message}")
    }
}
```

`data/remote/ScheduleApi.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.SubscriptionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NetworkException(cause: Throwable) : Exception(cause)

interface ScheduleSource {
    suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String>
    suspend fun fetch(type: SubscriptionType, siteId: String?, name: String, range: DateRange): ParsedSchedule
}

class ScheduleApi(
    private val client: OkHttpClient,
    private val baseUrl: String = BASE_URL,
) : ScheduleSource {

    override suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String> {
        val url = buildString {
            append(baseUrl)
            append(if (type == SubscriptionType.GROUP) "?n=701&lev=142" else "?n=701&lev=141")
            append("&faculty=").append(faculty)
            if (type == SubscriptionType.GROUP) append("&course=").append(course)
            append("&query=").append(SiteCodec.queryEncode(query))
        }
        return SuggestParser.parse(execute(Request.Builder().url(url).build()))
    }

    override suspend fun fetch(type: SubscriptionType, siteId: String?, name: String, range: DateRange): ParsedSchedule {
        val dates = "sdate=${SiteCodec.formatDate(range.from)}&edate=${SiteCodec.formatDate(range.to)}"
        val request = if (siteId != null) {
            val param = if (type == SubscriptionType.GROUP) "group" else "teacher"
            Request.Builder().url("$baseUrl?n=700&$param=$siteId&$dates").build()
        } else {
            val group = if (type == SubscriptionType.GROUP) SiteCodec.formEncode(name) else ""
            val teacher = if (type == SubscriptionType.TEACHER) SiteCodec.formEncode(name) else ""
            val body = "faculty=0&teacher=$teacher&course=0&group=$group&$dates&n=700"
                .toRequestBody(FORM)
            Request.Builder().url("$baseUrl?n=700").post(body).build()
        }
        return ScheduleParser.parse(execute(request))
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                SiteCodec.decode(response.body?.bytes() ?: ByteArray(0))
            }
        } catch (e: IOException) {
            throw NetworkException(e)
        }
    }

    companion object {
        const val BASE_URL = "http://195.162.83.28/cgi-bin/timetable.cgi"
        private val FORM = "application/x-www-form-urlencoded".toMediaType()
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleApiTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: HTTP client for schedule and suggestions"
```

---

### Task 6: Форматирование и diff расписания

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/domain/Format.kt`, `domain/LessonDiff.kt`
- Create: `app/src/test/java/io/github/kwebmn/rozklad/fakes/TestData.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/domain/FormatTest.kt`, `domain/LessonDiffTest.kt`

**Interfaces:**
- Consumes: `Lesson` (Task 3).
- Produces:
  - `fun dayShort(DayOfWeek): String`, `fun dayFull(DayOfWeek): String`, `fun shortDate(LocalDate): String` (`"16.10"`), `fun formatTime(LocalTime): String` (`"08:30"`), `fun shortRoom(String): String`, `fun shortTeacher(String): String`, `fun formatSyncStatus(lastSyncedAt: Long?, nowMillis: Long, offline: Boolean, zone: ZoneId): String`
  - `sealed interface LessonChange { Added(lesson), Removed(lesson), Changed(old, new) }`, `fun diffLessons(old, new, since: LocalDate): List<LessonChange>`, `fun describe(LessonChange): String`, `fun changeSummary(changes, max = 5): List<String>`
  - test helpers: `val FIXED_CLOCK: Clock` (2026-10-15 09:00 Europe/Kyiv, четверг), `fun clockAt(local: String): Clock`, `fun lesson(date: LocalDate, pair: Int, subject = "Предмет", ...)`, `val THU: LocalDate`, `val FRI: LocalDate`

- [ ] **Step 1: Test data helpers**

`app/src/test/java/io/github/kwebmn/rozklad/fakes/TestData.kt`:
```kotlin
package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.domain.Lesson
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

/** Часы, остановленные на местном времени Киева, формат "2026-10-15T09:00". */
fun clockAt(local: String): Clock =
    Clock.fixed(LocalDateTime.parse(local).atZone(KYIV).toInstant(), KYIV)

/** Четверг 15.10.2026, 09:00 по Киеву. */
val FIXED_CLOCK: Clock = clockAt("2026-10-15T09:00")
val THU: LocalDate = LocalDate.of(2026, 10, 15)
val FRI: LocalDate = LocalDate.of(2026, 10, 16)

private val PAIR_TIMES = mapOf(
    1 to ("08:30" to "09:50"), 2 to ("10:00" to "11:20"), 3 to ("12:00" to "13:20"),
    4 to ("13:30" to "14:50"), 5 to ("15:10" to "16:30"), 6 to ("16:40" to "18:00"),
    7 to ("18:10" to "19:30"),
)

fun lesson(
    date: LocalDate,
    pair: Int,
    subject: String = "Предмет",
    slot: Int = 0,
    kind: String = "Пр",
    room: String? = "к.1ауд.410",
    teacher: String? = "Демчина Микола Миколайович",
    cancelled: Boolean = false,
    remote: Boolean = false,
): Lesson {
    val (start, end) = PAIR_TIMES.getValue(pair)
    return Lesson(
        date = date, pairNo = pair, slot = slot,
        start = LocalTime.parse(start), end = LocalTime.parse(end),
        subject = subject, kind = kind, teacher = teacher, room = room,
        note = if (cancelled) "Увага! Заняття відмінено!" else null,
        cancelled = cancelled, remote = remote,
    )
}
```

- [ ] **Step 2: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/domain/FormatTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.KYIV
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class FormatTest {
    private val now = FIXED_CLOCK.millis()
    private val minute = 60_000L

    @Test
    fun `day names`() {
        assertEquals("Чт", dayShort(DayOfWeek.THURSDAY))
        assertEquals("П'ятниця", dayFull(DayOfWeek.FRIDAY))
        assertEquals("16.10", shortDate(LocalDate.of(2026, 10, 16)))
        assertEquals("08:30", formatTime(LocalTime.of(8, 30)))
    }

    @Test
    fun `rooms are shortened`() {
        assertEquals("ауд. 330/5к", shortRoom("к.1ауд.330/5к"))
        assertEquals("ауд. радіостудія", shortRoom("к.1ауд.радіостудія"))
        assertEquals("к.2, ауд. 101", shortRoom("к.2ауд.101"))
        assertEquals("спортзал", shortRoom("спортзал"))
    }

    @Test
    fun `teachers are shortened to initials`() {
        assertEquals("Демчина М.М.", shortTeacher("Демчина Микола Миколайович"))
        assertEquals("Іванов", shortTeacher("Іванов"))
    }

    @Test
    fun `sync status`() {
        assertEquals("Ще не оновлювалось", formatSyncStatus(null, now, offline = false, zone = KYIV))
        assertEquals("Оновлено щойно", formatSyncStatus(now - 30_000, now, false, KYIV))
        assertEquals("Оновлено 5 хв тому", formatSyncStatus(now - 5 * minute, now, false, KYIV))
        assertEquals("Оновлено 3 год тому", formatSyncStatus(now - 180 * minute, now, false, KYIV))
        assertEquals("Оновлено 13.10 09:00", formatSyncStatus(now - 2 * 24 * 60 * minute, now, false, KYIV))
        assertEquals("Офлайн · дані від 15.10 08:55", formatSyncStatus(now - 5 * minute, now, true, KYIV))
        assertEquals("Офлайн · немає даних", formatSyncStatus(null, now, true, KYIV))
    }
}
```

`app/src/test/java/io/github/kwebmn/rozklad/domain/LessonDiffTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonDiffTest {
    private val uiux = lesson(FRI, 2, "UI/UX дизайн", room = "к.1ауд.330/1к")

    @Test
    fun `identical schedules have no changes`() {
        assertTrue(diffLessons(listOf(uiux), listOf(uiux), since = THU).isEmpty())
    }

    @Test
    fun `detects added removed and changed`() {
        val moved = uiux.copy(room = "к.1ауд.503")
        val added = lesson(FRI, 3, "Безпека")
        val removed = lesson(THU, 1, "Алгоритми")
        val changes = diffLessons(listOf(removed, uiux), listOf(moved, added), since = THU)
        assertEquals(
            listOf(
                LessonChange.Removed(removed),
                LessonChange.Changed(uiux, moved),
                LessonChange.Added(added),
            ),
            changes,
        )
    }

    @Test
    fun `past days are ignored`() {
        val past = lesson(THU.minusDays(1), 1)
        assertTrue(diffLessons(listOf(past), emptyList(), since = THU).isEmpty())
    }

    @Test
    fun `describes changes in ukrainian`() {
        assertEquals("Пт 16.10, 2 пара: нова — UI/UX дизайн (Пр)", describe(LessonChange.Added(uiux)))
        assertEquals("Пт 16.10, 2 пара: прибрано — UI/UX дизайн", describe(LessonChange.Removed(uiux)))
        assertEquals(
            "Пт 16.10, 2 пара: відмінено — UI/UX дизайн",
            describe(LessonChange.Changed(uiux, uiux.copy(cancelled = true))),
        )
        assertEquals(
            "Пт 16.10, 2 пара: змінено — UI/UX дизайн (ауд. 503)",
            describe(LessonChange.Changed(uiux, uiux.copy(room = "к.1ауд.503"))),
        )
        assertEquals(
            "Пт 16.10, 2 пара: змінено — UI/UX дизайн",
            describe(LessonChange.Changed(uiux, uiux.copy(kind = "Л"))),
        )
    }

    @Test
    fun `summary is capped`() {
        val changes = (1..7).map { LessonChange.Added(lesson(FRI, it)) }
        val summary = changeSummary(changes)
        assertEquals(6, summary.size)
        assertEquals("і ще: 2", summary.last())
    }
}
```

- [ ] **Step 3: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*FormatTest' --tests '*LessonDiffTest'`
Expected: FAIL — unresolved `dayShort`, `diffLessons`.

- [ ] **Step 4: Implement**

`domain/Format.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")
private val DAY_FULL = listOf("Понеділок", "Вівторок", "Середа", "Четвер", "П'ятниця", "Субота", "Неділя")
private val ROOM = Regex("""^к\.(\d+)ауд\.(.+)$""")

fun dayShort(day: DayOfWeek): String = DAY_SHORT[day.value - 1]

fun dayFull(day: DayOfWeek): String = DAY_FULL[day.value - 1]

fun shortDate(date: LocalDate): String = "%02d.%02d".format(date.dayOfMonth, date.monthValue)

fun formatTime(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

fun shortRoom(room: String): String {
    val match = ROOM.find(room.trim()) ?: return room.trim()
    val (building, number) = match.destructured
    return if (building == "1") "ауд. $number" else "к.$building, ауд. $number"
}

fun shortTeacher(full: String): String {
    val parts = full.trim().split(Regex("\\s+"))
    if (parts.size < 2) return full.trim()
    return parts[0] + " " + parts.drop(1).take(2).joinToString("") { "${it.first()}." }
}

fun formatSyncStatus(lastSyncedAt: Long?, nowMillis: Long, offline: Boolean, zone: ZoneId): String {
    if (lastSyncedAt == null) return if (offline) "Офлайн · немає даних" else "Ще не оновлювалось"
    val at = Instant.ofEpochMilli(lastSyncedAt).atZone(zone)
    val stamp = "${shortDate(at.toLocalDate())} ${formatTime(at.toLocalTime())}"
    if (offline) return "Офлайн · дані від $stamp"
    val minutes = (nowMillis - lastSyncedAt) / 60_000
    return when {
        minutes < 1 -> "Оновлено щойно"
        minutes < 60 -> "Оновлено $minutes хв тому"
        minutes < 24 * 60 -> "Оновлено ${minutes / 60} год тому"
        else -> "Оновлено $stamp"
    }
}
```

`domain/LessonDiff.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import java.time.LocalDate

sealed interface LessonChange {
    val lesson: Lesson

    data class Added(override val lesson: Lesson) : LessonChange
    data class Removed(override val lesson: Lesson) : LessonChange
    data class Changed(val old: Lesson, val new: Lesson) : LessonChange {
        override val lesson: Lesson get() = new
    }
}

private data class LessonKey(val date: LocalDate, val pairNo: Int, val slot: Int)

private fun Lesson.key() = LessonKey(date, pairNo, slot)

fun diffLessons(old: List<Lesson>, new: List<Lesson>, since: LocalDate): List<LessonChange> {
    val before = old.filter { !it.date.isBefore(since) }.associateBy { it.key() }
    val after = new.filter { !it.date.isBefore(since) }.associateBy { it.key() }
    val keys = (before.keys + after.keys).sortedWith(compareBy({ it.date }, { it.pairNo }, { it.slot }))
    return keys.mapNotNull { key ->
        val a = before[key]
        val b = after[key]
        when {
            a == null -> LessonChange.Added(b!!)
            b == null -> LessonChange.Removed(a)
            a != b -> LessonChange.Changed(a, b)
            else -> null
        }
    }
}

fun describe(change: LessonChange): String {
    val l = change.lesson
    val prefix = "${dayShort(l.date.dayOfWeek)} ${shortDate(l.date)}, ${l.pairNo} пара"
    return when (change) {
        is LessonChange.Added -> "$prefix: нова — ${l.subject} (${l.kind})"
        is LessonChange.Removed -> "$prefix: прибрано — ${l.subject}"
        is LessonChange.Changed -> {
            if (change.new.cancelled && !change.old.cancelled) {
                "$prefix: відмінено — ${l.subject}"
            } else {
                val details = listOfNotNull(
                    change.new.room?.takeIf { it != change.old.room }?.let(::shortRoom),
                    change.new.teacher?.takeIf { it != change.old.teacher }?.let(::shortTeacher),
                )
                val suffix = if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
                "$prefix: змінено — ${l.subject}$suffix"
            }
        }
    }
}

fun changeSummary(changes: List<LessonChange>, max: Int = 5): List<String> {
    val lines = changes.take(max).map(::describe)
    return if (changes.size > max) lines + "і ще: ${changes.size - max}" else lines
}
```

- [ ] **Step 5: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*FormatTest' --tests '*LessonDiffTest'`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat: formatting helpers and schedule diff"
```

---

### Task 7: Логика дня и расчёт напоминаний

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/domain/DayLogic.kt`, `domain/Reminders.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/domain/DayLogicTest.kt`, `domain/RemindersTest.kt`

**Interfaces:**
- Consumes: `Lesson`, `shortRoom` (Tasks 3, 6); test helpers from Task 6.
- Produces:
  - `fun chooseStartDay(now: LocalDateTime, lessons: List<Lesson>): LocalDate`
  - `fun currentOrNext(now: LocalDateTime, dayLessons: List<Lesson>): Lesson?`
  - `sealed interface DayRow { val pairNo: Int; data class Lessons(pairNo, start, end, lessons); data class Window(pairNo) }`, `fun buildDayRows(dayLessons: List<Lesson>): List<DayRow>`
  - `data class Reminder(id: Int, triggerAt: LocalDateTime, title: String, text: String)`, `fun upcomingReminders(lessons, now, minutesBefore: Int, horizonHours: Long = 24): List<Reminder>`, `fun reminderId(date: LocalDate, pairNo: Int): Int`, `const val MAX_PAIR = 8`

- [ ] **Step 1: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/domain/DayLogicTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DayLogicTest {
    private fun at(time: String) = LocalDateTime.parse("2026-10-15T$time")
    private val week = listOf(lesson(THU, 1), lesson(THU, 2), lesson(FRI.plusDays(3), 1))

    @Test
    fun `today while pairs remain`() {
        assertEquals(THU, chooseStartDay(at("07:00"), week))
        assertEquals(THU, chooseStartDay(at("10:30"), week))
    }

    @Test
    fun `next day with pairs after the last one`() {
        assertEquals(FRI.plusDays(3), chooseStartDay(at("11:30"), week))
    }

    @Test
    fun `today when nothing ahead`() {
        assertEquals(THU, chooseStartDay(at("12:00"), listOf(lesson(THU, 1))))
    }

    @Test
    fun `cancelled pairs do not keep today open`() {
        val lessons = listOf(lesson(THU, 4, cancelled = true), lesson(FRI, 1))
        assertEquals(FRI, chooseStartDay(at("09:00"), lessons))
    }

    @Test
    fun `current or next pair`() {
        val day = listOf(lesson(THU, 1), lesson(THU, 2, cancelled = true), lesson(THU, 3))
        assertEquals(1, currentOrNext(at("09:00"), day)?.pairNo)
        assertEquals(3, currentOrNext(at("10:30"), day)?.pairNo)
        assertNull(currentOrNext(at("14:00"), day))
    }

    @Test
    fun `day rows mark windows between pairs`() {
        val rows = buildDayRows(listOf(lesson(THU, 3), lesson(THU, 1), lesson(THU, 1, "Інше", slot = 1)))
        assertEquals(3, rows.size)
        val first = rows[0] as DayRow.Lessons
        assertEquals(listOf("Предмет", "Інше"), first.lessons.map { it.subject })
        assertTrue(rows[1] is DayRow.Window)
        assertEquals(2, rows[1].pairNo)
        assertEquals(3, rows[2].pairNo)
        assertTrue(buildDayRows(emptyList()).isEmpty())
    }
}
```

`app/src/test/java/io/github/kwebmn/rozklad/domain/RemindersTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class RemindersTest {
    private val now = LocalDateTime.parse("2026-10-15T09:00")

    @Test
    fun `schedules future pairs within 24 hours`() {
        val lessons = listOf(
            lesson(THU, 1), lesson(THU, 2), lesson(THU, 3, kind = "Л"),
            lesson(FRI, 1), lesson(FRI, 2),
        )
        val r = upcomingReminders(lessons, now, minutesBefore = 10)
        assertEquals(
            listOf("2026-10-15T09:50", "2026-10-15T11:50", "2026-10-16T08:20").map(LocalDateTime::parse),
            r.map { it.triggerAt },
        )
        assertEquals("Через 10 хв: Предмет", r[0].title)
        assertEquals("Пр · ауд. 410", r[0].text)
        assertEquals(reminderId(THU, 2), r[0].id)
    }

    @Test
    fun `pair starting sooner than the offset gets no reminder`() {
        val soon = lesson(THU, 2).copy(start = LocalTime.of(9, 5))
        assertTrue(upcomingReminders(listOf(soon), now, minutesBefore = 10).isEmpty())
    }

    @Test
    fun `cancelled pairs are skipped and slots are merged`() {
        val lessons = listOf(
            lesson(THU, 2, "Відмінена", cancelled = true),
            lesson(THU, 2, "Заміна", slot = 1, room = null, remote = true, kind = "Л"),
            lesson(THU, 3, cancelled = true),
        )
        val r = upcomingReminders(lessons, now, minutesBefore = 10)
        assertEquals(1, r.size)
        assertEquals("Через 10 хв: Заміна", r[0].title)
        assertEquals("Л · дистанційно", r[0].text)
    }

    @Test
    fun `ids are unique per day and pair`() {
        assertTrue(reminderId(THU, 2) != reminderId(FRI, 2))
        assertTrue(reminderId(THU, 2) != reminderId(THU, 3))
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*DayLogicTest' --tests '*RemindersTest'`
Expected: FAIL — unresolved `chooseStartDay`, `upcomingReminders`.

- [ ] **Step 3: Implement**

`domain/DayLogic.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Сегодня, если ещё есть неотменённые пары; иначе ближайший следующий день с парами; иначе сегодня. */
fun chooseStartDay(now: LocalDateTime, lessons: List<Lesson>): LocalDate {
    val today = now.toLocalDate()
    val active = lessons.filter { !it.cancelled }
    val todayEnd = active.filter { it.date == today }.maxOfOrNull { it.end }
    if (todayEnd != null && now.toLocalTime().isBefore(todayEnd)) return today
    return active.map { it.date }.filter { it.isAfter(today) }.minOrNull() ?: today
}

/** Идущая сейчас или ближайшая неотменённая пара дня. */
fun currentOrNext(now: LocalDateTime, dayLessons: List<Lesson>): Lesson? =
    dayLessons
        .filter { !it.cancelled && it.date.atTime(it.end).isAfter(now) }
        .minWithOrNull(compareBy({ it.date.atTime(it.start) }, { it.slot }))

sealed interface DayRow {
    val pairNo: Int

    data class Lessons(
        override val pairNo: Int,
        val start: LocalTime,
        val end: LocalTime,
        val lessons: List<Lesson>,
    ) : DayRow

    data class Window(override val pairNo: Int) : DayRow
}

fun buildDayRows(dayLessons: List<Lesson>): List<DayRow> {
    if (dayLessons.isEmpty()) return emptyList()
    val byPair = dayLessons.groupBy { it.pairNo }
    return (byPair.keys.min()..byPair.keys.max()).map { pair ->
        val lessons = byPair[pair]?.sortedBy { it.slot }
        if (lessons == null) DayRow.Window(pair)
        else DayRow.Lessons(pair, lessons.first().start, lessons.first().end, lessons)
    }
}
```

`domain/Reminders.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import java.time.LocalDate
import java.time.LocalDateTime

const val MAX_PAIR = 8

data class Reminder(
    val id: Int,
    val triggerAt: LocalDateTime,
    val title: String,
    val text: String,
)

fun reminderId(date: LocalDate, pairNo: Int): Int = (date.toEpochDay() * 16 + pairNo).toInt()

fun upcomingReminders(
    lessons: List<Lesson>,
    now: LocalDateTime,
    minutesBefore: Int,
    horizonHours: Long = 24,
): List<Reminder> {
    val horizon = now.plusHours(horizonHours)
    return lessons
        .filter { !it.cancelled }
        .groupBy { it.date to it.pairNo }
        .values
        .mapNotNull { slots ->
            val l = slots.minBy { it.slot }
            val trigger = l.date.atTime(l.start).minusMinutes(minutesBefore.toLong())
            if (!trigger.isAfter(now) || trigger.isAfter(horizon)) return@mapNotNull null
            val where = l.room?.let(::shortRoom) ?: if (l.remote) "дистанційно" else null
            Reminder(
                id = reminderId(l.date, l.pairNo),
                triggerAt = trigger,
                title = "Через $minutesBefore хв: ${l.subject}",
                text = listOfNotNull(l.kind.ifEmpty { null }, where).joinToString(" · "),
            )
        }
        .sortedBy { it.triggerAt }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*DayLogicTest' --tests '*RemindersTest'`
Expected: PASS (10 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: start day, day rows and reminder calculation"
```

---

### Task 8: Хранилище (интерфейс + Room) и fake для тестов

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/data/LessonStore.kt`, `data/local/Entities.kt`, `data/local/ScheduleDao.kt`, `data/local/AppDatabase.kt`, `data/local/RoomLessonStore.kt`
- Create: `app/src/test/java/io/github/kwebmn/rozklad/fakes/FakeLessonStore.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/data/local/EntitiesTest.kt`

**Interfaces:**
- Consumes: `Subscription`, `Lesson`, `DateRange`, `lessonOrder` (Task 3).
- Produces:
  - `interface LessonStore { observeSubscriptions(): Flow<List<Subscription>>; suspend subscriptions(): List<Subscription>; suspend insertSubscription(sub): Long; suspend updateSubscription(sub); suspend deleteSubscription(id: Long); observeLessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>>; suspend lessons(subscriptionId: Long, range: DateRange): List<Lesson>; suspend replaceLessons(subscriptionId: Long, range: DateRange, lessons: List<Lesson>) }`
  - `AppDatabase.build(context: Context): AppDatabase`, `RoomLessonStore(dao: ScheduleDao)`
  - `fun Lesson.toEntity(subscriptionId: Long): LessonEntity`, `fun LessonEntity.toDomain(): Lesson`, аналогично для `Subscription`
  - test fake `FakeLessonStore : LessonStore`

- [ ] **Step 1: Failing test (маппинг)**

`app/src/test/java/io/github/kwebmn/rozklad/data/local/EntitiesTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.local

import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Test

class EntitiesTest {
    @Test
    fun `lesson survives round trip`() {
        val full = lesson(THU, 2, remote = true, room = null)
            .copy(stream = "потік А", links = listOf("https://a", "https://b"))
        assertEquals(full, full.toEntity(subscriptionId = 7).toDomain())
        val bare = lesson(THU, 1, teacher = null, room = null)
        assertEquals(bare, bare.toEntity(7).toDomain())
        assertEquals(7L, bare.toEntity(7).subscriptionId)
    }

    @Test
    fun `subscription survives round trip`() {
        val sub = Subscription(3, SubscriptionType.TEACHER, "Демчина", "66", isPrimary = true, lastSyncedAt = 42)
        assertEquals(sub, sub.toEntity().toDomain())
        val noId = Subscription(4, SubscriptionType.GROUP, "ІПЗс-24-1", null)
        assertEquals(noId, noId.toEntity().toDomain())
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*EntitiesTest'`
Expected: FAIL — unresolved `toEntity`.

- [ ] **Step 3: Implement**

`data/LessonStore.kt`:
```kotlin
package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import kotlinx.coroutines.flow.Flow

interface LessonStore {
    fun observeSubscriptions(): Flow<List<Subscription>>
    suspend fun subscriptions(): List<Subscription>
    suspend fun insertSubscription(sub: Subscription): Long
    suspend fun updateSubscription(sub: Subscription)
    /** Удаляет подписку вместе с её парами. */
    suspend fun deleteSubscription(id: Long)
    fun observeLessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>>
    suspend fun lessons(subscriptionId: Long, range: DateRange): List<Lesson>
    /** Атомарно заменяет все пары подписки в периоде. */
    suspend fun replaceLessons(subscriptionId: Long, range: DateRange, lessons: List<Lesson>)
}
```

`data/local/Entities.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val name: String,
    val siteId: String?,
    val isPrimary: Boolean,
    val lastSyncedAt: Long?,
)

@Entity(
    tableName = "lessons",
    primaryKeys = ["subscriptionId", "date", "pairNo", "slot"],
    indices = [Index("subscriptionId")],
)
data class LessonEntity(
    val subscriptionId: Long,
    val date: Long,
    val pairNo: Int,
    val slot: Int,
    val start: String,
    val end: String,
    val subject: String,
    val kind: String,
    val teacher: String?,
    val room: String?,
    val stream: String?,
    val note: String?,
    val cancelled: Boolean,
    val remote: Boolean,
    val links: String,
)

fun Subscription.toEntity() = SubscriptionEntity(id, type.name, name, siteId, isPrimary, lastSyncedAt)

fun SubscriptionEntity.toDomain() =
    Subscription(id, SubscriptionType.valueOf(type), name, siteId, isPrimary, lastSyncedAt)

fun Lesson.toEntity(subscriptionId: Long) = LessonEntity(
    subscriptionId = subscriptionId,
    date = date.toEpochDay(),
    pairNo = pairNo,
    slot = slot,
    start = start.toString(),
    end = end.toString(),
    subject = subject,
    kind = kind,
    teacher = teacher,
    room = room,
    stream = stream,
    note = note,
    cancelled = cancelled,
    remote = remote,
    links = links.joinToString("\n"),
)

fun LessonEntity.toDomain() = Lesson(
    date = LocalDate.ofEpochDay(date),
    pairNo = pairNo,
    slot = slot,
    start = LocalTime.parse(start),
    end = LocalTime.parse(end),
    subject = subject,
    kind = kind,
    teacher = teacher,
    room = room,
    stream = stream,
    note = note,
    cancelled = cancelled,
    remote = remote,
    links = links.split("\n").filter { it.isNotEmpty() },
)
```

`data/local/ScheduleDao.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScheduleDao {
    @Query("SELECT * FROM subscriptions ORDER BY id")
    abstract fun observeSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY id")
    abstract suspend fun subscriptions(): List<SubscriptionEntity>

    @Insert
    abstract suspend fun insertSubscription(entity: SubscriptionEntity): Long

    @Update
    abstract suspend fun updateSubscription(entity: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    protected abstract suspend fun deleteSubscriptionRow(id: Long)

    @Query("DELETE FROM lessons WHERE subscriptionId = :subscriptionId")
    protected abstract suspend fun deleteAllLessons(subscriptionId: Long)

    @Transaction
    open suspend fun deleteSubscription(id: Long) {
        deleteAllLessons(id)
        deleteSubscriptionRow(id)
    }

    @Query(
        "SELECT * FROM lessons WHERE subscriptionId = :subscriptionId AND date BETWEEN :from AND :to " +
            "ORDER BY date, pairNo, slot",
    )
    abstract fun observeLessons(subscriptionId: Long, from: Long, to: Long): Flow<List<LessonEntity>>

    @Query(
        "SELECT * FROM lessons WHERE subscriptionId = :subscriptionId AND date BETWEEN :from AND :to " +
            "ORDER BY date, pairNo, slot",
    )
    abstract suspend fun lessons(subscriptionId: Long, from: Long, to: Long): List<LessonEntity>

    @Query("DELETE FROM lessons WHERE subscriptionId = :subscriptionId AND date BETWEEN :from AND :to")
    protected abstract suspend fun deleteLessons(subscriptionId: Long, from: Long, to: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertLessons(lessons: List<LessonEntity>)

    @Transaction
    open suspend fun replaceLessons(subscriptionId: Long, from: Long, to: Long, lessons: List<LessonEntity>) {
        deleteLessons(subscriptionId, from, to)
        insertLessons(lessons)
    }
}
```

`data/local/AppDatabase.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SubscriptionEntity::class, LessonEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ScheduleDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "rozklad.db").build()
    }
}
```

`data/local/RoomLessonStore.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.local

import io.github.kwebmn.rozklad.data.LessonStore
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLessonStore(private val dao: ScheduleDao) : LessonStore {
    override fun observeSubscriptions(): Flow<List<Subscription>> =
        dao.observeSubscriptions().map { list -> list.map { it.toDomain() } }

    override suspend fun subscriptions(): List<Subscription> = dao.subscriptions().map { it.toDomain() }

    override suspend fun insertSubscription(sub: Subscription): Long =
        dao.insertSubscription(sub.toEntity().copy(id = 0))

    override suspend fun updateSubscription(sub: Subscription) = dao.updateSubscription(sub.toEntity())

    override suspend fun deleteSubscription(id: Long) = dao.deleteSubscription(id)

    override fun observeLessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>> =
        dao.observeLessons(subscriptionId, range.from.toEpochDay(), range.to.toEpochDay())
            .map { list -> list.map { it.toDomain() } }

    override suspend fun lessons(subscriptionId: Long, range: DateRange): List<Lesson> =
        dao.lessons(subscriptionId, range.from.toEpochDay(), range.to.toEpochDay()).map { it.toDomain() }

    override suspend fun replaceLessons(subscriptionId: Long, range: DateRange, lessons: List<Lesson>) =
        dao.replaceLessons(
            subscriptionId,
            range.from.toEpochDay(),
            range.to.toEpochDay(),
            lessons.map { it.toEntity(subscriptionId) },
        )
}
```

`app/src/test/java/io/github/kwebmn/rozklad/fakes/FakeLessonStore.kt`:
```kotlin
package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.data.LessonStore
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.lessonOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeLessonStore : LessonStore {
    val subs = MutableStateFlow<List<Subscription>>(emptyList())
    val lessonsBySub = MutableStateFlow<Map<Long, List<Lesson>>>(emptyMap())
    private var nextId = 1L

    override fun observeSubscriptions(): Flow<List<Subscription>> = subs
    override suspend fun subscriptions(): List<Subscription> = subs.value

    override suspend fun insertSubscription(sub: Subscription): Long {
        val id = nextId++
        subs.value = subs.value + sub.copy(id = id)
        return id
    }

    override suspend fun updateSubscription(sub: Subscription) {
        subs.value = subs.value.map { if (it.id == sub.id) sub else it }
    }

    override suspend fun deleteSubscription(id: Long) {
        subs.value = subs.value.filterNot { it.id == id }
        lessonsBySub.value = lessonsBySub.value - id
    }

    override fun observeLessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>> =
        lessonsBySub.map { all -> select(all, subscriptionId, range) }

    override suspend fun lessons(subscriptionId: Long, range: DateRange): List<Lesson> =
        select(lessonsBySub.value, subscriptionId, range)

    override suspend fun replaceLessons(subscriptionId: Long, range: DateRange, lessons: List<Lesson>) {
        val kept = lessonsBySub.value[subscriptionId].orEmpty().filterNot { it.date in range }
        lessonsBySub.value = lessonsBySub.value + (subscriptionId to kept + lessons)
    }

    private fun select(all: Map<Long, List<Lesson>>, id: Long, range: DateRange) =
        all[id].orEmpty().filter { it.date in range }.sortedWith(lessonOrder)
}
```

- [ ] **Step 4: Run, verify pass (и что Room-кодогенерация проходит)**

Run: `./gradlew :app:testDebugUnitTest --tests '*EntitiesTest' && ./gradlew :app:compileDebugKotlin`
Expected: PASS (2 tests), `BUILD SUCCESSFUL` (KSP проверил SQL-запросы DAO).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: Room storage for subscriptions and lessons"
```

---

### Task 9: ScheduleRepository

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/data/ScheduleRepository.kt`
- Create: `app/src/test/java/io/github/kwebmn/rozklad/fakes/FakeScheduleSource.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/data/ScheduleRepositoryTest.kt`

**Interfaces:**
- Consumes: `ScheduleSource`, `ParsedSchedule`, `NetworkException`, `SiteFormatException` (Tasks 4–5), `LessonStore` (Task 8), `diffLessons`, `syncWindow`.
- Produces: `class ScheduleRepository(source: ScheduleSource, store: LessonStore, clock: Clock)` with:
  - `val subscriptions: Flow<List<Subscription>>`
  - `fun lessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>>`
  - `suspend fun lessonsOnce(subscriptionId: Long, range: DateRange): List<Lesson>`
  - `suspend fun subscriptionsOnce(): List<Subscription>`
  - `suspend fun primary(): Subscription?`
  - `suspend fun suggest(type, query, faculty: Int, course: Int): List<String>`
  - `suspend fun addSubscription(type: SubscriptionType, name: String): Subscription`
  - `suspend fun refresh(sub: Subscription, range: DateRange = syncWindow(today)): List<LessonChange>` — бросает `NetworkException`/`SiteFormatException`, кэш при ошибке не меняется; при первой синхронизации возвращает пустой список.
  - `suspend fun deleteSubscription(id: Long)`, `suspend fun setPrimary(id: Long)`
  - test fake `FakeScheduleSource`

- [ ] **Step 1: Fake source**

`app/src/test/java/io/github/kwebmn/rozklad/fakes/FakeScheduleSource.kt`:
```kotlin
package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.data.remote.ParsedSchedule
import io.github.kwebmn.rozklad.data.remote.ScheduleSource
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.SubscriptionType

class FakeScheduleSource : ScheduleSource {
    data class FetchCall(val siteId: String?, val name: String, val range: DateRange)

    var suggestions: List<String> = emptyList()
    val suggestCalls = mutableListOf<String>()
    var schedule = ParsedSchedule(SubscriptionType.GROUP, "ІПЗс-24-1", "-607", emptyList())
    var error: Exception? = null
    val fetchCalls = mutableListOf<FetchCall>()

    override suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String> {
        suggestCalls += query
        error?.let { throw it }
        return suggestions
    }

    override suspend fun fetch(type: SubscriptionType, siteId: String?, name: String, range: DateRange): ParsedSchedule {
        fetchCalls += FetchCall(siteId, name, range)
        error?.let { throw it }
        return schedule
    }
}
```

- [ ] **Step 2: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/data/ScheduleRepositoryTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ParsedSchedule
import io.github.kwebmn.rozklad.data.remote.SiteFormatException
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.SubscriptionType.GROUP
import io.github.kwebmn.rozklad.domain.SubscriptionType.TEACHER
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class ScheduleRepositoryTest {
    private val source = FakeScheduleSource()
    private val store = FakeLessonStore()
    private val repo = ScheduleRepository(source, store, FIXED_CLOCK)
    private val window = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25))

    @Test
    fun `first subscription is primary and keeps site id and lessons`() = runTest {
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", listOf(lesson(THU, 1)))
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        assertEquals("-607", sub.siteId)
        assertTrue(sub.isPrimary)
        assertEquals(FIXED_CLOCK.millis(), sub.lastSyncedAt)
        assertEquals(FakeScheduleSource.FetchCall(null, "ІПЗс-24-1", window), source.fetchCalls.single())
        assertEquals(listOf(lesson(THU, 1)), store.lessons(sub.id, window))
    }

    @Test
    fun `second subscription is not primary`() = runTest {
        repo.addSubscription(GROUP, "ІПЗс-24-1")
        source.schedule = ParsedSchedule(TEACHER, "Демчина Микола Миколайович", "66", emptyList())
        val second = repo.addSubscription(TEACHER, "Демчина Микола Миколайович")
        assertFalse(second.isPrimary)
    }

    @Test
    fun `adding the same subscription twice returns the existing one`() = runTest {
        val first = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val again = repo.addSubscription(GROUP, "ІПЗс-24-1")
        assertEquals(first.id, again.id)
        assertEquals(1, source.fetchCalls.size)
        assertEquals(1, store.subscriptions().size)
    }

    @Test
    fun `empty period on add keeps null site id`() = runTest {
        source.schedule = ParsedSchedule(null, null, null, emptyList())
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        assertNull(sub.siteId)
        assertEquals("ІПЗс-24-1", sub.name)
    }

    @Test
    fun `refresh returns changes and stores new lessons`() = runTest {
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", listOf(lesson(FRI, 2)))
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val moved = lesson(FRI, 2, room = "к.1ауд.503")
        source.schedule = source.schedule.copy(lessons = listOf(moved))
        val changes = repo.refresh(sub)
        assertEquals(listOf(LessonChange.Changed(lesson(FRI, 2), moved)), changes)
        assertEquals(listOf(moved), store.lessons(sub.id, window))
        assertEquals(FakeScheduleSource.FetchCall("-607", "ІПЗс-24-1", window), source.fetchCalls.last())
    }

    @Test
    fun `first refresh without previous sync reports nothing`() = runTest {
        val id = store.insertSubscription(
            io.github.kwebmn.rozklad.domain.Subscription(type = GROUP, name = "ІПЗс-24-1", siteId = "-607"),
        )
        source.schedule = source.schedule.copy(lessons = listOf(lesson(FRI, 1)))
        assertTrue(repo.refresh(store.subscriptions().single { it.id == id }).isEmpty())
    }

    @Test
    fun `refresh learns site id when it was missing`() = runTest {
        source.schedule = ParsedSchedule(null, null, null, emptyList())
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", emptyList())
        repo.refresh(sub)
        assertEquals("-607", store.subscriptions().single().siteId)
    }

    @Test
    fun `failed refresh keeps the cache`() {
        val sub = runBlocking {
            source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", listOf(lesson(FRI, 2)))
            repo.addSubscription(GROUP, "ІПЗс-24-1")
        }
        source.error = SiteFormatException("broken")
        assertThrows(SiteFormatException::class.java) { runBlocking { repo.refresh(sub) } }
        source.error = NetworkException(IOException("down"))
        assertThrows(NetworkException::class.java) { runBlocking { repo.refresh(sub) } }
        assertEquals(listOf(lesson(FRI, 2)), runBlocking { store.lessons(sub.id, window) })
        assertEquals(FIXED_CLOCK.millis(), runBlocking { store.subscriptions().single().lastSyncedAt })
    }

    @Test
    fun `deleting the primary promotes another subscription`() = runTest {
        val first = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val second = repo.addSubscription(GROUP, "ІПЗс-24-2")
        repo.deleteSubscription(first.id)
        assertEquals(second.id, repo.primary()?.id)
        assertTrue(store.subscriptions().single().isPrimary)
    }

    @Test
    fun `set primary switches the flag`() = runTest {
        val first = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val second = repo.addSubscription(GROUP, "ІПЗс-24-2")
        repo.setPrimary(second.id)
        assertEquals(listOf(false, true), store.subscriptions().map { it.isPrimary })
        assertEquals(second.id, repo.primary()?.id)
        assertEquals(first.id, store.subscriptions().first().id)
    }
}
```

- [ ] **Step 3: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleRepositoryTest'`
Expected: FAIL — unresolved `ScheduleRepository`.

- [ ] **Step 4: Implement**

`data/ScheduleRepository.kt`:
```kotlin
package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.data.remote.ScheduleSource
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.domain.diffLessons
import io.github.kwebmn.rozklad.domain.syncWindow
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.LocalDate

class ScheduleRepository(
    private val source: ScheduleSource,
    private val store: LessonStore,
    private val clock: Clock,
) {
    val subscriptions: Flow<List<Subscription>> = store.observeSubscriptions()

    private fun today(): LocalDate = LocalDate.now(clock)

    fun lessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>> =
        store.observeLessons(subscriptionId, range)

    suspend fun lessonsOnce(subscriptionId: Long, range: DateRange): List<Lesson> =
        store.lessons(subscriptionId, range)

    suspend fun subscriptionsOnce(): List<Subscription> = store.subscriptions()

    suspend fun primary(): Subscription? {
        val all = store.subscriptions()
        return all.firstOrNull { it.isPrimary } ?: all.firstOrNull()
    }

    suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String> =
        source.suggest(type, query, faculty, course)

    suspend fun addSubscription(type: SubscriptionType, name: String): Subscription {
        val existing = store.subscriptions()
        existing.firstOrNull { it.type == type && it.name == name }?.let { return it }
        val range = syncWindow(today())
        val parsed = source.fetch(type, siteId = null, name = name, range = range)
        val sub = Subscription(
            type = type,
            name = name,
            siteId = parsed.siteId,
            isPrimary = existing.isEmpty(),
            lastSyncedAt = clock.millis(),
        )
        val id = store.insertSubscription(sub)
        store.replaceLessons(id, range, parsed.lessons)
        return sub.copy(id = id)
    }

    /** Бросает NetworkException / SiteFormatException; при ошибке кэш не меняется. */
    suspend fun refresh(sub: Subscription, range: DateRange = syncWindow(today())): List<LessonChange> {
        val current = store.subscriptions().firstOrNull { it.id == sub.id } ?: return emptyList()
        val parsed = source.fetch(current.type, current.siteId, current.name, range)
        val old = store.lessons(current.id, range)
        store.replaceLessons(current.id, range, parsed.lessons)
        store.updateSubscription(
            current.copy(siteId = current.siteId ?: parsed.siteId, lastSyncedAt = clock.millis()),
        )
        return if (current.lastSyncedAt == null) emptyList() else diffLessons(old, parsed.lessons, since = today())
    }

    suspend fun deleteSubscription(id: Long) {
        val all = store.subscriptions()
        val target = all.firstOrNull { it.id == id } ?: return
        store.deleteSubscription(id)
        if (target.isPrimary) {
            all.firstOrNull { it.id != id }?.let { store.updateSubscription(it.copy(isPrimary = true)) }
        }
    }

    suspend fun setPrimary(id: Long) {
        store.subscriptions().forEach { sub ->
            val wanted = sub.id == id
            if (sub.isPrimary != wanted) store.updateSubscription(sub.copy(isPrimary = wanted))
        }
    }
}
```

- [ ] **Step 5: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleRepositoryTest'`
Expected: PASS (10 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat: schedule repository with cache-safe refresh"
```

---

### Task 10: Настройки, AppContainer, Application, тема

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/data/SettingsStore.kt`, `AppContainer.kt`, `RozkladApp.kt`, `ui/theme/Theme.kt`
- Create: `app/src/test/java/io/github/kwebmn/rozklad/fakes/FakeSettingsStore.kt`
- Modify: `app/src/main/AndroidManifest.xml` (атрибут `android:name=".RozkladApp"` у `<application>`)
- Test: `app/src/test/java/io/github/kwebmn/rozklad/data/DataStoreSettingsTest.kt`

**Interfaces:**
- Consumes: `ScheduleRepository`, `ScheduleApi`, `AppDatabase`, `RoomLessonStore`.
- Produces:
  - `enum class ThemeMode { SYSTEM, LIGHT, DARK }`
  - `data class AppSettings(remindersEnabled = true, reminderMinutes = 10, changeNotifications = true, theme = ThemeMode.SYSTEM, lastUpdateCheck = 0L)`
  - `interface SettingsStore { val settings: Flow<AppSettings>; suspend fun update(transform: (AppSettings) -> AppSettings) }`, `class DataStoreSettings(DataStore<Preferences>)`
  - `class AppContainer(context)` с полями `clock: Clock`, `appScope: CoroutineScope`, `http: OkHttpClient` (internal), `store: LessonStore`, `repository: ScheduleRepository`, `settings: SettingsStore`
  - `val Context.container: AppContainer`
  - `@Composable fun RozkladTheme(mode: ThemeMode, content: @Composable () -> Unit)`
  - test fake `FakeSettingsStore(initial = AppSettings())` с полем `flow: MutableStateFlow<AppSettings>`

- [ ] **Step 1: Failing test**

`app/src/test/java/io/github/kwebmn/rozklad/data/DataStoreSettingsTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `defaults then round trip`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = DataStoreSettings(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") },
        )
        assertEquals(AppSettings(), store.settings.first())
        store.update { it.copy(reminderMinutes = 15, theme = ThemeMode.DARK, changeNotifications = false, lastUpdateCheck = 99) }
        assertEquals(
            AppSettings(remindersEnabled = true, reminderMinutes = 15, changeNotifications = false, theme = ThemeMode.DARK, lastUpdateCheck = 99),
            store.settings.first(),
        )
        scope.cancel()
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*DataStoreSettingsTest'`
Expected: FAIL — unresolved `DataStoreSettings`.

- [ ] **Step 3: Implement**

`data/SettingsStore.kt`:
```kotlin
package io.github.kwebmn.rozklad.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val remindersEnabled: Boolean = true,
    val reminderMinutes: Int = 10,
    val changeNotifications: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val lastUpdateCheck: Long = 0L,
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class DataStoreSettings(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private object Keys {
        val REMINDERS = booleanPreferencesKey("reminders_enabled")
        val MINUTES = intPreferencesKey("reminder_minutes")
        val CHANGES = booleanPreferencesKey("change_notifications")
        val THEME = stringPreferencesKey("theme")
        val LAST_UPDATE = longPreferencesKey("last_update_check")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.REMINDERS] = next.remindersEnabled
            prefs[Keys.MINUTES] = next.reminderMinutes
            prefs[Keys.CHANGES] = next.changeNotifications
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.LAST_UPDATE] = next.lastUpdateCheck
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            remindersEnabled = this[Keys.REMINDERS] ?: d.remindersEnabled,
            reminderMinutes = this[Keys.MINUTES] ?: d.reminderMinutes,
            changeNotifications = this[Keys.CHANGES] ?: d.changeNotifications,
            theme = this[Keys.THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: d.theme,
            lastUpdateCheck = this[Keys.LAST_UPDATE] ?: d.lastUpdateCheck,
        )
    }
}
```

`AppContainer.kt`:
```kotlin
package io.github.kwebmn.rozklad

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.kwebmn.rozklad.data.DataStoreSettings
import io.github.kwebmn.rozklad.data.LessonStore
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.SettingsStore
import io.github.kwebmn.rozklad.data.local.AppDatabase
import io.github.kwebmn.rozklad.data.local.RoomLessonStore
import io.github.kwebmn.rozklad.data.remote.ScheduleApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class AppContainer(context: Context) {
    val clock: Clock = Clock.systemDefaultZone()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val database = AppDatabase.build(context)
    val store: LessonStore = RoomLessonStore(database.dao())
    val repository = ScheduleRepository(ScheduleApi(http), store, clock)
    val settings: SettingsStore = DataStoreSettings(context.settingsDataStore)
}

val Context.container: AppContainer
    get() = (applicationContext as RozkladApp).container
```

> `Clock.systemDefaultZone()` фиксирует зону при создании; если пользователь сменит часовой пояс, `BootReceiver` (Task 15) перепланирует напоминания при `TIMEZONE_CHANGED`, а новая зона подхватится после перезапуска процесса. Это допустимо.

`RozkladApp.kt`:
```kotlin
package io.github.kwebmn.rozklad

import android.app.Application

class RozkladApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

`ui/theme/Theme.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.kwebmn.rozklad.data.ThemeMode

private val Brand = Color(0xFF1E5AA8)

@Composable
fun RozkladTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF9EC2FF))
        else -> lightColorScheme(primary = Brand)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
```

Manifest — добавить атрибут в `<application`:
```xml
    <application
        android:name=".RozkladApp"
        android:icon="@mipmap/ic_launcher"
```

`app/src/test/java/io/github/kwebmn/rozklad/fakes/FakeSettingsStore.kt`:
```kotlin
package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.data.AppSettings
import io.github.kwebmn.rozklad.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = flow
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        flow.value = transform(flow.value)
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*DataStoreSettingsTest' && ./gradlew assembleDebug`
Expected: PASS (1 test), `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: settings store, app container, theme"
```

---

### Task 11: Экран «Додати розклад»

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/ui/Messages.kt`, `ui/add/AddViewModel.kt`, `ui/add/AddScreen.kt`
- Create: `app/src/test/java/io/github/kwebmn/rozklad/fakes/MainDispatcherRule.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/ui/add/AddViewModelTest.kt`

**Interfaces:**
- Consumes: `ScheduleRepository.suggest/addSubscription`, `NetworkException`, `SiteFormatException`.
- Produces:
  - `const val NETWORK_ERROR`, `const val PARSE_ERROR`, `fun errorMessage(e: Exception): String?` в `ui/Messages.kt`
  - `val FACULTIES: List<Pair<Int, String>>`, `val SubscriptionType.minChars: Int`
  - `data class AddUiState(type, faculty, course, query, suggestions, searched, loading, error, added: Subscription?)`
  - `class AddViewModel(repo: ScheduleRepository)`: `state: StateFlow<AddUiState>`, `setType`, `setFaculty`, `setCourse`, `setQuery`, `select(name)`, `consumeAdded()`
  - `@Composable fun AddScreen(vm: AddViewModel, onAdded: (Subscription) -> Unit, onBack: (() -> Unit)?)`
  - test rule `MainDispatcherRule(dispatcher: TestDispatcher = StandardTestDispatcher())`

- [ ] **Step 1: Test rule**

`app/src/test/java/io/github/kwebmn/rozklad/fakes/MainDispatcherRule.kt`:
```kotlin
package io.github.kwebmn.rozklad.fakes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

- [ ] **Step 2: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/ui/add/AddViewModelTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.add

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ParsedSchedule
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import io.github.kwebmn.rozklad.ui.NETWORK_ERROR
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AddViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeScheduleSource()
    private val repo = ScheduleRepository(source, FakeLessonStore(), FIXED_CLOCK)

    @Test
    fun `short query does not hit the site`() = runTest(main.dispatcher) {
        val vm = AddViewModel(repo)
        vm.setQuery("І")
        advanceUntilIdle()
        assertTrue(source.suggestCalls.isEmpty())
    }

    @Test
    fun `typing is debounced`() = runTest(main.dispatcher) {
        source.suggestions = listOf("ІПЗс-24-1")
        val vm = AddViewModel(repo)
        vm.setQuery("ІП")
        advanceTimeBy(100)
        vm.setQuery("ІПЗ")
        advanceUntilIdle()
        assertEquals(listOf("ІПЗ"), source.suggestCalls)
        assertEquals(listOf("ІПЗс-24-1"), vm.state.value.suggestions)
        assertTrue(vm.state.value.searched)
    }

    @Test
    fun `teacher search needs three characters`() = runTest(main.dispatcher) {
        val vm = AddViewModel(repo)
        vm.setType(SubscriptionType.TEACHER)
        vm.setQuery("Де")
        advanceUntilIdle()
        assertTrue(source.suggestCalls.isEmpty())
        vm.setQuery("Дем")
        advanceUntilIdle()
        assertEquals(listOf("Дем"), source.suggestCalls)
    }

    @Test
    fun `selecting a suggestion adds a subscription`() = runTest(main.dispatcher) {
        source.schedule = ParsedSchedule(SubscriptionType.GROUP, "ІПЗс-24-1", "-607", emptyList())
        val vm = AddViewModel(repo)
        vm.select("ІПЗс-24-1")
        advanceUntilIdle()
        assertEquals("-607", vm.state.value.added?.siteId)
        assertFalse(vm.state.value.loading)
        vm.consumeAdded()
        assertNull(vm.state.value.added)
    }

    @Test
    fun `network error while adding is shown`() = runTest(main.dispatcher) {
        source.error = NetworkException(IOException("down"))
        val vm = AddViewModel(repo)
        vm.select("ІПЗс-24-1")
        advanceUntilIdle()
        assertEquals(NETWORK_ERROR, vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.added)
    }
}
```

- [ ] **Step 3: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*AddViewModelTest'`
Expected: FAIL — unresolved `AddViewModel`.

- [ ] **Step 4: Implement**

`ui/Messages.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui

import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.SiteFormatException

const val NETWORK_ERROR = "Немає з'єднання з сайтом розкладу"
const val PARSE_ERROR = "Не вдалося розібрати відповідь сайту"

/** Текст для известных ошибок сайта; null — ошибка неизвестна и должна пробрасываться дальше. */
fun errorMessage(e: Exception): String? = when (e) {
    is NetworkException -> NETWORK_ERROR
    is SiteFormatException -> PARSE_ERROR
    else -> null
}
```

`ui/add/AddViewModel.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.ui.errorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val FACULTIES = listOf(
    0 to "Усі факультети",
    1001 to "Факультет суспільних і прикладних наук",
    1002 to "Фаховий коледж",
    1004 to "Факультет медицини",
)

val SubscriptionType.minChars: Int
    get() = if (this == SubscriptionType.GROUP) 2 else 3

data class AddUiState(
    val type: SubscriptionType = SubscriptionType.GROUP,
    val faculty: Int = 0,
    val course: Int = 0,
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val searched: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val added: Subscription? = null,
)

class AddViewModel(private val repo: ScheduleRepository) : ViewModel() {
    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun setType(type: SubscriptionType) {
        _state.update { it.copy(type = type, suggestions = emptyList(), searched = false, error = null) }
        search()
    }

    fun setFaculty(faculty: Int) {
        _state.update { it.copy(faculty = faculty) }
        search()
    }

    fun setCourse(course: Int) {
        _state.update { it.copy(course = course) }
        search()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, error = null) }
        search()
    }

    private fun search() {
        searchJob?.cancel()
        val s = _state.value
        val query = s.query.trim()
        if (query.length < s.type.minChars) {
            _state.update { it.copy(suggestions = emptyList(), searched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            try {
                val result = repo.suggest(s.type, query, s.faculty, s.course)
                _state.update { it.copy(suggestions = result, searched = true) }
            } catch (e: Exception) {
                val message = errorMessage(e) ?: throw e
                _state.update { it.copy(error = message) }
            }
        }
    }

    fun select(name: String) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val sub = repo.addSubscription(_state.value.type, name)
                _state.update { it.copy(loading = false, added = sub) }
            } catch (e: Exception) {
                val message = errorMessage(e) ?: throw e
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    fun consumeAdded() {
        searchJob?.cancel()
        _state.value = AddUiState()
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
```

> `catch (e: Exception)` с `?: throw e` пробрасывает `CancellationException` и неизвестные ошибки дальше — так отмена debounce-корутины не превращается в сообщение об ошибке.

`ui/add/AddScreen.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.add

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(vm: AddViewModel, onAdded: (Subscription) -> Unit, onBack: (() -> Unit)?) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.added) {
        state.added?.let { sub ->
            vm.consumeAdded()
            onAdded(sub)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Додати розклад") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SubscriptionType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.type == type,
                        onClick = { vm.setType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, SubscriptionType.entries.size),
                    ) { Text(if (type == SubscriptionType.GROUP) "Група" else "Викладач") }
                }
            }
            FacultyPicker(state.faculty, vm::setFaculty)
            if (state.type == SubscriptionType.GROUP) CourseChips(state.course, vm::setCourse)
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                label = { Text(if (state.type == SubscriptionType.GROUP) "Назва групи" else "ПІБ викладача") },
                supportingText = { Text("Мінімум ${state.type.minChars} символи") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.searched && state.suggestions.isEmpty()) {
                Text("Нічого не знайдено", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.suggestions) { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        modifier = Modifier.clickable(enabled = !state.loading) { vm.select(name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FacultyPicker(selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(FACULTIES.first { it.first == selected }.second, Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FACULTIES.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

@Composable
private fun CourseChips(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        (0..6).forEach { course ->
            FilterChip(
                selected = selected == course,
                onClick = { onSelect(course) },
                label = { Text(if (course == 0) "Усі курси" else "$course курс") },
            )
        }
    }
}
```

- [ ] **Step 5: Run, verify pass and compile**

Run: `./gradlew :app:testDebugUnitTest --tests '*AddViewModelTest' && ./gradlew assembleDebug`
Expected: PASS (5 tests), `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat: add-subscription screen with site autocomplete"
```

---

### Task 12: Главный экран расписания

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/ui/schedule/ScheduleViewModel.kt`, `ui/schedule/ScheduleScreen.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/ui/schedule/ScheduleViewModelTest.kt`

**Interfaces:**
- Consumes: `ScheduleRepository` (Task 9), `chooseStartDay`, `currentOrNext`, `buildDayRows`, `DayRow`, `weekStart`, `syncWindow`, `formatSyncStatus`, `shortRoom`, `shortTeacher`, `dayShort`, `dayFull`, `shortDate`, `formatTime`, `errorMessage`, `PARSE_ERROR`.
- Produces:
  - `data class ScheduleUiState(subscriptions, current: Subscription?, weekStart: LocalDate, selectedDate: LocalDate, weekLessons: List<Lesson>, refreshing, offline, error: String?)` с `val dayLessons`
  - `class ScheduleViewModel(repo: ScheduleRepository, clock: Clock, onSynced: () -> Unit = {})`: `state`, `showDate(LocalDate)`, `shiftDay(Long)`, `shiftWeek(Long)`, `selectSubscription(Long)`, `refresh()`
  - `@Composable fun ScheduleScreen(vm: ScheduleViewModel, clock: Clock, onAdd: () -> Unit, onSettings: () -> Unit)`

- [ ] **Step 1: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/ui/schedule/ScheduleViewModelTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.schedule

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.clockAt
import io.github.kwebmn.rozklad.fakes.lesson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val source = FakeScheduleSource()
    private val store = FakeLessonStore()
    private var groupId = 0L
    private var teacherId = 0L

    @Before
    fun seed() = runTest {
        groupId = store.insertSubscription(
            Subscription(type = SubscriptionType.GROUP, name = "ІПЗс-24-1", siteId = "-607", isPrimary = true, lastSyncedAt = FIXED_CLOCK.millis()),
        )
        teacherId = store.insertSubscription(
            Subscription(type = SubscriptionType.TEACHER, name = "Демчина", siteId = "66", lastSyncedAt = FIXED_CLOCK.millis()),
        )
        val window = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25))
        store.replaceLessons(groupId, window, listOf(lesson(THU, 1), lesson(THU, 2), lesson(FRI, 1)))
        store.replaceLessons(teacherId, window, listOf(lesson(THU, 3, "Лекція")))
    }

    private fun vm(clock: Clock = FIXED_CLOCK, onSynced: () -> Unit = {}) =
        ScheduleViewModel(ScheduleRepository(source, store, clock), clock, onSynced)

    @Test
    fun `opens today while pairs remain`() {
        val state = vm().state.value
        assertEquals("ІПЗс-24-1", state.current?.name)
        assertEquals(THU, state.selectedDate)
        assertEquals(LocalDate.of(2026, 10, 12), state.weekStart)
        assertEquals(2, state.dayLessons.size)
    }

    @Test
    fun `opens next day after the last pair`() {
        assertEquals(FRI, vm(clockAt("2026-10-15T12:00")).state.value.selectedDate)
    }

    @Test
    fun `week navigation inside the window does not fetch`() {
        val vm = vm()
        vm.shiftWeek(1)
        assertEquals(LocalDate.of(2026, 10, 22), vm.state.value.selectedDate)
        assertEquals(LocalDate.of(2026, 10, 19), vm.state.value.weekStart)
        assertTrue(source.fetchCalls.isEmpty())
    }

    @Test
    fun `week outside the window is loaded`() {
        val vm = vm()
        vm.shiftWeek(2)
        assertEquals(
            DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)),
            source.fetchCalls.single().range,
        )
    }

    @Test
    fun `offline refresh keeps lessons`() {
        source.error = NetworkException(IOException("down"))
        val vm = vm()
        vm.refresh()
        assertTrue(vm.state.value.offline)
        assertEquals(2, vm.state.value.dayLessons.size)
    }

    @Test
    fun `successful refresh notifies`() {
        var synced = 0
        val vm = vm(onSynced = { synced++ })
        vm.refresh()
        assertEquals(1, synced)
        assertEquals(false, vm.state.value.refreshing)
    }

    @Test
    fun `stale subscription refreshes on open`() = runTest {
        store.updateSubscription(store.subscriptions().first().copy(lastSyncedAt = null))
        vm()
        assertEquals(1, source.fetchCalls.size)
    }

    @Test
    fun `switching subscription shows its lessons`() {
        val vm = vm()
        vm.selectSubscription(teacherId)
        assertEquals("Демчина", vm.state.value.current?.name)
        assertEquals(listOf("Лекція"), vm.state.value.dayLessons.map { it.subject })
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleViewModelTest'`
Expected: FAIL — unresolved `ScheduleViewModel`.

- [ ] **Step 3: Implement ViewModel**

`ui/schedule/ScheduleViewModel.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.chooseStartDay
import io.github.kwebmn.rozklad.domain.syncWindow
import io.github.kwebmn.rozklad.domain.weekStart
import io.github.kwebmn.rozklad.ui.NETWORK_ERROR
import io.github.kwebmn.rozklad.ui.errorMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

data class ScheduleUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val current: Subscription? = null,
    val weekStart: LocalDate,
    val selectedDate: LocalDate,
    val weekLessons: List<Lesson> = emptyList(),
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
) {
    val dayLessons: List<Lesson> get() = weekLessons.filter { it.date == selectedDate }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(
    private val repo: ScheduleRepository,
    private val clock: Clock,
    private val onSynced: () -> Unit = {},
) : ViewModel() {
    private data class Status(val refreshing: Boolean = false, val offline: Boolean = false, val error: String? = null)

    private val today: LocalDate get() = LocalDate.now(clock)
    private val selectedSubId = MutableStateFlow<Long?>(null)
    private val selectedDate = MutableStateFlow(today)
    private val week = MutableStateFlow(weekStart(today))
    private val status = MutableStateFlow(Status())

    private val current: StateFlow<Subscription?> =
        combine(repo.subscriptions, selectedSubId) { subs, id ->
            subs.firstOrNull { it.id == id } ?: subs.firstOrNull { it.isPrimary } ?: subs.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val weekLessons =
        combine(current.map { it?.id }.distinctUntilChanged(), week) { id, w -> id to w }
            .flatMapLatest { (id, w) ->
                if (id == null) flowOf(emptyList()) else repo.lessons(id, DateRange(w, w.plusDays(6)))
            }

    private val view = combine(week, selectedDate, weekLessons) { w, d, l -> Triple(w, d, l) }

    val state: StateFlow<ScheduleUiState> =
        combine(repo.subscriptions, current, view, status) { subs, cur, (w, d, lessons), st ->
            ScheduleUiState(subs, cur, w, d, lessons, st.refreshing, st.offline, st.error)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ScheduleUiState(weekStart = week.value, selectedDate = selectedDate.value),
        )

    init {
        viewModelScope.launch {
            val sub = current.filterNotNull().first()
            val now = LocalDateTime.now(clock)
            showDate(chooseStartDay(now, repo.lessonsOnce(sub.id, syncWindow(now.toLocalDate()))))
            val last = sub.lastSyncedAt
            if (last == null || clock.millis() - last > STALE_MS) refresh()
        }
    }

    fun showDate(date: LocalDate) {
        selectedDate.value = date
        week.value = weekStart(date)
        loadIfOutsideWindow(date)
    }

    fun shiftDay(delta: Long) = showDate(selectedDate.value.plusDays(delta))

    fun shiftWeek(delta: Long) = showDate(selectedDate.value.plusWeeks(delta))

    fun selectSubscription(id: Long) {
        selectedSubId.value = id
    }

    fun refresh() {
        val sub = current.value ?: return
        if (status.value.refreshing) return
        status.value = status.value.copy(refreshing = true)
        viewModelScope.launch {
            status.value = runSync {
                repo.refresh(sub)
                val w = week.value
                if (w !in syncWindow(today)) repo.refresh(sub, DateRange(w, w.plusDays(6)))
                onSynced()
            }
        }
    }

    private fun loadIfOutsideWindow(date: LocalDate) {
        val sub = current.value ?: return
        if (date in syncWindow(today)) return
        val start = weekStart(date)
        viewModelScope.launch {
            status.value = runSync { repo.refresh(sub, DateRange(start, start.plusDays(6))) }
        }
    }

    private suspend fun runSync(block: suspend () -> Unit): Status = try {
        block()
        Status()
    } catch (e: Exception) {
        when (val message = errorMessage(e) ?: throw e) {
            NETWORK_ERROR -> Status(offline = true)
            else -> Status(error = message)
        }
    }

    private companion object {
        const val STALE_MS = 30 * 60 * 1000L
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*ScheduleViewModelTest'`
Expected: PASS (8 tests).

- [ ] **Step 5: Implement screen**

`ui/schedule/ScheduleScreen.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kwebmn.rozklad.domain.*
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel, clock: Clock, onAdd: () -> Unit, onSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val now by produceState(LocalDateTime.now(clock)) {
        while (true) {
            delay(30_000)
            value = LocalDateTime.now(clock)
        }
    }
    var details by remember { mutableStateOf<Lesson?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { SubscriptionPicker(state, vm::selectSubscription, onAdd) },
                actions = {
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Оновити") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Налаштування") }
                },
            )
        },
        bottomBar = {
            Text(
                formatSyncStatus(state.current?.lastSyncedAt, clock.millis(), state.offline, clock.zone),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(8.dp),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            WeekStrip(state, now.toLocalDate(), vm::showDate, vm::shiftWeek)
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var total = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total < -SWIPE_PX) vm.shiftDay(1) else if (total > SWIPE_PX) vm.shiftDay(-1)
                            },
                        ) { _, dx -> total += dx }
                    },
            ) {
                DayList(state, now, onLesson = { details = it })
            }
        }
    }
    details?.let { LessonSheet(it, onDismiss = { details = null }) }
}

private const val SWIPE_PX = 120f

@Composable
private fun SubscriptionPicker(state: ScheduleUiState, onSelect: (Long) -> Unit, onAdd: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(state.current?.name ?: "Розклад", style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Обрати розклад")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.subscriptions.forEach { sub ->
                DropdownMenuItem(
                    text = { Text(sub.name + if (sub.type == SubscriptionType.TEACHER) " (викладач)" else "") },
                    onClick = { open = false; onSelect(sub.id) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Додати…") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { open = false; onAdd() },
            )
        }
    }
}

@Composable
private fun WeekStrip(state: ScheduleUiState, today: LocalDate, onDate: (LocalDate) -> Unit, onWeek: (Long) -> Unit) {
    val sunday = state.weekStart.plusDays(6)
    val days = (0L..5L).map { state.weekStart.plusDays(it) } +
        listOfNotNull(sunday.takeIf { d -> state.weekLessons.any { it.date == d } })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        IconButton(onClick = { onWeek(-1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Попередній тиждень")
        }
        days.forEach { day ->
            val selected = day == state.selectedDate
            val hasLessons = state.weekLessons.any { it.date == day && !it.cancelled }
            val fg = when {
                selected -> MaterialTheme.colorScheme.onPrimary
                day == today -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onDate(day) }
                    .padding(vertical = 6.dp),
            ) {
                Text(dayShort(day.dayOfWeek), style = MaterialTheme.typography.labelMedium, color = fg)
                Text("${day.dayOfMonth}", style = MaterialTheme.typography.titleMedium, color = fg)
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (hasLessons) fg else Color.Transparent),
                )
            }
        }
        IconButton(onClick = { onWeek(1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Наступний тиждень")
        }
    }
}

@Composable
private fun DayList(state: ScheduleUiState, now: LocalDateTime, onLesson: (Lesson) -> Unit) {
    val rows = buildDayRows(state.dayLessons)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "${dayFull(state.selectedDate.dayOfWeek)}, ${shortDate(state.selectedDate)}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (rows.isEmpty()) {
            item {
                Text(
                    "Пар немає",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(rows, key = { it.pairNo }) { row ->
            when (row) {
                is DayRow.Window -> Text(
                    "${row.pairNo} · вікно",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 12.dp),
                )
                is DayRow.Lessons -> PairCard(row, now, state.current?.type, onLesson)
            }
        }
    }
}

@Composable
private fun PairCard(row: DayRow.Lessons, now: LocalDateTime, type: SubscriptionType?, onLesson: (Lesson) -> Unit) {
    val date = row.lessons.first().date
    val isCurrent = !now.isBefore(date.atTime(row.start)) && now.isBefore(date.atTime(row.end))
    Card(
        colors = if (isCurrent) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.width(56.dp)) {
                Text("${row.pairNo}", style = MaterialTheme.typography.titleLarge)
                Text(formatTime(row.start), style = MaterialTheme.typography.bodySmall)
                Text(formatTime(row.end), style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f)) {
                if (isCurrent) {
                    Text("● зараз", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                row.lessons.forEach { lesson ->
                    LessonBlock(lesson, type, Modifier.clickable { onLesson(lesson) })
                }
            }
        }
    }
}

@Composable
private fun LessonBlock(lesson: Lesson, type: SubscriptionType?, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        lesson.note?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Text(
            "${lesson.subject} (${lesson.kind})",
            style = MaterialTheme.typography.titleSmall.copy(
                textDecoration = if (lesson.cancelled) TextDecoration.LineThrough else null,
            ),
        )
        val who = if (type == SubscriptionType.TEACHER) lesson.stream else lesson.teacher?.let(::shortTeacher)
        val where = lesson.room?.let(::shortRoom) ?: if (lesson.remote) "дистанційно" else null
        val line = listOfNotNull(who, where).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LessonSheet(lesson: Lesson, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(lesson.subject, style = MaterialTheme.typography.titleLarge)
            Text(
                "${dayFull(lesson.date.dayOfWeek)} ${shortDate(lesson.date)}, ${lesson.pairNo} пара, " +
                    "${formatTime(lesson.start)}–${formatTime(lesson.end)}",
            )
            listOf(
                "Тип" to lesson.kind,
                "Викладач" to lesson.teacher,
                "Аудиторія" to (lesson.room ?: if (lesson.remote) "дистанційно" else null),
                "Групи" to lesson.stream,
                "Примітка" to lesson.note,
            ).forEach { (label, value) ->
                if (!value.isNullOrBlank()) Text("$label: $value", style = MaterialTheme.typography.bodyLarge)
            }
            lesson.links.forEach { link ->
                TextButton(onClick = { uriHandler.openUri(link) }) { Text(link) }
            }
        }
    }
}
```

- [ ] **Step 6: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat: schedule screen with week strip, swipe and details"
```

---

### Task 13: Самообновление из GitHub Releases

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/domain/SemVer.kt`, `data/remote/UpdateApi.kt`, `data/UpdateChecker.kt`, `ui/update/UpdateViewModel.kt`, `ui/update/UpdateDialog.kt`, `update/ApkInstaller.kt`, `app/src/main/res/xml/file_paths.xml`
- Modify: `AppContainer.kt` (поле `updateChecker`), `AndroidManifest.xml` (разрешение + FileProvider)
- Test: `app/src/test/java/io/github/kwebmn/rozklad/domain/SemVerTest.kt`, `data/remote/ReleaseParserTest.kt`, `data/UpdateCheckerTest.kt`, `ui/update/UpdateViewModelTest.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `AppSettings.lastUpdateCheck` (Task 10), `NetworkException` (Task 5), `FakeSettingsStore`, `MainDispatcherRule`.
- Produces:
  - `data class SemVer(major, minor, patch) : Comparable<SemVer>`, `SemVer.parse(String): SemVer?`, `fun isNewer(tag: String, current: String): Boolean`
  - `data class ReleaseInfo(version: String, notes: String, apkUrl: String)`, `object ReleaseParser { fun parse(json: String): ReleaseInfo? }`
  - `fun interface ReleaseSource { suspend fun latest(): ReleaseInfo? }`, `class UpdateApi(client: OkHttpClient, url: String = UpdateApi.LATEST_URL) : ReleaseSource`
  - `class UpdateChecker(source: ReleaseSource, settings: SettingsStore, currentVersion: String, clock: Clock) { suspend fun check(force: Boolean): ReleaseInfo? }`
  - `interface ApkDownloader { suspend fun download(release: ReleaseInfo): Boolean }`, `class ApkInstaller(context) : ApkDownloader { fun install() }`
  - `sealed interface UpdateState { Idle, Checking, UpToDate, Available(release), Downloading, Ready, Failed(message) }`
  - `class UpdateViewModel(checker: UpdateChecker, downloader: ApkDownloader)`: `state`, `check(force: Boolean)`, `download()`, `dismiss()`
  - `@Composable fun UpdateDialog(state: UpdateState, onUpdate: () -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit)`
  - `AppContainer.updateChecker: UpdateChecker`

- [ ] **Step 1: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/domain/SemVerTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    @Test
    fun parses() {
        assertEquals(SemVer(1, 2, 3), SemVer.parse("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), SemVer.parse("1.2.3"))
        assertNull(SemVer.parse("v1.2"))
        assertNull(SemVer.parse("nightly"))
    }

    @Test
    fun `compares numerically`() {
        assertTrue(isNewer("v1.10.0", "1.9.9"))
        assertTrue(isNewer("v0.2.0", "0.1.5"))
        assertFalse(isNewer("v1.0.0", "1.0.0"))
        assertFalse(isNewer("v0.9.0", "1.0.0"))
    }

    @Test
    fun `malformed tags are never newer`() {
        assertFalse(isNewer("nightly", "0.1.0"))
        assertFalse(isNewer("v2.0.0", "dev"))
    }
}
```

`app/src/test/java/io/github/kwebmn/rozklad/data/remote/ReleaseParserTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseParserTest {
    @Test
    fun `picks the apk asset`() {
        val json = """
            {"tag_name":"v0.2.0","body":"Що нового","draft":false,"extra":1,
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt"},
                       {"name":"rozklad-v0.2.0.apk","browser_download_url":"https://x/rozklad-v0.2.0.apk"}]}
        """.trimIndent()
        assertEquals(
            ReleaseInfo("v0.2.0", "Що нового", "https://x/rozklad-v0.2.0.apk"),
            ReleaseParser.parse(json),
        )
    }

    @Test
    fun `release without apk is ignored`() {
        assertNull(ReleaseParser.parse("""{"tag_name":"v0.2.0","body":null,"assets":[]}"""))
    }
}
```

`app/src/test/java/io/github/kwebmn/rozklad/data/UpdateCheckerTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import io.github.kwebmn.rozklad.data.remote.ReleaseSource
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FakeSettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private val newer = ReleaseInfo("v0.2.0", "notes", "https://x/a.apk")
    private var calls = 0
    private var release: ReleaseInfo? = newer
    private var error: Exception? = null
    private val source = ReleaseSource { calls++; error?.let { throw it }; release }
    private val now = FIXED_CLOCK.millis()

    private fun checker(settings: FakeSettingsStore) = UpdateChecker(source, settings, "0.1.0", FIXED_CLOCK)

    @Test
    fun `finds a newer release and records the check`() = runTest {
        val settings = FakeSettingsStore()
        assertEquals(newer, checker(settings).check(force = false))
        assertEquals(now, settings.flow.value.lastUpdateCheck)
    }

    @Test
    fun `automatic check runs at most daily`() = runTest {
        val settings = FakeSettingsStore(AppSettings(lastUpdateCheck = now - 60_000))
        assertNull(checker(settings).check(force = false))
        assertEquals(0, calls)
        assertEquals(newer, checker(settings).check(force = true))
        assertEquals(1, calls)
    }

    @Test
    fun `same or older version is not offered`() = runTest {
        release = newer.copy(version = "v0.1.0")
        assertNull(checker(FakeSettingsStore()).check(force = true))
    }

    @Test
    fun `network failure does not record the check`() {
        error = NetworkException(IOException("down"))
        val settings = FakeSettingsStore()
        assertThrows(NetworkException::class.java) { runBlocking { checker(settings).check(force = false) } }
        assertEquals(0L, settings.flow.value.lastUpdateCheck)
    }
}
```

`app/src/test/java/io/github/kwebmn/rozklad/ui/update/UpdateViewModelTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.update

import io.github.kwebmn.rozklad.data.UpdateChecker
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import io.github.kwebmn.rozklad.data.remote.ReleaseSource
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FakeSettingsStore
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import io.github.kwebmn.rozklad.update.ApkDownloader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val newer = ReleaseInfo("v0.2.0", "notes", "https://x/a.apk")
    private var release: ReleaseInfo? = newer
    private var error: Exception? = null
    private var downloadOk = true
    private val downloader = object : ApkDownloader {
        override suspend fun download(release: ReleaseInfo) = downloadOk
    }

    private fun vm() = UpdateViewModel(
        UpdateChecker(ReleaseSource { error?.let { throw it }; release }, FakeSettingsStore(), "0.1.0", FIXED_CLOCK),
        downloader,
    )

    @Test
    fun `offers newer release then downloads it`() {
        val vm = vm()
        vm.check(force = false)
        assertEquals(UpdateState.Available(newer), vm.state.value)
        vm.download()
        assertEquals(UpdateState.Ready, vm.state.value)
    }

    @Test
    fun `failed download is reported`() {
        downloadOk = false
        val vm = vm()
        vm.check(force = true)
        vm.download()
        assertTrue(vm.state.value is UpdateState.Failed)
    }

    @Test
    fun `manual check reports up to date`() {
        release = newer.copy(version = "v0.1.0")
        val vm = vm()
        vm.check(force = true)
        assertEquals(UpdateState.UpToDate, vm.state.value)
    }

    @Test
    fun `automatic check stays silent on errors`() {
        error = NetworkException(IOException("down"))
        val vm = vm()
        vm.check(force = false)
        assertEquals(UpdateState.Idle, vm.state.value)
        vm.check(force = true)
        assertTrue(vm.state.value is UpdateState.Failed)
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SemVerTest' --tests '*ReleaseParserTest' --tests '*UpdateCheckerTest' --tests '*UpdateViewModelTest'`
Expected: FAIL — unresolved `SemVer`, `ReleaseParser`, `UpdateChecker`, `UpdateViewModel`.

- [ ] **Step 3: Implement logic**

`domain/SemVer.kt`:
```kotlin
package io.github.kwebmn.rozklad.domain

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)$""")

        fun parse(text: String): SemVer? = PATTERN.find(text.trim())?.destructured?.let { (a, b, c) ->
            SemVer(a.toInt(), b.toInt(), c.toInt())
        }
    }
}

fun isNewer(tag: String, current: String): Boolean {
    val candidate = SemVer.parse(tag) ?: return false
    val installed = SemVer.parse(current) ?: return false
    return candidate > installed
}
```

`data/remote/UpdateApi.kt`:
```kotlin
package io.github.kwebmn.rozklad.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String)

fun interface ReleaseSource {
    suspend fun latest(): ReleaseInfo?
}

object ReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
    )

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tagName: String,
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    fun parse(text: String): ReleaseInfo? {
        val release = json.decodeFromString<GhRelease>(text)
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
        return ReleaseInfo(release.tagName, release.body.orEmpty(), apk.url)
    }
}

class UpdateApi(
    private val client: OkHttpClient,
    private val url: String = LATEST_URL,
) : ReleaseSource {
    override suspend fun latest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> null
                    !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                    else -> ReleaseParser.parse(response.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            throw NetworkException(e)
        } catch (e: IllegalArgumentException) {
            throw NetworkException(e)
        }
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/kwebmn/rozklad/releases/latest"
    }
}
```

`data/UpdateChecker.kt`:
```kotlin
package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import io.github.kwebmn.rozklad.data.remote.ReleaseSource
import io.github.kwebmn.rozklad.domain.isNewer
import kotlinx.coroutines.flow.first
import java.time.Clock

class UpdateChecker(
    private val source: ReleaseSource,
    private val settings: SettingsStore,
    private val currentVersion: String,
    private val clock: Clock,
) {
    /** Новый релиз или null. Без force — не чаще раза в сутки. Бросает NetworkException. */
    suspend fun check(force: Boolean): ReleaseInfo? {
        val last = settings.settings.first().lastUpdateCheck
        if (!force && clock.millis() - last < DAY_MS) return null
        val release = source.latest()
        settings.update { it.copy(lastUpdateCheck = clock.millis()) }
        return release?.takeIf { isNewer(it.version, currentVersion) }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
```

`update/ApkInstaller.kt`:
```kotlin
package io.github.kwebmn.rozklad.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import kotlinx.coroutines.delay
import java.io.File

interface ApkDownloader {
    suspend fun download(release: ReleaseInfo): Boolean
}

class ApkInstaller(private val context: Context) : ApkDownloader {
    override suspend fun download(release: ReleaseInfo): Boolean {
        val manager = context.getSystemService(DownloadManager::class.java)
        apkFile().delete()
        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Розклад ${release.version}")
            .setMimeType(MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, FILE_NAME)
        val id = manager.enqueue(request)
        while (true) {
            delay(1000)
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return false
                when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> return true
                    DownloadManager.STATUS_FAILED -> return false
                }
            }
        }
    }

    /** Открывает системный установщик; если установка из этого источника запрещена — экран разрешения. */
    fun install() {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apkFile())
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun apkFile() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FILE_NAME)

    private companion object {
        const val FILE_NAME = "rozklad-update.apk"
        const val MIME = "application/vnd.android.package-archive"
    }
}
```

`ui/update/UpdateViewModel.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwebmn.rozklad.data.UpdateChecker
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import io.github.kwebmn.rozklad.update.ApkDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data object Downloading : UpdateState
    data object Ready : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check(force: Boolean) {
        if (force) _state.value = UpdateState.Checking
        viewModelScope.launch {
            _state.value = try {
                val release = checker.check(force)
                when {
                    release != null -> UpdateState.Available(release)
                    force -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: NetworkException) {
                if (force) UpdateState.Failed("Не вдалося перевірити оновлення") else UpdateState.Idle
            }
        }
    }

    fun download() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        _state.value = UpdateState.Downloading
        viewModelScope.launch {
            _state.value = if (downloader.download(release)) {
                UpdateState.Ready
            } else {
                UpdateState.Failed("Не вдалося завантажити оновлення")
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SemVerTest' --tests '*ReleaseParserTest' --tests '*UpdateCheckerTest' --tests '*UpdateViewModelTest'`
Expected: PASS (13 tests).

- [ ] **Step 5: Dialog, wiring, manifest**

`ui/update/UpdateDialog.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.update

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun UpdateDialog(state: UpdateState, onUpdate: () -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        UpdateState.Idle, UpdateState.Checking -> Unit
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            text = { Text("У вас остання версія") },
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Доступна версія ${state.release.version}") },
            text = {
                Text(
                    state.release.notes.ifBlank { "Нова версія застосунку." },
                    Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = onUpdate) { Text("Оновити") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Пізніше") } },
        )
        UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Завантаження оновлення…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        UpdateState.Ready -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Оновлення завантажено") },
            confirmButton = { TextButton(onClick = onInstall) { Text("Встановити") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Пізніше") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
            text = { Text(state.message) },
        )
    }
}
```

`AppContainer.kt` — добавить импорты и поле в конец класса:
```kotlin
import io.github.kwebmn.rozklad.data.UpdateChecker
import io.github.kwebmn.rozklad.data.remote.UpdateApi
```
```kotlin
    val updateChecker = UpdateChecker(UpdateApi(http), settings, BuildConfig.VERSION_NAME, clock)
```

`app/src/main/res/xml/file_paths.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-files-path name="downloads" path="Download/" />
</paths>
```

`AndroidManifest.xml` — после `INTERNET`:
```xml
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```
и внутри `<application>` после `</activity>`:
```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 6: Compile**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat: self-update from GitHub releases"
```

---

### Task 14: Экран настроек и навигация в MainActivity

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`
- Modify (полная замена): `app/src/main/java/io/github/kwebmn/rozklad/MainActivity.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `ScheduleRepository`, `SettingsStore`, `AppSettings`, `ThemeMode`, все экраны и VM из Tasks 11–13, `AppContainer`.
- Produces:
  - `class SettingsViewModel(repo, settings: SettingsStore, onScheduleChanged: () -> Unit = {})`: `state: StateFlow<SettingsUiState>`, `setPrimary`, `delete`, `setRemindersEnabled`, `setReminderMinutes`, `setChangeNotifications`, `setTheme`
  - `data class SettingsUiState(subscriptions, settings)`
  - `@Composable fun SettingsScreen(vm, versionName: String, onBack, onAdd, onCheckUpdates)`
  - `MainActivity` с `enum class Screen { SCHEDULE, ADD, SETTINGS }`; параметры `onSynced`/`onScheduleChanged`/`onAdded`-эффект заполняются в Task 15.

- [ ] **Step 1: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/ui/settings/SettingsViewModelTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.settings

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.ThemeMode
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.FakeSettingsStore
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val store = FakeLessonStore()
    private val settings = FakeSettingsStore()
    private var changed = 0
    private val vm = SettingsViewModel(
        ScheduleRepository(FakeScheduleSource(), store, FIXED_CLOCK),
        settings,
        onScheduleChanged = { changed++ },
    )

    @Test
    fun `reminder minutes are saved and reschedule`() {
        vm.setReminderMinutes(15)
        assertEquals(15, settings.flow.value.reminderMinutes)
        assertEquals(15, vm.state.value.settings.reminderMinutes)
        assertEquals(1, changed)
    }

    @Test
    fun `disabling reminders reschedules`() {
        vm.setRemindersEnabled(false)
        assertEquals(false, settings.flow.value.remindersEnabled)
        assertEquals(1, changed)
    }

    @Test
    fun `theme and change notifications do not reschedule`() {
        vm.setTheme(ThemeMode.DARK)
        vm.setChangeNotifications(false)
        assertEquals(ThemeMode.DARK, settings.flow.value.theme)
        assertEquals(false, settings.flow.value.changeNotifications)
        assertEquals(0, changed)
    }

    @Test
    fun `deleting a subscription updates the list`() = runTest {
        val a = store.insertSubscription(Subscription(type = SubscriptionType.GROUP, name = "A", siteId = "1", isPrimary = true))
        store.insertSubscription(Subscription(type = SubscriptionType.GROUP, name = "B", siteId = "2"))
        vm.delete(a)
        assertEquals(listOf("B"), vm.state.value.subscriptions.map { it.name })
        assertEquals(true, vm.state.value.subscriptions.single().isPrimary)
        assertEquals(1, changed)
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsViewModelTest'`
Expected: FAIL — unresolved `SettingsViewModel`.

- [ ] **Step 3: Implement ViewModel**

`ui/settings/SettingsViewModel.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwebmn.rozklad.data.AppSettings
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.SettingsStore
import io.github.kwebmn.rozklad.data.ThemeMode
import io.github.kwebmn.rozklad.domain.Subscription
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

class SettingsViewModel(
    private val repo: ScheduleRepository,
    private val settings: SettingsStore,
    private val onScheduleChanged: () -> Unit = {},
) : ViewModel() {
    val state: StateFlow<SettingsUiState> =
        combine(repo.subscriptions, settings.settings, ::SettingsUiState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setPrimary(id: Long) = viewModelScope.launch {
        repo.setPrimary(id)
        onScheduleChanged()
    }

    fun delete(id: Long) = viewModelScope.launch {
        repo.deleteSubscription(id)
        onScheduleChanged()
    }

    fun setRemindersEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.update { it.copy(remindersEnabled = enabled) }
        onScheduleChanged()
    }

    fun setReminderMinutes(minutes: Int) = viewModelScope.launch {
        settings.update { it.copy(reminderMinutes = minutes) }
        onScheduleChanged()
    }

    fun setChangeNotifications(enabled: Boolean) = viewModelScope.launch {
        settings.update { it.copy(changeNotifications = enabled) }
    }

    fun setTheme(theme: ThemeMode) = viewModelScope.launch {
        settings.update { it.copy(theme = theme) }
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*SettingsViewModelTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Screen and MainActivity**

`ui/settings/SettingsScreen.kt`:
```kotlin
package io.github.kwebmn.rozklad.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kwebmn.rozklad.data.ThemeMode
import io.github.kwebmn.rozklad.domain.SubscriptionType

private val REMINDER_OPTIONS = listOf(5, 10, 15, 30)
private val THEMES = listOf(ThemeMode.SYSTEM to "Системна", ThemeMode.LIGHT to "Світла", ThemeMode.DARK to "Темна")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    versionName: String,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Налаштування") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item { SectionTitle("Мої розклади") }
            items(state.subscriptions, key = { it.id }) { sub ->
                ListItem(
                    headlineContent = { Text(sub.name) },
                    supportingContent = {
                        Text(
                            (if (sub.type == SubscriptionType.GROUP) "Група" else "Викладач") +
                                if (sub.isPrimary) " · основний (віджет і сповіщення)" else "",
                        )
                    },
                    leadingContent = {
                        IconButton(onClick = { vm.setPrimary(sub.id) }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = "Зробити основним",
                                tint = if (sub.isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { vm.delete(sub.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Видалити")
                        }
                    },
                )
            }
            item {
                TextButton(onClick = onAdd, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Додати розклад")
                }
            }

            item { SectionTitle("Сповіщення") }
            item { SwitchRow("Нагадування перед парою", s.remindersEnabled, vm::setRemindersEnabled) }
            if (s.remindersEnabled) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        REMINDER_OPTIONS.forEach { minutes ->
                            FilterChip(
                                selected = s.reminderMinutes == minutes,
                                onClick = { vm.setReminderMinutes(minutes) },
                                label = { Text("За $minutes хв") },
                            )
                        }
                    }
                }
            }
            item { SwitchRow("Сповіщати про зміни в розкладі", s.changeNotifications, vm::setChangeNotifications) }

            item { SectionTitle("Тема") }
            item {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    THEMES.forEach { (mode, title) ->
                        FilterChip(selected = s.theme == mode, onClick = { vm.setTheme(mode) }, label = { Text(title) })
                    }
                }
            }

            item { SectionTitle("Про застосунок") }
            item {
                ListItem(
                    headlineContent = { Text("Версія $versionName") },
                    trailingContent = { TextButton(onClick = onCheckUpdates) { Text("Перевірити оновлення") } },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}
```

`MainActivity.kt` (полная замена):
```kotlin
package io.github.kwebmn.rozklad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kwebmn.rozklad.data.AppSettings
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.ui.add.AddScreen
import io.github.kwebmn.rozklad.ui.add.AddViewModel
import io.github.kwebmn.rozklad.ui.schedule.ScheduleScreen
import io.github.kwebmn.rozklad.ui.schedule.ScheduleViewModel
import io.github.kwebmn.rozklad.ui.settings.SettingsScreen
import io.github.kwebmn.rozklad.ui.settings.SettingsViewModel
import io.github.kwebmn.rozklad.ui.theme.RozkladTheme
import io.github.kwebmn.rozklad.ui.update.UpdateDialog
import io.github.kwebmn.rozklad.ui.update.UpdateViewModel
import io.github.kwebmn.rozklad.update.ApkInstaller

private enum class Screen { SCHEDULE, ADD, SETTINGS }

class MainActivity : ComponentActivity() {
    /** Пересчитать напоминания/виджет после изменений расписания или настроек (наполняется в Task 15). */
    private fun onScheduleChanged() = Unit

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = container
        val installer = ApkInstaller(applicationContext)
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val subscriptions by c.repository.subscriptions.collectAsStateWithLifecycle(initialValue = null)
            val scheduleVm: ScheduleViewModel = viewModel(factory = viewModelFactory {
                initializer { ScheduleViewModel(c.repository, c.clock, onSynced = ::onScheduleChanged) }
            })
            val addVm: AddViewModel = viewModel(factory = viewModelFactory {
                initializer { AddViewModel(c.repository) }
            })
            val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory {
                initializer { SettingsViewModel(c.repository, c.settings, onScheduleChanged = ::onScheduleChanged) }
            })
            val updateVm: UpdateViewModel = viewModel(factory = viewModelFactory {
                initializer { UpdateViewModel(c.updateChecker, installer) }
            })
            val updateState by updateVm.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { updateVm.check(force = false) }

            RozkladTheme(settings.theme) {
                Surface(Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf(Screen.SCHEDULE) }
                    val subs = subscriptions
                    BackHandler(enabled = screen != Screen.SCHEDULE && !subs.isNullOrEmpty()) {
                        screen = Screen.SCHEDULE
                    }
                    val onAdded = { sub: Subscription ->
                        scheduleVm.selectSubscription(sub.id)
                        onScheduleChanged()
                        screen = Screen.SCHEDULE
                    }
                    when {
                        subs == null -> Unit
                        subs.isEmpty() || screen == Screen.ADD -> AddScreen(
                            vm = addVm,
                            onAdded = onAdded,
                            onBack = if (subs.isEmpty()) null else ({ screen = Screen.SCHEDULE }),
                        )
                        screen == Screen.SETTINGS -> SettingsScreen(
                            vm = settingsVm,
                            versionName = BuildConfig.VERSION_NAME,
                            onBack = { screen = Screen.SCHEDULE },
                            onAdd = { screen = Screen.ADD },
                            onCheckUpdates = { updateVm.check(force = true) },
                        )
                        else -> ScheduleScreen(
                            vm = scheduleVm,
                            clock = c.clock,
                            onAdd = { screen = Screen.ADD },
                            onSettings = { screen = Screen.SETTINGS },
                        )
                    }
                    UpdateDialog(
                        state = updateState,
                        onUpdate = updateVm::download,
                        onInstall = installer::install,
                        onDismiss = updateVm::dismiss,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 6: Full test run and build**

Run: `./gradlew :app:testDebugUnitTest assembleDebug`
Expected: все тесты PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat: settings screen and app navigation"
```

---

### Task 15: Напоминания перед парой

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/work/Notifier.kt`, `work/ReminderScheduler.kt`, `work/ReminderReceiver.kt`, `work/BootReceiver.kt`, `work/ScheduleEffects.kt`, `app/src/main/res/drawable/ic_notification.xml`
- Modify: `RozkladApp.kt`, `MainActivity.kt`, `AndroidManifest.xml`

**Interfaces:**
- Consumes: `upcomingReminders`, `reminderId`, `MAX_PAIR`, `Reminder` (Task 7), `ScheduleRepository.primary/lessonsOnce` (Task 9), `AppSettings` (Task 10), `container`.
- Produces:
  - `object Notifier { CHANNEL_CHANGES; CHANNEL_REMINDERS; createChannels(context); canNotify(context); showReminder(context, id, title, text); openAppIntent(context) }`
  - `object ReminderScheduler { suspend fun reschedule(context: Context) }`
  - `object ScheduleEffects { suspend fun apply(context: Context) }` — единая точка «расписание/настройки изменились» (Task 16 добавит обновление виджета).

Логика расчёта уже покрыта `RemindersTest` (Task 7); здесь — Android-обвязка, проверяется сборкой и ручной проверкой (Task 18).

- [ ] **Step 1: Notification icon**

`app/src/main/res/drawable/ic_notification.xml`:
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M17,12h-5v5h5v-5zM16,1v2L8,3L8,1L6,1v2L5,3c-1.11,0 -1.99,0.9 -1.99,2L3,19c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2L21,5c0,-1.1 -0.9,-2 -2,-2h-1L18,1h-2zM19,19L5,19L5,8h14v11z" />
</vector>
```

- [ ] **Step 2: Notifier**

`work/Notifier.kt`:
```kotlin
package io.github.kwebmn.rozklad.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.kwebmn.rozklad.MainActivity
import io.github.kwebmn.rozklad.R

object Notifier {
    const val CHANNEL_CHANGES = "changes"
    const val CHANNEL_REMINDERS = "reminders"

    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_CHANGES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Зміни розкладу")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_REMINDERS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Нагадування про пари")
                    .build(),
            ),
        )
    }

    fun canNotify(context: Context): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @SuppressLint("MissingPermission")
    fun showReminder(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
```

- [ ] **Step 3: Scheduler, receivers, effects**

`work/ReminderScheduler.kt`:
```kotlin
package io.github.kwebmn.rozklad.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.kwebmn.rozklad.container
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.MAX_PAIR
import io.github.kwebmn.rozklad.domain.Reminder
import io.github.kwebmn.rozklad.domain.reminderId
import io.github.kwebmn.rozklad.domain.upcomingReminders
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

object ReminderScheduler {
    suspend fun reschedule(context: Context) {
        val c = context.container
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now(c.clock)
        val today = now.toLocalDate()

        for (dayOffset in -1L..1L) {
            for (pair in 1..MAX_PAIR) cancel(context, alarms, reminderId(today.plusDays(dayOffset), pair))
        }

        val settings = c.settings.settings.first()
        if (!settings.remindersEnabled) return
        val primary = c.repository.primary() ?: return
        val lessons = c.repository.lessonsOnce(primary.id, DateRange(today, today.plusDays(1)))
        upcomingReminders(lessons, now, settings.reminderMinutes).forEach { reminder ->
            val at = reminder.triggerAt.atZone(c.clock.zone).toInstant().toEpochMilli()
            val intent = pendingIntent(context, reminder)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }
    }

    private fun pendingIntent(context: Context, reminder: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        reminder.id,
        Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            .putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            .putExtra(ReminderReceiver.EXTRA_TEXT, reminder.text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(context: Context, alarms: AlarmManager, id: Int) {
        PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }
}
```

`work/ReminderReceiver.kt`:
```kotlin
package io.github.kwebmn.rozklad.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.showReminder(
            context,
            intent.getIntExtra(EXTRA_ID, 0),
            intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            intent.getStringExtra(EXTRA_TEXT).orEmpty(),
        )
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}
```

`work/BootReceiver.kt`:
```kotlin
package io.github.kwebmn.rozklad.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kwebmn.rozklad.container
import kotlinx.coroutines.launch

/** BOOT_COMPLETED, обновление приложения, смена времени/пояса — будильники нужно поставить заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        app.container.appScope.launch {
            try {
                ScheduleEffects.apply(app)
            } finally {
                pending.finish()
            }
        }
    }
}
```

`work/ScheduleEffects.kt`:
```kotlin
package io.github.kwebmn.rozklad.work

import android.content.Context

/** Всё, что надо пересчитать после изменения расписания или настроек. */
object ScheduleEffects {
    suspend fun apply(context: Context) {
        ReminderScheduler.reschedule(context)
    }
}
```

- [ ] **Step 4: Wiring**

`RozkladApp.kt` — в `onCreate` после создания контейнера:
```kotlin
        Notifier.createChannels(this)
```
и импорт `import io.github.kwebmn.rozklad.work.Notifier`.

`MainActivity.kt`:
1. Добавить импорты:
```kotlin
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.kwebmn.rozklad.work.ScheduleEffects
import kotlinx.coroutines.launch
```
2. Заменить заглушку
```kotlin
    /** Пересчитать напоминания/виджет после изменений расписания или настроек (наполняется в Task 15). */
    private fun onScheduleChanged() = Unit
```
на
```kotlin
    /** Пересчитать напоминания/виджет после изменений расписания или настроек. */
    private fun onScheduleChanged() {
        container.appScope.launch { ScheduleEffects.apply(applicationContext) }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onScheduleChanged() }
```
3. В `onCreate` сразу после `super.onCreate(savedInstanceState)`:
```kotlin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch { ScheduleEffects.apply(applicationContext) }
```

`AndroidManifest.xml` — разрешения после `REQUEST_INSTALL_PACKAGES`:
```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```
receivers внутри `<application>` перед `<provider`:
```xml
        <receiver
            android:name=".work.ReminderReceiver"
            android:exported="false" />

        <receiver
            android:name=".work.BootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
                <action android:name="android.intent.action.TIME_SET" />
                <action android:name="android.intent.action.TIMEZONE_CHANGED" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 5: Build**

Run: `./gradlew :app:testDebugUnitTest assembleDebug`
Expected: все тесты PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src
git commit -m "feat: reminders before classes"
```

---

### Task 16: Виджет на рабочий стол

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/widget/WidgetData.kt`, `widget/RozkladWidget.kt`, `app/src/main/res/xml/widget_info.xml`
- Modify: `work/ScheduleEffects.kt`, `AndroidManifest.xml`, `res/values/strings.xml`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/widget/WidgetDataTest.kt`

**Interfaces:**
- Consumes: `chooseStartDay`, `currentOrNext`, `dayFull`, `shortDate`, `formatTime`, `shortRoom`, `lessonOrder`, `ScheduleRepository.primary/lessonsOnce`, `container`.
- Produces:
  - `data class WidgetRow(time, subject, room, highlight: Boolean, cancelled: Boolean)`, `data class WidgetData(title, rows, empty: String?)`
  - `fun buildWidgetData(subscriptionName: String?, lessons: List<Lesson>, now: LocalDateTime): WidgetData`
  - `suspend fun loadWidgetData(context: Context): WidgetData`
  - `class RozkladWidget : GlanceAppWidget`, `class RozkladWidgetReceiver : GlanceAppWidgetReceiver`

- [ ] **Step 1: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/widget/WidgetDataTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.widget

import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class WidgetDataTest {
    private fun at(time: String) = LocalDateTime.parse("2026-10-15T$time")
    private val lessons = listOf(
        lesson(THU, 2, "Менеджмент", room = "к.1ауд.330/5к"),
        lesson(THU, 1, "Prompt"),
        lesson(FRI, 1, "Іноземна", room = null, remote = true),
    )

    @Test
    fun `today with the current pair highlighted`() {
        val data = buildWidgetData("ІПЗс-24-1", lessons, at("10:30"))
        assertEquals("ІПЗс-24-1 · Четвер", data.title)
        assertEquals(listOf("08:30", "10:00"), data.rows.map { it.time })
        assertEquals(listOf(false, true), data.rows.map { it.highlight })
        assertEquals("ауд. 330/5к", data.rows[1].room)
        assertNull(data.empty)
    }

    @Test
    fun `switches to the next day after the last pair`() {
        val data = buildWidgetData("ІПЗс-24-1", lessons, at("12:00"))
        assertEquals("ІПЗс-24-1 · П'ятниця 16.10", data.title)
        assertEquals("дист.", data.rows.single().room)
        assertEquals(true, data.rows.single().highlight)
    }

    @Test
    fun `no subscription asks to open the app`() {
        val data = buildWidgetData(null, emptyList(), at("10:00"))
        assertEquals("Розклад", data.title)
        assertEquals("Відкрийте застосунок, щоб обрати групу", data.empty)
    }

    @Test
    fun `no lessons says so`() {
        assertEquals("Пар немає", buildWidgetData("ІПЗс-24-1", emptyList(), at("10:00")).empty)
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*WidgetDataTest'`
Expected: FAIL — unresolved `buildWidgetData`.

- [ ] **Step 3: Implement data**

`widget/WidgetData.kt`:
```kotlin
package io.github.kwebmn.rozklad.widget

import android.content.Context
import io.github.kwebmn.rozklad.container
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.chooseStartDay
import io.github.kwebmn.rozklad.domain.currentOrNext
import io.github.kwebmn.rozklad.domain.dayFull
import io.github.kwebmn.rozklad.domain.formatTime
import io.github.kwebmn.rozklad.domain.lessonOrder
import io.github.kwebmn.rozklad.domain.shortDate
import io.github.kwebmn.rozklad.domain.shortRoom
import java.time.LocalDateTime

data class WidgetRow(
    val time: String,
    val subject: String,
    val room: String,
    val highlight: Boolean,
    val cancelled: Boolean,
)

data class WidgetData(val title: String, val rows: List<WidgetRow>, val empty: String?)

fun buildWidgetData(subscriptionName: String?, lessons: List<Lesson>, now: LocalDateTime): WidgetData {
    if (subscriptionName == null) {
        return WidgetData("Розклад", emptyList(), "Відкрийте застосунок, щоб обрати групу")
    }
    val day = chooseStartDay(now, lessons)
    val dayLessons = lessons.filter { it.date == day }.sortedWith(lessonOrder)
    val next = currentOrNext(now, dayLessons)
    val title = buildString {
        append(subscriptionName).append(" · ").append(dayFull(day.dayOfWeek))
        if (day != now.toLocalDate()) append(" ").append(shortDate(day))
    }
    val rows = dayLessons.map { l ->
        WidgetRow(
            time = formatTime(l.start),
            subject = l.subject,
            room = l.room?.let(::shortRoom) ?: if (l.remote) "дист." else "",
            highlight = l == next,
            cancelled = l.cancelled,
        )
    }
    return WidgetData(title, rows, if (rows.isEmpty()) "Пар немає" else null)
}

suspend fun loadWidgetData(context: Context): WidgetData {
    val c = context.container
    val now = LocalDateTime.now(c.clock)
    val sub = c.repository.primary()
    val lessons = sub?.let {
        c.repository.lessonsOnce(it.id, DateRange(now.toLocalDate(), now.toLocalDate().plusDays(7)))
    }.orEmpty()
    return buildWidgetData(sub?.name, lessons, now)
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests '*WidgetDataTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Glance widget, receiver, manifest, effects**

`widget/RozkladWidget.kt`:
```kotlin
package io.github.kwebmn.rozklad.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import io.github.kwebmn.rozklad.MainActivity

class RozkladWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent { GlanceTheme { WidgetContent(data) } }
    }
}

class RozkladWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RozkladWidget()
}

@Composable
private fun WidgetContent(data: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            data.title,
            style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = GlanceTheme.colors.onSurface),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(6.dp))
        data.empty?.let {
            Text(it, style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurfaceVariant))
        }
        data.rows.forEach { WidgetRowView(it) }
    }
}

@Composable
private fun WidgetRowView(row: WidgetRow) {
    val background = if (row.highlight) GlanceTheme.colors.primaryContainer else GlanceTheme.colors.widgetBackground
    val color = if (row.highlight) GlanceTheme.colors.onPrimaryContainer else GlanceTheme.colors.onSurface
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .background(background)
            .cornerRadius(8.dp)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(
            row.time,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color),
            modifier = GlanceModifier.width(44.dp),
        )
        Text(
            row.subject,
            style = TextStyle(
                fontSize = 13.sp,
                color = color,
                textDecoration = if (row.cancelled) TextDecoration.LineThrough else TextDecoration.None,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(row.room, style = TextStyle(fontSize = 12.sp, color = color), maxLines = 1)
    }
}
```

`app/src/main/res/xml/widget_info.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_description"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:minWidth="250dp"
    android:minHeight="110dp"
    android:resizeMode="horizontal|vertical"
    android:targetCellWidth="4"
    android:targetCellHeight="2"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen" />
```

`res/values/strings.xml` — добавить:
```xml
    <string name="widget_name">Розклад</string>
    <string name="widget_description">Пари на сьогодні для основного розкладу</string>
```

`AndroidManifest.xml` — receiver внутри `<application>` перед `<provider`:
```xml
        <receiver
            android:name=".widget.RozkladWidgetReceiver"
            android:exported="true"
            android:label="@string/widget_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/widget_info" />
        </receiver>
```

`work/ScheduleEffects.kt` — полная замена:
```kotlin
package io.github.kwebmn.rozklad.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import io.github.kwebmn.rozklad.widget.RozkladWidget

/** Всё, что надо пересчитать после изменения расписания или настроек. */
object ScheduleEffects {
    suspend fun apply(context: Context) {
        ReminderScheduler.reschedule(context)
        RozkladWidget().updateAll(context)
    }
}
```

- [ ] **Step 6: Build**

Run: `./gradlew :app:testDebugUnitTest assembleDebug`
Expected: все тесты PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src
git commit -m "feat: home screen widget"
```

---

### Task 17: Фоновая синхронизация и уведомления об изменениях

**Files:**
- Create: `app/src/main/java/io/github/kwebmn/rozklad/work/SyncRunner.kt`, `work/SyncWorker.kt`
- Modify: `work/Notifier.kt` (`showChanges`), `RozkladApp.kt`
- Test: `app/src/test/java/io/github/kwebmn/rozklad/work/SyncRunnerTest.kt`

**Interfaces:**
- Consumes: `ScheduleRepository.subscriptionsOnce/refresh`, `SettingsStore`, `changeSummary`, `ScheduleEffects`, `Notifier`.
- Produces:
  - `class SyncRunner(repo, settings, notify: (Subscription, List<LessonChange>) -> Unit) { suspend fun run(): Boolean }` — `true`, если была сетевая ошибка (нужен retry)
  - `class SyncWorker : CoroutineWorker` + `SyncWorker.schedule(context)`
  - `Notifier.showChanges(context, subscription, changes)`

- [ ] **Step 1: Failing tests**

`app/src/test/java/io/github/kwebmn/rozklad/work/SyncRunnerTest.kt`:
```kotlin
package io.github.kwebmn.rozklad.work

import io.github.kwebmn.rozklad.data.AppSettings
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.SiteFormatException
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.FakeSettingsStore
import io.github.kwebmn.rozklad.fakes.lesson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class SyncRunnerTest {
    private val source = FakeScheduleSource()
    private val store = FakeLessonStore()
    private val settings = FakeSettingsStore()
    private val notified = mutableListOf<Pair<String, List<LessonChange>>>()
    private val runner = SyncRunner(ScheduleRepository(source, store, FIXED_CLOCK), settings) { sub, changes ->
        notified += sub.name to changes
    }
    private val window = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25))

    @Before
    fun seed() = runTest {
        val synced = FIXED_CLOCK.millis() - 3_600_000
        val primary = store.insertSubscription(
            Subscription(type = SubscriptionType.GROUP, name = "Основна", siteId = "1", isPrimary = true, lastSyncedAt = synced),
        )
        val other = store.insertSubscription(
            Subscription(type = SubscriptionType.GROUP, name = "Інша", siteId = "2", lastSyncedAt = synced),
        )
        store.replaceLessons(primary, window, listOf(lesson(FRI, 2)))
        store.replaceLessons(other, window, listOf(lesson(FRI, 2)))
        source.schedule = source.schedule.copy(lessons = listOf(lesson(FRI, 2, room = "к.1ауд.503")))
    }

    @Test
    fun `notifies only for the primary subscription`() = runTest {
        assertFalse(runner.run())
        assertEquals(listOf("Основна"), notified.map { it.first })
        assertEquals(1, notified.single().second.size)
        assertEquals(2, source.fetchCalls.size)
    }

    @Test
    fun `respects the notification setting`() = runTest {
        settings.flow.value = AppSettings(changeNotifications = false)
        runner.run()
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `network failure asks for retry`() = runTest {
        source.error = NetworkException(IOException("down"))
        assertTrue(runner.run())
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `format error does not ask for retry`() = runTest {
        source.error = SiteFormatException("broken")
        assertFalse(runner.run())
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*SyncRunnerTest'`
Expected: FAIL — unresolved `SyncRunner`.

- [ ] **Step 3: Implement**

`work/SyncRunner.kt`:
```kotlin
package io.github.kwebmn.rozklad.work

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.SettingsStore
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.SiteFormatException
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import kotlinx.coroutines.flow.first

class SyncRunner(
    private val repo: ScheduleRepository,
    private val settings: SettingsStore,
    private val notify: (Subscription, List<LessonChange>) -> Unit,
) {
    /** Обновляет все подписки. @return true, если хоть одна упала из-за сети и нужен retry. */
    suspend fun run(): Boolean {
        val notifyChanges = settings.settings.first().changeNotifications
        var networkFailed = false
        for (sub in repo.subscriptionsOnce()) {
            try {
                val changes = repo.refresh(sub)
                if (sub.isPrimary && notifyChanges && changes.isNotEmpty()) notify(sub, changes)
            } catch (e: NetworkException) {
                networkFailed = true
            } catch (e: SiteFormatException) {
                // Кэш не тронут; следующий периодический запуск попробует снова.
            }
        }
        return networkFailed
    }
}
```

`work/SyncWorker.kt`:
```kotlin
package io.github.kwebmn.rozklad.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.kwebmn.rozklad.container
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val networkFailed = SyncRunner(c.repository, c.settings) { sub, changes ->
            Notifier.showChanges(applicationContext, sub, changes)
        }.run()
        ScheduleEffects.apply(applicationContext)
        return if (networkFailed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "schedule-sync"
        private const val MAX_RETRIES = 3

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
```

`work/Notifier.kt` — добавить импорты и метод:
```kotlin
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.changeSummary
```
```kotlin
    private const val CHANGES_NOTIFICATION_ID = 1

    @SuppressLint("MissingPermission")
    fun showChanges(context: Context, subscription: Subscription, changes: List<LessonChange>) {
        if (!canNotify(context) || changes.isEmpty()) return
        val lines = changeSummary(changes)
        val style = NotificationCompat.InboxStyle()
        lines.forEach(style::addLine)
        val notification = NotificationCompat.Builder(context, CHANNEL_CHANGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Розклад змінився: ${subscription.name}")
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(CHANGES_NOTIFICATION_ID, notification)
    }
```

`RozkladApp.kt` — в `onCreate` после `Notifier.createChannels(this)`:
```kotlin
        SyncWorker.schedule(this)
```
и импорт `import io.github.kwebmn.rozklad.work.SyncWorker`.

- [ ] **Step 4: Run, verify pass and build**

Run: `./gradlew :app:testDebugUnitTest assembleDebug`
Expected: все тесты PASS (включая 4 новых), `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -m "feat: periodic sync with change notifications"
```

---

### Task 18: Финальная проверка, push и первый релиз

**Files:** нет новых.

Шаги 3–6 — действия во внешнем мире (GitHub, телефон). **Перед каждым — спросить пользователя**; ключ подписи пользователь создаёт и хранит сам.

- [ ] **Step 1: Полный прогон**

Run: `./gradlew clean :app:testDebugUnitTest assembleDebug assembleRelease`
Expected: все тесты PASS; `app-debug.apk` и `app-release-unsigned.apk` собраны.

- [ ] **Step 2: Проверка live-сайта (smoke, не тест)**

Run:
```bash
curl -s -m 20 'http://195.162.83.28/cgi-bin/timetable.cgi?n=700&group=-607&sdate=13.10.2026&edate=19.10.2026' | iconv -f cp1251 -t utf-8 | grep -c 'class="p_name"'
```
Expected: число > 0 (разметка сайта не изменилась с момента снятия фикстур).

- [ ] **Step 3: Push в GitHub (с согласия пользователя)**

```bash
git push -u origin main
```
Expected: workflow «CI» зелёный в Actions; артефакт `rozklad-debug` содержит APK.

- [ ] **Step 4: Секреты подписи (делает пользователь)**

По README: `keytool …`, `base64 -w0 …`, добавить 4 секрета в Settings → Secrets and variables → Actions.

- [ ] **Step 5: Первый релиз (с согласия пользователя)**

```bash
git tag v0.1.0 && git push origin v0.1.0
```
Expected: workflow «Release» зелёный, в Releases есть `rozklad-v0.1.0.apk`.

- [ ] **Step 6: Ручная проверка на телефоне (пользователь)**

1. Установить `rozklad-v0.1.0.apk`, разрешить уведомления.
2. Добавить группу через поиск → открывается расписание на сегодня/следующий учебный день.
3. Свайп влево/вправо меняет день, стрелки — неделю; тап по паре — детали; ссылка Meet открывается.
4. Включить авиарежим, pull-to-refresh → «Офлайн · дані від …», расписание на месте.
5. Добавить виджет «Розклад» на рабочий стол — видны пары, текущая подсвечена.
6. Настройки → напоминание «за 30 хв» → уведомление приходит за 30 минут до ближайшей пары.
7. Выпустить `v0.1.1` (тег) → в приложении «Налаштування → Перевірити оновлення» → «Оновити» → «Встановити» → версия 0.1.1.
