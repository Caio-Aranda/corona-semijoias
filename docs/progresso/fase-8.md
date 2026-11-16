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
