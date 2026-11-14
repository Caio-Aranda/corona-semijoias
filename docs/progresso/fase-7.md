# Fase 7: acerto e financeiro (detalhe das tarefas)

O plano está em [fase-7-plano.md](fase-7-plano.md); o desenho, no [ADR-0012](../decisoes/ADR-0012-acerto-e-financeiro.md).

## Tarefa 7.1b: decisões do responsável de 13/11

Só documentos, sem código (issue #144, PR 159). O responsável aceitou o ADR-0012 em 13/11/2026 com quatro mudanças, aplicadas antes de qualquer código da Fase 7.

**O que entrou**

- **ADR-0012:** status "Aceito" (13/11/2026, pelo responsável), com a seção "Mudanças do aceite" no topo e o histórico no fim.
  - Sem entrada: saem `entrada_abatida`, o momento `ENTRADA` do recebimento e o abatimento no fechamento; `a_pagar_no_acerto = liquido` (itens 2, 3, 8 e 9, conciliação C2).
  - Sem cheque: saem as tabelas `cheques` e `eventos_cheque`, o lançamento `CHEQUE_COMPENSADO`, a conta a receber de cheque devolvido, a trava e a auditoria de cheque e a conciliação C6 (itens 1, 2, 7, 10, 11, 13 a 17). Repasse do vendedor: `DINHEIRO`, `PIX` (na tela, "Transferência") e `BOLETO`; fornecedor: dinheiro, transferência e boleto.
  - Comissão por meta (item 5 reescrito): soma do vendido líquido das vendas do acerto em Guarani, cada venda pela cotação gravada nela; 700.000 Gs ou mais → 40%, abaixo → 30%, sobre o total; meta e percentuais em `configuracoes`; `vendedores.comissao_percentual` fica sem uso; comissão por venda com o percentual na linha; estorno pelo percentual da linha original; prévia com quanto falta para a meta.
  - Exemplo numérico (item 18) refeito sem entrada e sem cheque, com a meta e 30%.
- **Plano da Fase 7:** tarefas 7.5 (comissão por meta), 7.6 (sem entrada e sem cheque), 7.7 (formas do fornecedor), 7.8 (nove conciliações), 7.10 e 7.11 (meta nas telas); testes obrigatórios trocados (meta no limite, cotações diferentes, percentual da linha original, boleto, forma recusada; saíram os de cheque e de entrada); dúvidas 2, 4, 7, 11, 14 e 20 marcadas como respondidas; última migração V040.
- **Especificação v1.14:** §2 (aba Totais: sem entrada e sem cheque no sistema novo), §6 premissa 8, §7.4 (comissão por meta e formas do repasse), §7.5, §9 (`cheques` sai; `acertos`, `comissoes`, `vendedores` e `configuracoes` anotados), §12, §15, §17 (itens 6, 12 e 13) e §22.
- **Índice de decisões e `PROGRESSO.md`:** ADR-0012 como Aceito e a linha desta tarefa.

**Testes:** não há código. `npm run format:check` passa.

**Decisões que tomei**

- **O que conta para a meta:** a venda estornada, ou a parte devolvida, sai da soma quando o estorno ou a devolução está no mesmo acerto (pela cotação da própria venda); estorno e devolução de venda de acerto já fechado não entram na soma, só geram o estorno de comissão pelo percentual da linha original. A issue fala em "vendido líquido das vendas ligadas ao acerto" e não diz o que fazer com o estorno.
- **Venda sem cotação gravada** (`vendas.cotacao_id` aceita vazio): usa a cotação que valia no dia dela (ADR-0006).
- **O acerto guarda o percentual decidido e a soma em Guarani** (`percentual_comissao`, `vendido_para_meta_guarani`), para a dona e o vendedor conferirem; é a única exceção a "não se grava valor convertido", e nenhuma conciliação em Real a usa.
- **Meta e percentuais valem pelo instante do fechamento**; mudar depois não altera acerto fechado. As três chaves de `configuracoes` nascem na migração da 7.5 (nomes e faixas ficam para o plano dela).
- **"Quanto falta para a meta" em Real:** convertido pela cotação mais recente, só para mostrar.
- **Numeração preservada:** os itens 9 e 10 do ADR e a conciliação C6 mantêm o número (o 10 e a C6 dizem que foram retirados), para as referências "item 17", "item 18" e "C1 a C10" continuarem valendo. São nove conciliações.
- **Pagamento a fornecedor** grava os mesmos três valores do repasse (`DINHEIRO`, `PIX`, `BOLETO`).
- **§15 da especificação** (linha da Fase 7) também perdeu "cheques", embora a issue não cite a seção, para não ficar contraditória. A §13 não mudou: não cita entrada nem cheque (as fórmulas corretas estão no ADR-0012, item 17).
- **Cabeçalho da especificação** dizia versão 1.12 com o histórico já na v1.13; passou a 1.14.
- **Prazo de 3 a 4 dias para o acerto** (dúvida 20): só informativo, sem bloqueio; quem fecha a semana continua no padrão seguro.
- **Glossário e ADR-0014** (fora da lista da issue, acertados na revisão para não ficarem contra a decisão): no `docs/glossario-interface.md` saíram as situações de cheque, a forma `PIX` aparece como **Transferência** também no acerto e no pagamento a fornecedor, "cheque" só vale para pagamento antigo e "entrada" não existe (a dúvida da parte 10 foi fechada); o ADR-0014 cita o ADR-0012 como Aceito.

**Não fiz (fica para as próximas tarefas)**

- Os termos novos do glossário (acerto, pendência, ficou devendo, meta da comissão) entram nas tarefas das telas (7.9 a 7.12).
- `docs/perguntas-para-a-dona.md` e os outros ADRs não foram mexidos.
- Nenhuma migração, chave de configuração ou tela: tudo isso é das tarefas 7.5, 7.6, 7.10 e 7.11.
