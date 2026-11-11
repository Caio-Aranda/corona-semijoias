# Certificado HTTPS do servidor (sem domínio)

O servidor não tem nome na internet, só o **endereço IP**. Por isso o HTTPS usa um certificado
**autoassinado**, e o Corona Gestão **só confia nele**: o aplicativo guarda a **impressão digital da chave
pública** do servidor (o "pin") e recusa qualquer outro certificado para esse endereço. A decisão e os
motivos estão no [ADR-0009](../../docs/decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md).

A chave privada (`servidor.key`) **fica só no servidor** (`/etc/corona/tls/`, permissão 600) e nunca vai ao Git.

## Gerar pela primeira vez

No servidor, com o IP real da VPS:

```bash
sudo /opt/corona/deploy/certificado/gerar-certificado.sh novo 203.0.113.10
```

O script mostra a **impressão digital** (`sha256/...`). Anote: ela vai no build do app (`MAIN_VITE_CORONA_API_PINS`).

## Gerar uma chave reserva (faça já, antes de entregar o app)

A reserva permite trocar a chave do servidor no futuro **sem derrubar o app**: o app já nasce aceitando as
duas impressões digitais.

```bash
sudo /opt/corona/deploy/certificado/gerar-certificado.sh reserva /root/reserva
```

Guarde `reserva.key` **fora do servidor** (cofre de senhas do responsável) e **apague a cópia do servidor**.
Coloque as duas impressões digitais no build, separadas por vírgula: `sha256/ATUAL,sha256/RESERVA`.

## Renovar (todo ano; o certificado vale 730 dias)

Mesma chave, certificado novo. **O app não precisa ser atualizado**, porque a impressão digital é da chave:

```bash
sudo /opt/corona/deploy/certificado/gerar-certificado.sh renovar 203.0.113.10
cd /opt/corona/deploy && docker compose restart proxy
```

Para ver a validade: `openssl x509 -in /etc/corona/tls/servidor.crt -noout -enddate`. Marque no calendário
uma renovação a cada 12 meses.

## Trocar a chave (se a chave privada vazar ou se perder)

1. Pegue a chave reserva guardada fora do servidor e coloque em `/etc/corona/tls/servidor.key` (permissão 600).
2. `sudo ... gerar-certificado.sh renovar <IP>` (usa essa chave) e `docker compose restart proxy`.
3. O app já aceita a reserva, então **continua funcionando**. Gere uma **nova reserva** e publique uma versão
   do app que traga a nova lista de impressões digitais (`owner:publicar`); só depois tire a antiga.

Se a chave vazou, considere também trocar todas as senhas e as chaves do `.env` (JWT e sessão).

## Se o IP mudar

O certificado só vale para o IP que está nele, e o app só fala com o endereço que veio no instalador (as
atualizações também vêm de lá). **Se o IP da VPS mudar, o app para de conectar e de se atualizar.** Para
resolver: gerar o certificado para o IP novo (`renovar <IP-NOVO>`, mesma chave), gerar um **instalador novo**
com o endereço novo (`owner:empacotar`) e **reinstalar uma vez** no computador da dona. Para evitar: nunca
recriar nem trocar a VPS sem avisar, e pedir à Hostinger que mantenha o IP. O destino é um domínio próprio
com Let's Encrypt (Fase 9 completa).

## Ver a impressão digital de um certificado qualquer

```bash
openssl x509 -in /etc/corona/tls/servidor.crt -pubkey -noout \
  | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl base64
```
