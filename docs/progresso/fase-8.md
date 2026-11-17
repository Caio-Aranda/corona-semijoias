# Fase 8: painel, relatórios e conciliações (detalhe das tarefas)

O plano está em [fase-8-plano.md](fase-8-plano.md); as decisões, no [ADR-0015](../decisoes/ADR-0015-numeros-gerenciais-e-conferencia-diaria.md).

## Tarefa 8.2: arrumação dos testes

Issue #176, PR 188. Só código de teste; nenhuma mudança de produção, de regra ou de migração.

**Como reproduzi.** A suíte inteira em `-Dsurefire.runOrder=reversealphabetical` antes de corrigir: 1612 testes, 34 falhas em 12 classes. Depois de cada correção rodei as classes afetadas em combinações pequenas e, no fim, a suíte inteira.

| Classe que quebrava | Causa | Correção |
| --- | --- | --- |
| `RelatoriosDeMaletaTest` (14 testes, 409 ao criar semana) | `VendasDoVendedorTest` usava as mesmas semanas (número 80000 e ano 2400) e as deixava gravadas | `VendasDoVendedorTest` passou a usar o ano 2800 e os números 91000 (cada classe com a sua faixa) |
| `CotacaoVigenteTest`, `CotacoesDaDonaTest`, `AvisoDeCotacaoTest`, `BuscaDeCotacoesTest`, `TabelaCotacoesTest`, `ComissaoEPendenciasTest`, `FechamentoDoAcertoTest` (exemplo do ADR) | `RotasDoVendedorTest` gravava cotações de 1990 de verdade e não tirava (a tabela é imutável e vale a "mais antiga do banco" quando não há anterior) | `RotasDoVendedorTest` tira as cotações no `@AfterAll`, por `CotacoesDaMassa.tirarDias` (novo, genérico por dia) |
| `LoginTest` e `SenhaTemporariaTest` (primeiro administrador, 403) | `GestaoDeUsuariosTest` desativava todas as administradoras e só reativava uma, deixando a do primeiro acesso inativa | Os dois testes anotam as administradoras ativas e devolvem todas no fim |
| `TrocaTest.prazoContaEmDiasDeBrasiliaComoNaDevolucao` | Os prazos de garantia só eram postos em 7 e 30 depois de cada teste; o primeiro da classe via o padrão de 45 dias | `ApoioDasTrocas` e `ApoioDasDevolucoes` montam os prazos também antes de cada teste |
| `RecebimentosDoVendedorTest.boletoPagoSoInteiroENaContaDoBoleto` | Lia "o último lançamento do caixa do banco", que podia ser de outra classe | Lê o lançamento daquele recebimento |
| (efeito colateral da minha correção) | Semanas de 2700 colidiam com as do `CenarioDeSync` | Vendas ficou em 2800 |

**Resultado final (verdadeiro).** Todos no mesmo computador, um Maven por vez:

- `./mvnw test -Dsurefire.runOrder=reversealphabetical`: 1612 testes, 0 falhas.
- `./mvnw test -Dsurefire.runOrder=alphabetical`: 0 falhas.
- `npm run api:test` (ordem normal, `verify`): 1612 testes, 0 falhas, BUILD SUCCESS.
- `TrocaTest` e `AutorizacaoDeDevolucaoTest` rodados sozinhos: verdes.
- `npm run lint`, `typecheck` e `format:check`: passam.

**Não fiz:** rodar cada uma das classes sozinha, uma a uma (só as citadas acima e as combinações). Nenhum defeito real de produção apareceu.

## Tarefa 8.10: Corona Vendas, pedir o acerto e contar as sobras

PR 189 (issue #184). Implementador (Sonnet, esforço médio). Só no Corona Vendas e em `testes-sync/`: sem backend, sem migração, sem rota nova, sem outbox.

**O que entrou**

- **Ponte e motor do Corona Vendas:** três operações novas, `acertos.pedir`, `acertos.agora` e `acertos.ler`, que vão **direto** à API do vendedor (`pedirMeuAcerto`, `verMeuAcerto`, `registrarMinhaLeituraDoAcerto`, do cliente já gerado) e **nunca** são consulta local (`ehConsultaLocal`). A validação da ponte é exata: só ids (UUID), a peça, a quantidade inteira diferente de zero (1 para ler, negativa para desfazer) e o estado `BOA` ou `DEFEITO`; qualquer campo a mais (custo, total, comissão) é recusado.
- **Telas:** em "Meu acerto e minha comissão", a seção "Pedir o acerto" (botão **Pedir acerto** com janela de confirmação; com acerto em andamento, só **Contar as sobras**). `TelaContarSobras`: leitor de código (acha a peça nas maletas do acerto pela mesma consulta da conferência), escolha Boa ou Com defeito, painel da peça lida com foto, tabela "Peças contadas" com o que o servidor devolveu e **Desfazer uma** por linha. Acerto que a dona já fechou ou desistiu aparece sem leitor ("A dona cuida do resto"). Nenhum botão de fechar ou desistir.
- **Idempotência:** o id do pedido é reaproveitado na nova tentativa e só muda depois de um pedido que deu certo; o id da leitura é reaproveitado enquanto a mesma leitura (peça, estado, quantidade) não deu certo.
- **Sem internet:** "Para pedir o acerto é preciso internet. Conecte-se e tente de novo." e "Para contar as sobras é preciso internet. Conecte-se e leia o código de novo."; nada de fila, envio ou pendência na tela (teste confere).
- **Testes:** `validacao-da-fase-8.test.ts` (ponte), `main/api-do-acerto.test.ts` (rota e corpo de cada operação, falha sem internet, erro do servidor, nunca local), `renderer/pedir-acerto.test.tsx` (14 regras de tela) e o **caso 23 de duas pontas** (`testes-sync/src/cenarios/pedir-acerto-e-contar.duas-pontas.test.ts`): pedir (e repetir o pedido com o mesmo id: um acerto só), ler, reenviar a mesma leitura (conta uma vez), ler e desfazer, sem internet nada é gravado nem entra na outbox, a dona vê que as leituras são do vendedor, prévia e fechamento (líquido 70,00 pela comissão de 30%), o computador recebe o acerto fechado com esperado 2 e contado 2, e depois de fechado o servidor recusa nova leitura. Conciliações finais do `testes-sync` passam.

**Desvios e observações**

- O caso 23 precisa de uma **cotação do dia** para a dona fechar, e a API só a busca na internet. Foi criado `testes-sync/src/cotacao-de-teste.ts` (grava uma cotação de hoje no banco descartável, no mesmo molde da máquina do tempo); a máquina do tempo deixou de ser dita "o único ponto" que grava no servidor.
- Peça "para a loja" de uma devolução do cliente que não é de nenhuma maleta do acerto: a tela só acha a peça pelas maletas do acerto, então ela não é lida pelo vendedor (a dona conta essa). Sem mudança de regra.
- `docs/progresso/fase-8.md` e a linha da 8.10 em `docs/PROGRESSO.md` podem conflitar com os da 8.2 (branch paralela): é só juntar as duas entradas.

**Como conferir:** `npx vitest run --root apps/seller-app`, `npm run test:duas-pontas -w @corona/testes-sync -- src/cenarios/pedir-acerto-e-contar.duas-pontas.test.ts`, `npm run seller:empacotar` e `npm run barreira:varrer -- apps/seller-app/release/win-unpacked/resources/app.asar packages/shared packages/ui packages/conexao-segura`.

## Tarefa 8.3: conciliações C11 e C12

Issue #177, PR 190. Plano aprovado no 1º comentário do PR. Só leitura, sem migração, sem rota nova, nada no Corona Vendas.

**O que mudou.**

- `ConciliacoesDaFase7` ganhou `c11()` e `c12()` (as onze, no mesmo `conferir()` e no mesmo retrato `REPEATABLE_READ`). A C11 confere cada peça: loja mais saldo das maletas abertas contra comprado (compras registradas) mais lançamentos da dona, menos vendido, mais devolvido à maleta, mais recebido na loja pelo acerto, mais peça achada, menos falta e defeito aprovado, cada parcela do documento de origem. A C12 confere cada maleta enviada, por peça: saldo pelos movimentos contra enviado (baixa do estoque), vendido, devolvido pelo cliente, devolvido à dona e falta; mais as regras "nenhuma peça negativa" e "maleta cancelada sem peça".
- Uma fórmula só: `ServicoDeEstoque.SQL_SALDO_NAS_MALETAS_ABERTAS` (loja + maletas) serve à C7 e à C11 (o texto da consulta da C7 ficou o mesmo); `saldoDaMaleta` serve à C8 e à C12.
- Corona Gestão: só os títulos e as explicações da C11 e da C12 (a tela já desenha o que a API manda por `chave`); teste de tela com a C12. Cliente da dona regerado (só a descrição); `api-client-vendedor` sem diferença.
- Documentos: especificação v1.24 (§13), ADR-0012 item 17, ADR-0008 item 9 e glossário.

**Testes** (`ConciliacoesDoEstoqueCompletoTest`, massa `MassaDoEstoqueCompleto` por cima da massa da 7.8, conta à mão no comentário da massa): as onze dão "Tudo certo" antes e depois do acerto; estragos provam cada regra: compra de 10 cujo movimento guarda 9 (a C11 acusa e a C7 não), venda alterada, peça a mais na loja, envio maior que a baixa, movimento de troca apagado, `AJUSTE` de maleta, saldo negativo, movimento em maleta cancelada, falta alterada no acerto (C11 e C12). As permissões (401 e 403 do vendedor), a contagem de onze conferências e a prova de que a rota não grava (`SoLeituraDasConciliacoesTest`, com mais seis tabelas) foram atualizadas.

**O que a execução mostrou (diferença do plano, sem mudar regra).** No plano, a devolução D1 voltava à maleta. A devolução registrada **pela dona** manda a peça para a loja (linha "peças para a loja" do acerto), então o saldo de M1 A é 3 e não 4; a conta à mão foi refeita no comentário da massa. Isso também exercita o termo "recebido na loja" da C11. Outra diferença: apagar um lançamento da dona junto com o atalho da peça nenhuma conferência vê (o lançamento da dona é o próprio documento de origem); por isso o estrago da compra altera a quantidade do movimento, não o apaga.

**Não fiz.** A rodada diária e o aviso (8.4). Nenhum botão de corrigir. Nenhum valor em Real (só peças).

## Tarefa 8.4: conferência diária

Issue #178, PR 195. Plano aprovado no 1º comentário do PR, ajustes no 2º. Migração **V047** (a próxima livre na `main`).

**O que entrou**

- **Banco (V047).** `rodadas_conferencia` e `diferencas_conferencia`, só do servidor, imutáveis (as convenções do servidor recusam `UPDATE`, `DELETE` e `TRUNCATE`, até para o dono das tabelas), fora do sync (registradas como tabelas do servidor). `CHECK`s: `PEDIDA` exige quem pediu e `AUTOMATICA` não tem; `FALHOU` se e só se há chave com falha; `SEM_DIFERENCA` tem total zero; término depois do início.
- **Rodada.** `ServicoDaConferenciaDiaria.rodar(id, origem, pedidaPor)`: id já gravado devolve a rodada (200); trava consultiva `pg_try_advisory_xact_lock` (outra rodando: 409); uma transação `REPEATABLE_READ`; cada uma das treze conferências (`CatalogoDasConferencias`: estoque, devolução e troca, C1 a C5, C7 a C12) dentro de um `SAVEPOINT`, para o erro de uma não derrubar as outras; só `INSERT` nas duas tabelas novas. Nenhuma fórmula de conferência mudou: os adaptadores chamam as classes de produção.
- **Agenda.** `AgendaDaConferenciaDiaria`, `0 0 3 * * *` na zona `America/Sao_Paulo`, ligada por padrão e desligada nos testes (`corona.conferencia-diaria.agendada`). Erro dentro dela só vai para o log.
- **Rotas (só ADMIN).** `POST /api/dona/conferencias` (201, 200, 409, 400 sem id), `GET /api/dona/conferencias` (histórico, 30 por página, no máximo 100) e `GET /api/dona/conferencias/{id}` (404 em português). Nenhuma rota do vendedor.
- **Aviso.** `FonteDeAvisosDaConferencia`, calculado na hora sobre a última rodada, com o `Clock`.
- **Corona Gestão.** Tela **Conferência de hoje** (última rodada, **Conferir agora** com o mesmo id no segundo toque depois de erro de conexão, histórico, detalhe), canais novos na ponte (`conferencias.listar`, `conferencias.ver`, `conferencias.conferirAgora`) e o aviso novo leva à tela. Cliente da dona regerado; `api-client-vendedor` sem diferença.

**Desvios e observações**

- **409 (registrado no ADR-0015).** A segunda rodada pedida enquanto outra roda recebe 409, e não o resultado da que está rodando.
- **`noRollbackFor`.** Os três métodos de leitura que a rodada chama (`ConciliacoesDaFase7.conferir`, `ConferenciasDeDevolucaoETroca.conferir` e `ServicoDeEstoque.conciliar`) ganharam `noRollbackFor = RuntimeException.class`: sem isso, um erro que passa por eles marca a transação de fora como "só desfazer" (o teste do erro forçado provou). Eles só leem, então não há nada a desfazer.
- **Nome do corpo.** O corpo do "Conferir agora" chama `PedidoDeConferenciaDiaria` porque `PedidoDeConferencia` já existe no cliente do vendedor (conferência da maleta) e o OpenAPI trocaria o nome dele.
- **Testes antigos.** Nenhum teste existente esperava a lista de avisos vazia por inteiro (todos filtram por tipo). Os testes novos que gravam rodada deixam uma rodada limpa no fim (`RodadasDeTeste`), para o aviso novo não aparecer nos outros.

**Como conferir:** `npm run api:test`, `npx vitest run --root apps/owner-app`, `npm run lint`, `npm run typecheck`, `npm run format:check`.

## Tarefa 8.5: relatórios de vendas e curva ABC

Issue #179, PR 196. Só leitura: nenhuma migração, nenhuma regra nova, nada vai ao Corona Vendas.

**O que entrou**

- **Uma consulta só do vendido líquido** (`VendidoLiquido`): uma linha por peça de cada venda, com vendido (preço x quantidade - desconto da linha), devolvido (devoluções e trocas não canceladas, pelo preço que o cliente pagou) e líquido. Os relatórios de vendas e a curva ABC só agrupam essa consulta; o DRE (8.6) e o painel (8.7) vão usar a mesma. Soma no banco, em `NUMERIC`.
- **Onze relatórios de vendas** em `/api/dona/relatorios/vendas/...` (só ADMIN): `vendas` (uma linha por venda; padrão só as feitas, `situacao=CANCELADAS` ou `TODAS`), `canceladas` (com quem cancelou, quando e o motivo), `por-vendedor`, `por-praca`, `por-cliente`, `por-produto`, `por-categoria`, `por-periodo` (por dia de Brasília), `ranking-de-vendedores`, `ranking-de-pecas` e `ticket-medio`. Filtros: busca, vendedor, praça, cliente, produto, categoria, período. Rodapé e detalhamento (`recorte`) no motor dos demais relatórios.
- **Curva ABC** em `/api/dona/relatorios/estoque/curva-abc`: por produto, pelo vendido líquido do período; A até 80%, B até 95%, C o resto; a peça que cruza a fronteira fica na classe de cima (a classe sai do acumulado antes da peça, em números exatos, nunca do percentual arredondado); empate pelo código; peça sem venda líquida fica fora. Mostra a foto (a falta de foto não trava).
- **`Dinheiro`** ganhou `dividir` (ticket médio: R$ 100,00 em 3 vendas = 33,33) e `percentualDe` (HALF_UP, duas casas); o arredondamento continua só lá.
- **Corona Gestão.** Grupo de menu **Relatórios de vendas** (onze telas) e **Curva ABC dos produtos** no grupo Estoque, no padrão da 7.12 (filtros, totais no rodapé, PDF, Excel e CSV, três moedas). Cliente da dona regerado; `api-client-vendedor` sem diferença.

**Definições adotadas (padrões seguros do plano, sem mudar regra)**

- O período e o dia de uma linha são os da **data da venda** (dia de Brasília). A devolução ou a troca de uma venda abate a venda de onde ela saiu, qualquer que seja a data da devolução.
- Dólar e Guarani: cotação do dia da **venda**, por linha de peça (o devolvido e o líquido da linha usam a mesma cotação da venda); o total em Dólar ou Guarani é a soma das linhas já convertidas. Venda sem cotação fica sem Dólar e sem Guarani. Nos relatórios agrupados não há uma data de cotação única por linha (a linha reúne várias vendas); a data aparece na linha de cada venda (`vendas`, `canceladas`) e, no relatório por dia, é o próprio dia.
- Venda estornada fica fora de todo total e aparece marcada (`Cancelada`). Nos agrupados nunca entra. O rodapé "canceladas" só tem valor quando a lista pede canceladas ou todas (como nas devoluções).
- Ticket médio = vendido líquido ÷ número de vendas do grupo (venda com tudo devolvido continua contando como venda); em produto e categoria, "vendas" são as vendas que têm aquela peça ou categoria.
- Peça sem custo em nenhuma resposta: um teste confere que as respostas não têm "custo" nem "lucro".

**Testes** (`RelatoriosDeVendasTest`, massa de março de 2292 com a conta à mão no comentário: vendido 950, devolvido 275, líquido 675, com desconto de linha, troca, devolução cancelada e venda estornada): por vendedor, praça, cliente, categoria, produto e dia com Real, Dólar e Guarani; rankings e valor médio; filtros; venda estornada fora e marcada; detalhamento; curva ABC com as três faixas e com filtro; **a curva e todos os relatórios de vendas batem no vendido líquido** (por produto e no total, e contra uma soma direta no banco); 401 e 403 em todas as rotas; pedidos mal feitos; paginação. Mais `DinheiroTest` (ticket médio e percentual) e `relatorios-vendas.test.tsx` na Gestão.

**Não fiz.** Custo e lucro (8.6). Filtro por cliente e por produto na tela da Gestão (a busca já acha os dois pelo nome; a API aceita os dois). Data de cotação única nas linhas agrupadas.

**Como conferir:** `npm run api:test`, `npx vitest run --root apps/owner-app`, `npm run lint`, `npm run typecheck`, `npm run format:check`.

## Tarefa 8.6: resultado do período e lucro

**O que mudou.** Quatro rotas só da dona (ADMIN), só leitura, em `/api/dona/relatorios/financeiro`: `resultado` (o DRE), `resultado/detalhamento` (os registros de cada linha), `lucro-por-produto` e `lucro-por-vendedor`. Período obrigatório (400 em português). Classes novas `CustoDaPeca` (a fórmula do custo na data, só SQL) e `ConsultasDoResultado`. **Sem migração.**

- **Uma fórmula só.** Receita, devolução e peças saem do `VendidoLiquido` (o mesmo da 8.5); o custo só do `CustoDaPeca`; a comissão do DRE e do lucro por vendedor da mesma subconsulta. Somas no banco, em `NUMERIC`; o único arredondamento é a margem (`Dinheiro`).
- **Custo da peça (ADR-0015, R1).** Último custo de compra até o dia da venda (o dia da compra vale; duas no mesmo dia, a última; compra desfeita não conta); sem compra, o custo do cadastro (`produtos_custos`), marcado. Calculado na leitura; nada gravado.
- **Devolução pela data da venda** (diverge do item 2 do ADR, registrado em "Como ficou 8.6"): o DRE bate com os relatórios de vendas e a curva ABC.
- **Perdas.** Faltas do acerto pelo custo no dia do fechamento, menos as encontradas depois (no dia em que foram encontradas); avarias do estoque; inventário só para menos. **Cobrado do vendedor** = `acertos.faltas_cobradas`. **Caixa** só com lançamentos manuais, por categoria; o caixa automático do acerto e o pagamento a fornecedor ficam fora.
- **Gestão.** Quatro telas no grupo Relatórios financeiros (Resultado do período, Registros do resultado, Lucro por produto, Lucro por vendedor), com o aviso "A devolução abate o mês em que a venda foi feita...", a marca "Custo do cadastro" e a contagem de peças nessa situação; PDF, Excel e CSV pelo motor existente. Termos novos no glossário.

**Testes.** `CustoDaPecaTest` (antes, no dia e depois da compra; sem compra; compra desfeita; mesmo dia; custo exato 3 x 7,33 = 21,99). `ResultadoDoPeriodoTest` (massa de abril de 2292 com a conta à mão: resultado -168,00; as três moedas linha a linha; sem cotação = coluna vazia; detalhamento de cada linha; lucro por produto 73,00 e 67,50 e por vendedor 63,50 e 63,00; **bate** com vendas, curva ABC, comissões, acertos e fluxo de caixa; devolução que chega no mês seguinte; falta encontrada depois; 400; só leitura). `PermissoesDoResultadoTest` (401, 403 e nenhuma rota do vendedor devolve custo ou lucro). `relatorios-resultado.test.tsx` na Gestão.

**Não fiz / decisões.** As classes de teste do plano `LucroPorProdutoEVendedorTest` e `UmaFormulaSoDoResultadoTest` ficaram dentro de `ResultadoDoPeriodoTest` (a massa de abril é única e o banco é dividido entre as classes). O detalhamento abre pela tela "Registros do resultado" (escolhendo a linha), não ao tocar na linha do resultado. A exclusão do caixa automático foi testada só com o pagamento ao vendedor do acerto a favor (45,00); o pagamento a fornecedor e o recebimento do acerto não têm massa própria.

**Como conferir:** `npm run api:test`, `npx vitest run --root apps/owner-app`, `npm run lint`, `npm run typecheck`, `npm run format:check`.
