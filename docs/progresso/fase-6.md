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
