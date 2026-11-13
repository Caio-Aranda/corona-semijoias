# Fase 6: devolução, troca e garantia (detalhe das tarefas)

O plano está em [fase-6-plano.md](fase-6-plano.md); o desenho, no [ADR-0014](../decisoes/ADR-0014-devolucao-troca-e-garantia.md).

## Tarefa 6.2: garantia e parâmetros

**O que entrou**

- Migração `V035__garantia_e_parametros.sql`: coluna `valor_dinheiro NUMERIC(14,2)` e tipo `DINHEIRO` em `configuracoes` (dinheiro vazio = "sem limite"); cinco chaves com os padrões seguros e `CHECK` de faixa: `prazo_arrependimento_dias` (7; 1 a 365), `prazo_defeito_dias` (30; 1 a 365), `limite_reembolso_semanal` (vazio; maior que 0), `valor_aprovacao_reembolso` (vazio; maior que 0) e `percentual_alerta_devolucao` (20; 1 a 100).
- Backend (`api/configuracoes`): `ChaveDeConfiguracao` com as cinco chaves, `Configuracoes.dinheiroOuVazio`, `ServicoDeGarantia` (ler e mudar, auditando só o que mudou, ação `GARANTIA_ALTERADA`, com o valor antigo) e `GarantiaDaDonaController` (`GET` e `PUT /api/dona/configuracoes/garantia`, só ADMIN).
- Bloco `parametros` do pull e da resposta do envio: ganha `prazoArrependimentoDias` e `prazoDefeitoDias` (o limite semanal e o valor de aprovação ficam só no servidor).
- Corona Vendas: o ciclo guarda os dois prazos no estado local (só para a prévia; o servidor decide).
- Clientes da API regerados (`npm run api:clientes`).
- Especificação (v1.8): §7.3 e §9 com as decisões do ADR-0014 (venda não muda, peça para a loja, sem `itens_troca`, crédito, revisão, autorização de 7 dias).
- `docs/perguntas-para-a-dona.md`: seção 7 com D1 a D13 e o padrão seguro de cada uma.

**Testes (PostgreSQL de verdade, escritos antes do código):** `GarantiaTest` (padrões; mudança gravada com dinheiro em texto de duas casas; faixa recusada na API e no banco; auditoria com o valor antigo e sem registro quando nada muda; 403 do vendedor e 401 sem login; `parametros` do pull com exatamente as chaves combinadas) e `ConfiguracoesTest` (lista de chaves).

**Decisões que tomei:** uma rota só com os cinco valores; dinheiro vazio = sem limite (padrão de D3); `percentual_alerta_devolucao` inteiro de 1 a 100 (o plano não fixava a faixa); as perguntas à dona têm identificação própria D1 a D13 para não colidir com a numeração 1 a 27 (a D1 completa a pergunta 9).

**Não fiz (fica para as próximas tarefas):** tela da dona (6.9), uso dos limites (6.3 e 6.4), glossário (nenhum texto de tela novo nesta tarefa).

## Tarefa 6.3: devolução do cliente (à vista)

Tarefa crítica: plano em [plano-6-3-devolucao-do-cliente.md](plano-6-3-devolucao-do-cliente.md), aprovado no PR #125 (dúvidas Q1 a Q6 pelo padrão e ajustes 1 a 3).

**O que entrou**

- Migração `V036__devolucao_do_cliente.sql`: `devolucoes` (sincronizável; a situação muda, o conteúdo não) e `itens_devolucao` (sincronizável, imutável), com as travas no banco: a devolução só nasce de venda `CONCLUIDA` e copia dela cliente, vendedor e maleta; por linha da venda, a soma das devoluções não canceladas nunca passa da quantidade nem do valor vendidos (gatilho que trava a linha da venda, como a V024); peça com defeito nunca vai à maleta (`CHECK`); só as transições `ACEITA → CANCELADA` e `EM_REVISAO → CANCELADA | MANTIDA | NAO_ACEITA`; venda com devolução não cancelada não vira `ESTORNADA`. `pagamentos_cliente` ganha `devolucao_id`, `estorna_id` e o tipo `ESTORNO_REEMBOLSO` (positivo, só por "Cancelar devolução"); `movimentos_remessa` ganha `devolucao_id` (obrigatório em `DEVOLUCAO_CLIENTE`). A função de pertença da V032 ganhou o ramo de `devolucoes` (sem editar a V032).
- Backend (pacote novo `api/devolucoes`): `ValorDaDevolucao` (preço pago por unidade pelo acumulado de `Dinheiro.dividirEmParcelas`, fechando no total da linha mesmo com cancelamentos no meio; quando a divisão recusa, `Dinheiro.parteProporcional`, novo), `ServicoDeDevolucoes` (devolver e cancelar; travas maleta → venda, na ordem do estorno; prazo em dias de Brasília pelo motivo; fora do prazo: 409 na rota online e `EM_REVISAO` no modo de envio; peça boa de maleta com o vendedor volta ao saldo, a de maleta acertada e toda com defeito viram peça para a loja; reembolso integral na forma escolhida), `ConsultasDeDevolucoes` (a devolução com Dólar e Guarani pela cotação do dia dela, a lista do vendedor e as vendas com a peça lida) e as rotas `POST/GET /api/vendedor/devolucoes`, `GET /api/vendedor/devolucoes/{id}`, `GET /api/vendedor/devolucoes/vendas-com-a-peca`, `POST /api/vendedor/devolucoes/{id}/cancelar` e `POST /api/dona/devolucoes/{id}/cancelar`.
- Vendas: o estorno recusa venda com devolução (409 `VENDA_COM_DEVOLUCAO`); a venda ganha o campo `devolvido` e o "a receber" (da venda e do relatório do vendedor) desconta o devolvido; a conciliação "estoque da loja + maletas" (a do serviço e a do relatório da dona) conta `DEVOLUCAO_CLIENTE` (a peça para a loja continua como vendida até o acerto, dúvida Q5); na maleta do vendedor, a coluna "vendida" desconta a peça que voltou à maleta.
- `RegistroDeTabelas`: `devolucoes` e `itens_devolucao` com a regra "nada vai ao vendedor ainda" (`FALSE`, dúvida Q4).
- 11 códigos de erro novos, com o texto do glossário (parte 6, "Devolução do cliente"); especificação v1.9 (§7.3: peças pelo produto, "outro" exige observação, "defeito" exige peças com defeito); pacote `devolucoes` no filtro das duas pontas (`ci.yml` e `docs/ci.md`, ajuste 1); clientes da API regerados.

**Testes (PostgreSQL de verdade, escritos antes do código):** `DevolucaoDoClienteTest` (24: total e parcial; centavos com desconto que não divide exato, com cancelamento no meio e a linha de 12 peças por 0,18; acima do vendido, com a segunda trava no banco; seis devoluções da mesma linha ao mesmo tempo e devolução contra estorno ao mesmo tempo; prazo em Brasília contra UTC, defeito 30/31 dias, prazo mudado e modo de envio para revisão; destino da peça e revenda pelo preço congelado; maleta em acerto e em outro computador; cancelar, reenvio, só no dia e pela dona, peça já revendida; estorno recusado na rota e no banco; idempotência; venda a prazo; permissões 404/403/401; nada de custo; conciliação; imutabilidade; busca pela peça lida; pedido malformado; registro de tabelas e pull) e `ValorDaDevolucaoTest` (6, com 500 sequências aleatórias fechando no total).

**Decisões que tomei:** as do corpo do PR #125 ("Decisões que tomei"): nomes de tipos que não repetem nomes já usados (para o OpenAPI não renomear esquemas antigos); pedido sem `id` recebe um do servidor, como na venda; venda a prazo = soma dos pagamentos menor que o total; o computador é conferido sempre que a maleta está com o vendedor; a "vendida" da maleta do vendedor é líquida da devolução que voltou à maleta (para enviada − vendida − devolvida à dona continuar igual ao saldo); dois testes antigos acompanham a V036 (`DevolucaoDaMaletaTest`: `DEVOLUCAO_CLIENTE` solta é recusada; `PullDoVendedorTest`: 17 tabelas na conferência por resumo, que o Corona Vendas ignora quando não conhece); cabeçalho da especificação corrigido para a versão 1.9.

**O que a 6.7 e a 6.8 herdam (ajuste 2)**

- **6.7 (push e pull):** trocar o `FALSE` de `devolucoes` e `itens_devolucao` pelo filtro do vendedor (as devoluções dele; os itens pela devolução), com expansão e oráculo de dois vendedores; levar ao pull as colunas novas `pagamentos_cliente.devolucao_id` e `estorna_id` e `movimentos_remessa.devolucao_id` (hoje fora, porque o Corona Vendas recusa o lote com coluna desconhecida); tipos `DEVOLUCAO` e `CANCELAR_DEVOLUCAO` na outbox chamando `ServicoDeDevolucoes` com `ModoDaDevolucao.ENVIO`, a hora corrigida e `horaAjustada`.
- **6.8 (Corona Vendas):** levar `devolucoes` e `itens_devolucao` ao banco do computador; o "a receber" local precisa descontar o devolvido (o servidor já desconta); o app hoje mostra `ESTORNO_REEMBOLSO` como pagamento e não conta `DEVOLUCAO_CLIENTE` como "vendida" nos números locais (`consultas-locais.ts`); a tela de devolução lê o código e usa `vendas-com-a-peca`.
- Até a 6.7 e a 6.8, nenhuma tela grava devolução. Se alguém gravar pela API, o computador do vendedor recebe o reembolso e o movimento da maleta (o saldo fica certo), mas mostra a venda com valor a receber.

**Não fiz (fica para as próximas tarefas):** autorização, revisão da dona, limites e taxa de devolução (6.4); troca e crédito (6.5); devolução de venda a prazo (6.6, até lá 409); outbox, push e filtro do pull (6.7); telas (6.8 e 6.9); relatórios de devolução e a conciliação com peças para a loja (6.10); comissão e acerto (Fase 7, que também precisa recusar cancelar devolução cuja peça já entrou num acerto).

## Correção: troca de computador

Correção crítica (issue #127, PR #128): o estresse das duas pontas com a semente `43338020207` perdia uma venda e um cliente do vendedor que **troca de computador**. Plano e diagnóstico no PR, aprovados pela orquestradora.

**O que acontecia (em linguagem simples).** O computador antigo manda as vendas em lotes e, em cada lote, avisa quantas ainda faltam. No último lote ele avisa "não falta nada". Enquanto esse lote estava a caminho, o vendedor (que não sabia da troca) cadastrou um cliente e vendeu no computador antigo. O servidor, que só sabia do "não falta nada", respondeu "pode apagar". O computador obedeceu na hora, sem olhar que tinha duas coisas novas guardadas, e apagou tudo. As duas iam para a quarentena, mas a quarentena é só do computador bloqueado e recusou o computador trocado; o apagar seguiu, e elas sumiram sem registro. Havia ainda uma segunda brecha: depois da ordem de apagar, as telas continuavam gravando até o banco fechar, e o que entrasse nesse tempo também se perdia.

**O defeito era do Corona Vendas**, não do servidor (que respondeu certo com o que recebeu) nem do teste (as duas operações tinham sido aceitas pelo app).

**O que mudou (só o Corona Vendas; a API e a quarentena não mudaram)**

- `sync/ciclo.ts`: com `apagarDados`, o app só obedece se a fila do computador está zerada naquele instante, logo depois de aplicar a resposta. Se ainda há algo aguardando, ignora a ordem daquela resposta, registra "ordem de apagar adiada: N aguardando" e manda o resto; a ordem volta no último lote. A conferência e a passagem para "desligando" rodam no mesmo trecho síncrono, sem `await` no meio.
- `montagem.ts`, `servico-de-sessao.ts` e `sync/operacoes-locais.ts`: a partir do "desligando" (troca ou bloqueio), nenhuma gravação nova entra; a tela recebe "Este computador não pode mais registrar vendas. Fale com a dona." (glossário, parte 6).
- ADR-0013: linhas datadas no item 13 ("só apaga com a fila local zerada; desligando, nada grava") e no item 14 (o bloqueio também recusa gravação durante o "desligando").

**Testes (escritos antes da correção, SQLCipher de verdade; nas duas pontas, PostgreSQL e API de verdade)**

- `ciclo.test.ts`: o servidor falso ganhou o modo "computador trocado" (responde `apagarDados` como o `ServicoDeEnvio`) e um gancho para gravar enquanto o envio está na rede. Novo: "operação gravada durante o envio vai no lote seguinte, e só apaga com a fila zerada" (vermelho antes: um envio só e a ordem obedecida com 2 aguardando). O teste antigo do `apagarDados` passou a responder `ACEITO` (com a fila zerada para); o irmão sem resposta para a operação não apaga.
- `montagem.test.ts`: com a ordem de apagar, cadastrar cliente e vender são recusados com o texto do glossário e a fila não cresce (vermelho antes: o cliente gravava).
- Duas pontas, `varios-aparelhos` cenário **6b**: o proxy ganhou `antesDeRepassar` e segura o 1º envio do computador antigo (com "nada mais aguardando"); nesse meio-tempo a tela cadastra um cliente e vende. No fim, as duas vendas e o cliente estão no servidor, nada foi à quarentena, o antigo apagou tudo e a afirmação final passa **sem `perdaAceita`**. Sem a correção, falha (a venda não chega ao servidor).
- Estresse curto (PT1M): a semente `43338020207` rodou **5 vezes** depois da correção, todas verdes (antes, falhava 1 em 4), e mais 5 sementes seguidas, `43338020208` a `43338020212`, todas verdes (cada rodada com uma troca de computador, cerca de 2 minutos).

**Riscos que ficam:** computador trocado com uma operação que nunca se resolve (presa em `NAO_PROCESSADO`) nunca apaga; é o lado seguro, e a dona continua vendo "o computador antigo avisou N vendas aguardando envio". O vendedor que tenta vender no computador antigo depois da ordem recebe a recusa em vez de uma venda que sumiria.

**Não fiz:** nada na API nem na quarentena (aceitar computador trocado na quarentena seria um segundo caminho de envio, contra o item 14); nenhuma migração.
