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
