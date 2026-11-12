# Plano da tarefa 6.4: autorização, revisão e antifraude

Tarefa crítica (modo de plano). **Nenhum código antes do `[ORQUESTRADORA] APROVADO`.** Base: [fase-6-plano.md](fase-6-plano.md) (Regras da Fase 6, seção 6.4, Dúvidas R1 a R6 respondidas "Sim" e D3, D8, D9 e D11 pelo padrão seguro), [ADR-0014](../decisoes/ADR-0014-devolucao-troca-e-garantia.md) (Aceito; itens 5, 6, 10 e 12), especificação §7.3 (garantia, fora do prazo, antifraude), §9 (Devolução e troca: `autorizacoes_excecao`), §11 (auditoria de autorização de exceção) e §19.3 ("O que precisa da sua atenção"), e o que a 6.2 e a 6.3 deixaram em [fase-6.md](fase-6.md).

## O que já existe (não refazer)

- **Garantia (V035, `api/configuracoes`):** `limite_reembolso_semanal` e `valor_aprovacao_reembolso` (tipo `DINHEIRO`, vazio = sem limite), `percentual_alerta_devolucao` (inteiro 1 a 100, padrão 20); `Configuracoes.dinheiroOuVazio` e `inteiro` leem a cada uso; `GET/PUT /api/dona/configuracoes/garantia` já muda os três. O limite semanal e o valor de aprovação **não** vão ao app (decisão da 6.2): sem internet o app não sabe deles.
- **Devolução (V036, `api/devolucoes`):** `devolucoes` já tem `situacao` (`ACEITA | EM_REVISAO | MANTIDA | NAO_ACEITA | CANCELADA`), `dentro_garantia` e `autorizacao_id` **sem chave estrangeira** (esta tarefa cria a tabela e a chave); o gatilho `proteger_devolucao` já permite só `ACEITA → CANCELADA` e `EM_REVISAO → CANCELADA | MANTIDA | NAO_ACEITA`. `ServicoDeDevolucoes.devolver(..., momento, horaAjustada, ModoDaDevolucao)`: travas maleta → venda; fora do prazo = 409 `GARANTIA_VENCIDA` na rota (`ONLINE`) e `EM_REVISAO` no `ENVIO` (o que a 6.7 vai ligar no push); idempotência pelo id; auditoria `DEVOLUCAO_REGISTRADA`. `cancelar` aceita só `ACEITA | EM_REVISAO` (Q6 da 6.3). `ValorDaDevolucao` calcula o preço pago por unidade. `ConsultasDeDevolucoes` monta a devolução com peça, foto e Dólar e Guarani pela cotação do dia.
- **Avisos (`api/avisos`):** `FonteDeAvisos` (um `@Component` por tipo, **calculado na hora, nunca gravado**) e `GET /api/dona/avisos` junta todas; exemplos: `FonteDeAvisosDosEnvios` (contagem com texto no singular e plural) e `FonteDeAvisosDeCotacao`.
- **Semanas (V010):** `semanas` (`inicio`, `fim` em `date`, sem sobreposição, `ABERTA | FECHADA`); `remessas.semana_id`.
- **Travas e agendas:** `pg_advisory_xact_lock(hashtextextended(...))` já usado em compras, remessas e inventário; `@Scheduled(..., zone = "UTC")` com `try/catch` que nunca derruba a agenda (`AgendaDaBuscaDeCotacoes`).
- `VendedorAtivo.exigir`, `Validacoes`, `UuidV7`, `OrigemDoDispositivo`, `RelogioDeTeste`, `ServicoDeAuditoria`, `RegistroDeTabelas` (devoluções com a regra `FALSE` até a 6.7).

## Migração `V037__autorizacao_e_revisao.sql` (o próximo número livre na `main` na hora do código)

1. **`autorizacoes_excecao`** (sincronizável; a situação muda, o conteúdo não): `venda_id`, `vendedor_id`, `cliente_id` (copiados da venda e conferidos por gatilho, como em `devolucoes`), `motivo` (`ARREPENDIMENTO | DEFEITO | TAMANHO | OUTRO`, o da devolução pedida), `observacao` (até 400; obrigatória com `OUTRO`), `tipo` (`FORA_DO_PRAZO | ACIMA_DO_VALOR`, decidido pelo servidor), `situacao` (`PEDIDA | APROVADA | NEGADA | USADA | VENCIDA`), `valor_pedido NUMERIC(14,2) >= 0` (o preço pago das peças pedidas, calculado pelo servidor na hora do pedido: é o que a dona aprova), `cotacao_id` (do dia do pedido, só para mostrar Dólar e Guarani), `pedida_em`, `hora_ajustada`, `pedida_por`, e a decisão: `decidida_em`, `decidida_por`, `motivo_decisao` (até 400; obrigatório em `NEGADA`), `valida_ate date` (último dia de Brasília em que vale; obrigatório de `APROVADA` em diante).
   - `CHECK`s de "tudo ou nada": `PEDIDA` sem nenhum dado de decisão; `NEGADA` com `decidida_*` e `motivo_decisao`, sem `valida_ate`; `APROVADA | USADA | VENCIDA` com `decidida_*` e `valida_ate`.
   - Gatilho de inserção: a venda existe, está `CONCLUIDA` e o vendedor e o cliente são os dela; nasce `PEDIDA`.
   - Gatilho `autorizacoes_conteudo_imutavel`: nada muda além da situação e dos dados da decisão; transições permitidas `PEDIDA → APROVADA | NEGADA` (com os dados da decisão), `APROVADA → USADA` (só se existir devolução com este `autorizacao_id`: segunda trava do uso) e `APROVADA → VENCIDA`; `deleted_at` nunca.
   - Gatilho `corona_pertenca_nao_muda`: `CREATE OR REPLACE` da função (a da V036, inteira) com o ramo `autorizacoes_excecao` (vendedor e venda não mudam). A V036 não é editada.
   - Índices: por vendedor e `pedida_em`, por venda, e parcial por situação `PEDIDA` (lista da dona e aviso).
2. **`itens_autorizacao`** (sincronizável, **imutável**): `autorizacao_id`, `item_venda_id`, `quantidade > 0`, `UNIQUE (autorizacao_id, item_venda_id)`; gatilho que confere que a linha é da venda da autorização (dúvida P1).
3. **`devolucoes`**:
   - chave estrangeira `autorizacao_id → autorizacoes_excecao` e **índice único** `devolucoes_autorizacao_unica (autorizacao_id) WHERE autorizacao_id IS NOT NULL` (uma devolução por autorização, para sempre: dúvida P11);
   - gatilho de inserção ampliado (por `CREATE OR REPLACE` de `conferir_devolucao_da_venda`): com `autorizacao_id`, a autorização é da mesma venda e do mesmo vendedor e está `APROVADA`;
   - colunas da revisão: `motivos_revisao text` (códigos separados por vírgula, `CHECK` por expressão regular com `FORA_DO_PRAZO`, `ACIMA_DO_LIMITE_SEMANAL`, `ACIMA_DO_VALOR_SEM_AUTORIZACAO`, `AUTORIZACAO_INVALIDA`, `REEMBOLSO_MAIOR_QUE_O_PAGO`; a 6.5 acrescenta `CREDITO_ACIMA_DO_SALDO`; dúvida P2), `revisada_em`, `revisada_por`, `observacao_revisao` (até 400; obrigatória em `NAO_ACEITA`);
   - `CHECK`: `ACEITA` sem motivo de revisão; `EM_REVISAO | MANTIDA | NAO_ACEITA` com pelo menos um; `MANTIDA | NAO_ACEITA` se e só se `revisada_em` e `revisada_por` preenchidos;
   - preenche `motivos_revisao = 'FORA_DO_PRAZO'` nas `EM_REVISAO` que já existirem (a 6.3 só criava revisão por prazo) **antes** de trocar o gatilho;
   - `CREATE OR REPLACE` de `proteger_devolucao`: tudo o que a V036 protegia, mais `motivos_revisao` imutável e os dados da revisão só mudando junto com `EM_REVISAO → MANTIDA | NAO_ACEITA`.
4. `REVOKE ALL ... FROM PUBLIC` nas tabelas novas.

## Backend (pacote `api/devolucoes`, que já está no filtro de duas pontas do CI)

- **`ServicoDeAutorizacoes.pedir(vendedorId, usuarioId, aparelho, pedido, momento, horaAjustada, modo)`**, `@Transactional`. O app manda id, venda, motivo, observação e peças (produto e quantidade, como na devolução); nunca tipo nem valor.
  1. Confere o pedido com as mesmas regras de motivo, observação e peças da devolução (o código de conferência de `ServicoDeDevolucoes` vira compartilhado, sem mudar o comportamento).
  2. Venda de outro vendedor ou inexistente: 404 `VENDA_NAO_ENCONTRADA`. Trava a venda (`FOR UPDATE`).
  3. Idempotência: mesmo id e mesmo conteúdo, 200; diferente, 409 `AUTORIZACAO_JA_REGISTRADA_DIFERENTE`.
  4. Recusas: vendedor inativo; venda estornada; venda a prazo (409 da 6.3, até a 6.6); peça fora da venda (400); quantidade acima do que ainda pode voltar (409 `DEVOLUCAO_ACIMA_DO_VENDIDO`); já existe pedido `PEDIDA` ou `APROVADA` ainda válido para a mesma venda (409 `AUTORIZACAO_JA_PEDIDA`, dúvida P10).
  5. Tipo pelo servidor: fora do prazo (mesma conta de dias de Brasília da 6.3) = `FORA_DO_PRAZO`; senão, valor pedido acima de `valor_aprovacao_reembolso` = `ACIMA_DO_VALOR`; senão, na rota 409 `AUTORIZACAO_DESNECESSARIA` "Esta devolução está no prazo. Registre a devolução direto."; no `ENVIO` grava como `FORA_DO_PRAZO` (o app só pede pelo prazo; dúvida P13).
  6. Grava `autorizacoes_excecao` + `itens_autorizacao`, `valor_pedido` com `ValorDaDevolucao` (o mesmo cálculo da devolução, com o que já voltou), cotação do dia; auditoria `AUTORIZACAO_PEDIDA`.
- **`ServicoDeAutorizacoes.aprovar(id, usuarioId)` e `negar(id, usuarioId, motivo)`** (só ADMIN): trava a autorização; só de `PEDIDA`; repetir a mesma decisão = 200, a outra = 409 `AUTORIZACAO_JA_DECIDIDA`; aprovar grava `valida_ate = dia_Brasília(agora) + 7` (dúvida P7); negar exige motivo (400 sem). Auditoria `AUTORIZACAO_APROVADA` e `AUTORIZACAO_NEGADA`.
- **`ServicoDeAutorizacoes.marcarVencidas()`** e **`AgendaDasAutorizacoes`** (`@Scheduled`, todo dia 03:10 UTC = 00:10 de Brasília, com `try/catch`): `APROVADA` com `valida_ate` antes de hoje (Brasília) vira `VENCIDA`. A regra do uso olha a data, nunca depende da agenda ter rodado (dúvida P8).
- **`ServicoDeDevolucoes.devolver`** (pedido ganha `autorizacaoId`, opcional; a idempotência passa a comparar também esse campo):
  1. **Antes das travas da 6.3**, `pg_advisory_xact_lock` por vendedor (`corona.devolucoes.vendedor:<id>`): duas devoluções do mesmo vendedor em vendas diferentes somam o limite semanal uma depois da outra. Ordem: vendedor → maleta → venda → autorização. Nenhum outro caminho pega essa trava, então não há ordem invertida.
  2. **Autorização** (se veio): trava a linha. Inexistente ou de outro vendedor: 404 `AUTORIZACAO_NAO_ENCONTRADA` (recusa nos dois modos: ADR-0014, item 5). Outra venda, outro motivo, peça fora do autorizado ou quantidade acima da autorizada (menos pode): na rota 409 `AUTORIZACAO_NAO_CONFERE`; `PEDIDA` ou `NEGADA`: 409 `AUTORIZACAO_NAO_APROVADA`; `USADA`: 409 `AUTORIZACAO_JA_USADA`; vencida pela data: 409 `AUTORIZACAO_VENCIDA`. No `ENVIO`, todos esses (menos o inexistente) viram revisão com `AUTORIZACAO_INVALIDA`, e a autorização **não** é consumida (`autorizacao_id` gravado vazio; o id que o app mandou vai para a auditoria). Válida: dispensa o prazo e o valor de aprovação desta devolução (dúvida P6), é gravada em `autorizacao_id` e passa a `USADA` na mesma transação.
  3. **Prazo** (6.3): fora do prazo sem autorização válida: rota 409 `GARANTIA_VENCIDA`; `ENVIO` revisão `FORA_DO_PRAZO`.
  4. **Valor de aprovação**: reembolso desta devolução acima de `valor_aprovacao_reembolso` (vazio = nunca) sem autorização válida: rota 409 `DEVOLUCAO_PRECISA_DE_AUTORIZACAO` "Esta devolução passa do valor que a dona aprova antes. Peça autorização à dona."; `ENVIO` revisão `ACIMA_DO_VALOR_SEM_AUTORIZACAO`.
  5. **Limite semanal**: soma de `valor_reembolsado` das devoluções não canceladas do vendedor na semana (dúvida P3) + esta, acima de `limite_reembolso_semanal` (vazio = nunca): **revisão nos dois modos** `ACIMA_DO_LIMITE_SEMANAL` (não trava o balcão; dúvida P5). Vale mesmo com autorização.
  6. **Reembolso maior que o pago**: reembolsos líquidos já feitos na venda (`REEMBOLSO` + `ESTORNO_REEMBOLSO`) + este, acima dos `PAGAMENTO` da venda: revisão nos dois modos `REEMBOLSO_MAIOR_QUE_O_PAGO` (defesa: com as travas da 6.3 só acontece com dado torto).
  7. Situação: `ACEITA` sem motivos; `EM_REVISAO` com a lista (ordem fixa). Movimentos e reembolso são gravados igual nos dois casos (o dinheiro já saiu). A auditoria `DEVOLUCAO_REGISTRADA` ganha os motivos e o `autorizacao_id`.
- **`ServicoDeDevolucoes.manter(id, usuarioId, observacao)` e `naoAceitar(id, usuarioId, observacao)`** (só ADMIN): travas maleta → venda → devolução (a ordem do cancelar); só de `EM_REVISAO` (409 `DEVOLUCAO_NAO_ESTA_EM_REVISAO`); repetir a mesma decisão = 200, a outra = 409 `DEVOLUCAO_JA_REVISADA`; `naoAceitar` exige observação. **Nenhum movimento, pagamento ou valor muda** (ADR-0014, item 5): `NAO_ACEITA` é a pendência do vendedor, com o `valor_devolvido`, até a 7.5 criar `pendencias_acerto` (dúvida P12). Auditoria `DEVOLUCAO_MANTIDA` e `DEVOLUCAO_NAO_ACEITA`.
- **`TaxaDeDevolucao`** (consulta): por vendedor ativo, nos últimos 28 dias de Brasília até hoje, devolvido = soma de `valor_devolvido` das devoluções não canceladas pelo dia delas; vendido = soma do total das vendas `CONCLUIDA` pelo dia delas. Acima quando `devolvido × 100 > percentual × vendido` (conta exata, sem arredondar) ou vendido zero com devolução (dúvida P9).
- **`FonteDeAvisosDasDevolucoes`** (`FonteDeAvisos`, calculada na hora; textos do glossário):
  - `DEVOLUCOES_PARA_AUTORIZAR`: "Há 1 pedido de autorização de devolução esperando você." / "Há N pedidos..." + "Abra Devoluções para autorizar e escolha Aprovar ou Negar.";
  - `DEVOLUCOES_PARA_REVISAR`: "Há N devolução(ões) para revisar." + "Abra Devoluções para revisar e escolha Manter ou Não aceitar.";
  - `MUITAS_DEVOLUCOES` (um por vendedor): "Muitas devoluções de {vendedor}." + "Nas últimas 4 semanas, {vendedor} devolveu {P}% do que vendeu. Confira as devoluções dele." (só porcentagem, sem valor, para não mostrar Real sem Dólar e Guarani).
- **`ConsultasDeAutorizacoes`**: a autorização com venda, cliente, vendedor (nome), peças (nome e foto), quantidades, `valor_pedido` em Real, Dólar e Guarani pela cotação do dia dela, dias desde a venda, prazo da garantia, situação e `valida_ate`; lista da dona (filtro de situação, padrão `PEDIDA`) e lista do vendedor (só as dele). Nada de custo.
- `ConsultasDeDevolucoes`: a devolução ganha `motivosRevisao`, `autorizacaoId`, `revisadaEm` e `observacaoRevisao`; lista da dona com filtro de situação (padrão `EM_REVISAO`) e de vendedor.
- **Rotas** (`@ExigePerfil`; o vendedor vem do token):
  - Vendedor: `POST /api/vendedor/autorizacoes` (201/200/409), `GET /api/vendedor/autorizacoes`, `GET /api/vendedor/autorizacoes/{id}`; `POST /api/vendedor/devolucoes` aceita `autorizacaoId`.
  - Dona (ADMIN): `GET /api/dona/autorizacoes`, `GET /api/dona/autorizacoes/{id}`, `POST /api/dona/autorizacoes/{id}/aprovar`, `POST /api/dona/autorizacoes/{id}/negar`; `GET /api/dona/devolucoes`, `GET /api/dona/devolucoes/{id}`, `POST /api/dona/devolucoes/{id}/manter`, `POST /api/dona/devolucoes/{id}/nao-aceitar`. As telas são da 6.9.
- **`RegistroDeTabelas`**: `autorizacoes_excecao` e `itens_autorizacao` com a regra `FALSE` (como a 6.3, Q4) até a 6.7; as colunas novas de `devolucoes` entram na lista dela (que continua `FALSE`).
- **`CodigoErro`**: `AUTORIZACAO_NAO_ENCONTRADA`, `AUTORIZACAO_JA_REGISTRADA_DIFERENTE`, `AUTORIZACAO_DESNECESSARIA`, `AUTORIZACAO_JA_PEDIDA`, `AUTORIZACAO_JA_DECIDIDA`, `AUTORIZACAO_NAO_APROVADA`, `AUTORIZACAO_JA_USADA`, `AUTORIZACAO_VENCIDA`, `AUTORIZACAO_NAO_CONFERE`, `DEVOLUCAO_PRECISA_DE_AUTORIZACAO`, `DEVOLUCAO_NAO_ESTA_EM_REVISAO`, `DEVOLUCAO_JA_REVISADA`, todos com texto da loja e sem valor em Real no texto.
- Clientes da API regerados (`npm run api:clientes`). Nenhuma tela nesta tarefa.

## Documentos

- `docs/glossario-interface.md` **antes do código** (parte 6): situações do pedido (**Esperando a dona**, **Aprovado (vale até dd/mm)**, **Negado**, **Usado**, **Vencido**), botões (**Pedir autorização à dona**, **Aprovar**, **Negar**, **Manter**, **Não aceitar**), os motivos da revisão em linguagem da loja ("passou do prazo", "passou do limite da semana", "passou do valor que a dona aprova antes", "autorização que não vale", "devolveu mais do que o cliente pagou"), os textos dos três avisos e das 12 mensagens novas.
- Especificação **antes do código** (v1.10, §22): §9 com `itens_autorizacao` e as colunas da revisão em `devolucoes`; §7.3 com o que for aprovado de P3 a P7 e P9 (semana do limite, "acima" estrito, autorização dispensa prazo e valor, validade em dias de Brasília, janela da taxa).
- `docs/progresso/fase-6.md` (relatório da 6.4, com o que a 6.7, a 6.8 e a 6.9 herdam) e a linha da 6.4 em `docs/PROGRESSO.md`.

## Testes primeiro (PostgreSQL de verdade, Testcontainers; escritos e vistos falhando antes do código)

Arquivos: `backend/src/test/java/.../devolucoes/AutorizacaoDeDevolucaoTest.java` (pedido, decisão, uso, validade), `AntifraudeDaDevolucaoTest.java` (limites, revisão, taxa, avisos) e ajustes pontuais em `DevolucaoDoClienteTest` e `PullDoVendedorTest`.

1. **Pedido**: fora do prazo = 201 `PEDIDA`, tipo `FORA_DO_PRAZO`, `valor_pedido` calculado pelo servidor (com centavos que não dividem exato e com devolução parcial anterior), cotação do dia; reenvio igual 200; mesmo id com outra quantidade 409; no prazo e abaixo do valor = 409 `AUTORIZACAO_DESNECESSARIA`; no prazo e acima do valor = tipo `ACIMA_DO_VALOR`; segundo pedido da mesma venda com um `PEDIDA` aberto = 409; quantidade acima do que pode voltar 409; venda a prazo e venda estornada 409; malformado (sem peças, motivo fora da lista, `OUTRO` sem observação, `DEFEITO` com peça boa) 400.
2. **Aprovar e negar**: aprovar grava quem, quando e `valida_ate` = dia de Brasília + 7 (aprovação às 23:30 de Brasília, 02:30 UTC do dia seguinte, conta pelo dia de Brasília); repetir aprovar 200; negar depois 409; negar sem motivo 400; negar grava o motivo; auditoria das duas.
3. **Uso**: devolução fora do prazo com autorização aprovada = 201 `ACEITA`, `dentro_garantia = false`, `autorizacao_id` gravado e autorização `USADA`; com menos peças que o autorizado passa; com outra peça, mais peças, outro motivo ou outra venda = 409 `AUTORIZACAO_NAO_CONFERE`; com autorização `PEDIDA` ou `NEGADA` = 409 `AUTORIZACAO_NAO_APROVADA`; sem autorização continua 409 `GARANTIA_VENCIDA`.
4. **Validade**: no 7º dia depois da aprovação passa, no 8º = 409 `AUTORIZACAO_VENCIDA` (com virada de dia em Brasília contra UTC); a agenda marca `VENCIDA` só as aprovadas com data passada (não toca `PEDIDA`, `USADA` nem as do dia); a regra vale mesmo sem a agenda ter rodado; no `ENVIO`, autorização vencida = devolução `EM_REVISAO` com `AUTORIZACAO_INVALIDA` e a autorização continua `APROVADA`/`VENCIDA` (não consumida).
5. **Usada duas vezes**: segunda devolução com a mesma autorização = 409 `AUTORIZACAO_JA_USADA`; duas ao mesmo tempo (duas threads, `CountDownLatch`) = uma 201 e outra 409, sem travamento; segunda trava: `INSERT` direto de outra devolução com o mesmo `autorizacao_id` recusado pelo índice único, e `UPDATE` direto `APROVADA → USADA` sem devolução ligada recusado; cancelar a devolução deixa a autorização `USADA`.
6. **Limite semanal**: vazio nunca põe em revisão; limite 100,00: 60,00 + 40,00 = `ACEITA` (no limite exato), 60,00 + 40,01 = `EM_REVISAO` com `ACIMA_DO_LIMITE_SEMANAL` na rota e no envio, com reembolso e movimento gravados; devolução cancelada não conta; devolução da semana anterior não conta (semana cadastrada e, sem semana cadastrada, segunda a domingo de Brasília, com virada no domingo 23:30); devoluções de outro vendedor não contam; vale mesmo com autorização; duas devoluções do mesmo vendedor em vendas diferentes ao mesmo tempo, cada uma abaixo e juntas acima: exatamente uma em revisão.
7. **Valor de aprovação**: vazio nunca; valor 50,00: reembolso 50,00 = `ACEITA`, 50,01 na rota = 409 `DEVOLUCAO_PRECISA_DE_AUTORIZACAO` sem gravar nada, no envio = `EM_REVISAO` com `ACIMA_DO_VALOR_SEM_AUTORIZACAO`; com autorização `ACIMA_DO_VALOR` aprovada = `ACEITA`; autorização `FORA_DO_PRAZO` aprovada também dispensa o valor.
8. **Reembolso maior que o pago**: venda com um `REEMBOLSO` avulso gravado direto no banco; a devolução entra `EM_REVISAO` com `REEMBOLSO_MAIOR_QUE_O_PAGO`.
9. **Motivos juntos**: envio fora do prazo e acima do limite = os dois motivos, em ordem fixa; uma `EM_REVISAO` da 6.3 (só prazo) continua com `FORA_DO_PRAZO`.
10. **Revisão da dona**: **Manter** = `MANTIDA`, nada muda (saldo da maleta, pagamentos, a receber); **Não aceitar** = `NAO_ACEITA` com observação obrigatória, movimentos e reembolso ficam e o valor aparece como pendência do vendedor na consulta; repetir a mesma decisão 200, a outra 409; devolução `ACEITA` = 409 `DEVOLUCAO_NAO_ESTA_EM_REVISAO`; depois da decisão, cancelar = 409 (regra da 6.3); banco recusa `MANTIDA` sem `revisada_*`, motivo da revisão alterado e `ACEITA` com motivo.
11. **Taxa de devolução** (massa conhecida): vendedor A vendeu 1.000,00 nas 4 semanas e devolveu 200,00 = sem aviso (20% exato); 200,01 = aviso com o nome e "20%" arredondado na tela; vendedor B abaixo = sem aviso; venda e devolução do 29º dia para trás não contam; devolução cancelada e venda estornada não contam; vendido zero com devolução = aviso; mudar o percentual pela rota da garantia vale na hora; vendedor inativo não aparece.
12. **Avisos**: `DEVOLUCOES_PARA_AUTORIZAR` conta as `PEDIDA` (singular e plural) e some quando a dona decide; `DEVOLUCOES_PARA_REVISAR` conta as `EM_REVISAO` e some ao decidir; sem nada, nenhum dos três; os avisos que já existiam continuam.
13. **Permissões**: vendedor nas rotas da dona (listar, ver, aprovar, negar, manter, não aceitar) = 403; sem login = 401; vendedor B não vê a autorização de A (404), não pede autorização de venda de A (404) e não usa autorização de A numa devolução (404).
14. **Nada de custo**: nenhuma resposta das rotas novas tem chave com "custo" (nem "fornecedor").
15. **Imutabilidade (banco)**: `UPDATE` de conteúdo em `autorizacoes_excecao` recusado; transições `NEGADA → APROVADA`, `PEDIDA → USADA`, `USADA → APROVADA`, `VENCIDA → USADA` recusadas; `UPDATE` e `DELETE` em `itens_autorizacao` recusados; item de outra venda recusado; pertença (vendedor e venda) não muda.
16. **Conciliação**: saldo da maleta e "estoque da loja + maletas" sem diferença depois de devolução autorizada, em revisão, mantida e não aceita.
17. **Registro de tabelas e pull**: as duas tabelas novas no registro com regra declarada, o pull não entrega linha delas, a conferência por resumo passa de 17 para 19 tabelas (`PullDoVendedorTest`), e os testes de convenção (`VerificadorDeConvencoes`, `RegrasDoVendedorTest`) passam.

## Ordem dos commits (cada passo com push e Situação atualizada)

1. `docs`: glossário e especificação (só o aprovado).
2. `test(devolucoes)`: os testes acima, vermelhos.
3. `feat(db)`: V037.
4. `feat(devolucoes)`: pedido, aprovação, negação, consultas e rotas da autorização; agenda das vencidas.
5. `feat(devolucoes)`: autorização, limites e motivos de revisão na devolução; manter e não aceitar.
6. `feat(devolucoes)`: taxa de devolução e os três avisos.
7. `chore(api)`: registro de tabelas e clientes regerados.
8. `docs`: `fase-6.md` e `PROGRESSO.md`. Localmente: `npm run api:test`, `lint`, `typecheck`, `format:check` e `barreira:varrer` (o cliente gerado do vendedor vai no pacote do Corona Vendas).

## Riscos

- **Trocar `proteger_devolucao` e `conferir_devolucao_da_venda`**: o `CREATE OR REPLACE` precisa manter tudo o que a V036 conferia; os testes de imutabilidade da 6.3 (item 14 dela) rodam de novo e provam.
- **`motivos_revisao` em texto**: não há lista (`text[]`) em tabela sincronizável e o SQLite do app não tem esse tipo; o `CHECK` por expressão regular segura os códigos (dúvida P2).
- **Trava por vendedor**: nova e pega antes da maleta; só a devolução a usa, então não inverte ordem com venda, estorno ou acerto. O teste 6 prova a soma sob concorrência.
- **Sem internet o app não sabe do limite semanal nem do valor de aprovação** (6.2): devolução acima deles chega pelo envio e vira revisão, nunca recusa. Na rota online o vendedor recebe o 409 do valor e pede autorização.
- **Taxa calculada a cada abertura da tela inicial da dona**: duas somas por vendedor em 28 dias, com os índices `vendas_por_vendedor` e `devolucoes_por_vendedor`; se pesar, vira agenda depois.
- **6.3 e testes antigos**: `DevolucaoDoClienteTest` ganha o campo novo no pedido (opcional, sem mudar os casos); `PullDoVendedorTest` muda a contagem de tabelas (como a 6.3 fez).
- **Pendência do vendedor**: até a 7.5, "Não aceita" é só a situação da devolução; a 7.5 precisa ler `devolucoes NAO_ACEITA` ainda não cobradas (fica no relatório).
- Número da migração: se outra tarefa ocupar a V037 antes, uso o próximo livre.

## O que não fazer

- Nada de crédito do cliente nem `CREDITO_ACIMA_DO_SALDO` em uso (6.5); venda a prazo (6.6); outbox, push e filtro real do pull (6.7: tipo `PEDIDO_AUTORIZACAO`); telas (6.8 e 6.9); devolução registrada pela dona (ADR-0014, item 13, fica para a 6.9); relatório de autorizações e de taxa (6.10); tabela `pendencias_acerto` e cobrança no acerto (7.5); cancelar pedido de autorização pelo vendedor.
- Nunca `UPDATE` de conteúdo nem `DELETE`; revisão e decisão não mexem em movimento, pagamento nem valor; nenhum valor ou tipo vindo do app vale; nenhuma migração publicada editada; o limite semanal e o valor de aprovação continuam fora do bloco `parametros`.

## Dúvidas (com o padrão que sigo se não houver resposta)

| Nº | Pergunta | Padrão |
| --- | --- | --- |
| P1 | Peças da autorização numa tabela filha `itens_autorizacao` (sincronizável, imutável, com chave para a linha da venda) ou numa coluna JSON? | **Tabela filha** (muda a §9) |
| P2 | Motivos da revisão numa coluna de texto com códigos separados por vírgula e `CHECK`? | **Sim** (sem lista no PostgreSQL nem tabela nova no sync) |
| P3 | Qual é a "semana de acerto" do limite semanal? | A **semana cadastrada** que contém o dia de Brasília da devolução; sem semana cadastrada, **segunda a domingo** de Brasília |
| P4 | "Acima do limite" e "acima do valor de aprovação" são estritos? O limite conta o reembolso das devoluções não canceladas (incluindo em revisão e não aceitas)? | **Sim** e **sim** (no valor exato passa) |
| P5 | Acima do limite semanal na rota online: revisão ou 409? | **Revisão** nos dois modos (não trava o balcão); o valor de aprovação, sim, é 409 na rota |
| P6 | Autorização aprovada (de qualquer tipo) dispensa o prazo **e** o valor de aprovação daquela devolução, mas não o limite semanal? A devolução precisa ser da mesma venda, do mesmo motivo e com peças dentro do autorizado (menos pode)? | **Sim** e **sim** |
| P7 | Validade de 7 dias contados como? | **Dias de Brasília**: vale até o dia da aprovação + 7, inclusive; fixo (sem chave nova), gravado em `valida_ate` |
| P8 | `VENCIDA` gravada por agenda diária ou só calculada? | **Agenda diária** (00:10 de Brasília) para a situação; a regra do uso olha sempre a data |
| P9 | A taxa de devolução é uma tarefa diária gravada ou calculada na hora? Qual janela? | **Calculada na hora** (`FonteDeAvisos`, como os outros avisos); **28 dias de Brasília** até hoje, devolvido e vendido cada um pelo seu dia; vendido zero com devolução avisa |
| P10 | Pode haver dois pedidos abertos para a mesma venda? | **Não**: com um `PEDIDA` ou `APROVADA` ainda válido, 409 |
| P11 | Cancelar a devolução devolve a autorização para uso? | **Não**: continua `USADA` (uma devolução por autorização, para sempre) |
| P12 | "Não aceitar" cria tabela de pendência agora? | **Não**: a pendência é a própria devolução `NAO_ACEITA` até a 7.5; observação obrigatória no "Não aceitar", opcional no "Manter" |
| P13 | Pedido de autorização que o servidor acha desnecessário: | Rota online **409**; pelo envio (6.7) **grava** como `FORA_DO_PRAZO` (nunca recusa o que o app fez) |

## Custo de CI

Uma rodada no fim (o rascunho não gasta): `backend` (~4 min), `sync-duas-pontas` (~4 min, pela migração e pelo pacote `devolucoes`), `frontend` (~3 min, pelos clientes regerados), `migracoes`, `mudancas` e `ci` (~1 min cada). Como a 6.3, cerca de **12 minutos de execução (por volta de 16 cobrados)**; o dobro se uma correção pedir outra rodada.
