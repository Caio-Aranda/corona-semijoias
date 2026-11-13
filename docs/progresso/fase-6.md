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

## Tarefa 6.4: autorização, revisão e antifraude

Tarefa crítica: plano em [plano-6-4-autorizacao-e-antifraude.md](plano-6-4-autorizacao-e-antifraude.md), aprovado no PR #126 (dúvidas P1 e P3 a P13 pelo padrão; P2 com o ajuste da orquestradora: uma coluna booleana por motivo de revisão, nada de texto separado por vírgula).

**O que entrou**

- Migração `V037__autorizacao_e_revisao.sql`: `autorizacoes_excecao` (sincronizável; só a situação e a decisão mudam) e `itens_autorizacao` (sincronizável, imutável; dúvida P1), com as travas no banco: o pedido nasce `PEDIDA` de venda `CONCLUIDA`, com o vendedor e o cliente dela; `CHECK`s de "tudo ou nada" da decisão; só `PEDIDA → APROVADA | NEGADA` e `APROVADA → USADA | VENCIDA`, e `USADA` só com a devolução que a usa já gravada; a linha pedida é da venda do pedido. Em `devolucoes`: a chave estrangeira da autorização e o índice único (uma devolução por autorização, para sempre: dúvida P11); o gatilho de inserção (recriado inteiro) confere que a autorização é da mesma venda, do mesmo vendedor e do mesmo motivo e está aprovada e válida no dia de Brasília da devolução; as cinco colunas booleanas de motivo (`revisao_fora_do_prazo`, `revisao_acima_do_limite_semanal`, `revisao_acima_do_valor_de_aprovacao`, `revisao_autorizacao_invalida`, `revisao_reembolso_maior_que_pago`) com `CHECK` (aceita sem nenhum; em revisão, mantida ou não aceita com pelo menos um), `revisada_em`, `revisada_por` e `observacao_revisao` (obrigatória no "não aceita"); as `EM_REVISAO` da 6.3 ganham `revisao_fora_do_prazo` antes dos `CHECK`s; o gatilho de alteração (recriado inteiro) mantém tudo o que a V036 protegia, os motivos nunca mudam e os dados da revisão só mudam com `EM_REVISAO → MANTIDA | NAO_ACEITA`. A função de pertença ganhou o ramo de `autorizacoes_excecao`. Nenhuma migração publicada foi editada.
- Backend (`api/devolucoes`): `RegrasDaDevolucao` (a conferência e o cálculo que a devolução e o pedido dividem, sem mudar o comportamento da 6.3); `ServicoDeAutorizacoes` (pedir, com o tipo e o valor do servidor, um pedido aberto por venda, e o pedido desnecessário com 409 na rota online e gravado como fora do prazo pelo envio; aprovar com `valida_ate` = dia de Brasília + 7; negar com motivo; `marcarVencidas`); `AgendaDasAutorizacoes` (todo dia às 03:10 UTC, com `try/catch`, desligada nos testes); `ServicoDeDevolucoes.devolver` com a autorização (válida dispensa prazo e valor, fica ligada e passa a `USADA`; inválida dá 409 na rota e revisão sem consumir no envio; de outro vendedor, 404 nos dois), o valor de aprovação (409 `DEVOLUCAO_PRECISA_DE_AUTORIZACAO` na rota, revisão no envio), o limite da semana (semana cadastrada ou segunda a domingo de Brasília; revisão nos dois modos, mesmo com autorização) e o reembolso maior que o pago (revisão); a trava do vendedor (`pg_advisory_xact_lock`) antes de maleta, venda e autorização, com a ordem comentada no serviço; `manter` e `naoAceitar` (travas maleta → venda → devolução; nada muda além da situação e da revisão); `TaxaDeDevolucao` (28 dias de Brasília, conta exata, vendedores ativos) e `FonteDeAvisosDasDevolucoes` (`DEVOLUCOES_PARA_AUTORIZAR`, `DEVOLUCOES_PARA_REVISAR` e `MUITAS_DEVOLUCOES`, calculados na hora, só com a porcentagem); `ConsultasDeAutorizacoes` e `ConsultasDeDevolucoes` com `motivosRevisao`, `autorizacaoId`, `revisadaEm`, `observacaoRevisao` e `pendenciaDoVendedor`.
- Rotas: `POST/GET /api/vendedor/autorizacoes` e `GET /api/vendedor/autorizacoes/{id}`; `POST /api/vendedor/devolucoes` aceita `autorizacaoId`; `GET /api/dona/autorizacoes`, `GET /api/dona/autorizacoes/{id}`, `POST /api/dona/autorizacoes/{id}/aprovar` e `/negar`; `GET /api/dona/devolucoes`, `GET /api/dona/devolucoes/{id}`, `POST /api/dona/devolucoes/{id}/manter` e `/nao-aceitar`.
- `RegistroDeTabelas`: `autorizacoes_excecao` e `itens_autorizacao` com a regra `FALSE` (nada vai ao vendedor até a 6.7); as colunas novas de `devolucoes` entram na lista dela (sem `revisada_por`, como `registrada_por`). 12 códigos de erro novos com o texto do glossário (parte 6, "Autorização, revisão e antifraude"); especificação v1.10 (§7.3 e §9); clientes da API regerados.

**Testes (PostgreSQL de verdade, escritos antes do código):** `AutorizacaoDeDevolucaoTest` (15: pedido fora do prazo com centavos que não dividem e devolução parcial antes, cotação do dia e auditoria; reenvio igual e diferente; um pedido aberto por venda; desnecessário e acima do valor; acima do que pode voltar, a prazo, estornada e malformado; aprovar às 23:30 de Brasília contando pelo dia de Brasília, repetir e negar depois; negar sem e com motivo e pedir de novo; uso com menos peças, não confere (mais peças, outra peça, outro motivo, outra venda), não aprovada, negada, usada, cancelar não devolve; validade no 7º e no 8º dia sem a agenda, e o envio com a vencida vira revisão sem consumir; agenda só nas aprovadas com data passada; duas devoluções ao mesmo tempo com a mesma autorização; o banco recusa a usada sem devolução e a segunda devolução; permissões 403, 401 e 404; nada de custo; imutabilidade e transições proibidas) e `AntifraudeDaDevolucaoTest` (16: limite vazio e no valor exato, outro vendedor não conta; acima na rota e no envio com reembolso e movimento; cancelada não conta; segunda a domingo com a virada de domingo às 23:30; semana cadastrada; limite mesmo com autorização; duas devoluções do mesmo vendedor ao mesmo tempo, em três rodadas, sempre uma em revisão; valor de aprovação na rota, no envio e com os dois tipos de autorização; reembolso maior que o pago; os dois motivos em ordem fixa; Manter e Não aceitar sem mexer em nada, repetição, os 409, cancelar depois e o banco recusando; taxa de devolução com 20% exato, 0,01 a mais, percentual mudado pela rota, 29º e 28º dia, cancelada, estornada, sem venda e vendedor desativado; avisos no singular e no plural sumindo ao decidir; conciliação; registro de tabelas e pull). `PullDoVendedorTest` passa a contar 19 tabelas; `DevolucaoDoClienteTest` roda sem mudança (o pedido ganhou um construtor sem a autorização).

**Decisões que tomei:** as do corpo do PR #126 ("Decisões que tomei").

**O que a 6.7, a 6.8, a 6.9 e a 7.5 herdam**

- **6.7 (push e pull):** trocar o `FALSE` de `autorizacoes_excecao` e `itens_autorizacao` pelo filtro do vendedor (os pedidos dele; os itens pelo pedido) e levar as colunas novas de `devolucoes` junto com a tabela; o tipo `PEDIDO_AUTORIZACAO` na outbox chamando `ServicoDeAutorizacoes.pedir` com `ModoDaDevolucao.ENVIO` (o desnecessário é gravado como fora do prazo); a devolução pelo envio já recebe `autorizacaoId` e já vira revisão em vez de recusa.
- **6.8 (Corona Vendas):** botão **Pedir autorização à dona** quando passou do prazo; mostrar a situação do pedido e o "vale até"; mandar `autorizacaoId` na devolução. Sem internet o app não sabe do limite da semana nem do valor de aprovação (decisão da 6.2): a devolução acima deles chega pelo envio e vira revisão.
- **6.9 (Corona Gestão):** listas de pedidos (padrão: esperando a dona) e de devoluções para revisar (padrão: em revisão), com Aprovar, Negar, Manter e Não aceitar; os três avisos já chegam em `GET /api/dona/avisos`.
- **7.5 (acerto):** a pendência do vendedor é a devolução `NAO_ACEITA` (`pendenciaDoVendedor` na consulta) ainda não cobrada; a 7.5 cria `pendencias_acerto` e passa a ler essas devoluções.

**Não fiz (fica para as próximas tarefas):** crédito do cliente e o motivo de revisão do crédito (6.5, por migração nova); venda a prazo (6.6); outbox, push e filtro do pull (6.7); telas (6.8 e 6.9); devolução registrada pela dona (6.9); relatórios de autorizações e de taxa (6.10); `pendencias_acerto` e a cobrança no acerto (7.5); cancelar pedido de autorização pelo vendedor.

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
- Estresse curto (PT1M): a semente `43338020207` rodou **6 vezes** depois da correção (5 antes e 1 depois de mesclar a 6.4), todas verdes (antes, falhava 1 em 4), e mais 5 sementes seguidas, `43338020208` a `43338020212`, todas verdes (cada rodada com uma troca de computador, cerca de 2 minutos).

**Riscos que ficam:** computador trocado com uma operação que nunca se resolve (presa em `NAO_PROCESSADO`) nunca apaga; é o lado seguro, e a dona continua vendo "o computador antigo avisou N vendas aguardando envio". O vendedor que tenta vender no computador antigo depois da ordem recebe a recusa em vez de uma venda que sumiria.

**Não fiz:** nada na API nem na quarentena (aceitar computador trocado na quarentena seria um segundo caminho de envio, contra o item 14); nenhuma migração.

## Correção: pull perde cliente

Correção crítica (issue #130, PR #133): no CI do PR #129, o estresse das duas pontas falhou dizendo que um cliente que estava no servidor faltava em dois computadores (`V0` e `V1`, "falta no computador (servidor: versão 1)"). A mesma semente passava em outras rodadas. Diagnóstico e plano no PR, aprovados pela orquestradora.

**O que acontecia (em linguagem simples).** Nada se perdia: o defeito era **do teste**, na conferência do fim. Para conferir, o teste ligava a internet de um computador de cada vez, na ordem, esperava ele enviar e receber, e passava para o próximo. Se um computador do fim da fila ainda tinha um cliente guardado (estava sem internet ou esperando depois de um erro da rede), ele só enviava na vez dele, e os que vieram antes já tinham recebido pela última vez. O teste então cobrava deles um cliente que o servidor ainda não tinha quando eles receberam. Passava só quando a volta automática do programa caía por acaso no meio. É exatamente o que a issue mostra: o cliente foi criado pelo `V2` e faltou só em `V0` e `V1`, os dois primeiros da fila. No computador de verdade, o cliente chegaria no ciclo seguinte.

**Evidência.** O registro do CI (o último envio do `V2` com erro 502 dois segundos antes da conferência), a reprodução sempre vermelha (hoje o caso 5b) e o cursor de quem ficou sem o cliente **antes** da linha dele no servidor (o recebimento seguinte entrega; se o cursor tivesse passado por cima, nunca entregaria). O servidor (`ServicoDePull`, limite seguro do `sync_seq`) e o diretório de clientes estão certos; o `EstresseDoPullTest` confirma o lado do servidor.

**Dois achados a mais, também do teste.**

1. **A semana da dona passava do limite da API durante 1/3 do tempo.** O número da semana de teste era `segundos % 150000`, mas a API aceita até 100000. Nessas horas (a de 13/11/2026 ia de ~06:06 a ~20:00 UTC), toda maleta das duas pontas recebia 400 e o `sync-duas-pontas` falhava em **qualquer** PR. Agora começa entre 1 e 90000 (`1 + segundos % 90000`) e as seguintes voltam ao começo se passarem de 100000.
2. **`Aparelho.sincronizar` girava em falso.** Logo depois de um ciclo, a agenda do motor ainda está "rodando" por um instante; o pedido do teste nesse meio-tempo só marcava "pedido durante o ciclo", e o teste esperava, volta após volta, o ciclo que a própria agenda disparava (na reprodução, 52 s e 682 recebimentos numa vez só). Agora o teste cede a vez antes de pedir.

**O que mudou (só `testes-sync/`; o Corona Vendas e a API não mudaram)**

- `afirmacoes.ts` (`afirmarNoFim`): liga a internet de **todos** os computadores vivos de uma vez e dá voltas (cada um envia e recebe até zerar) até uma volta inteira em que o retrato do servidor não mudou (md5 de `tabela:id:versão` das 15 tabelas que vão ao vendedor), no máximo 5; se não parar, é falha com a contagem de linhas de cada volta. O log diz quantas voltas foram. Na falha, o relatório traz, para cada linha que faltou, o `sync_xid`/`sync_seq` dela, o envio no `sync_log` e o cursor e o último recebimento de cada computador. O oráculo não ficou mais frouxo: uma linha que o cursor pulou continua faltando, porque mais voltas só entregam o que está depois do cursor.
- `aparelho.ts` (`sincronizar`): `setImmediate` antes do `internetVoltou()`.
- `proxy-de-rede-ruim.ts`: a hora de cada pedido no histórico e no relatório.
- `dona.ts`: `semanaInicial` e `numeroDaSemana` dentro dos limites da API.
- `README.md` do `testes-sync`: a afirmação em voltas, o limite da semana e os casos 5b e 6b.

**Testes (escritos antes da correção; SQLCipher, PostgreSQL e API de verdade)**

- Duas pontas, `varios-aparelhos` caso **5b**: a Ana (relógio sem aceleração, para a volta automática dela não cair no meio) e a Bia; a Bia cadastra um cliente sem internet e a conferência do fim pega as duas, a Ana primeiro. Antes da correção: vermelho com a mesma mensagem da issue (`Ana: clientes:… falta no computador (servidor: versão 1)`, semente 1404852074), e a vez da Ana levou 109 s. Depois: verde em 5,8 s.
- `dona.test.ts` (sem API): a primeira semana cabe no limite em todo segundo de 200000 segundos seguidos (a conta se repete a cada 90000), com folga de 10000; a hora da issue dá uma semana válida; as seguintes cabem para qualquer contador; e os limites usados são os do `SemanasDaDonaController` (lidos do código da API). Antes da correção: vermelho.
- `proxy-de-rede-ruim.test.ts`: o histórico traz a hora de cada pedido, em ordem. Antes: vermelho.
- Os 5 arquivos de duas pontas (17 testes, com o estresse curto, semente 1405156511) verdes às 07:20 UTC de 13/11, dentro da janela em que a semana antiga derrubava toda maleta.

**Estresse curto (PT1M), 10 rodadas seguidas, uma de cada vez (jar da API sem mudança), todas verdes**, cada uma com uma troca de computador e cerca de 340 vendas, 110 clientes, 140 estornos e 45 quedas de internet:

| Rodada | Semente | Voltas da conferência do fim | Linhas nas 15 tabelas |
| --- | --- | --- | --- |
| 1 | 1405366384 | 1 | 1874 |
| 2 | 1405462216 | 1 | 1741 |
| 3 | 1405558951 | 1 | 1769 |
| 4 | 1405654906 | 1 | 1689 |
| 5 | 1405749256 | 1 | 1821 |
| 6 | 1405843925 | 1 | 1639 |
| 7 | 1405937922 | 1 | 1760 |
| 8 | 1406032559 | 1 | 1785 |
| 9 | 1406126210 | 1 | 1769 |
| 10 | 1406220141 | 1 | 1825 |

Mais a semente do CI que falhou na issue (`43360365851`, o número da rodada do CI): verde, 1 volta. Todas precisaram de 1 volta só. O plano esperava ver às vezes 2. Com a internet de todos ligada de uma vez, o que estava guardado sai logo, antes do primeiro retrato, e a primeira volta já acha o servidor parado. A volta extra fica como garantia para quando um envio chega depois disso (o caso que o 5b reproduz e a passada única deixava passar).

**`EstresseDoPullTest` (servidor, PostgreSQL de verdade), verde:** `PT20S` (202 transações confirmadas, 11553 linhas recebidas, 1063 registros conferidos), junto com o `EstresseDoPullComFiltroTest`, e uma rodada `PT2M` (3842 transações confirmadas, 105 transações longas, 39633 linhas recebidas, 20732 registros conferidos), semente 20261109.

**Riscos que ficam:** se um dia algum processo agendado da API gravar sem parar nas 15 tabelas durante o teste, a conferência do fim falha depois de 5 voltas (com a explicação); hoje nenhum grava nelas no ambiente de teste. A conferência ficou mais rápida (sem o giro em falso).

**Não fiz:** nada no `ServicoDePull`, no motor nem no aplicador do Corona Vendas (nada lá estava errado); nenhuma migração; o limite de 100000 da API ficou como está.

## Tarefa 6.5: crédito do cliente e troca

Tarefa crítica: plano em [plano-6-5-credito-e-troca.md](plano-6-5-credito-e-troca.md), aprovado no PR #129 (dúvidas T1 a T14 pelo padrão de cada uma). Começada por uma conta e terminada por outra (o limite de uso parou a primeira no passo 7); a fase CÓDIGO seguiu a "Ordem dos commits" do plano, um commit por passo.

**O que entrou**

- Migração `V038__credito_e_troca.sql`: `trocas` (venda de origem, devolução com destino `TROCA`, venda nova, cliente, vendedor, `diferenca`, `ATIVA | CANCELADA`), `creditos_cliente` (`ABERTO | UTILIZADO | CANCELADO`, com `valor_utilizado` sempre igual à soma dos usos) e `usos_credito` (uso e estorno do uso), imutáveis no conteúdo e com `REVOKE ALL ... FROM PUBLIC`; `vendas.troca_id` (chave `DEFERRABLE INITIALLY DEFERRED`, índice único); `devolucoes.valor_em_credito` (reembolso + crédito ≤ devolvido; a devolução de troca vai toda em crédito); forma `CREDITO` em `pagamentos_cliente` com `credito_id` obrigatório; `TROCA_ENTRADA` amarrada à devolução e `TROCA_SAIDA` à venda nova. Conferência no fim da transação (`CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED`): a devolução é de troca e da venda de origem, a venda nova aponta para a troca, mesmo cliente e vendedor, `diferenca = total(B) − valor_devolvido(A)`. `proteger_venda`, `recusar_estorno_com_devolucao` e `proteger_devolucao` recriadas inteiras (tudo o que a V022, a V036 e a V037 conferiam, mais as colunas novas); a venda e a devolução de uma troca só se desfazem com a troca já `CANCELADA`. Nenhuma migração publicada foi editada.
- Backend: `api/creditos` (`ServicoDeCreditos`: uso na venda do mesmo cliente e do mesmo vendedor, estorno do uso quando a venda é estornada, cancelamento, transferência do saldo inteiro pela dona para um crédito novo de outro vendedor; `ConsultasDeCreditos`) e `api/trocas` (`ServicoDeTrocas`: devolução de A com destino `TROCA` e venda de B na mesma transação, tudo ou nada, com as travas vendedor → maletas por `id` → vendas por `id` → devolução, troca e autorização → créditos por `id`; o valor de A vira `REEMBOLSO` forma `CREDITO` na venda A e `PAGAMENTO` forma `CREDITO` na venda B; a sobra fica de crédito em aberto e a diferença o cliente paga como numa venda comum, à vista ou a prazo; "Cancelar troca" desfaz tudo, pelo vendedor no dia e depois só pela dona; a conferência adiada que falhar no `COMMIT` vira 409 `TROCA_NAO_CONFERE`, nunca 500; `ConsultasDeTrocas`). `ServicoDeVendas` e `ServicoDeDevolucoes` ganharam o caminho interno da troca (as rotas e o envio de hoje seguem no caminho de sempre); a devolução de venda paga com crédito devolve **primeiro** o crédito (crédito novo) e só o resto em dinheiro; o estorno da venda de troca e o cancelamento da devolução de troca dão 409 (`VENDA_DE_TROCA`, `DEVOLUCAO_DE_TROCA`). A troca fica fora do limite da semana e do valor de aprovação, mas passa pelo prazo da garantia e pela autorização (6.4); a taxa de devolução conta a troca como devolução + venda.
- `TROCA_ENTRADA` e `TROCA_SAIDA` nas consultas de peça, na conciliação "estoque da loja + maletas" e na maleta do vendedor (`ServicoDeEstoque`, `ConsultasDeEstoqueECompras`, `ConsultasDaMaletaDoVendedor`).
- Rotas: `POST/GET /api/vendedor/trocas`, `GET /api/vendedor/trocas/{id}`, `POST /api/vendedor/trocas/{id}/cancelar`; `GET /api/vendedor/creditos` e `GET /api/vendedor/creditos/{id}`; `POST /api/vendedor/vendas` aceita a forma `CREDITO` com `creditoId`; `GET /api/dona/trocas`, `GET /api/dona/trocas/{id}`, `POST /api/dona/trocas/{id}/cancelar`; `GET /api/dona/creditos`, `GET /api/dona/creditos/{id}`, `POST /api/dona/creditos/{id}/transferir`.
- `RegistroDeTabelas`: `trocas`, `creditos_cliente` e `usos_credito` com a regra `FALSE` (nada vai ao vendedor até a 6.7); `vendas.troca_id`, `pagamentos_cliente.credito_id` e `devolucoes.valor_em_credito` fora das listas de colunas do pull. 15 códigos de erro novos com o texto do glossário; especificação v1.11 (§7.3 e §9) e linha datada no ADR-0014; clientes da API regerados; `trocas` e `creditos` no filtro `sync` do `ci.yml` e em [docs/ci.md](../ci.md).

**Testes (PostgreSQL de verdade, escritos antes do código):** `TrocaTest` (24: B mais cara, mais barata e do mesmo valor; diferença a prazo; várias peças com desconto que não divide exato; peça com defeito; A de maleta acertada e B da maleta nova; recusa no meio desfaz tudo; prazo em dias de Brasília; autorização; revisão pelo envio; fora do limite da semana e do valor de aprovação; "Cancelar troca" com tudo como era, vendedor só no dia, sobra já usada e A vendida de novo; idempotência; conta do vendedor = B − A; conciliações; permissões; nada de custo; imutabilidade, sinal dos movimentos e segundas travas; o banco recusa no `COMMIT` a troca que não fecha; pedido malformado) e `CreditoDoClienteTest` (11: uso em parte e até zerar; outro cliente, outro vendedor e pedido errado; reenvio da venda com crédito; transferência e venda com o mesmo crédito ao mesmo tempo, só uma passa; estorno devolvendo o crédito; devolução de venda paga com crédito devolvendo primeiro o crédito; transferência pela dona; cada vendedor só vê os próprios; nada de custo; imutabilidade e segunda trava do saldo; registro de tabelas sem nada ir ao vendedor). `PullDoVendedorTest` conta 22 tabelas; `DevolucaoDaMaletaTest` confere `TROCA_ENTRADA` e `TROCA_SAIDA` pelas amarras da V038.

**Pedidos da aprovação**

1. **Imutabilidade depois de recriar as funções:** rodaram de novo, todos verdes, `VendasDoVendedorTest` (V022, 20 testes, com `vendaItensPagamentosEMovimentosNaoSaoAlteradosNemApagados`), `DevolucaoDoClienteTest` (V036, 24, com `devolucaoEItensNaoSaoAlteradosNemApagados`) e `AutorizacaoDeDevolucaoTest` (V037, 15, com `autorizacaoEItensNaoSaoAlteradosNemApagados`), junto com os vizinhos `DevolucaoDaMaletaTest` (28), `TrocaTest` (24) e `CreditoDoClienteTest` (11): 122 testes; e depois a suíte inteira (`npm run api:test`).
2. **Conferência adiada traduzida para 409:** `TrocaTest.trocaQueNaoFechaNaConferenciaDoFimDaTransacaoDa409ENaoGravaNada` (um gatilho de teste entorta a diferença só daquela troca; a resposta é 409 `TROCA_NAO_CONFERE`, sem texto técnico, e nada fica gravado; sem o gatilho, a mesma troca passa).
3. **Herança para a 6.7 e a 6.8:** abaixo.

**Decisões que tomei:** as do corpo do PR #129 ("Decisões que tomei").

**O que a 6.6, a 6.7, a 6.8, a 6.9, a 6.10 e a Fase 7 herdam**

- **6.6 (a prazo):** troca com B a prazo pago em parcelas e devolução de venda a prazo.
- **6.7 (push e pull):** trocar o `FALSE` de `trocas`, `creditos_cliente` e `usos_credito` pelo filtro do vendedor (os créditos dele, as trocas dele, os usos pelos créditos) e levar `vendas.troca_id`, `pagamentos_cliente.credito_id` e `devolucoes.valor_em_credito` junto com as tabelas; **o formato novo da venda com crédito** (`creditoId` no pagamento: o formato 1 de hoje não leva o crédito, e a venda com `CREDITO` pelo envio é recusada); os tipos `TROCA` e `CANCELAR_TROCA` na outbox; a revisão do uso acima do saldo pelo envio (ADR-0014, item 9: a dona pode transferir o crédito enquanto o vendedor está sem internet; hoje, acima do saldo é sempre 409).
- **6.8 (Corona Vendas):** a tela da troca e o "Cancelar troca"; pagar com crédito na venda; **o nome "Crédito" na tela** (até lá, o pagamento em crédito que chega pelo pull aparece com o nome técnico `CREDITO`); **`TROCA_SAIDA` nos números locais** (`consultas-locais.ts` só conta `VENDA` e `ESTORNO_VENDA` como vendida; o saldo da maleta já fica certo, porque o pull traz os movimentos `TROCA_*` pelas tabelas que o app já conhece). Nenhuma tela grava troca até lá.
- **6.9 (Corona Gestão):** listas de trocas e de créditos, "Cancelar troca" e "Transferir crédito" pela dona; troca e devolução registradas pela dona (é onde "a dona usa o crédito").
- **6.10 (relatórios):** relatórios de trocas e créditos; separar crédito de dinheiro no relatório de recebimentos do vendedor (hoje a forma `CREDITO` aparece como mais uma linha, que entra e sai e fica negativa quando sobra crédito em aberto; o total continua certo).
- **Fase 7 (acerto):** a troca aparece como uma devolução (−A) e uma venda (+B) nas consultas que a 7.5 vai ler; nenhum evento de troca à parte; o crédito não reduz o que o vendedor deve (ADR-0012).

**Não fiz (fica para as próximas tarefas):** outbox, push e filtro do pull das tabelas novas (6.7); telas (6.8 e 6.9); troca com B a prazo em parcelas e devolução de venda a prazo (6.6); sobra da troca devolvida em dinheiro (só se a dona responder D4); relatórios de trocas e créditos (6.10); comissão e acerto (Fase 7).

## Tarefa 6.7: push e pull das tabelas da Fase 6

Tarefa crítica de sincronização: plano em [plano-6-7-sync-da-fase-6.md](plano-6-7-sync-da-fase-6.md), aprovado no PR #131 (dúvidas S1 a S12 pelo padrão, com os pontos 1 a 4 da orquestradora). A fase CÓDIGO seguiu a "Ordem dos commits" do plano.

**O que entrou**

- **Pull** (`RegistroDeTabelas`): `devolucoes`, `autorizacoes_excecao`, `trocas` e `creditos_cliente` pelo `vendedor_id`; `itens_devolucao`, `itens_autorizacao` e `usos_credito` pela linha mãe; `creditos_sem_saldo` pela venda. Nenhuma expansão nova (S12, o oráculo prova). Saem das listas `creditos_cliente.credito_origem_id` e `usos_credito.credito_destino_id` (apontam para o crédito de outro vendedor, S4) e todos os `*_por`; entram `devolucoes.valor_em_credito` e `cancelamento_id`, `trocas.cancelamento_id`, `vendas.troca_id`, `pagamentos_cliente.devolucao_id`, `estorna_id` e `credito_id` e `movimentos_remessa.devolucao_id`. Nenhuma sincronizável fica com `FALSE`. `ServicoDePull`, `FiltroPorVendedor` e `CursorDeSync` não mudaram.
- **Push** (`OperacoesDoVendedor`, com o conteúdo e a forma canônica dos tipos novos em `OperacoesDaFase6`): `DEVOLUCAO`, `PEDIDO_AUTORIZACAO`, `TROCA`, `CANCELAR_DEVOLUCAO` e `CANCELAR_TROCA`, formato 1 (publicado em 13/11/2026 no `FormatosDeOperacao`), cada um pelo mesmo serviço da rota online no modo do envio, com a hora corrigida e limitada (venda; venda A e envio da maleta B; devolução ou troca) e a hora ajustada acima de 5 minutos. A `VENDA` continua no formato 1, com o `creditoId` opcional em cada pagamento: o resumo canônico só muda quando o crédito vem (S3; um teste fixa o resumo de uma venda sem crédito). Conflito: `DEVOLUCAO_JA_REGISTRADA_DIFERENTE`, `AUTORIZACAO_JA_REGISTRADA_DIFERENTE` e `TROCA_JA_REGISTRADA_DIFERENTE`; os outros 409 são `RECUSADO`. A dependência do crédito de uma troca recusada é achada pelo `creditoId` da `TROCA` no `sync_log` (S9).
- **Crédito acima do saldo pelo envio** (S1 e S2): migração `V039__sync_da_fase_6.sql` com `creditos_sem_saldo` (id do servidor, `pagamento_id` único com o pagamento que o computador mandou, venda, crédito, pedido, usado, `EM_REVISAO | MANTIDA | NAO_ACEITA` e a revisão; gatilhos de nascimento, de conteúdo imutável e de pertença; `REVOKE ALL`). `ServicoDeVendas.venderPeloEnvio` (e a venda B da troca pelo envio) usa `ServicoDeCreditos.usarAteOSaldo`: o pagamento em crédito fica no saldo que havia (nenhuma linha quando era zero, cancelado ou já usado), o resto fica a receber, nasce a linha de revisão com auditoria `CREDITO_SEM_SALDO_NA_VENDA`; inexistente ou de outro vendedor (404) e de outro cliente (409) continuam recusados; na rota online continua 409. Rotas da dona (ADMIN): `GET /api/dona/creditos-sem-saldo`, `POST .../{id}/manter` e `POST .../{id}/nao-aceitar` (observação obrigatória). Aviso `CREDITOS_SEM_SALDO_PARA_REVISAR` (`FonteDeAvisosDosCreditos`). Dois códigos de erro novos com o texto do glossário.
- A peça nova da troca numa maleta `EM_ACERTO` responde `MALETA_EM_ACERTO` (antes, `MALETA_NAO_ESTA_COM_VOCE`).
- **Corona Vendas** (só o motor e o banco local): migração local 3 (as oito tabelas, as colunas novas e a marca `recarga_dirigida` só em banco que já tinha cursor); `TABELAS_SINCRONIZAVEIS` e `COLUNAS_DO_PULL` com as 23 tabelas; `aplicarLote` com `substituirIgual` (só a recarga dirigida) e `naMesmaTransacao`; o ciclo faz a recarga dirigida depois do pull, tabela por tabela pelo `?tabela=`, **sem apagar nada antes**, tirando a tabela da marca na transação da última página; `banco-v3.sqlite` de exemplo.
- `testes-sync/src/oraculo.ts` com as 23 tabelas e as linhas privadas novas em `idsDeOutrosVendedores`. Clientes da API regerados (só o da dona mudou). Glossário (parte 6), especificação v1.12 e linhas datadas no ADR-0013 e no ADR-0014.

**Testes (PostgreSQL de verdade; SQLCipher de verdade no app)**

- `PushDaFase6Test` (13): cada tipo pelo envio grava o mesmo que a rota (devolução, cancelamento, pedido, troca com B mais cara e mais barata e o cancelamento dela), com a hora corrigida; reenvio `JA_RECEBIDO` sem mudar contagem, JSON equivalente e `CONFLITO`; dependência da venda e do crédito de troca ainda não enviados; fora do prazo pelo servidor (relógio atrasado e adiantado, virada às 23h30 de Brasília, pedido desnecessário, limite da semana, valor de aprovação, autorização vencida) vira revisão, nunca `RECUSADO`; maleta em acerto e "Tentar de novo" depois de acertada; a `VENDA` sem crédito com o resumo de antes; hora limitada e "mesmo dia" pela hora corrigida; nada de outro vendedor; computador trocado, quarentena aprovada pelo mesmo serviço e formato 2 inválido; a troca que não fecha no fim da transação vira `RECUSADO` sem 500.
- `PullDaFase6Test` (4): cada tabela nova chega só ao dono, com as colunas exatas; as colunas novas chegam; as decisões da dona chegam como versão nova; crédito transferido sem id do outro; nenhuma `FALSE` e a pertença não muda; **o app abaixo da versão mínima recebe 426 no pull, nunca um lote com tabela desconhecida** (ponto 2).
- `CreditoSemSaldoTest` (7): **a C1 continua fechando** (soma dos usos = usado; total − devolvido = pagos + a receber) e a linha de revisão nunca mexe em dinheiro (ponto 1); crédito usado duas vezes no mesmo lote; saldo menor; transferido e cancelado; recusas; rota online 409; Manter e Não aceitar; segundas travas do banco.
- `OraculoDeDoisVendedoresTest` com devolução, pedido, decisão, troca, uso de crédito (também acima do saldo pelo envio), transferência, cancelamentos e revisões, e a varredura de toda coluna com UUID (mais a prova de que ela pega o `credito_origem_id`); `EstresseDoPullComFiltroTest` com um escritor de devoluções e trocas; `FormatosDeOperacaoTest`, `RegrasDoVendedorTest` e `PullDoVendedorTest` (23 tabelas); os testes da 6.3 a 6.5 passaram a esperar as tabelas no pull.
- Corona Vendas, `sync-da-fase-6.test.ts` (8): migração do `banco-v2` com outbox e provisórias sem perder nada; a marca só em banco com cursor; colunas; aplicador (tabelas novas, `credito_origem_id` e `revisada_por` recusam o lote, `substituirIgual`); **o banco v2 com cursor migrado recebe as tabelas e as colunas novas sem apagar nada e sem duplicar, cai na 2ª tabela e retoma** (ponto 3); banco novo não faz recarga; `RECUSADO` e `CONFLITO` desfazem as provisórias das tabelas novas.

**Nota de deploy (ponto 2 da aprovação):** primeiro publicar o Corona Vendas com a migração local 3 (versão maior, por exemplo `1.1.0`); **depois** subir a API com a 6.7 e `APP_VERSAO_MINIMA_VENDAS` nessa versão. O app velho continua enviando e recebe 426 no pull até atualizar. Passo a passo em [entrega-corona-vendas.md](../entrega-corona-vendas.md#nota-de-deploy-da-tarefa-67-tabelas-da-fase-6-no-pull).

**Decisões que tomei** (também no PR #131)

1. **`creditos_sem_saldo` com id próprio e `pagamento_id`** (o plano dizia "id = o id do pagamento"): o mesmo UUID em `pagamentos_cliente` e `creditos_sem_saldo` quebrava a carga completa depois da restauração (`RestauracaoDoSyncTest` exige ids únicos entre as sincronizáveis). O `pagamento_id` é único e vai ao vendedor; o gatilho confere o pagamento por ele.
2. A peça nova da troca em maleta `EM_ACERTO` passou a responder `MALETA_EM_ACERTO` também na rota online (teste 6 do plano).
3. A "devolução registrada pela dona de venda do vendedor" ainda não existe (6.9): o teste prova a decisão da dona (cancelar a devolução, manter o crédito sem saldo) chegando como versão nova.
4. O "erro passageiro da VENDA para o lote antes da devolução" já é o comportamento genérico do envio (5.5) e não ganhou teste novo.
5. A versão do Corona Vendas não foi aumentada aqui: quem publica aumenta (nota de deploy).

**O que a 6.8, a 6.9, a 6.11 e a 7.5 herdam**

- **6.8 (Corona Vendas):** gravar as operações novas na outbox (formato 1 acima), as telas e os textos do glossário ("A dona vai olhar esta devolução.", "O crédito do cliente já tinha acabado. A dona vai olhar esta venda."); números locais com devolução e troca; o nome "Crédito" na tela. As tabelas já chegam ao banco local.
- **6.9 (Corona Gestão):** a tela das vendas com crédito que já tinha acabado (as rotas e o aviso já existem); a devolução registrada pela dona.
- **6.11 (duas pontas):** cenários de devolução e troca nas duas pontas (o oráculo do `testes-sync/` já conhece as tabelas).
- **7.5 (acerto):** cobrar do vendedor o "não aceita" da devolução e do crédito sem saldo; a regra de corte da maleta em acerto (hoje `RECUSADO` e "Tentar de novo" depois do acerto).

**Resultado local (13/11/2026):** `npm run api:test` com 1064 testes verdes; Corona Vendas com 495 testes verdes; `testes-sync` sem API (18) verde; `typecheck`, `lint` e `format:check` limpos; `seller:empacotar` e `barreira:varrer` limpos (nenhum dos 6 marcadores). As duas pontas e o estresse curto (60 s) **nesta branch sozinha** param todos antes do sync, em `Dona.semana` (a API recusa o número da semana acima de 100000): é o defeito do teste que o PR #133 (issue #130) corrige, sem relação com esta tarefa. Com a correção do #133 juntada só no computador (branch temporária, nem enviada nem mantida), os 17 passaram, inclusive o estresse (semente 1407582607), com o banco local v3. Por isso o job `sync-duas-pontas` deste PR falha no CI até o #133 entrar na `main`.

## Tarefa 6.8: Corona Vendas, devolução, troca e crédito

Tarefa crítica (Opus, esforço alto). Plano aprovado no PR #134 ([plano](plano-6-8-devolucao-no-corona-vendas.md); V1 a V13 com o padrão de cada um). O desenho das telas **não foi aprovado pela dona**: segue os padrões das telas da Fase 5 e foi como a **pergunta 29** para `docs/perguntas-para-a-dona.md`.

**O que entrou**

- **Documentos antes do código:** o bloco "Devolução, troca e crédito no Corona Vendas" no glossário (parte 6), o quinto botão **Devolução e troca** na lista do glossário e na §19.7 da especificação (linha datada de 13/11/2026) e a pergunta 29.
- **Prévia de valor e prazo** (`src/shared/valor-da-devolucao.ts` e `garantia.ts`): a mesma regra do `ValorDaDevolucao` e do `RegrasDaDevolucao` do servidor, em centavos `bigint` e dias de Brasília; sem os prazos guardados, 7 e 30 (V2).
- **Cinco operações pela outbox** (`sync/operacoes-da-fase-6.ts`, chamadas por `OperacoesLocais`): `DEVOLUCAO`, `PEDIDO_AUTORIZACAO`, `TROCA`, `CANCELAR_DEVOLUCAO` e `CANCELAR_TROCA`, cada uma com as linhas provisórias como o servidor grava e a linha da outbox na mesma transação, no JSON do formato 1 da 6.7, montado uma vez. **O JSON nunca leva valor calculado no computador** (`valorConferido` e `totalConferido` nulos; reforço 2 da aprovação). As conferências são as do servidor, com os textos dele (`sync/mensagens-da-fase-6.ts`). "Cancelada aqui" e "usada aqui" vêm da outbox; nenhum `UPDATE` em cópia do servidor, e o `RECUSADO`/`CONFLITO` desfaz tudo pelas provisórias de sempre.
- **Venda com crédito e estorno:** o pagamento `CREDITO` leva o `creditoId` só nele (a `VENDA` sem crédito continua com os mesmos bytes) e grava o uso provisório; o crédito confere cliente, vendedor, situação e saldo do computador. O estorno local recusa venda com devolução e venda de troca, e o estorno de venda paga com crédito devolve o saldo (o uso é estornado).
- **"Ainda podem voltar" pelo produto** (`pecasDaVenda`): a cópia do servidor (`itens_devolucao` das devoluções ativas) e, para a devolução ou troca ainda sem cópia do servidor, as peças do JSON na outbox; nada conta em dobro quando a venda ou a devolução chegam. **Nenhuma migração local nova** (V12).
- **Consultas** (`local/consultas-da-fase-6.ts`): vendas com a peça, prévias da devolução e da troca, Minhas devoluções e trocas (as duas juntas, com a cotação do dia de cada lançamento), pedidos de autorização com os avisos das respostas e o crédito do cliente; sempre só do vendedor.
- **Números locais como o servidor:** "vendida" com `TROCA_SAIDA` e descontando `DEVOLUCAO_CLIENTE`/`TROCA_ENTRADA`; "a receber" = total − devolvido − pago na venda e no relatório; `ESTORNO_REEMBOLSO` como tipo próprio; a venda nova de uma troca cancelada aqui aparece estornada; a venda com crédito sem saldo em revisão; as descrições novas dos envios não aceitos.
- **Ponte** (`shared/operacoes.ts`, `shared/validacao.ts`, `api.ts`): onze operações, todas "só no banco do computador"; a validação recusa qualquer campo de valor vindo da tela.
- **Telas** (`renderer/telas/devolucao/`): o quinto botão e os avisos na tela inicial; Devolução e troca (ler a peça, vendas com a peça, Devolver e Trocar); Devolução (prévia, bloqueio fora do prazo, Pedir autorização à dona, "vale até"); Troca (peças que voltam e que o cliente leva, a diferença, o pagamento à vista ou a prazo); Minhas devoluções e trocas (Aguardando envio, Para revisão da dona, Cancelar no mesmo dia, pedidos); Usar crédito em Vender; o crédito na ficha do cliente; Devolver e Trocar, Devolução, Devolução cancelada e Crédito do cliente na ficha da venda.

**Testes (escritos antes do código, SQLCipher de verdade)**

- `shared/valor-da-devolucao.test.ts` e `garantia.test.ts` (12): os casos do servidor, 500 sequências aleatórias e o prazo no limite pelo relógio (23:59 e 00:00 de Brasília, venda às 23:30, defeito no 30º e no 31º dia).
- `sync/operacoes-da-fase-6.test.ts` (37): cada caminho sem internet, o JSON byte a byte e sem nenhum valor do app, tudo ou nada com falha injetada, as autorizações que não servem, o pedido, a troca nos três casos da diferença, o crédito usado até zerar e as recusas dele, os cancelamentos, o estorno com devolução, troca e crédito, as recusas com o texto do servidor e o "ainda podem voltar" quando a venda chega do servidor.
- `sync/ciclo-da-fase-6.test.ts` (3, servidor falso): as cinco operações na ordem da fila com os bytes guardados, `RECUSADO` e `CONFLITO` desfazendo saldo, crédito e "a receber", a cópia para revisão da dona e o crédito sem saldo.
- `local/consultas-da-fase-6.test.ts` (12): números locais e a conciliação da maleta depois de cada caminho, as consultas das telas e nada de custo nem de outro vendedor.
- `shared/validacao-da-fase-6.test.ts` e `api-operacoes.test.ts` (ponte), `renderer/devolucao-e-troca.test.tsx` (16 telas, inclusive as palavras proibidas).

**Decisões que tomei** (também no PR #134)

1. **Nenhum valor na outbox** (reforço 2): `valorConferido` e `totalConferido` vão nulos (o servidor aceita nulo; só não gera o alerta de divergência). O plano previa mandar a prévia para o alerta.
2. **Uma lista só** para Minhas devoluções e trocas (`devolucoes.listar` traz devoluções e trocas) e `autorizacoes.listar` traz os pedidos e os avisos; não fiz `devolucoes.ver` nem `trocas.ver` (a lista mostra tudo).
3. **Filtro `sync` do `ci.yml` sem mudança:** `apps/seller-app/src/(main|shared)/` já cobre tudo o que esta tarefa mexe; mexer no `ci.yml` faria rodar `backend` e `dependencias` sem necessidade. `docs/ci.md` também fica como está.
4. Na tela, **um estado por peça** (Boa ou Com defeito); as operações aceitam a mesma peça nos dois estados, a tela ainda não.
5. Fora do prazo na **troca**, a tela mostra o bloqueio; o pedido de autorização é feito pela tela da devolução.
6. `operacoes-locais.ts` ficou com cerca de 520 linhas (o plano dizia ~450): só os delegados e as mudanças da venda e do estorno; a regra nova está em `operacoes-da-fase-6.ts`.

**O que a 6.10, a 6.11 e a 6.12 herdam**

- **6.10 (relatórios):** os relatórios de vendas e de recebimentos do computador continuam somando como o servidor somava antes (o crédito não está separado); `consultas-locais.ts` é arquivo em comum: quem mesclar depois faz `git merge origin/main`, mantém os dois lados nos números locais e roda os testes das duas telas.
- **6.11 (duas pontas):** os cenários de devolução, troca e crédito com o motor de verdade (o formato e a ordem estão fixados pelos testes 3, 6, 7 e 12 desta tarefa; a prova contra o servidor real é dela).
- **6.12:** a pergunta 29 e o desenho das telas com a dona.

**Não fiz (fica para as próximas tarefas)**

- Devolução de venda a prazo (6.6; até lá o bloqueio "Esta venda tem parcelas..."), foto da peça devolvida (D10), relatórios de devoluções, trocas e créditos (6.10), cenários de duas pontas (6.11), acerto e pendência do vendedor (7.5), pagar a diferença da troca com outro crédito (V8), usar o crédito de devolução sem internet (decisão 7), pedir autorização por valor (o computador não sabe o valor de aprovação).
- Teste no computador com a dona; o desenho precisa da aprovação dela (pergunta 29).

**Resultado local (13/11/2026):** Corona Vendas com 594 testes verdes (1 pulado, o de antes); `testes-sync` sem API (24) verde; `typecheck`, `lint` e `format:check` limpos; `seller:empacotar` e `barreira:varrer` limpos (nenhum dos 6 marcadores). Nada no backend; o job `backend` do CI roda porque o glossário mudou (`GlossarioDasMensagensTest`). As duas pontas e o estresse ficam para o CI (o plano não pede rodar no computador).

## Tarefa 6.9: Corona Gestão, devoluções e créditos

Tarefa não crítica (Sonnet, esforço alto). Plano no PR #132. O desenho das telas **não foi aprovado pela dona**: segue os padrões das telas da Fase 5 e a pergunta 28 foi para `docs/perguntas-para-a-dona.md`.

**O que entrou**

- **Backend mínimo para a dona registrar a devolução** (ADR-0014, item 13): `POST /api/dona/devolucoes` (201 na primeira gravação, 200 no reenvio igual, 409 com o mesmo id e dados diferentes) e `GET /api/dona/devolucoes/vendas-com-a-peca` (as vendas de **todos** os vendedores com a peça lida). `ServicoDeDevolucoes.devolverPelaDona` usa o mesmo caminho da devolução do vendedor, com quatro diferenças: o vendedor da devolução é o da venda; a peça vai **sempre para a loja** (`destino_peca = LOJA`, nada entra na maleta do vendedor nem confere computador); o limite da semana e o valor de aprovação não valem para a dona; a venda de vendedor desativado também pode ser devolvida. Prazo da garantia, maleta em acerto, venda a prazo, acima do vendido e a imutabilidade valem iguais. A auditoria `DEVOLUCAO_REGISTRADA` leva `registrada_pela_dona`. Nenhuma migração, nenhuma tabela nova. Cliente da API da dona regerado (o do vendedor não mudou).
- **Ponte do Corona Gestão** (`shared/devolucoes.ts`): 15 operações (`devolucoes.*`, `trocas.*`, `creditos.*`, `garantia.*`) conferidas no processo principal (ids, situações, motivo obrigatório até 400 letras, pedido de devolução com os campos exatos e **nenhum valor em dinheiro**, garantia em faixa, dinheiro em texto) e ligadas às rotas já existentes da 6.2, 6.4 e 6.5.
- **Telas** (`renderer/telas/devolucoes/`, grupo de menu **Devoluções e créditos**): **Devoluções para autorizar** (filtro de situação, **Aprovar reembolso** com confirmação e **Negar** com motivo; mostra "Vale até"), **Devoluções para revisar** (os motivos da revisão com palavras da loja, **Manter** e **Não aceitar** com motivo), **Devoluções** (filtros de situação e vendedor, o que voltou em dinheiro e em crédito, **Registrar devolução** pela dona em duas etapas e **Cancelar devolução** com motivo), **Trocas** (diferença "a pagar" ou "fica de crédito", **Cancelar troca** com motivo), **Créditos dos clientes** (valor, já usado, saldo, **Transferir crédito**, sem oferecer o vendedor de agora) e **Garantia e limites** (os cinco valores; "Sem limite" e "Não aprovar antes" como caixas; dinheiro em texto). Os três avisos da tela inicial ganharam o botão **Abrir**. Dólar e Guarani vêm do servidor, pela cotação do dia de cada lançamento (ADR-0006), com a frase da parte 11 do glossário embaixo de cada lista. Cancelar devolução, cancelar troca e transferir crédito levam um id novo por janela aberta (reenvio igual não duplica).
- **Glossário** (parte 6, "Devoluções e créditos no Corona Gestão") escrito antes das telas; o botão do pedido de autorização passou a **Aprovar reembolso** (o plano pedia; o glossário da 6.4 dizia "Aprovar").

**Testes (escritos antes do código)**

- Backend, PostgreSQL de verdade: `DevolucaoPelaDonaTest` (12: peça boa e com defeito para a loja sem mexer na maleta; reembolso gravado, quem registrou e auditoria; reenvio 200 e 409; fora do prazo 409; limite da semana e valor de aprovação não valem para a dona; maleta em acerto; acima do vendido e venda inexistente; vendedor desativado; a dona cancela o que registrou; busca da dona acha vendas de dois vendedores; 403 do vendedor e 401; nada de custo na resposta). Rodaram de novo, verdes, `DevolucaoDoClienteTest`, `AutorizacaoDeDevolucaoTest`, `AntifraudeDaDevolucaoTest`, `TrocaTest` e `CreditoDoClienteTest`.
- Corona Gestão: `validacao-devolucoes.test.ts` (conferência dos dados, inclusive recusar campo de valor), `api.test.ts` (caminho de cada operação e o corpo/filtros enviados) e `devolucoes.test.tsx` (32: menu, avisos com Abrir, cada tela e cada ação com confirmação e motivo obrigatório, recusa do servidor dentro da janela, registrar sem peças/motivo/forma e com "Outro" sem observação, defeito marca a peça, fora do prazo com a frase da dona, "Todas" sem situação, Garantia com faixa e "sem limite", Dólar e Guarani do servidor, nenhuma palavra técnica nas seis telas).
- Ajuste antes do merge (conciliação): o CI falhou em `RelatoriosDeEstoqueEComprasTest.conciliacaoVemVaziaEMostraOQueDiverge` quando a ordem das classes mudou. A devolução pela dona **não** deixa produto divergente (conferido: `DevolucaoPelaDonaTest` ganhou a conferência da conciliação do banco inteiro depois de cada caso, e passa). O produto divergente era de `TrocaTest.aDeMaletaAcertadaEBDaMaletaNova` (tarefa 6.5): o teste simulava o acerto com `UPDATE ... status = 'ACERTADA'` numa maleta de 3 peças em que só 1 foi vendida, e as 2 que sobraram saíam da conta da conciliação. Como os testes dividem o mesmo banco, o defeito só aparecia quando `TrocaTest` rodava antes do relatório (a ordem no CI depende do sistema de arquivos e mudou com a classe nova). Correção só no teste (maleta de 1 peça, toda vendida) e a mesma conferência da conciliação depois de cada caso em `ApoioDasTrocas`, que falhava antes da correção. Reproduzido localmente rodando as classes na ordem do CI; a rodada completa nessa ordem passou.

**Decisões que tomei** (as mesmas do corpo do PR #132)

- A dona registra a devolução **dentro do prazo** (ADR-0014, item 13); fora dele continua 409. A tela troca o texto do servidor ("Peça autorização à dona") por "Passou do prazo da garantia desta venda. O vendedor pode pedir a autorização no computador dele, e você aprova em Devoluções para autorizar."
- A devolução da dona **conta no limite da semana do vendedor** da venda quando o vendedor devolve depois (conservador: pode gerar mais revisão, nunca menos); ela mesma não é barrada pelo limite.
- **Fica de fora: troca pela dona e usar crédito pela dona.** Os dois precisam de uma venda feita pela dona, que não existe (a venda sempre sai de uma maleta de vendedor, ADR-0011). A dona vê, cancela e **transfere** (já existia, 6.5); quem troca e quem usa o crédito é o vendedor. Vai na pergunta 28.
- Nome do vendedor nas listas: as listas da API trazem só o id; a tela usa a lista de vendedores ativos (`vendedores.escolha`) e, para um vendedor já desligado, escreve "Vendedor desligado". Não mudei os contratos do Corona Vendas só para isso.
- Um menu e uma tela de cada coisa (seis itens), em vez de abas, para seguir o padrão da Fase 5.

**Não fiz (fica para as próximas tarefas)**

- Troca pela dona e uso do crédito pela dona (acima).
- Devolução de venda a prazo (6.6): continua 409 também para a dona.
- Foto da peça devolvida (dúvida D10, "fica para depois").
- Relatórios de devoluções, trocas e créditos, com exportação (6.10).
- Teste no computador com a dona; o desenho precisa da aprovação dela (pergunta 28).
