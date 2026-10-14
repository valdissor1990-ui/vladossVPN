# Протоколы и конфигурация

## VLESS + XHTTP + Reality (основной стек)

- **XHTTP** — HTTP-транспорт, скрывающий пост-хендшейк поведение (тайминги/размеры пакетов)
- **Reality** — скрывает TLS-хендшейк, используя реальный сайт как маскировку
- **mode = stream-one** — обязателен, `auto` имеет баги (Xray-core #5635, sing-box #2082)
- **flow = ""** на XHTTP — `xtls-rprx-vision` только на TCP

## Fallback: TCP + Reality + Vision

- Порт 8443 (отличный от 443)
- `flow = "xtls-rprx-vision"`
- Требуется для iOS (XHTTP+Reality сломан на Happ iOS)

## Fingerprint

- **firefox** — дефолт (TSPU 2026 меньше флагит)
- **chrome** — поддержка обязательна (многие провайдеры выдают chrome)

## Пример JSON outbound

```json
{
  "protocol": "vless",
  "settings": {
    "vnext": [{
      "address": "SERVER_IP",
      "port": 443,
      "users": [{ "id": "UUID", "encryption": "none", "flow": "" }]
    }]
  },
  "streamSettings": {
    "method": "xhttp",
    "xhttpSettings": { "path": "/yourpath", "mode": "stream-one" },
    "security": "reality",
    "realitySettings": {
      "serverName": "www.microsoft.com",
      "fingerprint": "firefox",
      "publicKey": "PUBLIC_KEY",
      "shortId": "SHORT_ID"
    }
  }
}
```

## Важные ограничения

1. Reality несовместим с CDN — только point-to-point
2. auto mode запрещён — только stream-one
3. flow: xtls-rprx-vision на XHTTP — критическая ошибка (error -1)
4. Парсинг share-link: vless://uuid@host:port?type=xhttp&security=reality&fp=firefox&pbk=...&sid=...&sni=...&path=...&mode=stream-one#name
