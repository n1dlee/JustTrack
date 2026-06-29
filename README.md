# JustTrack

Android-приложение для управления телефоном жестами через фронтальную камеру. Использует MediaPipe Hand Landmarker для распознавания жестов рук в реальном времени.

## Возможности

- **Управление музыкой** жестами: play/pause, следующий/предыдущий трек
- **Управление громкостью** — зажми пальцы и двигай рукой вверх/вниз
- **Работа в фоне** — foreground service с уведомлением, работает при свёрнутом приложении
- **Система активации** — покажи открытую ладонь 1.5 секунды для включения жестов (защита от ложных срабатываний)
- **Haptic feedback** — вибрация при распознании жеста
- **Настройки** — чувствительность захвата и движения, вкл/выкл вибрации
- **Визуальный overlay** — отрисовка 21 точки руки поверх превью камеры

## Устройство

- Samsung Galaxy S24 Ultra (SM-S938B)
- Android 14+ (compileSdk 34, minSdk 26)

## Жесты

| Жест | Действие |
|------|----------|
| **Открытая ладонь 1.5с** | Активация системы |
| **Тап** (быстрый pinch) | Play / Pause |
| **Двойной тап** | Следующий трек |
| **Тройной тап** | Предыдущий трек |
| **Зажми + двигай вверх** | Громкость + |
| **Зажми + двигай вниз** | Громкость − |
| *10с без жестов* | Автоматическая деактивация |

## Как пользоваться

1. Установи APK на телефон
2. Открой приложение, разреши камеру и уведомления
3. Нажми **"Запустить JustTrack"**
4. Покажи **открытую ладонь** перед камерой и **держи 1.5 секунды** — увидишь прогресс-круг, потом двойная вибрация = активировано
5. Используй жесты для управления музыкой и громкостью
6. Можешь свернуть приложение — жесты продолжают работать в фоне

### Важно для Samsung

Отключи оптимизацию батареи для JustTrack:

**Настройки → Приложения → JustTrack → Батарея → Без ограничений**

Иначе Samsung может убить фоновый сервис.

## Архитектура

```
app/src/main/java/com/justtrack/app/
├── MainActivity.kt          — UI, превью камеры, делегирует в HandTrackingEngine
├── GestureService.kt        — LifecycleService, фоновый трекинг через HandTrackingEngine
├── HandTrackingEngine.kt    — MediaPipe инференс + обработка кадров (убирает дубликацию)
├── PinchVolumeGesture.kt    — State machine распознавания жестов
├── VolumeController.kt      — AudioManager + MediaKey dispatch + haptics
├── HandOverlayView.kt       — Custom View: отрисовка точек руки, фидбек действий
├── PrefsKeys.kt             — Централизованные ключи настроек и формулы порогов
└── SettingsActivity.kt      — Экран настроек (PreferenceFragment)
```

### State Machine жестов

```
INACTIVE → (open palm) → ACTIVATING → (1.5s hold) → IDLE
IDLE → (pinch) → PINCHED → (quick release) → TAP_WAITING → (timeout) → resolve taps
                         → (hold > 300ms) → VOLUME_MODE → (move up/down) → volume action
Any active state → (10s timeout) → INACTIVE
```

## Стек

- **Kotlin** — основной язык
- **CameraX** 1.3.4 — камера (Preview + ImageAnalysis)
- **MediaPipe Tasks Vision** 0.10.14 — hand landmark detection (21 точка руки)
- **Material Components** 1.11.0 — UI
- **AndroidX Preference** 1.2.1 — настройки
- **AGP** 8.12.3, **Gradle** 8.13, **JDK** 17

## Сборка

```bash
# Клонировать
git clone https://github.com/YourUser/JustTrack.git
cd JustTrack

# Собрать debug APK
./gradlew assembleDebug

# APK будет в:
# app/build/outputs/apk/debug/app-debug.apk

# Установить на телефон
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Запустить
adb shell am start -n com.justtrack.app/.MainActivity
```

## Тесты

```bash
# Все unit-тесты (51 тест)
./gradlew test

# Конкретный класс
./gradlew test --tests "com.justtrack.app.PinchVolumeGestureTest"
```

### Покрытие тестами

- `PinchVolumeGestureTest` — 27 тестов: активация, тапы, громкость, edge cases, deactivation
- `VolumeControllerTest` — 17 тестов: volume, media keys, haptics, dispatchAction
- `GestureActionTest` — 2 теста: enum validation
- `PrefsKeysTest` — 5 тестов: формулы порогов, граничные значения

## Лицензия

Private project.
