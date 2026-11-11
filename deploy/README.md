# Implantação em produção (VPS)

Para quem opera o servidor. A API e o PostgreSQL rodam na VPS (Ubuntu) em Docker Compose, atrás de um
proxy HTTPS. Decisões e riscos: [ADR-0009](../docs/decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md).
O passo a passo completo da entrega está em `docs/entrega-corona-gestao.md`.

```
Corona Gestão ── HTTPS :443 (certificado fixado) ──► proxy (nginx) ──► api (Spring, perfil prod) ──► postgres
                                                        └─ /atualizacoes/ (instaladores)        (sem porta aberta)
```

## O que tem aqui

| Pasta ou arquivo                | Para quê                                                               |
| ------------------------------- | ---------------------------------------------------------------------- |
| `docker-compose.yml`            | API, PostgreSQL e proxy. Só o proxy publica porta (443).               |
| `.env.example`                  | Modelo do `.env` do servidor (o `.env` real nunca vai para o Git).     |
| `nginx/`                        | Configuração do proxy (HTTPS, limite de login, pasta de atualizações). |
| `certificado/`                  | Gerar, renovar e trocar o certificado. Leia o `README.md` de lá.       |
| `servidor/instalar-servidor.sh` | Prepara a VPS: firewall, SSH só por chave, fail2ban, UTC, Docker.      |
| `servidor/verificar-portas.sh`  | Confere que só a 443 está aberta (o Docker pode furar o firewall).     |
| `monitoramento/`                | Aviso se a API cair ou o disco encher (timer do systemd).              |
| `backup/`                       | Backup diário, cópia semanal para o Google Drive e restauração.        |

## Primeira instalação (resumo)

1. **Com a chave SSH do responsável já funcionando**, na VPS como root: `bash servidor/instalar-servidor.sh`.
2. Copiar o repositório para `/opt/corona` e `cd /opt/corona/deploy`.
3. `cp .env.example .env && chmod 600 .env` e preencher (valores aleatórios: `openssl rand -base64 32 | tr -d '=+/'`).
4. `sudo certificado/gerar-certificado.sh novo <IP-DA-VPS>` (e a chave reserva; veja `certificado/README.md`).
5. `docker compose up -d --build --wait`. A API cria as tabelas sozinha (Flyway) e cria a conta da dona
   a partir de `ADMIN_*` do `.env`.
6. `sudo servidor/verificar-portas.sh` e `sudo monitoramento/instalar-timers.sh`.
7. Conferir de fora (do seu computador): `curl -k https://<IP>/actuator/health` deve responder `{"status":"UP"}`.
8. **Apagar `ADMIN_SENHA` do `.env`** depois do primeiro acesso da dona (ela já criou a senha nova).

## Rotina

| O que                   | Comando (na pasta `deploy/`)                                                                                                         |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| Ver como está           | `docker compose ps` e `docker compose logs --tail 100 api`                                                                           |
| Atualizar a API         | `git pull && docker compose up -d --build --wait`                                                                                    |
| Reiniciar só a API      | `docker compose restart api`                                                                                                         |
| Senha esquecida da dona | `docker compose exec api java -jar /app/corona-api.jar redefinir-senha-admin --login=LOGIN` (veja `docs/redefinir-senha-da-dona.md`) |
| Entrar no banco         | `docker compose exec postgres psql -U "$DB_MIGRACAO_USER" -d "$DB_NAME"`                                                             |

Nunca use `docker compose down -v` em produção: o `-v` **apaga o banco**.

## Perfil `prod` da API

Swagger e OpenAPI desligados; erros sem detalhe técnico; segredos só por variável de ambiente (o `.env`
do servidor); sem vendedor de demonstração. Arquivo: `backend/src/main/resources/application-prod.properties`.

## Aviso de queda

Dois níveis. (1) O timer `corona-saude` testa a API a cada 5 minutos pelo próprio servidor e avisa por
`ntfy` (celular) ou e-mail, configurado em `/etc/corona/aviso.env`. (2) **Recomendado também**: uma
verificação externa gratuita (por exemplo UptimeRobot) em `https://<IP>/actuator/health`, aceitando
certificado não confiável, porque o servidor externo não conhece o nosso certificado. Esta rota não mostra dado nenhum.
O responsável escolhe e configura; o ADR-0009 explica.
