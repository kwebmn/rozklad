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
