

Неофициальный клиент **Twitch** для **Android** (телефон, планшет) и **Android TV**.

Тёмный интерфейс на **Jetpack Compose + Material 3**, настраиваемый акцентный цвет.

> **Отказ от ответственности.** NovaStreams не связан с Twitch Interactive, Inc. и не одобрен ею.  
> Twitch — товарный знак соответствующего правообладателя.  
> Часть функций использует неофициальные API (GraphQL / Usher) — Twitch может изменить их в любой момент.

**Лицензия:** GPL-3.0

---

## Возможности

### Просмотр
- Живые трансляции через **ExoPlayer (HLS)**
- Качество по умолчанию и переключение в плеере
- Масштаб видео: кадр / обрезка / растянуть
- Записи (VODs) и клипы
- Рекомендации и лента с учётом **языка устройства**

### Каталог
- Главная: подписки в эфире, популярные стримы, категории
- Поиск каналов и категорий (с историей)
- Страницы категории и канала
- Локальное **избранное** + подписки Twitch после входа

### Чат
- **Нативный IRC** (WebSocket): чтение без входа, отправка после авторизации
- **WebView-чат** (сайт Twitch): опросы, баллы, полный интерфейс — по настройке
- Смайлики **Twitch, 7TV, BetterTTV, FrankerFaceZ**
- На телефоне: чат под плеером или оверлей; закрытие **свайпом** / стрелкой с края
- На TV: боковая панель, навигация пультом (стрелки / курсор)

### Аккаунт
- Вход по **Device Code Flow** (удобно на TV)
- Токены в **EncryptedSharedPreferences**
- «Подписаться на сайте»: браузер (телефон) или **QR-код** (TV)

### Уведомления
- «Канал из подписок вышел в эфир» (WorkManager, ~раз в 15 минут, когда приложение закрыто)

### Платформы
| | Телефон | Android TV |
|--|---------|------------|
| Плеер + чат | ✓ | ✓ |
| Пульт / D-pad | — | ✓ |
| Уведомления | ✓ | — |
| QR для Follow | — | ✓ |

---
# Скриншоты
# Andoid 
<img width="1080" height="2400" alt="photo_2026-10-18_14-05-00" src="https://github.com/user-attachments/assets/3ba74163-87b6-46ef-b107-1c0942aa9e87" />
<img width="1080" height="2400" alt="photo_2026-10-18_14-05-32" src="https://github.com/user-attachments/assets/15184817-b5eb-442a-89b2-0c39037de416" />


---
# Andoid TV
<img width="1920" height="1080" alt="Screenshot_1792064603585" src="https://github.com/user-attachments/assets/d634520f-3920-4100-b850-27fac7915c8e" />
<img width="1920" height="1080" alt="Screenshot_1792064628355" src="https://github.com/user-attachments/assets/ff40e717-31a0-415d-ab25-3c298fa61693" />
<img width="1920" height="1080" alt="Screenshot_1792064694127" src="https://github.com/user-attachments/assets/50981a1e-ca23-4f69-9e98-57ad064ad9ee" />
<img width="1920" height="1080" alt="Screenshot_1792064967689" src="https://github.com/user-attachments/assets/56b46843-8bc5-4156-8f8b-7af3325cf865" />

---
## Стек

- Kotlin, Jetpack Compose, Material 3  
- Media3 ExoPlayer, OkHttp, DataStore, WorkManager, Coil  
- minSdk 23 · targetSdk 35  

---

## Сборка

1. Android Studio (Ladybug 2024.2+) → Open → папка проекта  
2. Gradle Sync  
3. Run на устройстве API 23+ или Android TV AVD  

