# AnyCast.ac Minecraft Addon

Плагин для серверов Minecraft Java за защитой [anycast.ac](https://anycast.ac). Один jar для **Paper**, **Velocity** и **BungeeCord**.

- **Настоящий адрес игрока.** Сервер видит IP игрока, а не адрес anycast.ac: работают баны по IP, лимиты, античиты и логи.
- **Сервер закрыт для прямых атак.** Соединения не с адресов anycast.ac закрываются сразу, до разбора первого пакета.
- **Подпись входа.** Адрес игрока подписан ключом именно вашего сервера. Чужой аккаунт anycast.ac, который направит свой домен на ваш IP, не проведёт игроков в обход ваших настроек.
- **Без лишнего трафика.** Добавка идёт только во входе, пинги списка серверов уходят как есть. После первого пакета плагин убирает себя из соединения.

[English below](#english)

## Плагин или PROXY protocol

В панели anycast.ac у каждого сервера выбирается один из двух способов передать адрес игрока.

|                                     | Плагин AnyCast                   | PROXY protocol v2                     |
|-------------------------------------|----------------------------------|---------------------------------------|
| Добавка к соединению                | 41 байт (IPv4), только при входе | 28 байт на **каждое** соединение      |
| Пинги списка серверов               | без добавок                      | с заголовком                          |
| Прямые соединения мимо anycast.ac   | закрывает сам                    | закрывать самим, файрволом            |
| Чужой маршрут на ваш IP             | не пустит (подпись)              | пустит                                |
| Сервер без поддержки                | вход работает, адрес anycast.ac  | любое соединение ломается             |
| Настройка                           | jar и ключ                       | правка конфига платформы              |

## Установка

1. Скачайте `AnyCast-Minecraft-Addon-<версия>.jar` со страницы [Releases](https://github.com/vaflya972-hub/AnyCast.ac-Minecraft-Addon/releases/latest) и положите в папку `plugins`.
2. Перезапустите сервер. Появится `plugins/AnyCast/config.yml` (на Velocity `plugins/anycast/config.yml`).
3. В панели anycast.ac откройте Minecraft → ваш сервер → «Сервер» → «Адрес игрока», выберите «Плагин AnyCast» и скопируйте ключ.
4. Вставьте ключ в строку `key` и выполните `/anycast reload`.

Ставьте плагин туда, куда приходят игроки: на прокси (Velocity, BungeeCord), если он есть, иначе на сам Paper. На серверы за прокси плагин не нужен.

### Платформы

| Платформа                           | Версии                 | Java |
|-------------------------------------|------------------------|------|
| Paper, Purpur, Pufferfish, Folia    | 1.17 и новее, до 26.x  | 17+  |
| Velocity                            | 3.x и 4.x              | 17+  |
| BungeeCord, Waterfall               | 1.19 и новее           | 17+  |

Spigot и CraftBukkit без Paper не поддерживаются: у них нет точки, через которую плагин встаёт перед разбором пакетов.

**BungeeCord:** поставьте `connection_throttle: -1` в `config.yml` BungeeCord. Все игроки приходят с нескольких адресов anycast.ac, и встроенный лимит BungeeCord начнёт отбивать их. Лимиты на адрес игрока anycast.ac держит сам.

## Настройки

```yaml
key: ""                       # ключ сервера из панели anycast.ac
only-anycast: true            # пускать соединения только с адресов anycast.ac
ip-list-url: "https://anycast.ac/ips/minecraft.txt"
ip-list-refresh-minutes: 10   # как часто обновлять список
allow:                        # свои адреса мимо проверки (CIDR можно)
  - 127.0.0.1
  - ::1
signature-max-age-seconds: 600
debug: false
```

Список адресов anycast.ac плагин берёт сам по `ip-list-url`. Если скачать не вышло, работает прошлый список (`ip-cache.txt`) или встроенный в jar. Страница с ошибкой или пустой ответ никогда не заменят рабочий список.

## Команды

| Команда           | Право           | Что делает                                    |
|-------------------|-----------------|-----------------------------------------------|
| `/anycast`        | `anycast.admin` | состояние: ключ, список адресов, счётчики     |
| `/anycast reload` | `anycast.admin` | перечитать `config.yml` и скачать список      |

## Что видит игрок

| Ситуация                                          | Результат                                            |
|---------------------------------------------------|------------------------------------------------------|
| Вход через anycast.ac с верной подписью           | вход, сервер видит настоящий IP                      |
| Прямое соединение на IP сервера                   | закрыто без ответа                                   |
| Вход через anycast.ac без подписи (режим PROXY)   | отказ с текстом «вход пришёл без подписи»            |
| Подпись другим ключом или старше 10 минут         | отказ с текстом «подпись входа не прошла проверку»   |
| Ключ ещё не вставлен                              | вход, адрес anycast.ac, предупреждение в логе        |

На BungeeCord отказы закрываются без текста: до состояния входа он не умеет отправить сообщение. Причина всегда есть в логе сервера.

Если игроков отбивает по времени подписи, проверьте часы сервера: они должны идти по NTP.

## Как это устроено

Край anycast.ac при входе игрока дописывает к адресу сервера в рукопожатии подписанный адрес игрока: `play.example.com///<подпись>`. Плагин стоит первым в цепочке Netty, проверяет подпись ключом сервера, возвращает адрес в исходный вид и подставляет настоящий IP игрока в соединение. Подробно формат описан в [PROTOCOL.md](PROTOCOL.md).

## Сборка

```bash
mvn package
```

Jar появится в `target/`. Нужны JDK 17+ и Maven.

## Лицензия

[MIT](LICENSE)

---

## English

A plugin for Minecraft Java servers protected by [anycast.ac](https://anycast.ac). One jar for **Paper**, **Velocity** and **BungeeCord**.

- **Real player IPs.** Your server sees the player's address, not the anycast.ac one, so IP bans, limits, anticheats and logs work.
- **Origin lock.** Connections that do not come from anycast.ac are closed before the first packet is read.
- **Signed logins.** The player address is signed with your server's own key. Another anycast.ac account pointing its domain at your IP cannot bring players around your settings.
- **No extra traffic.** The address goes only with the login, server list pings stay untouched, and the plugin leaves the connection after the first packet.

### Install

1. Download `AnyCast-Minecraft-Addon-<version>.jar` from [Releases](https://github.com/vaflya972-hub/AnyCast.ac-Minecraft-Addon/releases/latest) into `plugins`.
2. Restart the server. `plugins/AnyCast/config.yml` appears (`plugins/anycast/config.yml` on Velocity).
3. In the anycast.ac panel open Minecraft → your server → Server → Player address, choose "AnyCast plugin" and copy the key.
4. Paste it into `key` and run `/anycast reload`.

Install it where players connect: on the proxy (Velocity, BungeeCord) if you have one, otherwise on Paper itself. On BungeeCord set `connection_throttle: -1`, because every player arrives from a few anycast.ac addresses.

Supported: Paper and forks (Purpur, Pufferfish, Folia) 1.17 to 26.x, Velocity 3.x and 4.x, BungeeCord and Waterfall 1.19+. Java 17+. Plain Spigot is not supported.

See [PROTOCOL.md](PROTOCOL.md) for the wire format. License: [MIT](LICENSE).
