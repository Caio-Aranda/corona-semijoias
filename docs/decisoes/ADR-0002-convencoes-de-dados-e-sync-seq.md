# ADR-0002: Convenções de dados e `sync_seq` seguro

- **Status:** Proposto
- **Data:** 09/11/2026
- **Tarefas que implementam:** 5 (papéis do banco), 6 (UTC e formato do JSON), 8 (convenções do schema, gatilho, pull seguro, utilitário de dinheiro e todos os testes), 9 e 11 (tabelas só do servidor: usuários, sessões, tentativas de login)

## Contexto

Migração Flyway publicada nunca é editada (CLAUDE.md). Por isso o formato dos dados, os campos de controle e a forma de numerar as mudanças para o pull precisam estar decididos **antes** da primeira tabela. A especificação já fixa as regras gerais (UUID gerado no app, `NUMERIC(14,2)`, HALF_UP com escala 2, UTC, exclusão lógica, imutabilidade; §7.5, §9, §10, §13) e remete a este ADR duas coisas: o campo `sync_seq` que não perde registros (§9, §10) e o momento do arredondamento (§7.5).

## Decisão

### 1. Tipos

| Dado | Tipo | Regra |
| --- | --- | --- |
| Identificador | `uuid` | Gerado no cliente, **versão 7** (RFC 9562). O servidor aceita v4 e v7 e recusa qualquer outra coisa. Registros que nascem no servidor (tabelas só do servidor, geração de parcelas e comissões) também recebem v7 |
| Data e hora | `timestamptz` | Sempre UTC. Proibido `timestamp` sem fuso. No JSON, ISO-8601 com `Z`. A JVM e a conexão com o banco rodam em UTC. Exibição em horário de Brasília só na tela |
| Dinheiro | `NUMERIC(14,2)` | Proibido `float`, `real` e `double precision` no banco, e `float`/`double` no Java |
| Porcentagem | `NUMERIC(5,2)`, com `CHECK` entre 0 e 100 | Exemplo: `7.50` |
| Quantidade | `INTEGER`, com `CHECK` de faixa | Peças inteiras |
| Código de barras | `text` | Nunca número (§8) |

**Por que UUID v7 e não v4:** o v7 começa pelo horário, então registros novos caem no fim do índice em vez de espalhados, o que é melhor para as tabelas que mais crescem (vendas, itens, movimentos). A unicidade vem dos bits aleatórios e não depende do relógio do aparelho. A desvantagem é que o identificador revela o horário aproximado em que o aparelho o criou; isso já é público em `created_at` e não é segredo. O v7 **não** serve de ordem para a sincronização, porque o relógio do aparelho pode estar errado; a ordem vem do `sync_seq` (item 5). O PostgreSQL 18 tem `uuidv7()` para o que nascer no servidor.

### 2. Dinheiro, porcentagem e arredondamento

**Regra única:** `RoundingMode.HALF_UP`, escala 2.

**Em que momento se arredonda:** **cada valor calculado é arredondado no instante em que é calculado e gravado. Totais são a soma dos valores já arredondados**; nunca se arredonda o resultado de uma conta feita com valores intermediários sem arredondar. Quem grava um valor grava o valor final, e ninguém o recalcula depois (relatórios somam o que está gravado).

- **Preço da linha:** quantidade (inteira) x preço unitário. Já tem 2 casas; não há arredondamento.
- **Desconto em porcentagem:** incide **por linha** e é arredondado por linha. O desconto da venda é a soma dos descontos das linhas. *(Padrão proposto, a confirmar na Fase 4; ver dúvida 3.)*
- **Desconto em valor:** gravado como foi digitado, 2 casas.
- **Parcelas:** as n-1 primeiras são `total / n` arredondado; a última é `total` menos a soma das anteriores, para fechar exato.
- **Estornos e reembolsos:** são registros negativos, **gravados já arredondados e nunca recalculados**. O estorno proporcional de uma comissão é arredondado, e o último estorno leva o resto para a soma fechar exata.
- **Comissão:** a base de cálculo ainda **não está decidida** (ver dúvida 2). A regra de arredondamento vale nos dois casos possíveis, e o utilitário de dinheiro da tarefa 8 oferece os dois:
  - **(a) por venda:** comissão da venda = líquido da venda x porcentagem, arredondada; a do acerto é a soma das comissões das vendas;
  - **(b) sobre o total do acerto:** comissão = (soma dos líquidos vendidos no acerto) x porcentagem, arredondada uma vez.

**Exemplos numéricos** (todos conferidos à mão; viram testes da tarefa 8):

| Situação | Conta | Resultado |
| --- | --- | --- |
| Desconto de 10% em 3 linhas de R$ 10,05, por linha | 1,005 vira **1,01** em cada linha; soma 3 x 1,01 | desconto **3,03**; líquido 30,15 - 3,03 = **27,12** |
| O mesmo desconto, arredondando só o total (não é a regra) | 30,15 x 10% = 3,015 | 3,02 (diferença de R$ 0,01, por isso a regra diz "por linha") |
| Comissão de 7,5% sobre R$ 33,33 | 33,33 x 0,075 = 2,49975 | **2,50** |
| Parcelas de R$ 100,00 em 3 vezes | 33,33 + 33,33 + o resto | **33,33 + 33,33 + 33,34** |
| Comissão de 7,5%, três vendas de R$ 10,05, caso (a) por venda | 0,75375 vira 0,75 em cada venda; soma | **2,25** |
| O mesmo, caso (b) sobre o total do acerto | 30,15 x 0,075 = 2,26125 | **2,26** (os dois casos diferem em R$ 0,01) |
| Estorno proporcional: venda de R$ 100,00 com comissão de 7,50; o cliente devolve R$ 33,33 e depois R$ 66,67 | 7,50 x 33,33 / 100,00 = 2,49975 vira 2,50; o último estorno é 7,50 - 2,50 | estornos **2,50** e **5,00** (soma 7,50) |

**Dinheiro fora do banco (R6):**

- **Java:** só `BigDecimal` (nunca `double` nem `float`), em um único utilitário de dinheiro, que é o único lugar com `setScale(2, RoundingMode.HALF_UP)`.
- **JSON:** dinheiro e porcentagem trafegam **sempre como texto decimal**, com ponto e duas casas: `"123.45"`, `"7.50"`. Nunca como número JSON. Quantidade é número inteiro.
- **TypeScript:** o texto é lido por um conversor próprio (sem passar por `parseFloat`), transformado em **centavos inteiros** só para fazer conta, e devolvido como texto. `number` fracionário nunca representa dinheiro. A formatação para a tela segue o glossário (R$ 1.234,56).
- O app mostra prévias, mas **quem vale é o servidor**, que recalcula tudo (§7.5, §10).

### 3. Campos de controle e quem preenche cada um

Toda tabela sincronizável tem os campos da tabela abaixo. O gatilho (`BEFORE INSERT OR UPDATE`, por linha) é o único que escreve `updated_at`, `version`, `sync_seq` e `sync_xid`; valores enviados pelo cliente nesses campos são **ignorados**.

| Campo | Quem preenche | Observação |
| --- | --- | --- |
| `id` | **Cliente** (UUID v7) | O servidor só gera para o que nasce nele |
| `created_at` | **Servidor** (valor padrão `now()` na inserção) | É a hora de chegada ao servidor. A hora em que a venda ou o movimento aconteceu é um **campo de negócio** (`vendas.data`, `movimentos_remessa.criado_em`), vem do app e o servidor valida; se o relógio do aparelho divergir, o servidor decide (§7.3). No SQLite o app preenche um valor provisório que o pull sobrescreve |
| `updated_at` | **Gatilho** (`now()` a cada update) | |
| `version` | **Gatilho** (1 na inserção; +1 a cada update) | Base da edição otimista: quem edita informa a versão que viu; versão diferente vira conflito, tratado pela política da §10 |
| `deleted_at` | **Operação de exclusão lógica**, no app ou na API | O gatilho não preenche. Excluir é um update que preenche `deleted_at` |
| `origem_dispositivo` | **Servidor**, a partir da sessão autenticada | Nunca do corpo da requisição. Fica vazio para o que nasce no Corona Gestão |
| `sync_seq` | **Gatilho** (`nextval` de uma sequência global) | Número de ordem dentro de uma transação, **não** garante ordem de confirmação (item 5) |
| `sync_xid` | **Gatilho** (`pg_current_xact_id()`, tipo `xid8`) | Identifica a transação que gravou a linha; é o que torna o pull seguro (item 5) |

**Tabelas sincronizáveis e tabelas só do servidor.** Regra: é sincronizável a tabela cujos registros **nascem no app do vendedor** ou **chegam ao app do vendedor** (§10). Todas as demais são só do servidor. A decisão fica registrada numa lista única no código, e o teste de convenções da tarefa 8 falha se uma tabela nova não estiver em uma das duas listas.

| Grupo | Tabelas (a lista final de cada fase é fechada na tarefa que cria a tabela) |
| --- | --- |
| Sincronizáveis (nascem no app) | `clientes`, `vendas`, `itens_venda`, `pagamentos_cliente`, `movimentos_remessa`, `devolucoes`, `itens_devolucao`, `trocas`, `itens_troca`, `autorizacoes_excecao` (pedido) |
| Sincronizáveis (chegam ao app, só leitura) | `produtos` (sem custo), `produto_codigos`, `produtos_fotos`, `categorias`, `remessas`, `itens_remessa`, `creditos_cliente`, `contas_receber`, `comissoes`, `acertos`, `pendencias_acerto` (somente os do próprio vendedor), parte de `configuracoes` (garantia e descontos) |
| **Só do servidor** | `usuarios`, `perfis`, `sessoes`, `tentativas_login`, `auditoria`, `sync_log`, `dispositivos`, `importacoes`, `fornecedores`, `compras`, `itens_compra`, `contas_pagar`, `lancamentos_caixa`, `cheques`, `movimentacoes_estoque`, `inventarios`, `itens_inventario`, `vendedores` e suas tabelas filhas (o vendedor só enxerga o próprio resumo, pelos DTOs do ADR-0003), o restante de `configuracoes` |

**O que muda numa tabela só do servidor:** não tem `sync_seq`, `sync_xid` nem `origem_dispositivo`, e não tem o gatilho de sincronização. O `id` é gerado no servidor (UUID v7). Mantém `created_at`, e `updated_at`, `version` e `deleted_at` quando faz sentido editar ou excluir. **Tabelas de histórico** (`auditoria`, `tentativas_login`) só recebem inserção e não têm `updated_at`, `version` nem `deleted_at`. Permissão, dados de outros vendedores e custo nunca passam pelo pull porque essas tabelas **não estão no pull**.

**Regras em todas as tabelas (testadas na tarefa 8):**

- **Nunca `DELETE`.** Um gatilho `BEFORE DELETE` recusa a operação em todas as tabelas, inclusive as só do servidor. Exclusão é lógica; correção é novo registro.
- Tabelas imutáveis pela especificação (vendas, itens, pagamentos, movimentos de maleta e de estoque) têm também um gatilho que recusa `UPDATE`. Cancelar é inserir um registro novo (estorno).
- **Nada é apagado na Fase 0**, nem de `sessoes` nem de `tentativas_login`. A política de retenção desses dados (quanto tempo guardar e como descartar sem `DELETE`) fica para um ADR futuro (ver dúvida 5).

**`preco_custo` fora de `produtos`? (avaliação pedida)**

A §9 coloca `preco_custo` em `produtos`. Avaliei guardar o custo em uma tabela só do servidor, `produtos_custos` (1 para 1, chave `produto_id`).

- *Prós:* o custo **não pode** vazar pelo pull, porque a tabela não está no pull e não tem `sync_seq`, nem por descuido de quem mapeia a entidade ou escreve `SELECT *`; mudar o custo não gera nova versão do produto (hoje cada alteração de custo mandaria o produto de novo a todos os vendedores); o histórico de custo e a auditoria ficam separados; é fácil dar permissão de banco diferente.
- *Contras:* diverge da §9; telas e relatórios da dona (lucro por produto, cadastro, importação por planilha) precisam de uma junção; o produto e o custo precisam ser gravados na mesma transação.
- **Recomendação: criar `produtos_custos`.** Como a §9 diz o contrário, **não foi adotada**: fica como dúvida 4. Enquanto não houver resposta, vale a §9 (`preco_custo` em `produtos`) com duas proteções: o pull usa **lista explícita de colunas** (nunca `SELECT *`), e o teste do ADR-0003 confere que o custo não aparece em nada que o vendedor recebe.

### 4. Exclusão lógica e o pull

`DELETE` não existe. Excluir é um update que preenche `deleted_at`. Como o gatilho dá um `sync_seq` e um `sync_xid` novos a todo update, **a linha excluída volta no pull** com `deleted_at` preenchido (uma "lápide"). O app aplica o pull marcando a linha como excluída no SQLite; ele nunca apaga fisicamente. Uma linha que foi entregue e depois alterada ou excluída é entregue de novo, com a versão nova.

### 5. `sync_seq` sem perder registros

**O problema, passo a passo.** O número do `sync_seq` é dado quando a linha é gravada, não quando a transação é confirmada. Transações confirmam fora de ordem:

1. A transação A grava uma venda e recebe o `sync_seq` **10**. Ainda não confirmou.
2. A transação B grava outra venda, recebe o **11** e confirma.
3. O app chama o pull: "tudo depois de 8". O servidor só vê a linha 11 (a 10 ainda não foi confirmada). O app guarda o cursor **11**.
4. A confirma. A linha 10 passa a existir.
5. No próximo pull ("tudo depois de 11") a linha 10 **nunca chega**. O aparelho fica com dados diferentes do servidor, sem nenhum erro visível.

**A solução escolhida:** a linha guarda o `sync_seq` **e** o identificador da transação que a gravou (`sync_xid`, tipo `xid8`, de `pg_current_xact_id()`), os dois escritos pelo gatilho em todo insert e update (inclusive na exclusão lógica). O pull só entrega linhas de transações **já terminadas**, e o cursor do app é o par.

```sql
SELECT ... FROM <tabela>
 WHERE sync_xid < pg_snapshot_xmin(pg_current_snapshot())
   AND (sync_xid, sync_seq) > (:cursor_xid, :cursor_seq)
   AND <filtro do vendedor, vindo do token>
 ORDER BY sync_xid, sync_seq
 LIMIT :n
```

**Por que não perde registro (a prova).**
- `pg_snapshot_xmin` é o menor identificador de transação que ainda está em andamento. Toda transação com identificador **menor** já terminou (confirmou ou desfez).
- Uma transação nova, ou que ainda não gravou nada, só recebe identificador depois, e ele é **maior** que qualquer um já visto.
- Logo, o conjunto de linhas com `sync_xid` abaixo do `xmin` está **fechado**: nenhuma linha nova aparece nele. O cursor em `(xid, seq)`, que anda sempre para a frente nesse conjunto, nunca passa por cima de uma linha ainda não visível.
- No exemplo acima: A (xid menor) está aberta, então o `xmin` é o de A e a linha 11 (de B, xid maior) **não é entregue**. Quando A confirma, o pull entrega a 10 e a 11, nessa ordem.

**Verificado em 09/11/2026** num PostgreSQL 18.6 de teste: com A aberta e B confirmada, o pull seguro devolveu zero linhas, e depois que A confirmou devolveu as duas na ordem certa; já o pull ingênuo por `sync_seq` teria entregado só a de B. Linhas de um `SAVEPOINT` e da transação principal têm o mesmo `sync_xid` (o identificador é o da transação principal); linhas desfeitas por `ROLLBACK` ou `ROLLBACK TO SAVEPOINT` nunca aparecem; a lápide de uma exclusão lógica reaparece com `sync_xid` e `sync_seq` novos.

**Detalhes do desenho.**
- **Uma sequência global** para todas as tabelas sincronizáveis, então o cursor é um só. O pull consulta cada tabela com o mesmo critério, junta pelo par `(sync_xid, sync_seq)` e corta no limite do lote; cortar depois de juntar é seguro porque todo candidato fora do corte vem depois do último entregue.
- **Cursor opaco** para o app: `época:xid:seq` (o app só guarda e devolve; o campo local `ultimo_sync_seq` da §10 guarda esse texto). Pull sem linhas novas devolve o mesmo cursor.
- **Só transações que gravaram algo seguram o pull.** O `xmin` considera transações que já receberam identificador. Sessões paradas e leituras (inclusive o `pg_dump`) não seguram.
- `max_prepared_transactions` fica em 0 (transações preparadas segurariam o `xmin` indefinidamente).
- O índice `(sync_xid, sync_seq)` existe em toda tabela sincronizável.

**Transações longas (efeito e como evitar).** Uma transação que gravou e está demorando **atrasa o pull de todo mundo** a partir do identificador dela, porque o `xmin` não anda; ela **nunca faz perder dado**: quando termina, tudo é entregue na ordem. Para evitar:
- **Limites só para o usuário da aplicação, nunca globais:** `ALTER ROLE <usuario_da_aplicacao> SET transaction_timeout = '60s'` e `ALTER ROLE <usuario_da_aplicacao> SET idle_in_transaction_session_timeout = '30s'`. Eles **não valem** para o usuário que roda as migrações do Flyway nem para o usuário do `pg_dump`, que não podem ser derrubados no meio. Por isso o Flyway usa um usuário próprio (dono do schema), separado do usuário da aplicação (que também não é superusuário, §11). A tarefa 5 cria os papéis e a tarefa 6 configura o Flyway com o usuário dele.
- Lotes de importação e de geração de relatório em transações curtas (por exemplo 500 linhas por transação).
- **Alerta de atraso:** a API expõe (e o painel de saúde mostra) a idade da transação com identificador mais antiga em `pg_stat_activity`; passou de 30 segundos, alerta.
- Migração longa do Flyway também atrasa o pull durante a atualização do servidor. É aceitável e esperado.

**Restauração de backup (R1).** O backup é um `pg_dump` (§11). Ao restaurá-lo num servidor novo, o contador de transações **recomeça do zero**, mas os `sync_xid` gravados nas linhas continuam com os valores antigos, que podem ser **maiores** que o `xmin` do servidor novo. Essas linhas nunca seriam entregues (foi o que se viu no teste: depois da restauração, só 3 de 5 linhas apareceram no pull). O procedimento de restauração é, portanto, parte da decisão:

1. Parar a API (ninguém escreve durante o procedimento).
2. Restaurar o dump no banco novo.
3. Reescrever o `sync_xid` de **todas as linhas de todas as tabelas sincronizáveis** para o valor mínimo (`'0'::xid8`), **com os gatilhos do usuário desligados** (`ALTER TABLE ... DISABLE TRIGGER USER`, e `ENABLE TRIGGER USER` no fim), para não mexer em `sync_seq`, `version` nem `updated_at`. A ordem entre as linhas antigas continua a do `sync_seq`, e toda linha nova gravada depois recebe um identificador maior que zero.
4. Subir a **época** do cursor (valor interno `sync_epoca`, guardado em `configuracoes` e **nunca** enviado ao app, mais 1).
5. Subir a API.

Um app com cursor de época antiga recebe a resposta "cursor inválido" e **refaz a carga completa** do que pertence ao vendedor. O que o app guardou para enviar (vendas, clientes) **não é afetado**: o envio segue normal. O teste periódico de restauração da §11 passa a incluir um pull completo depois do procedimento. Um restore físico (cópia do diretório de dados) e o `pg_upgrade` preservam o contador, mas o mesmo roteiro é usado sempre, para existir um só procedimento.

**Testes que a tarefa 8 implementa** (com PostgreSQL de verdade via Testcontainers, nunca banco em memória):

*Concorrência* (determinísticos, com duas conexões controladas pelo teste):
- A abre e grava, B grava e confirma, pull não entrega B; A confirma, pull entrega A e B na ordem; o cursor avança e nada se perde.
- A ordem inversa (a transação de menor identificador confirma primeiro).
- `SAVEPOINT`: linhas antes e depois têm o mesmo `sync_xid`; linha desfeita pelo `ROLLBACK TO` nunca aparece; `ROLLBACK` total nunca aparece.
- Update de uma linha já entregue volta no pull com versão nova; exclusão lógica volta como lápide.
- Fronteira de página: lote menor que o número de linhas de uma única transação (mesmo `sync_xid`), sem repetir nem pular linha.
- O gatilho ignora `sync_seq`, `sync_xid`, `version`, `updated_at` e `origem_dispositivo` enviados pelo cliente; `DELETE` é recusado; `UPDATE` em tabela imutável é recusado.
- Cursor de época antiga recebe "cursor inválido".
- **Restauração:** fazer um `pg_dump`, restaurar em um segundo PostgreSQL novo, mostrar que **sem** o procedimento linhas ficam sem ser entregues (o teste prova que enxerga o problema) e que **com** o procedimento todas são entregues e a época sobe.
- Papéis: `transaction_timeout` vale para o usuário da aplicação e **não** para o usuário do Flyway.

*Estresse* (uma só classe, com semente fixa impressa no log para reproduzir):
- N escritores (por exemplo 8) em paralelo, cada transação com 1 a 20 inserts, updates e exclusões lógicas em várias tabelas, pausas aleatórias de 0 a 2 s antes de confirmar, cerca de 15% de `ROLLBACK`, uso de `SAVEPOINT`, e de tempos em tempos uma transação longa (cerca de 5 s).
- Um leitor pagina o pull sem parar, com lotes aleatórios de 1 a 50, aplicando tudo em um mapa na memória (id para versão mais recente).
- No fim, depois que todas as transações terminam, o leitor esvazia o pull. **Afirmação:** o mapa é idêntico ao conteúdo das tabelas (todo id, toda versão, todo `deleted_at`); o cursor nunca andou para trás.
- Duração: curta (cerca de 20 s) em todo Pull Request; longa (cerca de 10 minutos) em execução agendada à noite e sob demanda.

### 6. Idempotência

A chave de idempotência é o **UUID gerado no app** (`vendas.id`, `movimentos_remessa.id`, `pagamentos_cliente.id` etc.), com restrição de unicidade no banco. Vale também para as telas online do Corona Gestão, que também geram o `id` no cliente.

- **Primeiro envio:** o servidor grava o registro e **todos os efeitos** (contas a receber, comissões, movimentos) **na mesma transação**, e guarda o resultado do item em `sync_log` junto com um resumo (SHA-256) do conteúdo.
- **Reenvio com o mesmo `id` e o mesmo conteúdo** (queda de conexão no meio do envio): o servidor **devolve o mesmo resultado já guardado**, com os totais do servidor, e **não cria nada de novo**. Na resposta do lote, o item aparece como aceito e marcado como reenvio.
- **Dois envios iguais ao mesmo tempo:** a restrição de unicidade deixa um passar; o outro espera, lê o registro gravado e responde igual (`INSERT ... ON CONFLICT DO NOTHING`, depois leitura).
- **Mesmo `id` com conteúdo diferente:** é conflito. O servidor **não sobrescreve** (a venda é imutável), registra o fato em `sync_log` e na auditoria e responde conflito para aquele item, que vai para "envios com problema" da dona.

## Consequências

### Boas
- O pull não perde registro mesmo com confirmações fora de ordem, o que protege o dado mais valioso do app do vendedor (§10).
- Uma única regra de arredondamento e um único utilitário de dinheiro impedem somas diferentes entre telas e relatórios (§13).
- A diferença entre sincronizável e só do servidor fica explícita e testável.
- Mesmo uma restauração de backup tem roteiro e teste.

### Ruins ou custos
- O pull entrega só o que está abaixo do `xmin`: com uma transação longa, os dispositivos veem as mudanças com atraso. Os limites de tempo e o alerta reduzem isso, não eliminam.
- Cada tabela sincronizável carrega duas colunas e um índice a mais, e cada escrita paga o `nextval`.
- A época do cursor faz todo aparelho refazer a carga completa depois de uma restauração. É raro e é o preço de ficar seguro.
- O procedimento de restauração tem um passo manual (reescrever `sync_xid`) que, se esquecido, deixa linhas invisíveis ao pull; por isso entra no teste de restauração.
- UUID v7 mostra o horário aproximado de criação do aparelho.
- Arredondar a cada valor pode somar centavos de diferença de quem arredondaria só no fim. É a regra escolhida e deve ser explicada à dona na validação (§13.7).

## Alternativas consideradas

- **Só `sync_seq` crescente (sem `xid`):** perde registros (o problema acima). Descartada.
- **Travar os escritores para numerar na ordem de confirmação (bloqueio consultivo, tabela de numeração):** serializa todas as gravações; o servidor deixa de aguentar concorrência. Descartada.
- **Margem de tempo por `updated_at` (por exemplo, não entregar o que tem menos de 5 minutos):** é um palpite; uma transação mais longa que a margem ainda perde linha. Descartada.
- **`track_commit_timestamp` (hora de confirmação):** a ordem pela hora de confirmação não coincide com a ordem de visibilidade e exige ajuste global do servidor. Descartada.
- **Decodificação lógica / captura de mudanças (CDC):** correta, mas pesada de operar numa VPS pequena e distante do que a equipe domina. Descartada.
- **UUID v4:** funciona, mas fragmenta os índices das tabelas grandes. Descartado. **bigint/serial:** contra a especificação (ID gerado no cliente).
- **Calcular dinheiro com `double` ou arredondar só no fim da conta:** descartado pela §7.5.

## Seções da especificação relacionadas

§6 (premissa 5 e 15), §7.3 e §7.5 (arredondamento e imutabilidade), §8 (código de barras como texto), §9 (campos de controle, `configuracoes`, `sync_log`), §10 (sincronização, política de conflito, o que cada vendedor recebe), §11 (BCrypt e backup), §13 (garantias), §17.6 (comissão).

## Dúvidas

1. **Desconto em porcentagem por linha (padrão proposto):** confirmar na Fase 4 com a dona (é o que torna a soma das linhas igual ao total).
2. **Base de cálculo da comissão:** a §6.8 diz "sobre o vendido no acerto" e a §17.6 está pendente com o cliente. Este ADR **não decide**: descreve o arredondamento por venda e sobre o total do acerto. A resposta define qual dos dois a tarefa de comissões implementa na Fase 7.
3. **Onde fica a época do cursor:** proposto como valor interno de `configuracoes`; se o responsável preferir uma tabela própria, o restante do ADR não muda.
4. **`preco_custo` numa tabela só do servidor (`produtos_custos`):** recomendado, mas diverge da §9. Precisa de aprovação do responsável e, se aprovado, de uma linha na revisão da especificação.
5. **Retenção de `sessoes` e `tentativas_login`:** nada é apagado na Fase 0. Falta definir por quanto tempo guardar e como descartar sem `DELETE` (por exemplo, partições). Fica para um ADR futuro.
6. **Biblioteca de UUID v7** no TypeScript e no Java: escolha da tarefa 8 (e da 4 no front-end), sem ADR.
