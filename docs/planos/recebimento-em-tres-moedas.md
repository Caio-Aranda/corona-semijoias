# Plano: recebimento do representante em Guarani, Dólar e Real

Pedido do responsável (23/11/2026): "Recebimento do representante em Guarani, Dólar e Real". Parte crítica (financeiro):
plano antes do código, testes primeiro. Regras de base: §7.5 da especificação, ADR-0006 (moedas) e ADR-0012 (acerto e
financeiro).

## O que muda na regra

Hoje ninguém digita valor em Dólar ou Guarani (glossário, parte 11). Passa a valer uma **exceção só para o recebimento
do representante** (avulso e "pagou agora" do acerto):

1. A dona escolhe a **moeda** do que recebeu (**Real**, **Dólar** ou **Guarani**) e digita o valor nessa moeda.
2. O **servidor** converte para Real e grava em Real, como sempre (`valor`, `NUMERIC(14,2)`): é o valor que vale para as
   contas, o caixa, os totais e as conciliações. O app nunca manda o valor convertido.
3. A moeda e o valor digitado ficam guardados **só como informação** (para a lista e a ficha mostrarem "Recebido em Gs.
   500.000"). Nunca somam, nunca entram em conciliação.
4. Sem cotação, só Real.

## Conversão (o caminho inverso do ADR-0006, item 6)

```
R$ = arredondar(US$ × usd_venda, 2 casas, HALF_UP)
R$ = arredondar(Gs. × pyg_venda, 2 casas, HALF_UP)
```

- Uma multiplicação só, com o arredondamento no fim (`ConversaoDeMoeda.dolarParaReais` e o `guaraniParaReais` que já
  existe). Dólar com até 2 casas; Guarani inteiro (sem centavos).
- Resultado abaixo de R$ 0,01 é recusado (400): "O valor é pequeno demais para virar reais."
- Resultado acima do máximo de `NUMERIC(14,2)` é recusado (400).
- Na tela (prévia antes de confirmar), a mesma conta em `BigInt` (`packages/shared/src/moedas.ts`), com os mesmos
  exemplos do Java.

**Qual cotação:**
- **Recebimento avulso:** a do **dia do lançamento** (`CotacaoVigente.paraLancamento(agora)`; a mesma que a tela recebe
  em "cotação de hoje"). O pedido pode trazer `cotacaoData` (a data que a tela mostrou): se o servidor usar outra
  (cotação nova chegou com a tela aberta), recusa com 409 `COTACAO_DO_RECEBIMENTO_MUDOU` e nada é gravado.
- **No acerto:** a **cotação do acerto** (a mais recente no fechamento, ADR-0006 item 11), a mesma que converte todo o
  resto do acerto. A prévia e o fechar usam a mesma conta; se a cotação mudar entre os dois, o líquido não muda e o
  recebido em reais é recalculado no fechar (o servidor manda).
- **Sem cotação** (sistema novo, antes da primeira busca): Dólar e Guarani são recusados com 409
  `SEM_COTACAO_PARA_O_RECEBIMENTO`; Real continua normal. A tela já nem oferece as outras moedas.

## Banco (migração nova V058; nada publicado é editado)

`recebimentos_vendedor` ganha:
- `moeda text NOT NULL DEFAULT 'REAL'` com `CHECK (moeda IN ('REAL','DOLAR','GUARANI'))`;
- `valor_na_moeda numeric(14,2)` (o que a dona digitou);
- `cotacao_id uuid REFERENCES cotacoes` (a cotação que converteu).

Travas:
- `REAL` ⇒ `valor_na_moeda` e `cotacao_id` vazios; `DOLAR`/`GUARANI` ⇒ os dois preenchidos e `valor_na_moeda` com o
  mesmo sinal de `valor`;
- Guarani inteiro;
- o gatilho do estorno (`conferir_recebimento_do_vendedor`, `CREATE OR REPLACE`) passa a conferir também a moeda, a
  cotação e `valor_na_moeda = -original`.

As linhas antigas ficam `REAL` (o `DEFAULT` não dispara gatilho). As colunas novas **não vão** para o Corona Vendas (o
pull lista as colunas; o vendedor continua vendo só o valor em Real).

## API (só a dona)

- `POST /api/dona/recebimentos-vendedor`: `moeda` (opcional, padrão `REAL`), `valorNaMoeda` (obrigatório em
  `DOLAR`/`GUARANI`, e aí `valor` não pode vir), `cotacaoData` (opcional). Em `REAL`, igual a hoje. O resumo do 409 ("mesmo
  id, outro conteúdo") passa a incluir a moeda e o valor digitado; em `REAL` ele não muda.
- Estorno: copia moeda, cotação e valor digitado (negativo).
- Fechamento do acerto (`pagamento.recebimentos[]` e `pagamento.contasAntigas[]`): os mesmos `moeda` e `valorNaMoeda`;
  o resumo do pagamento inclui a moeda.
- Leitura (`GET /api/dona/recebimentos-vendedor` e a ficha do acerto): cada recebimento traz `moeda`, `valorNaMoeda`,
  `dolar`, `guarani` e `dataCotacao`, calculados pelo servidor. Na moeda em que foi recebido aparece **o valor
  digitado** (exato); a outra moeda é convertida do Real pela mesma cotação. Recebimento em Real: Dólar e Guarani pela
  cotação do dia do lançamento (como os relatórios). Sem cotação: só Real.

## Telas (Corona Gestão, PT e ES)

- **Financeiro → Recebimentos do representante → Registrar recebimento:** campo **Moeda** (Real, Dólar, Guarani; padrão
  Real), o valor na moeda escolhida e, antes de confirmar, o quadro **"Fica assim"** com R$, US$ e Gs. e a linha da
  cotação. Sem cotação: aviso "Ainda não há cotação do dia. Por enquanto, registre o recebimento em reais." e só Real.
- **Lista de recebimentos:** a coluna Valor mostra as três moedas que o servidor mandou e, quando não foi em Real,
  "Recebido em Gs. 500.000".
- **Acerto → Pagamento ("Pagou agora"):** a moeda ao lado de Dinheiro e de Transferência; os totais continuam vindo da
  prévia do servidor.
- **Ficha do acerto fechado (e PDF da ficha):** "Recebido em Gs. 500.000" na linha do recebimento.
- O relatório "Recebimentos por forma" continua em três moedas pela cotação do dia (fica para depois mostrar o valor
  digitado nele, para não mexer nos relatórios ao mesmo tempo que a tarefa que muda as colunas dos relatórios).

## Testes primeiro

- Java, unidade (`ConversaoDeMoedaTest`): Dólar e Guarani para Real, HALF_UP no meio exato (US$ 0,01 × 6,5 = 0,065 →
  R$ 0,07), Guarani com centavos recusado, taxa zero recusada.
- Java, API (`RecebimentoEmOutraMoedaTest`): Guarani e Dólar gravam o Real convertido, a moeda, o valor digitado e a
  cotação; o caixa e as contas recebem o Real; sem cotação → 409 e nada gravado; cotação mudou → 409; `valor` junto com
  outra moeda → 400; Guarani com centavos → 400; valor pequeno demais → 400; reenvio igual → 200, outro conteúdo → 409;
  estorno copia a moeda e o valor digitado negativo; a lista traz as três moedas com a digitada exata; fechamento do
  acerto com Guarani.
- Java, banco: o estorno que não confere a moeda é recusado pelo gatilho; `REAL` com `valor_na_moeda` é recusado.
- TypeScript: `lerValorNaMoeda`, `reaisDaMoeda` (mesmos exemplos do Java), validação da ponte, a tela (sem cotação só
  Real; Guarani mostra as três moedas e manda `moeda`/`valorNaMoeda`/`cotacaoData`, nunca o Real).

## Fora deste plano

- O app do vendedor: nada muda (ele não registra recebimento do representante).
- Conta antiga paga no acerto: o servidor aceita moeda; a tela continua em reais (fica de melhoria).
- Relatório "Recebimentos por forma" com a coluna do valor digitado (depois da tarefa das colunas dos relatórios).
