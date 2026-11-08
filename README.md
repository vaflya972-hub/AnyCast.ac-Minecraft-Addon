# AnyCast.ac Minecraft Addon

Плагин для серверов Minecraft за [anycast.ac](https://anycast.ac). Сервер видит настоящие IP игроков, а соединения в обход anycast.ac не проходят.

Paper (и форки), Velocity, BungeeCord. Java 17+.

## Установка

1. Скачайте jar из [релизов](https://github.com/vaflya972-hub/AnyCast.ac-Minecraft-Addon/releases/latest) и положите в `plugins`.
2. Перезапустите сервер.
3. В панели anycast.ac: Minecraft → ваш сервер → «Сервер» → «Адрес игрока», выберите плагин и скопируйте ключ.
4. Вставьте ключ в `plugins/AnyCast/config.yml` (на Velocity `plugins/anycast/config.yml`) и выполните `/anycast reload`.

Если перед сервером стоит Velocity или BungeeCord, ставьте плагин только на прокси.

На BungeeCord поставьте `connection_throttle: -1`: все игроки приходят с адресов anycast.ac, и встроенный лимит начнёт их отбивать.

## Команды

`/anycast` показывает состояние, `/anycast reload` перечитывает конфиг. Право `anycast.admin`.

## Сборка

```
mvn package
```

## English

Plugin for Minecraft servers behind anycast.ac: real player IPs, no connections around anycast.ac. Download the jar from [releases](https://github.com/vaflya972-hub/AnyCast.ac-Minecraft-Addon/releases/latest), put it into `plugins`, restart, then paste the server key from the anycast.ac panel into `config.yml` and run `/anycast reload`.
