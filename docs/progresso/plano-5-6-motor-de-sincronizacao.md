# Plano da tarefa 5.6: motor de sincronização do Corona Vendas

Tarefa crítica (modo de plano). **Nenhum código antes da aprovação da orquestradora.** Base: ADR-0013 itens 2, 3, 6, 7, 8, 10, 12 e 19; plano da Fase 5; banco local da 5.2 (`apps/seller-app/src/main/banco`); rotas da 5.4 e 5.5 (`/api/vendedor/sync/pull`, `/push`, `/conferencia`, `/conferencia/diferencas`).

## O que já existe (não refazer)

- Banco local (5.2): `ServicoDoBanco`/`BancoAberto`, cópias das 15 tabelas com `origem_local`, `outbox`, `estado_sync`, migrações com hash.
- Servidor: pull com `cursor`, `temMais`, `horaServidor`, `parametros`, `409 CURSOR_INVALIDO`, `?tabela=`; push com `ACEITO | JA_RECEBIDO | CONFLITO | RECUSADO | NAO_PROCESSADO`, `apagarDados`, `429 ENVIO_EM_ANDAMENTO`, `413 LOTE_GRANDE_DEMAIS`; conferência por resumo (`id:version`, SHA-256).
- App: `api.ts` (cliente gerado), `guarda-de-tokens` (renovação em fila única), `contato-com-servidor`, ponte fixa (`shared/ponte.ts`).

## Peças novas (todas no processo principal, `apps/seller-app/src/main/sync/`)

Cada peça é pequena, com dependências injetadas (relógio, sorteio, transporte HTTP, banco), para testar sem Electron.

1. **`aplicador-do-pull.ts`**: aplica um lote numa única transação SQLite junto com o cursor novo.
   - Por linha: só grava se `version` recebida > local (igual ou menor é ignorada); lápide marca `deleted_at` sem apagar; colunas só da lista da tabela (nome de tabela ou coluna desconhecida é erro do lote, não grava nada); dinheiro continua texto.
   - Cópia `PROVISORIO` com o mesmo `id`: o servidor vence (vira `SERVIDOR`) e a provisória sai do saldo (ver 3).
   - Queda no meio = nada gravado (uma transação), o lote volta no ciclo seguinte.
2. **`camada-de-pendentes.ts`**: reaplica por cima da cópia local de cliente as alterações `CLIENTE_ALTERADO` ainda `AGUARDANDO` (campo a campo, valor `depois`), depois de cada lote que tocou o cliente.
3. **`operacoes-locais.ts`**: as cinco operações do vendedor (venda, estorno, cliente novo, cliente alterado, conferência) gravam a cópia `PROVISORIO` e a linha da outbox **na mesma transação**; o `dados` é o JSON exato (formato 1 da 5.5) e nunca é remontado. Saldo e totais de prévia usam o utilitário de dinheiro de `packages/shared` (centavos inteiros). CPF repetido confere no diretório local. Disco cheio: transação falha inteira, mensagem do glossário.
4. **`ciclo.ts`** (um ciclo por vez, trava interna): push, depois pull, depois conferência quando for a hora.
   - **Push:** lê da outbox as `AGUARDANDO` na ordem, até 50 ou 512 KB; manda `horaLocal` e `aguardandoDepoisDeste`; aplica cada resultado: `ACEITO`/`JA_RECEBIDO` → `ENVIADO`; `CONFLITO` → `CONFLITO`; `RECUSADO` → `RECUSADO` e **desfaz o efeito local** (a provisória sai, o saldo volta); `NAO_PROCESSADO` → fica `AGUARDANDO`, conta tentativa. 5 seguidos na mesma operação: estado "uma venda não conseguiu ser enviada" na ponte; **nunca pula** a operação. Nada sai da outbox sem resposta do servidor. `413` → próximo lote pela metade; `429` → espera o `Retry-After`.
   - **Pull:** repete com o cursor até `temMais = false`; `409 CURSOR_INVALIDO` → recarga por época (item abaixo); guarda `horaServidor`, `parametros` e "dados até" ao fim de um pull completo.
   - **Recarga por época / carga inicial:** apaga só as cópias `SERVIDOR`, mantém `PROVISORIO` e a outbox, zera o cursor e puxa de novo.
   - **Conferência:** 1 vez por dia e depois de cada carga inicial, **só depois de receber tudo**. Diferença: confere de novo; persistindo, `POST /conferencia/diferencas` e recarga só daquela tabela (`?tabela=`).
5. **`agenda.ts`**: gatilhos (abrir, internet voltar, a cada 60 s, 2 s depois de gravar agrupando gravações seguidas); espera crescente 2, 4, 8 s... até 5 min com variação sorteada; internet medida pela rota de saúde (5 s), `net.isOnline()` só como dica; renovação de sessão pela fila única que já existe.
6. **`estado-da-ponte.ts`**: único dado que vai para a tela: `{ comInternet, enviando, aguardando, recusadas, dadosAte, naoConseguiuEnviar }`. Canal novo na ponte fixa (`sincronizacaoMudou`), nada de banco nem token. A tela só será ligada na 5.7.
7. **Orquestração em `index.ts`**: o motor só liga com o banco aberto e sessão válida; para ao sair/bloquear; ordens `apagarDados` e bloqueio são da 5.8 (aqui só ficam num ponto de extensão que para o motor).

## Fora desta tarefa

Telas (5.7), prazo offline e quarentena (5.8), fotos (5.9), simulador de rede e duas pontas reais (5.12), atualização com envios aguardando (5.13). A ponte e o ponto de extensão deixam esses encaixes prontos.

## Testes primeiro (Vitest, SQLCipher real, servidor falso em `node:http`)

O servidor falso fala o formato real (campos copiados do controller da 5.4/5.5) e é scriptável: respostas, atrasos, queda **depois de "gravar" e antes de responder**.

| Grupo | Casos |
| --- | --- |
| Aplicação do pull | fora de ordem; repetida; `version` igual/menor ignorada; lápide; provisório substituído pelo do servidor; coluna ou tabela desconhecida recusa o lote inteiro; nunca custo nem coluna fora da lista |
| Atomicidade | cursor e lote juntos; falha injetada no meio do lote não deixa linha nem cursor; processo morto (reuso do teste de queda da 5.2) |
| Outbox | operação e provisória na mesma transação (falha injetada: nenhuma das duas); JSON exato reenviado byte a byte; ordem preservada; disco cheio |
| Push | cada resultado; `RECUSADO` desfaz o saldo; `NAO_PROCESSADO` mantém ordem e não pula; 5 falhas seguidas sinalizam sem pular; resposta perdida e reenvio dá `JA_RECEBIDO` sem duplicar; `413` reduz a metade; `429` respeita espera; lote nunca passa de 50/512 KB |
| Pendentes | alteração de cliente aguardando sobrevive a pull que traz versão nova do cliente; some só quando enviada |
| Ciclo e agenda | um ciclo por vez (disparos simultâneos viram um); push antes do pull; espera crescente com teto de 5 min e variação; 2 s agrupa gravações; sem internet não chama as rotas; sessão vencida renova uma vez |
| Época e conferência | `CURSOR_INVALIDO` mantém provisórios e outbox; conferência só com `temMais = false`; diferença passageira não recarrega; persistente avisa e recarrega a tabela |
| Ponte | só o estado documentado atravessa; teste de varredura: nenhum token nem caminho de banco no que sai |
| Barreira | `npm run barreira:varrer` continua verde (nada importado da dona) |

## Decisões que tomo por padrão seguro (digam se discordam)

- A resposta do push não traz a cópia do servidor; **a cópia vem pelo pull seguinte** (o ciclo já faz push e depois pull). Entre os dois, a provisória continua valendo na tela.
- Recusa e conflito ficam na outbox até o "Entendi" (campo `entendido_em`, já no schema); `ENVIADO` sai depois de 30 dias numa limpeza do próprio motor.
- Sem migração local nova se o schema da 5.2 bastar; se faltar coluna, migração 2 (a 1 não se edita).
- Hora local do envio e a maior hora vista são gravadas em `estado_sync` já aqui (insumo da 5.8), sem regra de bloqueio ainda.

## Pronto quando

Testes acima verdes no CI (job `frontend`), lint, typecheck e barreira verdes, glossário sem termo proibido, `docs/PROGRESSO.md` e `docs/progresso/fase-5.md` atualizados.
