# Restauração de backup do banco

Roteiro para restaurar um backup (`pg_dump`) do PostgreSQL num servidor novo **sem que o
Corona Vendas perca registros na sincronização** (ADR-0002, item 5, "Restauração de backup (R1)").
O backup diário, o envio para fora da VPS e o usuário que faz o backup em produção são da Fase 9
(§11); este documento cobre o que a sincronização exige e vale também para o teste periódico de
restauração.

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
