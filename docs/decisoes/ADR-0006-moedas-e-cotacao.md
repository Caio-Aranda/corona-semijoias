# ADR-0006: Moedas e cotação

- **Status:** Aceito
- **Data:** 09/11/2026 (aceito no mesmo dia: as regras são do responsável; a fonte, a taxa de venda, as fases e o número da tarefa 19 foram decididos pela orquestradora na revisão do plano)
- **Tarefas que implementam:** 19 (backend das cotações, antes da Fase 1); telas da dona na Fase 1; app do vendedor online na Fase 4; pull e uso sem internet na Fase 5; acerto na Fase 7

## Contexto

Em 09/11/2026 o responsável decidiu (especificação v1.5, §7.5):

- o financeiro é completo, mas o sistema **não executa pagamentos**; as formas de pagamento são só registradas;
- tudo é **gravado em Real**, com `NUMERIC(14,2)` e as regras do ADR-0002, que não mudam;
- todo valor mostrado na tela, para a dona e para o vendedor, aparece **também em Dólar e em Guarani** (o Guarani sem centavos);
- vale a **cotação oficial do Banco Central**, buscada uma vez por dia pelo servidor e guardada dia a dia, sem apagar nem corrigir por cima;
- relatórios convertem pela cotação **do dia de cada lançamento**, e o acerto grava a cotação que usou;
- busca que falhou usa a do dia anterior e avisa a dona; fim de semana e feriado usam a do último dia útil, sem aviso;
- o app do vendedor recebe as cotações pelo servidor e, sem internet, usa a última recebida e mostra a data dela.

Isso precisa estar decidido **antes da Fase 1**, porque ela cria as primeiras telas com valores e a primeira migração que guarda algo além de dinheiro e porcentagem. Este ADR decide a fonte, a leitura, o calendário, o que se guarda, a taxa, a conversão, o arredondamento, os totais e as proteções.

## Decisão

### 1. Fonte: o arquivo diário de fechamento do Banco Central

**Fonte única, para o Dólar e o Guarani:** o arquivo diário de fechamento do PTAX publicado pelo Banco Central do Brasil (BCB):

```
https://www4.bcb.gov.br/Download/fechamento/AAAAMMDD.csv
```

**Por que não a API PTAX (Olinda).** Consulta de **09/11/2026**:

- `https://olinda.bcb.gov.br/olinda/servico/PTAX/versao/v1/odata/Moedas` devolve **só 10 moedas**: AUD, CAD, CHF, DKK, EUR, GBP, JPY, NOK, SEK e USD. **O Guarani (PYG) não está na lista.**
- `CotacaoMoedaDia` com `moeda='PYG'` e data 06/11/2026 devolve uma lista vazia.

**O que o arquivo traz.** Consulta de **09/11/2026** ao arquivo de 06/11/2026: 156 linhas, todas com 8 colunas, separador `;` e vírgula decimal. As duas linhas usadas:

```
06/11/2026;220;A;USD;5,50070000;5,50130000;1,00000000;1,00000000
06/11/2026;450;A;PYG;0,00086560;0,00089500;6147,00000000;6355,00000000
```

As colunas são:

1. data;
2. código da moeda no BCB;
3. tipo;
4. sigla;
5. taxa de compra em reais por unidade;
6. taxa de venda em reais por unidade;
7. paridade de compra;
8. paridade de venda.

Nas moedas do tipo A, a paridade é em unidades da moeda por dólar.

**O Guarani é publicado por paridade com o Dólar**, não direto:

- taxa de compra do PYG = compra do USD ÷ paridade de venda: 5,50070 ÷ 6355 = 0,00086557…, publicado como **0,0008656**;
- taxa de venda do PYG = venda do USD ÷ paridade de compra: 5,50130 ÷ 6147 = 0,00089496…, publicado como **0,0008950**.

O BCB publica a taxa do PYG com 7 casas (4 algarismos significativos). A página oficial "todas as moedas" do PTAX (`ptax.bcb.gov.br/ptax_internet/consultarTodasAsMoedas.do`) mostrou os mesmos números do PYG no mesmo dia (0,0008656; 0,000895; 6.147,0000).

**Por que esta fonte (alternativa A).** Uma fonte só dá as duas moedas do mesmo dia, com os mesmos números da página oficial do PTAX. Uma única leitura decide se o dia deu certo ou falhou. Continua sendo a "cotação oficial do Banco Central" escolhida pelo responsável. Decisão da orquestradora em 09/11/2026, depois da consulta acima.

### 2. Leitura estrita do arquivo

O arquivo não tem contrato com versão: o BCB pode mudá-lo sem aviso. Por isso a leitura é **estrita**, e qualquer coisa fora do esperado é **falha**, nunca um palpite. Para o dia pedido D, a leitura só é aceita se **tudo** abaixo valer:

1. o endereço é o do dia D e a resposta é 200;
2. **toda** linha não vazia tem **exatamente 8 colunas** e a **data igual a D**;
3. existe **exatamente uma** linha com código `220`, tipo `A` e sigla `USD`, e **exatamente uma** com código `450`, tipo `A` e sigla `PYG`;
4. nessas duas linhas, os quatro números seguem o padrão `^\d{1,10},\d{1,8}$` (vírgula decimal, sem separador de milhar, sem sinal) e são **maiores que zero**;
5. compra ≤ venda, nas taxas e nas paridades;
6. as duas paridades do USD são 1.

Qualquer desvio, inclusive uma coluna a mais em qualquer linha, faz a leitura ser recusada. As duas moedas são gravadas **na mesma transação**: **nunca se grava cotação parcial** (o Dólar sem o Guarani ou o contrário). Uma leitura recusada não grava nada. Se nenhuma tentativa do dia der certo (item 3), o dia fica sem cotação, a tela usa a do dia anterior e a dona recebe o aviso (item 12).

### 3. Calendário, horário da busca e novas tentativas (UTC)

O PTAX do dia sai depois das 13h de Brasília, ou seja, 16:00 UTC (Brasília é UTC-3 e hoje não tem horário de verão). Em 09/11/2026 às 14:42 UTC o arquivo do dia ainda não existia. O BCB não publica arquivo em fim de semana nem em feriado bancário nacional. Conferido em 09/11/2026:

- 404 no sábado 07/11/2026, em Finados (segunda, 02/11/2026) e na segunda e na terça de Carnaval (16 e 17/02/2026);
- 404 também na Sexta-feira Santa (18/04/2025 e 03/04/2026), em Corpus Christi (19/06/2025), no Dia da Consciência Negra (20/11/2025) e na segunda e na terça de Carnaval de 2025 (03 e 04/03/2025);
- 200 na Quarta-feira de Cinzas (18/02/2026), em 24/12/2025, em 31/12/2025 e em 02/01/2026.

**Regras:**

- **Só se busca de segunda a sexta.** Sábado e domingo não têm busca nem aviso.
- **Primeira tentativa às 16:30 UTC**, depois a cada 30 minutos **até 21:00 UTC**: 10 tentativas por dia. Cada pedido tem tempo limite de 20 segundos. O horário em UTC vale mesmo se o Brasil voltar a ter horário de verão: às 16:30 UTC o PTAX já saiu nos dois casos.
- Uma tentativa que lê um arquivo válido (item 2) grava a cotação e encerra o dia. Qualquer outro resultado só faz tentar de novo na próxima, **sem aviso**. Uma tentativa sem sucesso pode ser 404, erro de rede, tempo esgotado, resposta HTTP diferente de 200 e 404, ou arquivo fora da regra.
- **Arquivo atrasado:** 404 antes da última tentativa. É o caso normal entre 16:30 e a publicação.
- **Dia sem arquivo:** nenhuma tentativa deu certo, a última deu 404, e o **calendário de feriados bancários nacionais** marca o dia como feriado. Não é falha e não tem aviso: vale a cotação do último dia útil. O calendário fica no código:
  - os feriados fixos: 1/1, 21/4, 1/5, 7/9, 12/10, 2/11, 15/11, 20/11 e 25/12;
  - os móveis, calculados a partir da Páscoa: segunda e terça de Carnaval, Sexta-feira Santa e Corpus Christi.
- **Falha:** nenhuma das 10 tentativas deu certo num dia útil, qualquer que tenha sido o motivo da última. Exemplos: 404 às 21:00 UTC num dia que não é feriado, erro de rede, ou arquivo fora da regra até o fim.
- Se existir arquivo num dia que o calendário marca como feriado, ele é lido e gravado normalmente.
- **Recuperação:** cada rodada procura também os dias úteis dos **últimos 10 dias** que ficaram sem cotação e tenta buscá-los. Um arquivo que chegou atrasado ou um dia de falha passageira entra depois, como **linha nova**.
- **Dia já gravado nunca é relido nem sobrescrito.**

### 4. O que se guarda

Tabela `cotacoes`, **uma linha por dia** com cotação:

| Coluna | Tipo | Observação |
| --- | --- | --- |
| `data_cotacao` | `date`, `UNIQUE` | Dia do arquivo (dia útil do BCB). É uma data de calendário, não um instante, por isso `date` e não `timestamptz` |
| `usd_compra`, `usd_venda`, `usd_paridade_compra`, `usd_paridade_venda` | `NUMERIC(18,8)` | As quatro colunas do USD, como vieram |
| `pyg_compra`, `pyg_venda`, `pyg_paridade_compra`, `pyg_paridade_venda` | `NUMERIC(18,8)` | As quatro colunas do PYG, como vieram |
| `buscada_em` | `timestamptz` | Hora da leitura, em UTC |
| `fonte` | `text` | Endereço lido |

As oito colunas são `NOT NULL`, e por isso a tabela não aceita cotação parcial.

**Classificação (ADR-0002, item 3).** A tabela **nasce só no servidor**, mas **chega ao app do vendedor**. Pelo critério do ADR-0002, é **sincronizável, só de leitura**:

- criada com `corona.aplicar_convencoes_sincronizavel('cotacoes', true)`, ou seja, **imutável**: recusa `UPDATE`, `DELETE` e `TRUNCATE`;
- `id` UUID v7 gerado no servidor;
- o pull **não filtra por vendedor**, porque a cotação é pública e igual para todos. São cerca de 250 linhas por ano, então a carga completa é pequena;
- o pull usa lista explícita de colunas, como manda o ADR-0002.

### 5. Qual taxa a tela usa: a de venda

A tela usa a **taxa de venda** nas duas moedas (decidido em 09/11/2026):

- é a que o mercado chama de "dólar PTAX" e a referência usual dos contratos em dólar;
- é **uma regra só** para o Dólar e o Guarani.

As quatro colunas de cada moeda ficam guardadas: se um dia a regra mudar, nenhum dado se perde. **Consequência a conhecer:** a diferença entre compra e venda do Guarani é grande (paridade 6147 e 6355 em 06/11/2026, cerca de 3,4%). Com a venda, o valor em Guarani fica no lado menor.

### 6. Fórmula da conversão

```
US$ = arredondar(R$ ÷ usd_venda, 2 casas)
Gs. = arredondar(R$ ÷ pyg_venda, 0 casas)
```

- Usa **uma divisão só**, com o arredondamento no fim: em Java, `valor.divide(taxa, escala, RoundingMode.HALF_UP)`, que já devolve o quociente exato arredondado. Nunca uma conta intermediária arredondada.
- O Guarani usa a **coluna publicada** do PYG, e não a conta pela paridade. Assim os números são os mesmos da página do BCB. A perda de precisão dos 4 algarismos significativos é de no máximo 0,006% (ver riscos).

### 7. Que cotação vale para cada valor

- **Lançamento** (venda, pagamento, devolução, troca, conta, caixa): vale a cotação de **maior `data_cotacao` que seja menor ou igual à data do lançamento**, contada em **horário de Brasília** (§7.5). Sábado, domingo e feriado caem no último dia útil.
- **Valor que não é lançamento** (preço da peça, limite de consignação, saldo da maleta em valor): vale a **cotação mais recente**.
- **Acerto:** vale a cotação mais recente que existe **no momento em que o acerto é fechado**, gravada nele (item 11).
- **Hoje, antes da busca:** o lançamento de hoje aparece pela cotação do último dia útil, com a data dela na tela. Depois que a cotação do dia é gravada, passa a aparecer pela de hoje. A conversão é só para a tela, então essa troca não muda nada gravado. O acerto não muda, porque grava a sua (item 11).

### 8. Tipo no banco e o teste de convenções

A cotação **não é dinheiro**: 1 guarani vale frações pequenas de real (0,000895). Por isso o tipo é **`NUMERIC(18,8)`**:

- 8 casas, exatamente as do arquivo, então se grava **sem arredondar**;
- 10 dígitos inteiros, com folga para paridades como 6355.

**Teste de convenções.** Hoje o `VerificadorDeConvencoes` aceita só `numeric(14,2)` e `numeric(5,2)`. A tarefa 19 muda isso assim:

- o verificador passa a aceitar `numeric(18,8)` **só nas colunas de uma lista explícita** `tabela.coluna`, que são as 8 colunas de taxa e paridade de `cotacoes`;
- `numeric(18,8)` em qualquer outra coluna é violação, com a mensagem "numeric(18,8) só em colunas de cotação (ADR-0006)";
- o "teste do teste" (`ConvencoesDoSchemaTest`) ganha esse caso.

**JSON e OpenAPI.** A cotação trafega como **texto decimal com ponto e 8 casas** (`"5.50130000"`), nunca como número JSON, como o dinheiro do ADR-0002. Ela tem **esquema próprio** no OpenAPI (padrão `^\d{1,10}\.\d{8}$`). O padrão de dinheiro da tarefa 6, com no máximo 2 casas, recusaria a cotação. A cotação só sai do servidor e nunca entra por rota nenhuma.

### 9. Arredondamento da conversão

- **HALF_UP**, com **2 casas no Dólar** e **0 no Guarani**.
- O valor convertido é **só para mostrar** e **nunca é gravado** como valor de lançamento. Nenhuma regra de dinheiro do ADR-0002 muda.
- Em Java, a conversão fica numa classe própria, `ConversaoDeMoeda`, no pacote `comum.dinheiro`. O `Dinheiro` não muda. O `ArredondamentoSoNoUtilitarioTest` passa a aceitar `RoundingMode` **só** em `Dinheiro` e em `ConversaoDeMoeda`.
- **TypeScript**, só com inteiros **`BigInt`** (ADR-0002: `number` fracionário nunca representa dinheiro):
  - o valor em reais vem em **centavos** (`c`);
  - a taxa vem do texto como **inteiro em unidades de 10⁻⁸** (`t`; por exemplo `"5.50130000"` vira `550130000n`);
  - US$ em centavos = arredondar(c × 10⁸ ÷ t);
  - Gs. = arredondar(c × 10⁶ ÷ t).
- **Por que `BigInt` e não `number`.** O maior valor de `NUMERIC(14,2)` é R$ 999.999.999.999,99, ou seja, cerca de 10¹⁴ centavos. Vezes 10⁸, chega a cerca de 10²², muito acima do maior inteiro exato do `number` (2⁵³ − 1 = 9.007.199.254.740.991). Com `number`, a conta perderia dígitos sem dar erro.
  - A conversão faz **toda** a conta em `BigInt`: leitura dos textos, multiplicação, divisão e arredondamento.
  - O resultado continua `BigInt` até virar texto para a tela. Nunca passa por `Number()` nem por `parseFloat`.
- **HALF_UP em `BigInt`.** A divisão do `BigInt` corta as casas em direção ao zero. Para numerador `n` e divisor `d > 0`:
  - se `n ≥ 0`: `(2n + d) / (2d)`;
  - se `n < 0`: `-((-2n + d) / (2d))`.
  - Isso afasta do zero no meio exato, como o `RoundingMode.HALF_UP` do Java.
- Java e TypeScript usam **a mesma tabela de exemplos** (item 14) como teste, para darem o mesmo resultado.
- **Exceção registrada (auditoria M4, 17/11/2026):** os relatórios somam a conversão por linha dentro do SQL, com o `round(x / taxa, casas)` do PostgreSQL, que também afasta do zero no meio exato (mesmo resultado do HALF_UP). Isso só é permitido nos arquivos `SqlDaMaleta`, `ConsultasDeDevolucaoETroca`, `ConsultasDeEstoqueECompras`, `ConsultasDoResultado` e `VendidoLiquido`. O `ArredondamentoEmSqlTest` compara o SQL com a `ConversaoDeMoeda` em valores de borda e reprova `round(` em SQL de qualquer outro arquivo.

### 10. Totais convertidos: soma dos lançamentos convertidos

**O total em Dólar ou em Guarani é a soma dos lançamentos já convertidos**, cada um pela cotação do seu dia, e não a conversão do total em reais. Motivos:

- segue a regra do ADR-0002: total é a soma dos valores já arredondados;
- o **detalhamento** da §12 fecha: os lançamentos que aparecem ao clicar no total somam exatamente o total mostrado;
- num período de vários dias **não existe uma cotação só** para converter o total sem escolher um dia arbitrário (e "a de hoje" é proibida).

Exemplo no mesmo dia (06/11/2026, US$ 5,50130 e Gs. 0,00089500): três vendas de R$ 10,05.

- **Soma dos convertidos (a regra):** cada venda dá 10,05 ÷ 5,50130 = 1,82684…, ou US$ 1,83. A soma é **US$ 5,49**. No Guarani, cada uma dá 11.229,050…, ou Gs. 11.229, e a soma é **Gs. 33.687**.
- **Conversão do total (não é a regra):** R$ 30,15 ÷ 5,50130 = 5,48052…, ou **US$ 5,48**, um centavo de diferença. No Guarani, 33.687,150… dá Gs. 33.687, igual neste caso.

Exemplo de dois dias: ver a tabela do item 14.

### 11. O que o acerto grava

- **Qual cotação:** a **mais recente que existe no momento em que o acerto é fechado**.
- **O que grava:** **`cotacao_id`**, a ligação com essa linha de `cotacoes`. Como a linha é imutável, a **data da cotação** e as **taxas** ficam fixas para sempre.
- **Uma cotação só para o acerto inteiro:** todos os valores do acerto (vendido, descontos, comissão, líquido, repasse) são convertidos por essa mesma cotação, cada um arredondado sozinho.
- **Tela:** a tela do acerto (e o PDF dele) sempre mostra "Cotação do Banco Central de dd/mm/aaaa", com a data da cotação gravada (texto do glossário, parte 11).

**Consequência: o acerto pode diferir do relatório de vendas.** O relatório de vendas da mesma semana converte cada venda pela cotação do seu dia (item 7), e o acerto converte o total pela cotação única do fechamento. Por isso o total do acerto em US$ e Gs. pode diferir do total do relatório, mesmo com o mesmo valor em reais. O valor que vale é sempre o em reais, e esse é igual nos dois. Exemplo no item 14.

**Consequência: as linhas do acerto podem não fechar em US$.** Como cada valor é arredondado sozinho, "vendido − comissão" em US$ ou Gs. pode diferir em 1 centavo, ou em 1 guarani, do "líquido" convertido. A conta que fecha é a em reais. Exemplo no item 14.

**Por que gravar, se a tabela é imutável:** se o acerto foi fechado num dia em que a busca falhou, ou antes da busca do dia, ele usou a cotação anterior. Quando a cotação daquele dia é gravada depois, os relatórios passam a usá-la, mas o acerto **continua mostrando o que mostrou** na hora em que foi fechado e assinado. A coluna entra quando a tabela `acertos` for criada (Fase 7).

### 12. Aviso à dona

O aviso em "O que precisa da sua atenção" é **calculado, não gravado**. Ele aparece quando existe um **dia útil sem cotação** entre os últimos 10 dias. O dia de hoje só conta **depois das 21:00 UTC**, quando a última tentativa já passou. O aviso mostra a data da cotação em uso e **some sozinho** quando a recuperação grava o dia. O texto está no glossário.

### 13. Proteções

- O app do vendedor **nunca calcula com cotação própria**: não busca cotação na internet, não aceita taxa digitada e não deriva taxa de outros valores. Ele só usa as cotações que o **servidor manda**: pela API na Fase 4 e pelo pull na Fase 5.
- Sem internet, usa a última cotação recebida e **mostra a data dela**.
- Os relatórios e o acerto que valem são os do servidor, que converte pela mesma regra.
- Ninguém digita cotação: a tabela só recebe o que a busca leu do BCB. Não existe rota para gravar nem para corrigir cotação.

### 14. Exemplos numéricos

Conferidos à mão e com `decimal` do Python, com as **taxas de venda reais** publicadas pelo BCB. Viram testes da tarefa 19, iguais em Java e em TypeScript.

| Dia | US$ (venda) | PYG (venda) |
| --- | --- | --- |
| 30/10/2026 (sexta) | 5,42120 | 0,00092960 |
| 05/11/2026 (quinta) | 5,46210 | 0,00088770 |
| 06/11/2026 (sexta) | 5,50130 | 0,00089500 |

| Situação | Conta | Resultado |
| --- | --- | --- |
| R$ 1.234,56 em 06/11 | 1.234,56 ÷ 5,50130 = 224,4124…; 1.234,56 ÷ 0,000895 = 1.379.396,648… | **US$ 224,41**; **Gs. 1.379.397** (o Guarani arredonda para cima) |
| R$ 1,00 em 06/11 | 0,18177…; 1.117,318… | US$ 0,18; **Gs. 1.117** (arredonda para baixo) |
| R$ 0,01 em 06/11 | 0,0018…; 11,17… | US$ 0,00; Gs. 11 (valor pequeno pode mostrar US$ 0,00; é só tela) |
| Relatório de dois dias: venda de R$ 49,90 em 05/11 e de R$ 100,00 em 06/11 | 49,90 ÷ 5,46210 = 9,1357… e 100,00 ÷ 5,50130 = 18,1775…; 49,90 ÷ 0,0008877 = 56.212,684… e 100,00 ÷ 0,000895 = 111.731,843… | US$ 9,14 + 18,18 = **US$ 27,32**; Gs. 56.213 + 111.732 = **Gs. 167.945**; total em reais R$ 149,90 |
| O mesmo relatório convertido todo pela cotação de "hoje" (06/11): **errado** | 149,90 ÷ 5,50130 = 27,2481…; 149,90 ÷ 0,000895 = 167.486,03… | US$ 27,25 e Gs. 167.486: difere em US$ 0,07 e Gs. 459 |
| Três vendas de R$ 10,05 em 06/11, soma dos convertidos (a regra) | 1,82684… vira 1,83; 11.229,050… vira 11.229 | **US$ 5,49**; **Gs. 33.687** |
| O mesmo, convertendo o total (não é a regra) | 30,15 ÷ 5,50130 = 5,48052…; 33.687,150… | US$ 5,48; Gs. 33.687 |
| Venda de R$ 100,00 no sábado 07/11 | sem arquivo no sábado; vale a de 06/11 | US$ 18,18; Gs. 111.732; **sem aviso** |
| Venda às 23:30 de sexta, 06/11, em Brasília (02:30 UTC do sábado) | a data do lançamento em Brasília é 06/11 | cotação de 06/11 |
| Venda de R$ 100,00 em Finados, segunda 02/11/2026 | sem arquivo (feriado); vale a de 30/10: 100 ÷ 5,42120 = 18,4461…; 100 ÷ 0,0009296 = 107.573,149… | US$ 18,45; Gs. 107.573; **sem aviso** |
| Busca falha na segunda 09/11 (por exemplo, arquivo com 9 colunas) | nada é gravado; vale a de 06/11; às 21:00 UTC aparece o aviso | os relatórios de 09/11 usam 06/11. Em 10/11 a recuperação grava 09/11, o aviso some e os relatórios de 09/11 passam à cotação de 09/11. Um acerto feito em 09/11 continua com a de 06/11 (gravou `cotacao_id`) |
| Conta em inteiros do TypeScript: R$ 1.234,56 em 06/11 | c = 123456; t(USD) = 550130000; t(PYG) = 89500; 123456 × 10⁸ ÷ 550130000 = 22441,24…; 123456 × 10⁶ ÷ 89500 = 1.379.396,6… | 22441 centavos = **US$ 224,41**; **Gs. 1.379.397** (igual ao Java) |
| Acerto fechado na segunda 09/11 às 11h de Brasília (14:00 UTC, antes da busca do dia): vendas da semana de R$ 49,90 (05/11) e R$ 100,00 (06/11); comissão de 7,5% sobre o total, só para o exemplo (a base ainda não está decidida, ADR-0002) | cotação mais recente no fechamento: 06/11. Vendido 149,90 ÷ 5,50130 = 27,2481…; comissão 149,90 × 0,075 = 11,2425, que vira R$ 11,24, e 11,24 ÷ 5,50130 = 2,0431…; líquido R$ 138,66, e 138,66 ÷ 5,50130 = 25,2049…. No Guarani: 167.486,03…; 12.558,65…; 154.927,37… | o acerto grava a cotação de 06/11 e mostra: vendido **US$ 27,25** / **Gs. 167.486**; comissão US$ 2,04 / Gs. 12.559; líquido **US$ 25,20** / Gs. 154.927 |
| O mesmo acerto comparado com o relatório de vendas da semana | o relatório converte cada venda pela cotação do seu dia (linha "Relatório de dois dias") | relatório: **US$ 27,32** / **Gs. 167.945**; acerto: US$ 27,25 / Gs. 167.486. Diferença de US$ 0,07 e Gs. 459, com os mesmos R$ 149,90 |
| As linhas do acerto em US$ não fecham por 1 centavo | 27,25 − 2,04 = 25,21, mas o líquido convertido é 25,20; no Guarani, 167.486 − 12.559 = 154.927, igual | normal: cada valor é arredondado sozinho; a conta que fecha é a em reais (149,90 − 11,24 = 138,66) |
| Valor máximo de `NUMERIC(14,2)` no TypeScript: R$ 999.999.999.999,99 em 06/11 | c = 99.999.999.999.999n; c × 10⁸ = 9.999.999.999.999.900.000.000, que passa de 2⁵³ − 1 = 9.007.199.254.740.991; ÷ 550130000 = 18.177.521.676.694,4…; c × 10⁶ ÷ 89500 = 1.117.318.435.754.178,77… | **US$ 181.775.216.766,94**; **Gs. 1.117.318.435.754.179**. Só dá certo com `BigInt`; com `number`, a multiplicação perde dígitos |
| Por que a coluna publicada do PYG e não a paridade | 5,50130 ÷ 6147 = 0,00089496… | o BCB publica 0,0008950; a conversão usa 0,0008950 |

## Consequências

### Boas

- Uma fonte oficial só, com as duas moedas do mesmo dia e os mesmos números que qualquer pessoa vê na página do PTAX.
- Nenhum valor gravado muda: o Real e o ADR-0002 ficam intactos, e a conversão nunca entra em conciliação (§13).
- Relatórios reproduzíveis: o mesmo período dá sempre o mesmo resultado, porque cada dia tem sua cotação imutável. A única exceção é um dia que estava sem cotação e foi recuperado, e o aviso mostra isso enquanto acontece.
- O acerto fica igual ao que foi mostrado na hora.
- Falha nunca vira número errado: vira aviso.

### Ruins ou custos

- A cotação do dia só existe depois das 13h de Brasília. Antes disso, o lançamento de hoje aparece pela cotação do último dia útil.
- O calendário de feriados fica no código e precisa ser revisado se surgir feriado nacional novo.
- Uma tabela a mais no pull e um tipo numérico a mais no teste de convenções.
- O spread grande do Guarani (cerca de 3,4%) faz a escolha entre compra e venda pesar. A venda foi escolhida e pode ser revista sem perder dado.
- **O acerto e o relatório de vendas da mesma semana podem mostrar US$ e Gs. diferentes**, com os mesmos reais. O acerto usa uma cotação só, a do fechamento, e o relatório usa a do dia de cada venda (item 11; no exemplo do item 14, US$ 27,25 contra US$ 27,32). Isso precisa ser explicado à dona na validação (§13.7), junto com a regra de que vale sempre o valor em reais.
- No TypeScript, a conversão exige `BigInt`, um tipo que a equipe ainda não usa. Um teste com o valor máximo de `NUMERIC(14,2)` protege contra quem trocar por `number`.

### Riscos

- **O BCB pode mudar o endereço ou o formato do arquivo**, que não tem contrato com versão. Consequência: a leitura estrita recusa, o dia fica sem cotação e **a dona recebe o aviso**. **Nunca um valor errado.** A correção é uma tarefa nova que ajusta a leitura, e a recuperação de 10 dias preenche o que faltou.
- **O calendário pode errar um feriado.** Se marcar como dia útil um dia sem arquivo, o pior caso é um aviso falso. Se marcar como feriado um dia com arquivo, o arquivo é lido mesmo assim (item 3).
- **Precisão do Guarani:** o BCB publica a taxa do PYG com 4 algarismos significativos, um erro de até 0,006%. Em R$ 10.000,00, isso dá menos de Gs. 700 (menos de R$ 0,60). É aceitável porque é só tela e são os números oficiais.
- **O cabeçalho `Last-Modified` não serve para nada:** o arquivo de 06/11/2026 respondeu com `Last-Modified` de 09/11/2026 14:40 UTC. A leitura confere a data dentro das linhas e nunca relê dia gravado.
- **Correção pelo BCB:** se o BCB republicar um dia com números diferentes, o sistema mantém o que leu primeiro, porque nada é sobrescrito.

## Alternativas consideradas

- **B: o Dólar pela API Olinda e o Guarani pelo arquivo.** São duas leituras que podem divergir de dia e de estado: o Dólar chega e o Guarani não, ou o contrário. Isso exige regra de cotação parcial, e os números do Dólar são os mesmos do arquivo. Descartada.
- **C: o Dólar pelo PTAX e a paridade do Guarani pelo Banco Central del Paraguay.** São dois bancos centrais, com feriados e horários diferentes. O formato não foi conferido, porque o site recusou o acesso pelo terminal (403) em 09/11/2026. Descartada.
- **Só a API Olinda:** não publica o Guarani. Descartada.
- **APIs de terceiros ou pagas:** não são a cotação oficial do Banco Central escolhida pelo responsável. Descartadas.
- **Taxa de compra:** possível; a venda foi escolhida pelo item 5. Como as quatro colunas ficam guardadas, a troca é só de regra.
- **Calcular o Guarani pela paridade** (venda do USD ÷ paridade de compra): mais casas, mas números diferentes dos que o BCB publica. Descartada.
- **Converter o total em vez de somar os convertidos:** o detalhamento não fecha e não existe uma cotação só para vários dias. Descartada.
- **Cotação em `NUMERIC(14,2)`:** 0,000895 viraria 0,00. **`NUMERIC(20,10)` ou `numeric` sem limite:** mais casas do que a fonte tem, ou sem controle no teste de convenções. **Float:** proibido (§7.5). Descartados.
- **Gravar o valor convertido junto do lançamento:** duplicaria dinheiro em três moedas e abriria espaço para divergência. Contraria a regra "tudo é gravado em Real". Descartada.
- **Usar a cotação do dia útil anterior para o dia inteiro** (estável desde 0h): contraria "cotação do dia do lançamento". Descartada.

## Seções da especificação relacionadas

§7.5 (financeiro e três moedas), §9 (`cotacoes` e `acertos`), §10 (o que o vendedor recebe e a política de conflito), §12 (relatórios), §13 (conciliações em Real e o tipo da cotação), §15 e §21 (fases), §19.3 (O que precisa da sua atenção). ADR-0002 (tipos, arredondamento, tabelas sincronizáveis, JSON).

## Dúvidas

1. **A §19.3** ("O que precisa da sua atenção") lista exemplos de avisos e não cita o da cotação. A §7.5 já manda o aviso para esse bloco. Acrescentar o item na §19.3 fica para quando o responsável quiser mexer nela.
2. **Cartão:** a especificação não lista o cartão como forma de pagamento. A §17.13 pergunta ao cliente se o cliente final paga com cartão na maquininha do próprio vendedor. Se pagar, o cartão entra como forma de pagamento registrada, sem integração. Não muda nada deste ADR.

Resolvidas em 09/11/2026, na revisão do plano: a taxa de venda nas duas moedas (item 5); o vendedor recebe a cotação pela API na Fase 4 e pelo pull na Fase 5; o número 19 para a tarefa de backend das cotações.
