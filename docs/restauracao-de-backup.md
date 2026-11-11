# Restauração de backup do banco

Roteiro para restaurar um backup (`pg_dump`) do PostgreSQL num servidor novo **sem que o
Corona Vendas perca registros na sincronização** (ADR-0002, item 5, "Restauração de backup (R1)").
O backup de produção (diário, na VPS, e semanal, no Google Drive) está descrito na seção
[Backup em produção](#backup-em-produção-fase-9-mínima) no fim deste documento; o resto cobre o que a
sincronização exige e vale também para o teste periódico de restauração.

## Por que existe um passo a mais

Cada linha sincronizável guarda o identificador da transação que a gravou (`sync_xid`). O pull só
entrega linhas de transações já terminadas, comparando esse número com o contador de transações
do servidor. Num servidor novo, o contador **recomeça do zero**, mas as linhas restauradas continuam
com os números antigos, que são maiores. Sem o passo abaixo, essas linhas **nunca** chegam aos
aparelhos, e nenhum erro aparece. O teste `RestauracaoDoSyncTest` mostra isso: sem o procedimento,
nenhuma das linhas novas foi entregue; com ele, todas chegaram.

O procedimento `corona.preparar_sync_apos_restauracao()` (migração V003):

1. põe o `sync_xid` de todas as linhas sincronizáveis em `0`, com os gatilhos desligados durante a
   troca (não mexe em `sync_seq`, `version` nem `updated_at`);
2. sobe a **época** do cursor em `sync_estado`.

Todo aparelho com cursor da época antiga recebe "cursor inválido" e refaz a carga completa do que é
dele. O que o aparelho guardou para enviar (vendas, clientes) **não é afetado**: o envio segue normal.

## Quem faz cada passo

- **Usuário das migrações** (`DB_MIGRACAO_USER`, dono do schema `corona` e das tabelas): faz o
  `pg_dump`, o `pg_restore` e roda o procedimento. Só o dono da tabela pode desligar os gatilhos.
- O usuário da aplicação **não consegue** rodar o procedimento (não tem permissão de execução).

## Passo a passo

Os comandos abaixo usam as variáveis do `.env` (`DB_NAME`, `DB_MIGRACAO_USER`). Dentro do container
do PostgreSQL, as ferramentas `pg_dump` e `pg_restore` já estão instaladas e na mesma versão do banco.

**1. Fazer o backup** (no servidor antigo; pode ser com a API no ar, porque o `pg_dump` só lê):

```bash
pg_dump -U "$DB_MIGRACAO_USER" -d "$DB_NAME" -n corona -Fc -f backup.dump
```

**2. Parar a API** no servidor novo (ninguém pode gravar durante a restauração).

**3. Preparar o servidor novo** com o mesmo `infra/postgres/init/01-papeis.sh`, que cria os usuários e
o schema `corona` vazio. No Docker Compose isso acontece sozinho na primeira subida (`npm run db:up`
com o volume vazio).

**4. Restaurar.** Como o schema `corona` já existe, a linha que o criaria é tirada da lista do backup:

```bash
pg_restore -l backup.dump | grep -v -E '^[0-9]+; [0-9]+ [0-9]+ SCHEMA - corona ' > lista.txt
```

```bash
pg_restore -U "$DB_MIGRACAO_USER" -d "$DB_NAME" --no-owner --single-transaction --exit-on-error -L lista.txt backup.dump
```

Se algo der errado, `--single-transaction` desfaz tudo: o banco fica vazio e o passo pode ser repetido.

**5. Rodar o procedimento**, com o usuário das migrações:

```bash
psql -U "$DB_MIGRACAO_USER" -d "$DB_NAME" -c "SELECT corona.preparar_sync_apos_restauracao();"
```

A resposta é a época nova (por exemplo `2`), e o PostgreSQL mostra quantas linhas de cada tabela
foram preparadas. Rodar duas vezes não estraga nada: só sobe a época de novo, e os aparelhos refazem
a carga mais uma vez.

**6. Subir a API.**

**7. Conferir** (faz parte do teste periódico de restauração da §11): um aparelho de teste puxa a carga
completa e confere que recebeu todos os registros que devia.

## O mesmo roteiro sempre

Uma cópia física do diretório de dados e o `pg_upgrade` preservam o contador de transações e, em
tese, dispensariam o passo 5. Mesmo assim, o roteiro é sempre este, para existir um único
procedimento (ADR-0002).

## Backup em produção (Fase 9 mínima)

Decisões: [ADR-0009](decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md). Scripts: `deploy/backup/`.

**O que existe**

| O quê | Onde | Quando |
| --- | --- | --- |
| Banco (`pg_dump -Fc` do schema `corona`) + fotos e documentos (`.tar.gz`) + soma SHA-256 | `/var/backups/corona/diario/` na VPS | todo dia, 03:30 UTC (00:30 em Brasília), `corona-backup.timer`. Guarda 14 dias (e nunca menos de 3 cópias) |
| Cópia do backup mais recente | Google Drive do responsável (`rclone`) | todo domingo, 05:00 UTC, `corona-backup-semanal.timer`. Guarda 8 semanas |
| Snapshot da VPS | painel da Hostinger | agendado pelo responsável (segunda rede de segurança) |

Cada backup é conferido na hora (`pg_restore -l` precisa listar tabelas); a cópia do Drive é conferida com `rclone check`. Qualquer falha avisa o responsável (`deploy/monitoramento/avisar.sh`).
Perda máxima aceita: até 24 horas (do último diário); se a VPS e o snapshot se perderem juntos, até 7 dias.

**Ligar o envio ao Google Drive (uma vez, o responsável autoriza):** `rclone config` na VPS (remoto do tipo `drive`; se quiser criptografar, crie também um remoto `crypt` sobre ele), com `RCLONE_CONFIG=/etc/corona/rclone.conf`. Depois crie `/etc/corona/backup.env` (permissão 600) com `RCLONE_CONFIG`, `RCLONE_DESTINO` e, se quiser, `SEMANAS_NO_DRIVE` (quantas cópias semanais ficam no Drive; padrão 8: o envio apaga as mais antigas no Drive, contando cópias e não dias). Nada disso vai para o Git. Instalar os timers: `sudo deploy/backup/instalar-timers.sh`.

### Restaurar em produção (a VPS caiu ou o banco estragou)

1. Pegue o backup: o mais recente em `/var/backups/corona/diario/`; se a VPS foi perdida, baixe do Drive (`rclone copy drive-corona:corona-backups /var/backups/corona/diario`) e confira `sha256sum -c soma-*.sha256`.
2. Servidor novo: `deploy/servidor/instalar-servidor.sh`, `deploy/.env`, certificado (**a mesma chave**, ou o app precisa da chave reserva) e `docker compose up -d postgres` (a API ainda **não**).
3. Siga o "Passo a passo" acima (passos 2 a 5), com o contêiner do banco: copie o arquivo para dentro (`docker compose cp`), tire a linha do schema da lista, rode `pg_restore ... --single-transaction --exit-on-error` e `SELECT corona.preparar_sync_apos_restauracao();`.
4. Fotos e documentos: `tar -xzf arquivos-AAAAMMDDTHHMMSSZ.tar.gz -C /srv/corona/arquivos` e `chown -R 10001:10001 /srv/corona/arquivos`.
5. Suba a API (`docker compose up -d --build --wait`) e entre no Corona Gestão para conferir os produtos e as fotos.

### Teste de restauração (obrigatório antes de a dona lançar dado real, e depois de tempos em tempos)

`deploy/backup/testar-restauracao.sh <backup.dump>` sobe um PostgreSQL **novo e descartável** (não toca no banco de produção), restaura o backup do mesmo jeito do passo a passo, roda o procedimento do sync e confere (tabelas, administradora, migrações). Mostra o tempo de cada etapa. Faça este teste **na VPS**, com um backup real, e registre abaixo.

**Registro dos testes**

| Data | Onde | Backup | Resultado | Tempo |
| --- | --- | --- | --- | --- |
| 10/11/2026 | Computador de desenvolvimento, com a pilha de produção (`deploy/docker-compose.yml`, imagens iguais às da VPS) e valores de teste | Banco recém-criado (20 migrações, só a conta da dona; 208 KB) | OK: 38 tabelas, 1 administradora, 20 migrações, época do sync 2 | 4 s no total (banco 3 s, restauração 1 s, sync 0 s). Banco pequeno: com dados reais o tempo cresce, mas continua na casa dos segundos a poucos minutos |
| (a fazer) | **VPS**, antes do dado real | backup diário real | pendente: precisa da VPS | |

Limite honesto do teste de 10/11: provou o caminho completo (backup, conferência, restauração num banco limpo, preparação do sync), mas com catálogo vazio. O teste na VPS, com produtos importados pela planilha, fecha a tarefa.

