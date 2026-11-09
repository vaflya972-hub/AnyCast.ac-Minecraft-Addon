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

## Безопасность

Плагин закрывает соединение, если не смог прочитать рукопожатие, и не пускает вход, в котором игрок сам дописал адрес или UUID (так подделывают игрока на серверах с `bungeecord: true`). Если в spigot.yml стоит `bungeecord: true`, направляйте anycast.ac на свой прокси, а не на этот сервер.

Адреса из `allow` проходят мимо всех проверок. Не вписывайте туда 127.0.0.1, если на этой машине работает туннель вроде playit.gg или ngrok: через него придут все игроки.

## Как не дать найти сервер

Код плагина открыт, но без ключа сервера подпись не подделать. С ключом с адресов anycast.ac проходит только подписанное, остальное закрывается без ответа, поэтому через свой сервер в anycast.ac чужой ваш MOTD не увидит. Без ключа увидит, так что ключ обязателен.

Соединения не с адресов anycast.ac плагин закрывает сразу, ничего не отправив. Сам порт при этом виден открытым. Чтобы он выглядел закрытым, разрешите его в файрволе только адресам из https://anycast.ac/ips/minecraft.txt.

Адрес сервера находят и по тому, что отвечает рядом с ним: query (`enable-query`), Geyser для Bedrock, карты вроде Dynmap и BlueMap, сайт на той же машине. Плагин предупреждает о query и Geyser в логе. Остальное держите на другом адресе или закройте.

## Прямой вход

`allow-direct: true` пускает игроков и напрямую на сервер, вместе с игроками через anycast.ac. По умолчанию выключено. Включайте, только если знаете, что делаете: это влияет на безопасность. Прямые игроки идут без защиты anycast.ac от DDoS и ботов, баны и запреты стран из панели на них не действуют, а адрес сервера становится известен, и его могут атаковать в обход anycast.ac. Подпись входа через anycast.ac проверяется как обычно, и прямой игрок не может выдать себя за чужой адрес.

На BungeeCord с `connection_throttle: -1` частоту прямых входов ничто не ограничивает.

## Команды

`/anycast` показывает состояние, `/anycast reload` перечитывает конфиг. Право `anycast.admin`.

## Сборка

```
mvn package
```

## English

Plugin for Minecraft servers behind anycast.ac: real player IPs, no connections around anycast.ac. Download the jar from [releases](https://github.com/vaflya972-hub/AnyCast.ac-Minecraft-Addon/releases/latest), put it into `plugins`, restart, then paste the server key from the anycast.ac panel into `config.yml` and run `/anycast reload`. Addresses in `allow` skip every check, so keep 127.0.0.1 out of it when a tunnel agent runs on the same machine. `allow-direct: true` also lets players join the server directly; it is off by default, leave it off unless you know what you are doing, because direct players get no anycast.ac protection. Keep the key set: without it any anycast.ac account can point its server at your address and read your MOTD through anycast.ac. Turn query off and keep Geyser, web maps and sites off the server address.
