# Plano da tarefa 9.1: servidor e sincronização do consignado ao cliente

Issue #222. Decisões: [ADR-0016](../decisoes/ADR-0016-consignado-ao-cliente.md). Especificação: §23. Prazo da leva: sexta, 20/11/2026.

A fase CÓDIGO da 9.1 implementa **só o servidor** (migrações, serviços, rotas, sync, OpenAPI, `npm run api:clientes`) com os testes. As seções "Lado local" e "Para a 9.2/9.3/9.4" existem para que essas tarefas só implementem, sem decidir de novo.

## 1. Migrações (servidor)

Confirmar o próximo número livre na hora do código (hoje a última é a V048). Nenhuma migração publicada é editada.

| Migração | Conteúdo |
| --- | --- |
| `V049__clientes_do_consignado.sql` | Colunas novas de `clientes` (ADR item 4) com `CHECK` da comissão 0–40 e "indicado por: no máximo um"; gatilho `clientes.vendedor_responsavel_id` → `saidas_do_vendedor` (se ainda não existir); configuração `acerto_do_vendedor_visivel = false`. |
| `V050__semanas_e_notas_do_consignado.sql` | `semanas_vendedor`, `numeros_da_semana` (só servidor), `notas_consignado`, `itens_nota_consignado`, `reposicoes_nota`, `trocas_nota`, `remarcacoes_nota`, com `aplicar_convencoes_sincronizavel` (imutável onde o ADR diz), gatilhos de proteção (nota: só `situacao` e `data_cobranca`; semana: só `situacao` e `fechada_em`), índices únicos do ADR itens 2 e 3. |
| `V051__acerto_e_recibo_do_consignado.sql` | `acertos_nota`, `itens_acerto_nota`, `recibos_consignado`, `formas_recibo_consignado`, `aplicacoes_recibo`, `alertas_consignado` (só servidor); gatilho de estorno (só `estornado_em`, `estorno_id`, `motivo_estorno` mudam, uma vez); único parcial de acerto ativo por nota; `CHECK` das formas. |
| `V052__maleta_no_consignado.sql` | `movimentos_remessa`: tipos `CONSIGNADO_SAIDA`/`CONSIGNADO_RETORNO`, colunas `nota_id`/`acerto_nota_id`, regra de sinal nova (drop/add das constraints da V025/V038); `remessas.status` ganha `FECHADA`; gatilho que recusa `CONSIGNADO_SAIDA` em maleta fora de `ENVIADA`/`CONFERIDA` e aceita `CONSIGNADO_RETORNO` também em `FECHADA`; `fechamentos_maleta`, `leituras_fechamento_maleta`. |

Todas as colunas de dinheiro `NUMERIC(14,2)`; percentuais `NUMERIC(5,2)`; datas `timestamptz` (UTC) e `date` para Dt. Cobrança.

## 2. Servidor: pacote novo `consignado`

`backend/src/main/java/br/com/coronasemijoias/api/consignado/`:

| Classe | Papel |
| --- | --- |
| `CalculoDoAcertoDaNota` | Puro, sem banco: itens DX/DV/VD, valores, comissão HALF_UP sobre o total, bruto, total a receber, ficou devendo. Usa `Dinheiro` (ADR-0006). |
| `AplicacaoDePagamento` | Puro: dado débitos ordenados (mais antigo primeiro) e um valor, devolve as aplicações e a sobra "a aplicar". |
| `SituacaoDaNota` | Puro: situação a partir de acerto ativo, remarcações e saldo. |
| `ServicoDeSemanas` | `SEMANA_NOVA` (abre e fecha a anterior, trava o vendedor). |
| `ServicoDeNotas` | `NOTA_CONSIGNADO`, `REPOSICAO_NOTA`, `TROCA_NA_NOTA`, `REMARCAR_NOTA`: confere maleta do vendedor, preço = `preco_remessa`, saldo (alerta se faltar), semana, número; grava itens e movimentos. |
| `ServicoDeAcertosDaNota` | `ACERTO_NOTA` e `ESTORNO_ACERTO_NOTA`: trava o cliente, recalcula, grava acerto, itens, retornos, recibo e aplicações; reavalia situações; alertas. |
| `ServicoDeRecibos` | `PAGAMENTO_CONSIGNADO` e `ESTORNO_RECIBO`, e o "a aplicar" no débito novo. |
| `ServicoDeAlertasDoConsignado` + `FonteDeAvisosDoConsignado` | Grava e lista alertas; avisos da dona. |
| `ConsultasDoConsignado` | Leituras da dona: notas por vendedor/semana/situação, detalhe da nota (itens, acerto, recibos, aplicações), situação do cliente, totais da semana. |
| `ServicoDoFechamentoDaMaleta` + `ConsultaDoFechamentoDaMaleta` | ADR item 14. |
| `FotosDosClientes` | Upload/download com conferência do hash, `ArmazenamentoDeArquivos` em `clientes/`. |
| `ConciliacoesDoConsignado` | ADR item 17, no padrão de `ConciliacoesDaFase7`. |

Nenhuma regra de negócio em `OperacoesDoVendedor`: ele só lê, resume e chama os serviços (como hoje).

## 3. Rotas

**Do vendedor** (perfil VENDEDOR, só os dados dele; tudo de escrita é pela outbox):

- `POST /api/vendedor/sync/push`: tipos novos (ADR item 13).
- `GET /api/vendedor/sync/pull`: tabelas novas e `clientes` com o filtro novo.
- `PUT /api/vendedor/clientes/{id}/fotos/{tipo}` e `GET /api/vendedor/clientes/{id}/fotos/{tipo}`.

**Da dona** (perfil ADMIN):

- `GET /api/consignado/vendedores/{vendedorId}/semanas` e `GET /api/consignado/semanas/{semanaId}` (Pedidos + Totais da semana).
- `GET /api/consignado/notas?vendedorId&semanaId&situacao&clienteId&texto` e `GET /api/consignado/notas/{id}`.
- `GET /api/consignado/clientes/{id}/situacao` (pendência atual, notas, maletas com peças dele).
- `GET /api/clientes/{id}/fotos/{tipo}`.
- `POST /api/maletas/{id}/fechamento`, `POST …/fechamento/leituras`, `GET …/fechamento`, `POST …/fechamento/concluir`.
- `GET /api/configuracoes/telas` (inclui `acertoDoVendedorVisivel`).
- Relatório "Fechamento da maleta" no `MotorDeRelatorio` (`RelatoriosDeMaletaController`).
- `GET /api/dona/consignado/alertas` (lista e "visto").

Tudo com `@Operation(operationId=…)` em português, OpenAPI regerado e `npm run api:clientes`.

## 4. Formato das operações (outbox, formato 1)

Campos em ordem fixa para o resumo; dinheiro com 2 casas; listas ordenadas por id.

```text
SEMANA_NOVA          { id, numero, abertaEm }
NOTA_CONSIGNADO      { id, semanaId, sequencia, clienteId, notaOrigemId?, dataCobranca, saidaEm,
                       itens[{ id, produtoId, remessaId, quantidade, precoUnitario, movimentoId }], totalDxVisto }
REPOSICAO_NOTA       { id, notaId, feitaEm, itens[{ id, produtoId, remessaId, quantidade, precoUnitario, movimentoId }] }
TROCA_NA_NOTA        { id, notaId, trocadaEm,
                       volta{ itemId, produtoId, remessaId, movimentoId }, entra{ itemId, produtoId, remessaId, precoUnitario, movimentoId } }
REMARCAR_NOTA        { id, notaId, dataCobranca, remarcadaEm }
ACERTO_NOTA          { id, notaId, semanaId, acertadoEm, comissaoPercentual,
                       devolvidos[{ itemAcertoId, produtoId, remessaId, quantidade, movimentoId }],
                       recibo?{ id, sequencia, formas[{ forma, valor }] },
                       visto{ valorVd, comissao, bruto, pendenciasAnteriores, totalAReceber, totalPago, ficouDevendo } }
PAGAMENTO_CONSIGNADO { id, clienteId, semanaId, sequencia, pagoEm, formas[{ forma, valor }], pendenciaVista }
ESTORNO_ACERTO_NOTA  { id, acertoId, motivo, estornadoEm }
ESTORNO_RECIBO       { id, reciboId, motivo, estornadoEm }
CLIENTE_NOVO v2 / CLIENTE_ALTERADO v2: campos novos do ADR item 4 (inclui os três hashes de foto)
```

`devolvidos` traz só as linhas com DV > 0; o servidor completa as demais com DV 0 a partir dos itens da nota.

## 5. Testes primeiro (ordem de escrita)

Todos no `./mvnw verify` (Testcontainers). Os números dos casos 1 e 2 ficam em `packages/shared/exemplos/consignado-acerto.json`, lido pelos testes Java e TypeScript.

1. `CalculoDoAcertoDaNotaTest` — nota `0000064-21`: Itens VD 11; VD 2.310,00; Venda % 43,02; Comissão 693,00; Bruto 1.617,00; pendência 498,10; Total a Receber 2.115,10; Pago 1.770,00 (1.470 + 300); Ficou devendo 345,10. Mais: 0%, 40%, 40,01% recusado, −1% recusado, arredondamento HALF_UP (VD 0,05 × 30%).
2. `AplicacaoDePagamentoTest` — 3 momentos: (400, pago 300) → 100; (500 + 100, pago 550) → débito 1 quitado, débito 2 com 50; pagamento 50 → 0. Três débitos com pagamento parcial; sobra "a aplicar".
3. `SituacaoDaNotaTest` — as quatro situações e as passagens (Pendente → Fechado sozinha; remarcada → acerto → Fechado/Pendente; estorno → volta).
4. `ServicoDeNotasTest` (banco) — nota com duas maletas; bipe fora da maleta recusado; sem saldo aceito + alerta; preço diferente do `preco_remessa` (vale o da maleta + alerta); semana fechada recusada; número repetido recusado; Reposição na semana e de semana anterior; troca; remarcação; saldo da maleta (disponível/com clientes) depois de cada passo.
5. `ServicoDeAcertosDaNotaTest` (banco) — caso `0000064-21` de ponta a ponta com a pendência de `0000063-32` (nota 63-32 vira Fechado, 64-21 Pendente 345,10, recibo na semana 65); DV > DX recusado; segundo acerto recusado; estorno desfaz retornos, recibo e aplicações e libera "a aplicar"; acerto de nota de semana fechada.
6. `ServicoDeRecibosTest` (banco) — Lançar pagamento abate o mais antigo; acima do devido aceito + alerta + "a aplicar"; estorno reabre a pendência e volta a nota a Pendente.
7. `SyncDoConsignadoTest` — cada tipo pelo push; repetição → `JA_RECEBIDO`; mesmo id outro conteúdo → recusado; `NAO_PROCESSADO` com dependência ausente e aplicado no envio seguinte; **concorrência**: mesma operação em duas threads (um aplica, outro `JA_RECEBIDO`), dois `PAGAMENTO_CONSIGNADO` do mesmo cliente em duas threads (Σ aplicações ≤ débitos, ordem respeitada); formato 1 de `CLIENTE_NOVO` ainda aceito.
8. Pull — estender o oráculo de dois vendedores (ADR-0013 item 5) às tabelas novas e ao filtro novo de `clientes`; cliente transferido gera saída; nenhuma coluna proibida (`custo`, `*_por`, hashes de outro vendedor); fotos nunca no pull.
9. `FotosDosClientesTest` — hash errado recusado; repetir é inofensivo; outro vendedor recebe 404; foto antes do cadastro → 409.
10. `FechamentoDaMaletaTest` — enviadas, devolvidas, vendidas, com clientes, bipadas, diferença, rateio com arredondamento; concluir grava `DEVOLUCAO_DONA` e `FECHADA`; retorno depois do fechamento aceito; saída depois do fechamento recusada.
11. `ConciliacoesDoConsignadoTest` — cada conciliação acusa um defeito plantado e passa no caso limpo.
12. `ConfiguracaoDasTelasTest` — `acertoDoVendedorVisivel` falso por padrão; rotas antigas continuam respondendo.

## 6. Ordem da fase CÓDIGO (commits)

1. `test:` casos 1–3 + arquivo de exemplos; `feat:` cálculos puros.
2. `feat:` V049–V050 + semanas e notas (testes 4).
3. `feat:` V051 + acertos e recibos (testes 5, 6).
4. `feat:` sync: formatos, `OperacoesDoVendedor`, `RegistroDeTabelas`, filtro de clientes (testes 7, 8).
5. `feat:` fotos do cliente (teste 9).
6. `feat:` V052 + Fechamento da maleta, rota e relatório (teste 10).
7. `feat:` leituras da dona, alertas, configuração das telas, conciliações (testes 11, 12).
8. `chore:` OpenAPI + `npm run api:clientes`; `docs:` PROGRESSO, glossário se preciso; `python -m graphify update .`.

Local antes do `gh pr ready`: `npm run api:test`, `npm run lint`, `typecheck`, `format:check`.

## 7. Lado local do Corona Vendas (para a 9.2 só implementar)

- **Migração 9 do SQLite** (`apps/seller-app/src/main/banco/migracoes.ts`): espelho das 11 tabelas novas e das colunas novas de `clientes`; locais `rascunhos_nota`, `itens_rascunho_nota`, `contadores_da_semana` (`semana_id`, `proxima_sequencia`), `fila_fotos_cliente` (`hash`, `cliente_id`, `tipo`, `caminho`, `tentativas`, `enviada_em`); apaga clientes de outro `vendedor_responsavel_id`; acrescenta os `CONSIGNADO_*` ao saldo local da maleta.
- **Operações locais** (`sync/operacoes-locais.ts` e um `operacoes-do-consignado.ts` novo): cada ação grava, numa transação, o provisório no espelho + a linha da outbox + o avanço do contador. O número `semana-sequência` sai do contador local.
- **Camada de pendentes**: provisórios substituídos pela versão do servidor no pull; recusados mostram o motivo (PR #220) e voltam o espelho.
- **Cálculo** em `packages/shared` (`consignado/calculo-do-acerto.ts`, `aplicacao-de-pagamento.ts`, `situacao-da-nota.ts`), as mesmas fórmulas do servidor, testadas com o mesmo `consignado-acerto.json`. `packages/shared` já passa na barreira.
- **Fotos**: câmera ou arquivo → JPEG → SHA-256 → arquivo criptografado (`fotos-clientes/<hash>.bin`, AES-256-GCM, chave derivada da chave do banco) → fila de envio (três por vez, recomeça na queda).
- **Desligar**: tirar do menu e das rotas da tela Venda, Fiado, Devolução, Troca antiga, Crédito, Pedir acerto e a Conferência obrigatória; nada é apagado do banco local.
- **Testes da 9.2**: os casos 1–3 no TypeScript; ciclo offline → push → pull com o `ServidorFalso`; teste de duas pontas (`testes-sync`) com um cenário de nota, acerto com pendência, pagamento e estorno sem internet e rede ruim.

## 8. Para a 9.3 e a 9.4

- **9.3 (relatórios §23.11):** usa `ConsultasDoConsignado` e a consulta do Fechamento; no Corona Vendas, os relatórios saem do banco local (mesmas fórmulas de `packages/shared`). Todos com DX/DV/VD e Itens DX/DV/VD, Real com Dólar e Guarani pela cotação do lançamento.
- **9.4 (Corona Gestão):** telas do Fechamento da maleta (rotas do item 3), visão das notas/semanas/clientes do consignado, avisos dos alertas, esconder o acerto antigo pela `GET /api/configuracoes/telas`, foto e documento do cliente.

## 9. Dúvidas

As do ADR-0016 (D1–D9). Enquanto a orquestradora não decidir, valem os padrões propostos lá.
