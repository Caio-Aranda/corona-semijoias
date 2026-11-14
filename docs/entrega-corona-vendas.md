# Corona Vendas: instalador e atualização (Fase 9 mínima, preparação)

O Corona Vendas usa o **mesmo mecanismo** do Corona Gestão ([entrega-corona-gestao.md](entrega-corona-gestao.md),
[ADR-0009](decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md)): instalador NSIS por usuário, certificado
do servidor fixado pela impressão digital e atualização automática que instala ao fechar. O que muda:

| | Corona Gestão | Corona Vendas |
| --- | --- | --- |
| Pasta | `apps/owner-app` | `apps/seller-app` |
| Instalador | `Corona-Gestao-Setup-<versão>.exe` | `Corona-Vendas-Setup-<versão>.exe` |
| Canal no servidor | `/atualizacoes/` | `/atualizacoes/vendas/` (pasta própria; os dois `latest.yml` nunca se misturam) |
| Gerar | `npm run owner:empacotar` | `npm run vendas:empacotar` |
| Publicar | `npm run owner:publicar` | `npm run vendas:publicar` |
| Configuração | `apps/owner-app/.env.producao` e `.env.publicar` | `apps/seller-app/.env.producao` e `.env.publicar` (modelos `.example`) |

O código comum (certificado fixado, endereço, atualização) mora no pacote **neutro** `packages/conexao-segura`, sem nada
de gestão nem de vendas; a varredura da barreira (ADR-0003) confere esse pacote também.

**Estado:** o instalador e a atualização do Corona Vendas foram provados no computador de desenvolvimento (instalar a 1.0.0,
publicar a 1.0.1, o app se atualizar sozinho ao fechar). **Ainda não há venda nem sincronização no app**: o banco local
criptografado e o bloqueio do segundo aparelho são da Fase 5; o instalador só vai a vendedores depois disso.

## Como publicar uma atualização do Corona Vendas

1. Em `apps/seller-app/package.json`, aumente `version` (sempre **maior**).
2. Código na `main`, CI verde (inclui a varredura da barreira).
3. `npm run vendas:publicar`. Gera o instalador, recusa versão igual ou menor, envia o instalador e só depois o
   `latest.yml` (em `/srv/corona/atualizacoes/vendas/`), confere o hash no servidor e guarda os 3 instaladores mais novos.
4. Os computadores dos vendedores acham a versão ao abrir (ou em até 6 horas), baixam em segundo plano e instalam quando o
   vendedor **fecha** o programa. Atenção: o vendedor pode ficar **offline** por dias; ele recebe a versão no primeiro
   contato com a internet. Por isso `APP_VERSAO_MINIMA_VENDAS` só sobe depois de a maioria já estar na versão nova.
5. Versão com defeito: publique outra, maior, com a correção (a antiga não volta).

### Nota de deploy da tarefa 6.7 (tabelas da Fase 6 no pull)

A API com a 6.7 manda ao vendedor as tabelas da Fase 6 (devoluções, autorizações, trocas, créditos e a venda com crédito
para a dona revisar). O Corona Vendas sem a **migração local 3** não conhece essas tabelas e recusaria o lote inteiro do
recebimento (o pull pararia). Por isso, **nesta ordem**:

1. **Primeiro o Corona Vendas:** aumente `version` em `apps/seller-app/package.json` (por exemplo, para `1.1.0`) e
   publique (`npm run vendas:publicar`). Essa versão leva a migração local 3 e a recarga dirigida.
2. **Depois a API:** suba a API com a 6.7 e, no `.env` do servidor, `APP_VERSAO_MINIMA_VENDAS` igual à versão publicada
   no passo 1 (por exemplo, `1.1.0`).
3. O computador ainda na versão antiga **continua enviando** o que guardou (o envio passa abaixo da mínima, tarefa 5.13)
   e recebe 426 no recebimento, com "Seu aplicativo está desatualizado.", até atualizar. Nada se perde.
4. Ao abrir a versão nova, o banco local migra para a versão 3; se ele já tinha recebido dados, as tabelas novas e as
   vendas, pagamentos e movimentos da maleta vêm de novo pela **recarga dirigida**, sem apagar nada antes.

Se a API subir antes do app, o computador com o app velho recebe o lote com tabela desconhecida e para de receber (o
envio continua); publique o app e suba a versão mínima o quanto antes.

### Nota de deploy da tarefa 7.3 (contas a receber do cliente no pull)

A API com a 7.3 manda ao vendedor as tabelas `contas_receber` e `baixas_receber` e a coluna `posterior` dos pagamentos.
O Corona Vendas sem a **migração local 4** não conhece essas tabelas e recusaria o lote inteiro do recebimento (o pull
pararia). Por isso, **nesta ordem** (a mesma da nota da 6.7):

1. **Primeiro o Corona Vendas:** aumente `version` em `apps/seller-app/package.json` (por exemplo, para `1.2.0`) e
   publique (`npm run vendas:publicar`). Essa versão leva a migração local 4 e a recarga dirigida (as duas tabelas novas e
   os pagamentos).
2. **Depois a API:** suba a API com a 7.3 e, no `.env` do servidor, `APP_VERSAO_MINIMA_VENDAS` igual à versão publicada.
3. O computador ainda na versão antiga **continua enviando** o que guardou e recebe 426 no recebimento até atualizar. Nada
   se perde. As operações novas (`RECEBIMENTO_CLIENTE`, `ESTORNO_RECEBIMENTO_CLIENTE` e o `parcelamento` da venda) só
   passam a ser gravadas pelo app na tarefa 7.11.
4. Ao abrir a versão nova, o banco local migra para a versão 4; se ele já tinha recebido dados, as duas tabelas novas e os
   pagamentos vêm de novo pela **recarga dirigida**, sem apagar nada antes.

### Nota de deploy da tarefa 6.6 (devolução de venda a prazo)

A API com a 6.6 manda `devolucoes.valor_abatido` e `baixas_receber.devolucao_id` no pull. O Corona Vendas sem a **migração local 5** recusaria o lote (o pull pararia). **Nesta ordem**, como na 6.7 e na 7.3:

1. **Primeiro o Corona Vendas:** aumente `version` em `apps/seller-app/package.json` (por exemplo, `1.3.0`) e publique (`npm run vendas:publicar`). A migração local 5 só cria as duas colunas; não há recarga dirigida.
2. **Depois a API:** suba a API com a 6.6 e, no `.env` do servidor, `APP_VERSAO_MINIMA_VENDAS` igual à versão publicada.
3. O computador na versão antiga continua enviando o que guardou; a devolução de venda a prazo feita nele recebe o bloqueio antigo até atualizar. Nada se perde.

### Nota de deploy da tarefa 7.4 (acerto no pull)

A API com a 7.4 manda ao vendedor as tabelas `acertos` e `leituras_acerto`. O Corona Vendas sem a **migração local 6** não conhece essas tabelas e recusaria o lote inteiro (o pull pararia). **Nesta ordem**, como na 6.7, na 7.3 e na 6.6 (**app primeiro, depois a API**):

1. **Primeiro o Corona Vendas:** aumente `version` em `apps/seller-app/package.json` (por exemplo, `1.4.0`) e publique (`npm run vendas:publicar`). A migração local 6 cria as duas tabelas e a **recarga dirigida** as traz de novo para o computador que já tinha dados, sem apagar nada.
2. **Depois a API:** suba a API com a 7.4 e, no `.env` do servidor, `APP_VERSAO_MINIMA_VENDAS` igual à versão publicada. A migração V043 do banco roda junto.
3. O computador na versão antiga continua enviando o que guardou e recebe 426 no recebimento até atualizar. Nada se perde. Pedir o acerto e bipar são online: as telas são da tarefa 7.11 (nada novo na outbox).
4. Enquanto um vendedor está em acerto, a dona não envia maleta nova para ele ("Termine o acerto antes"); o rascunho continua livre.

### Nota de deploy da tarefa 7.5 (fechamento do acerto no pull)

A API com a 7.5 manda ao vendedor quatro tabelas novas (`itens_acerto`, `acerto_eventos`, `comissoes`, `pendencias_acerto`) e colunas novas de `acertos`. O Corona Vendas sem a **migração local 7** não as conhece e recusaria o lote inteiro (o pull pararia). **Nesta ordem**, como na 7.4 (**app primeiro, depois a API**):

1. **Primeiro o Corona Vendas:** aumente `version` em `apps/seller-app/package.json` (por exemplo, `1.5.0`) e publique (`npm run vendas:publicar`). A migração local 7 cria as colunas e as quatro tabelas e a **recarga dirigida** as traz de novo, sem apagar nada.
2. **Depois a API:** suba a API com a 7.5 e, no `.env` do servidor, `APP_VERSAO_MINIMA_VENDAS` igual à versão publicada. A migração V044 do banco roda junto.
3. O computador na versão antiga continua enviando o que guardou. Nada se perde. As telas do fechamento e da prévia da comissão são das tarefas 7.9 e 7.11.

### Nota de deploy da tarefa 7.6 (dinheiro do vendedor no pull)

A API com a 7.6 manda ao vendedor a tabela nova `recebimentos_vendedor`, as contas a receber que ele deve à loja (sem venda), as baixas pelo recebimento e colunas novas de `contas_receber` e `acertos`. O Corona Vendas sem a **migração local 8** não as conhece e recusaria o lote inteiro (o pull pararia). **Nesta ordem**, como na 7.4 e na 7.5 (**app primeiro, depois a API**):

1. **Primeiro o Corona Vendas:** aumente `version` em `apps/seller-app/package.json` (por exemplo, `1.6.0`) e publique (`npm run vendas:publicar`). A migração local 8 refaz a cópia local das contas (com as mesmas linhas, agora aceitando conta sem venda), cria as colunas e a tabela nova e a **recarga dirigida** traz tudo de novo, sem apagar nada.
2. **Depois a API:** suba a API com a 7.6 e, no `.env` do servidor, `APP_VERSAO_MINIMA_VENDAS` igual à versão publicada. A migração V045 do banco roda junto.
3. O computador na versão antiga continua enviando o que guardou. Nada se perde. O vendedor não registra nem estorna recebimento (tudo é da dona): não há tipo novo na fila de envio. As telas são das tarefas 7.9, 7.10 e 7.11. O caixa nunca vai ao computador do vendedor.

## Riscos da primeira instalação no computador do vendedor

| Risco | O que fazer |
| --- | --- |
| **Windows**: "O Windows protegeu o computador" (instalador sem assinatura) | Esperado na primeira instalação: **Mais informações** e **Executar assim mesmo**. Explique antes ao vendedor que isso é normal. Assinar o instalador (Fase 9 completa) acaba com o aviso. |
| **Antivírus** segura ou apaga o instalador | Liberar o arquivo e a pasta `%LocalAppData%\Programs\corona-vendas`; instalador sem assinatura é mais desconfiado. |
| **Firewall / rede da loja** bloqueia a porta 443 para o IP do servidor | O app só fala HTTPS (443) com o IP do servidor. Testar a conexão no computador **antes** de entregar. |
| **Atualizar** sem internet | O app segue funcionando; a versão nova chega quando houver internet. |
| **Computador sem permissão** para instalar em `%LocalAppData%` | Raro; o instalador é por usuário, sem administrador. Se bloqueado por política da empresa, falar com quem administra o computador. |
| **Dois aparelhos** com o mesmo vendedor | O bloqueio do segundo aparelho é da Fase 5; até lá, não entregar o instalador a vendedores reais. |
| **IP do servidor mudou** | O app para de conectar e de atualizar; precisa de instalador novo (mesmo risco do Corona Gestão, [certificado/README.md](../deploy/certificado/README.md)). |
