# ADR-0013: Sincronização e modo sem internet do Corona Vendas

- **Status:** Aceito (11/11/2026, pelo responsável, com as mudanças das linhas datadas no fim: formatos antigos por 90 dias, senha ao abrir o Corona Vendas, clientes da loja e retenção do `sync_log`). As dúvidas do [plano da Fase 5](../progresso/fase-5-plano.md#dúvidas) valem com os padrões seguros descritos lá
- **Data:** 11/11/2026
- **Tarefas que implementam:** 5.2 a 5.15 (Fase 5). Depende do ADR-0005 (SQLCipher), hoje Proposto

## Contexto

A §10 manda o Corona Vendas ser offline-first: lê e grava sempre no SQLite local criptografado, e um motor em segundo plano envia (push) e recebe (pull) quando há internet. A Fase 0 deixou pronto o que não podia mudar depois da primeira migração (ADR-0002): UUID v7 gerado no app, exclusão lógica, gatilho que escreve `version`, `sync_seq` e `sync_xid`, o pull com **limite seguro** pelo `pg_snapshot_xmin`, o cursor opaco `época:xid:seq`, a época para depois de restaurar backup, o `RegistroDeTabelas`, o `FiltroDoVendedor` como parâmetro e o estresse de 20 s (cada PR) e 10 min (semanal). As Fases 1 a 4 gravaram tudo já no formato do sync, e o ADR-0011 diz que a Fase 5 "só troca o transporte": venda, estorno, cliente e conferência já têm idempotência 201/200/409 por UUID.

Falta decidir: o protocolo de push e pull, o filtro por vendedor (inclusive quando uma linha **entra** ou **sai** do que é dele sem ser alterada, ponto que o `FiltroDoVendedor` já avisava), conflitos, fotos, relógio, prazo offline, bloqueio remoto, aparelho único, banco local, migração e atualização com envios pendentes, indicador na tela, falhas de rede, testes e métricas.

Princípio que guia todas as escolhas: **a venda guardada nunca se perde, e o vendedor nunca recebe o que não é dele.** Na dúvida, o padrão é o que não perde dado e não vaza dado, mesmo que custe mais trabalho para a dona resolver depois.

## Decisão

### 1. Rotas

- `POST /api/vendedor/sync/push` e `GET /api/vendedor/sync/pull`, mais `GET /api/vendedor/sync/conferencia` (item 6) e `POST /api/vendedor/sync/quarentena` (item 14). Ficam em `/api/vendedor/**` (ADR-0003): o que a §10 chama de `/sync/push` e `/sync/pull` é este par, com o prefixo do grupo. Só o perfil `VENDEDOR`, com o `vendedor_id` e o `dispositivo_id` **do token**, nunca do corpo.
- **O app passa a gravar tudo pela outbox, inclusive com internet.** Um caminho só (local + outbox + push) é mais fácil de provar do que dois. As rotas online da Fase 4 continuam existindo (testes, e a dona usa as dela), e **o push chama os mesmos serviços** (`ServicoDeVendas`, `ServicoDeConferencia`, clientes): a regra de negócio não é duplicada.

### 2. Push: outbox, ordem, lotes e resposta por item

**Outbox local** (tabela `outbox` no SQLCipher), uma linha por operação:

| Campo | Para quê |
| --- | --- |
| `ordem` | inteiro crescente local; só ordena a fila, nunca sai do aparelho como identificador |
| `operacao_id` | UUID v7. **É a chave de idempotência.** Na criação de um registro é o próprio `id` dele (venda, cliente, conferência, estorno); na alteração de cliente é um UUID novo por alteração |
| `tipo` e `formato` | por exemplo `VENDA`/1, `ESTORNO_VENDA`/1, `CLIENTE_NOVO`/1, `CLIENTE_ALTERADO`/1, `CONFERENCIA`/1. O formato só sobe com mudança incompatível (item 17) |
| `dados` | o JSON **exato** gravado na hora da operação. Reenvio manda sempre estes mesmos bytes; o app **nunca** monta o JSON de novo |
| `criado_em_local`, `ultimo_contato_servidor` | hora do aparelho e a última hora do servidor que o app conhecia ao gravar (item 11) |
| `situacao` | `AGUARDANDO`, `ENVIADO` (aceito ou já recebido), `RECUSADO`, `CONFLITO`; mais `tentativas` e o último erro |

A operação local e a linha da outbox são gravadas **na mesma transação** do SQLite: ou existem as duas, ou nenhuma.

**Lote.** Até 50 operações ou 512 KB, na ordem da fila, com `horaLocal` (relógio do aparelho no envio) e `aguardandoDepoisDeste` (quantas sobram). Só **um envio por vez por aparelho**: o servidor trava pelo `dispositivo_id` (trava na memória, como a `TravaPorIp` da tarefa 11; espera de no máximo 5 s, depois 429 `ENVIO_EM_ANDAMENTO` com `Retry-After`). Sem isso, um reenvio por tempo esgotado poderia rodar ao lado do envio original e processar uma venda antes do cliente que ela usa. Rodar mais de uma instância da API continua pedindo ADR (já anotado na tarefa 11).

**Processamento no servidor.** Na ordem do lote, **uma transação por operação** (uma operação recusada não desfaz as outras), cada uma com o registro e todos os efeitos (movimento da maleta, pagamentos) e a linha em `sync_log` (tabela só do servidor: `operacao_id` único, aparelho, vendedor, tipo, formato, SHA-256 do conteúdo, resultado, código, recebido em, e o JSON recebido). O resumo SHA-256 é calculado sobre o DTO **já lido e reescrito em forma canônica** (campos em ordem fixa, dinheiro como texto de duas casas), para dois JSON equivalentes darem o mesmo resumo.

**Resultado de cada operação:**

| Resultado | Quando | O que o app faz |
| --- | --- | --- |
| `ACEITO` | primeira gravação (o 201 da Fase 4) | marca `ENVIADO` e troca a cópia provisória pela do servidor, que vem na resposta |
| `JA_RECEBIDO` | mesmo `operacao_id` e mesmo resumo (o 200): queda de conexão no meio do envio | igual ao aceito; nada é criado de novo |
| `CONFLITO` | mesmo `operacao_id` com conteúdo diferente (o 409) | marca `CONFLITO`; o servidor **não sobrescreve**, grava na auditoria e mostra em "envios com problema" da dona |
| `RECUSADO` | regra de negócio (peça fora da maleta, saldo zero, maleta fechada, desconto acima do limite, cliente de outro vendedor, depende de operação recusada) | marca `RECUSADO`, desfaz o efeito local (o saldo volta) e mostra ao vendedor; a dona vê em "envios com problema" |
| `NAO_PROCESSADO` | erro passageiro (tempo esgotado, trava, erro interno) nesta operação ou o lote passou de 20 s | fica `AGUARDANDO`; o servidor **para o lote** nessa operação, para não quebrar a ordem |

- **Recusa nunca é silenciosa** (§10): ela fica em `sync_log` com o JSON inteiro, gera aviso para a dona e aparece para o vendedor. Saldo que ficaria negativo é recusado (`SALDO_INSUFICIENTE`), porque o saldo nunca passa de zero (ADR-0010 e ADR-0011); o aviso diz à dona que só acontece por erro ou fraude.
- **A dona resolve a recusa.** Em "envios com problema" ela vê a operação, o motivo e o vendedor, e escolhe **Tentar de novo** (depois de corrigir a causa, por exemplo devolver o cliente transferido ao vendedor: a operação guardada passa outra vez pelo mesmo serviço, com o mesmo `operacao_id`, e com auditoria) ou **Descartar** (com motivo, auditado). Nada é apagado. Exemplo que pede isso: venda feita sem internet para um cliente que a dona transferiu a outro vendedor nesse meio-tempo; a venda é real e não pode sumir, mas o servidor não aceita sozinho uma venda para cliente que não é do vendedor.
- **Dependência:** uma operação que usa um registro cuja operação foi recusada no mesmo lote, ou antes, é recusada com `DEPENDE_DE_ENVIO_RECUSADO`. O servidor decide pelos dados (o cliente da venda não existe), não por uma lista de dependências que o app mandaria.
- **Recusa por erro do servidor repetido:** depois de 5 `NAO_PROCESSADO` seguidos na mesma operação, o app mostra "Uma venda não conseguiu ser enviada. Avise a dona." e continua tentando a cada ciclo; ele **não pula** a operação, porque as seguintes podem depender dela. O servidor já registrou o erro no log.
- **O app nunca apaga uma operação da outbox sem resposta do servidor.** Operações `ENVIADO` saem da outbox depois de 30 dias; `RECUSADO` e `CONFLITO` ficam até o vendedor tocar em "Entendi" (a dona já foi avisada pelo servidor).

### 3. Pull: cursor e limite seguro

**O que a Fase 0 já tem e não muda:** transação `REPEATABLE READ READ ONLY`, `pg_snapshot_xmin` calculado uma vez e usado em todas as tabelas, junção por `(sync_xid, sync_seq)`, corte no lote, cursor `época:xid:seq` opaco para o app, `CursorInvalidoException` para a época antiga, e a regressão do xid 999/1000.

**O que a Fase 5 acrescenta:**

- Resposta: `linhas` (cada uma com `tabela`, `id`, `version`, `deletedAt` e os campos da lista explícita), `cursor`, `temMais`, `horaServidor` e `parametros` (item 4). Limite padrão 500 linhas, máximo 1000.
- Cursor vazio é a **carga inicial** (tudo o que é do vendedor). Cursor de época antiga responde 409 `CURSOR_INVALIDO` e o app refaz a carga inicial **sem tocar** na outbox nem nas cópias provisórias (item 7).
- O app aplica cada lote **numa transação local** que também grava o cursor novo: se o app cair no meio, o lote vem de novo e a aplicação é idempotente.
- **Regra de aplicação: uma linha só substitui a local se a `version` recebida for maior.** Igual é ignorada. Assim, receber a mesma linha duas vezes ou fora de ordem (o que a expansão do item 5 provoca) é inofensivo.
- Lápide (`deletedAt` preenchido) marca a linha local como excluída, sem apagar (ADR-0002, item 4). A exceção é a **saída do vendedor** (item 5), que apaga a cópia local, porque o dado deixou de ser dele.
- Ordem do ciclo: **primeiro o push, depois o pull**, para o pull já trazer o efeito do que acabou de ser aceito. Um ciclo por vez, no processo principal do Electron.

### 4. Tabelas que vão e que não vão ao aparelho

A lista sai do `RegistroDeTabelas` de hoje (`origin/main`, depois da tarefa 4.2) mais as duas da tarefa 4.3 (PR 68, ainda aberto). O teste de convenções continua reprovando tabela fora das listas; a Fase 5 acrescenta: **toda sincronizável precisa declarar sua regra de filtro** (item 5), senão o teste falha.

| Tabela sincronizável | Quem grava | Filtro do vendedor V | Conflito |
| --- | --- | --- | --- |
| `cotacoes` | servidor | todas (públicas, cerca de 250 por ano; ADR-0006) | imutável |
| `categorias` | dona | todas (só `nome` e `ativo`) | servidor vence |
| `semanas` | dona | todas | servidor vence |
| `clientes` | dona e vendedor | **todos os clientes ativos, só as colunas do diretório** (nome, CPF, telefone e cidade; decisão de 11/11/2026, ver a linha datada no fim) | campo a campo (item 8) |
| `produtos` | dona | produto que aparece em `itens_remessa` de uma remessa de V já enviada | servidor vence |
| `produto_codigos` | dona | do produto acima | servidor vence |
| `produtos_fotos` | dona | do produto acima (só hash, principal e ordem) | servidor vence |
| `remessas` | dona (estado pelo servidor) | `vendedor_id = V` e `enviada_em` preenchido (rascunho nunca vai) | servidor vence |
| `itens_remessa` | dona | da remessa acima | servidor vence |
| `movimentos_remessa` | dona e vendedor (pela venda) | da remessa acima | imutável, só inserção |
| `conferencias`, `itens_conferencia` (tarefa 4.3) | vendedor | da remessa acima | imutável, só inserção |
| `vendas` | vendedor (estado pelo servidor) | `vendedor_id = V` | só inserção; o estorno é operação nova |
| `itens_venda`, `pagamentos_cliente` | vendedor | da venda de V | imutável, só inserção |
| `saidas_do_vendedor` (nova, item 5) | gatilho do servidor | `vendedor_id = V` | imutável, só inserção |

**Não vão nunca** (só do servidor, como está no `RegistroDeTabelas`): `sync_estado`, `perfis`, `usuarios`, `configuracoes`, `sessoes`, `tokens_de_renovacao`, `tentativas_login`, `auditoria`, `importacoes`, `importacoes_linhas`, `movimentacoes_estoque`, `compras`, `itens_compra`, `historico_custo`, `inventarios`, `itens_inventario`, `leituras_inventario`, `fornecedores`, `pracas`, `restricoes`, **`produtos_custos`**, `vendedores` e as oito tabelas filhas. A Fase 5 acrescenta, também só do servidor: `sync_log`, `dispositivos` (item 13) e `envios_em_quarentena` (item 14).

**`configuracoes` continua só do servidor.** A §10 e o ADR-0002 dizem que "parte de `configuracoes`" chega ao app; em vez de tornar a tabela sincronizável (e arriscar mandar uma chave que não devia), cada resposta do pull e do push traz o bloco `parametros`, com **lista explícita de chaves**: `limiteDescontoPercentual`, `tempoMaximoOfflineDias`, a situação do aparelho e, na Fase 6, a garantia. Chave nova no bloco é uma linha de código com teste.

**Tabelas das próximas fases** entram com a mesma regra, na tarefa que as cria: `devolucoes`, `itens_devolucao`, `trocas`, `itens_troca`, `autorizacoes_excecao`, `creditos_cliente` (Fase 6) e `contas_receber`, `comissoes`, `acertos`, `pendencias_acerto` (Fase 7), todas filtradas pelo vendedor do token.

O pull usa a **lista explícita de colunas** de cada `TabelaSincronizavel` (nunca `SELECT *`), e nenhuma delas tem custo.

### 5. Filtro por vendedor: entrar e sair sem ser alterado

**O furo.** O cursor só anda para a frente. Se uma linha passa a ser de V **sem ser alterada**, o cursor já passou por ela e ela nunca chega. Exemplos reais: os itens de uma maleta são gravados no rascunho (fora do filtro) e a maleta só entra no filtro ao ser enviada; um produto antigo entra numa maleta nova; os códigos e as fotos desse produto foram gravados meses antes. E o contrário: um cliente transferido de V para outro vendedor deixa de passar no filtro de V, então V nunca recebe a mudança e **fica com o cliente do outro**.

**Regra 1: quem decide a pertença, expande.** Quando o pull entrega a V uma linha que faz outras linhas passarem a ser de V, ele entrega junto o estado atual dessas outras, na mesma posição do cursor:

| Linha entregue | Expande para |
| --- | --- |
| `remessas` | seus `itens_remessa` |
| `itens_remessa` | o `produtos` do item, com seus `produto_codigos` e `produtos_fotos` |

A expansão lê a mesma fotografia do banco (a transação do pull) e nunca é cortada no meio: um lote pode passar do limite pelo tamanho de uma expansão. Uma linha entregue pela expansão pode chegar de novo pelo caminho normal; a regra da `version` (item 3) torna isso inofensivo.

**Custo aceito:** cada mudança de estado de uma maleta (enviada, conferida, e na Fase 7 em acerto e acertada) entrega de novo os itens e os produtos dela, com códigos e fotos. Para 500 produtos, cerca de 5 mil linhas pequenas por mudança. Só otimizar com medida na mão.

**Regra 2: saída por gatilho no banco.** A tabela nova `saidas_do_vendedor` (`vendedor_id`, `tabela`, `registro_id`; sincronizável e imutável) recebe uma linha **por gatilho**, na mesma transação, sempre que muda a coluna que diz de quem é a linha: hoje só `clientes.vendedor_responsavel_id` (o antigo responsável ganha a saída; o novo recebe o cliente pelo caminho normal, porque a linha mudou). Gatilho, e não código no serviço, para ninguém esquecer. O app, ao receber a saída, **apaga a cópia local** (exceto se houver operação aguardando envio sobre ela, que segue para o servidor e é recusada lá se for o caso).

**Produtos não saem.** O que já foi de V continua sendo: o filtro de produtos é "apareceu numa maleta enviada de V", que só cresce, e mantém o nome e o código das peças das vendas antigas dele. São dados da loja sem custo, poucos milhares de linhas. As **fotos** desses produtos saem do disco depois do acerto (item 9).

**Prova de que não vaza e não falta (testes escritos antes, tarefa 5.4):**

1. **Oráculo com dois vendedores.** Uma consulta de referência, escrita à parte e sem usar o código do pull, diz o que é de A e o que é de B. O teste faz centenas de operações aleatórias com semente fixa (a dona cria produtos, monta e envia maletas, muda preço, troca foto, exclui código, transfere cliente; A e B vendem e cadastram clientes) e, entre elas, A e B puxam em momentos aleatórios, com lotes de 1 a 50. No fim: o banco local de cada um é **igual** ao oráculo (todo id, toda versão, toda lápide); **nenhum id de B** aparece no de A, nem o contrário.
2. **Nenhuma coluna fora da lista** e nenhum marcador proibido (`scripts/marcadores-proibidos.json`, mais `custo`) no JSON de nenhuma resposta do pull ou do push.
3. **Teste de convenções:** toda sincronizável tem regra de filtro declarada; toda coluna "de quem é" (`vendedor_responsavel_id`, `vendedor_id`) tem o gatilho de saída ou está marcada como "nunca muda" com teste que prova que ela não muda.
4. O estresse do pull (Fase 0) ganha um modo com filtro: os escritores fazem também mudanças de pertença.

### 6. Conferência periódica (rede de segurança)

`GET /api/vendedor/sync/conferencia` devolve, por tabela, a quantidade e um SHA-256 da lista ordenada de `(id, version)` do que é de V. O app compara com o banco local uma vez por dia e depois de cada carga inicial. Diferença numa tabela: o app refaz a carga **daquela tabela** e o servidor registra o fato como **defeito** (alerta no log e no painel de saúde), porque significa um furo no filtro. Não substitui o item 5; serve para um furo novo nunca ficar escondido.

### 7. Banco local: espelho, provisórios e carga inicial

- Cada tabela sincronizável tem uma cópia local com as mesmas colunas da lista explícita, mais `version`, `deleted_at` e `origem_local` (`SERVIDOR` ou `PROVISORIO`). O que o vendedor grava sem internet é `PROVISORIO` até o servidor aceitar.
- A tela calcula como o servidor: saldo da maleta = `qtd_enviada` + movimentos (confirmados e provisórios); totais e parcelas pelo mesmo utilitário de dinheiro do `packages/shared` (centavos inteiros). É **prévia**: o que vale é o que o servidor devolve.
- **Carga inicial e recarga por época:** apaga as cópias `SERVIDOR`, mantém as `PROVISORIO` e a outbox, puxa do cursor vazio. Cliente provisório sobrevive; venda provisória continua aguardando envio.
- O React nunca lê o banco direto: tudo passa pela ponte fixa do processo principal (ADR-0004, item 3).

### 8. Conflitos

- **Vendas, itens, pagamentos, movimentos, conferências:** só inserção. Mesma chave com outro conteúdo é `CONFLITO` (item 2). Estorno é operação nova com chave própria (ADR-0011, item 9).
- **Clientes, campo a campo.** `CLIENTE_ALTERADO` leva a `versaoBase` que o app tinha e, para cada campo alterado, o valor de antes e o de depois. O servidor aplica cada campo cujo valor atual ainda é o "antes"; se a dona mudou **o mesmo campo** nesse meio-tempo, vale a §10 ("última alteração vence") pela **ordem de chegada ao servidor** (nunca pelo relógio do aparelho), e o valor que foi substituído vai para a auditoria, para a dona poder ver e voltar (dúvida D1). O vendedor só altera os campos do cadastro que ele já preenche; `vendedor_responsavel_id`, `ativo` e situação são só da dona e são ignorados se vierem.
- **Camada de pendentes no app:** enquanto houver alteração de cliente aguardando envio, o pull que traz uma versão nova desse cliente é aplicado e as alterações pendentes são **reaplicadas por cima** na cópia local, para o vendedor não ver a edição dele sumir.
- ~~**CPF repetido** (o banco não tem `UNIQUE` em CPF, V010): o cliente novo é aceito, marcado para a revisão da dona, e o vendedor não recebe nada sobre o outro cadastro.~~ **Mudou em 11/11/2026: CPF repetido é BARRADO** (online, 409 "Este cliente já está cadastrado" com o cliente existente; offline, o app confere no diretório local; dois cadastros do mesmo CPF feitos ao mesmo tempo sem internet chegam como `CONFLITO` para a revisão da dona, que junta os cadastros). Ver a linha datada no fim.
- **Produtos, preços, códigos, maleta, estados:** servidor vence; o app só recebe. **Contas a receber, comissões, acertos:** gerados pelo servidor (Fase 7).

### 9. Fotos

- O pull traz só os metadados (`produtos_fotos`: hash, principal, ordem). As imagens vêm por uma **fila de download separada**, pela rota que a tarefa 4.3 já criou (`/api/vendedor/fotos/{hash}/{tamanho}`, só foto de peça de maleta do próprio vendedor).
- **Ordem:** primeiro a **miniatura** de todas as peças das maletas abertas, depois a **média**; a **grande** só sob demanda, com internet, num cache limitado (200 MB, apaga a menos usada). Três downloads ao mesmo tempo.
- **Cache em disco** na pasta do app, fora do banco: `fotos/<hash>/<tamanho>.jpg`. Grava num arquivo temporário e renomeia no fim (arquivo pela metade nunca vale). A resposta traz o SHA-256 daquele tamanho num cabeçalho; não bateu, apaga e tenta de novo. Queda no meio: o arquivo recomeça no próximo ciclo.
- **Troca de foto:** o hash muda, o pull traz o metadado novo, a fila baixa a nova e a antiga é apagada.
- **Limpeza:** depois do acerto (Fase 7) ou do cancelamento da maleta, miniatura e média de peças que não estão em nenhuma maleta aberta saem do disco.
- **Falta de foto nunca trava:** sem o arquivo, a tela mostra a imagem "sem foto" e a venda segue. A conferência **não espera** as fotos: mostra "Baixando fotos: 45 de 120" (§20) e deixa conferir.
- A foto da peça lida aparece em menos de 100 ms (cache local) e o leitor não espera a imagem.

### 10. Cotações

- `cotacoes` vem pelo pull, sem filtro, imutável (ADR-0006). Na carga inicial, tudo o que existe.
- Sem internet, a tela usa a cotação do dia da venda se o app a tiver recebido; senão, a última recebida, **mostrando a data dela** (ADR-0006, item 13). O app nunca busca, digita ou deriva cotação.
- A cotação que fica gravada na venda é **a do servidor**, pelo dia da venda já corrigido (item 11), e não a que a tela mostrou.

### 11. Relógio e datas

- O relógio do aparelho **nunca ordena nada** (a ordem vem do `sync_seq` e da fila) e **nunca decide prazo sozinho**.
- **Hora de negócio corrigida pelo servidor.** O lote traz `horaLocal`; o servidor calcula a diferença `horaLocal - agora` e corrige a hora declarada de cada operação (`vendida_em`, hora do estorno) por essa diferença. Depois limita o resultado a um intervalo possível: não antes do maior entre `ultimo_contato_servidor` da operação e o envio da maleta, e não depois da chegada. A hora declarada original e a diferença ficam em `sync_log`; se a correção passou de 5 minutos, a venda fica marcada "hora ajustada" (coluna nova `hora_ajustada` em `vendas`, migração nova) para a dona ver.
- O **dia da venda** (para semana, estorno pelo vendedor "até o fim do dia" e cotação) é o dia em horário de Brasília da hora corrigida. Tudo é gravado em UTC.
- Relógio mudado **no meio** do período sem internet não tem correção exata: o limite do intervalo impede datas impossíveis, e a marca "hora ajustada" mostra à dona.

### 12. Prazo máximo offline (10 dias) e sessão

- O app guarda, a cada resposta válida do servidor, o par (hora do servidor, hora local) e, a cada minuto e a cada gravação, a **maior hora local já vista**.
- Dias sem contato = hora local agora menos a hora local do último contato. Se o relógio local estiver **mais de 5 minutos antes** da maior hora já vista, ou antes do último contato, conta como **prazo esgotado** (ADR-0004, item 5). Relógio adiantado só faz o prazo acabar mais cedo, que é o lado seguro.
- **Aviso** a partir de 3 dias antes do fim (dúvida D3). **Esgotado:** o app bloqueia tudo o que grava (venda, estorno, conferência, cliente), continua mostrando os dados e **continua enviando** ao conectar, com o texto do glossário ("Este computador ficou muitos dias sem internet..."). A outbox nunca é apagada por prazo.
- ~~O app abre sem internet com a sessão guardada, sem pedir senha (dúvida R7).~~ **Mudou em 11/11/2026: o app pede a senha do vendedor ao abrir, também sem internet** (verificador lento local, 5 erros bloqueiam até conectar; ver a linha datada no fim). Se ao reconectar a sessão de 30 dias também tiver acabado, o vendedor entra de novo e o envio segue (ADR-0004, item 5).

### 13. Um aparelho por maleta e segundo aparelho

- Tabela só do servidor **`dispositivos`**: id (o identificador do aparelho do ADR-0004), usuário, situação (`ATIVO`, `AGUARDANDO_LIBERACAO`, `SUBSTITUIDO`, `BLOQUEADO`), primeiro e último contato, versão do app, última contagem `aguardandoDepoisDeste` e **último envio completo** (hora em que um push terminou com zero aguardando). Chave estrangeira de `origem_dispositivo` para ela (pendência da tarefa 8).
- **Primeiro aparelho** do vendedor nasce `ATIVO` no login. **Segundo aparelho** entra (o login funciona), mas nasce `AGUARDANDO_LIBERACAO`: push, pull e venda respondem 403 `APARELHO_NAO_LIBERADO` ("Este computador ainda não foi liberado pela dona."). A dona libera em "Trocar computador", que marca o antigo como `SUBSTITUIDO` e passa as maletas dele para o novo (unifica o `liberar-aparelho` por maleta da tarefa 4.3). A tela mostra o que o servidor sabe: "O computador antigo avisou 3 vendas aguardando envio em 10/11."
- **Aparelho `SUBSTITUIDO`** ainda pode **enviar** o que tem (as vendas são reais), não puxa nem vende; quando o envio termina com zero aguardando, recebe a ordem de apagar (item 14).
- **Offline:** o app só vende de maleta ligada a ele (ou ainda sem aparelho; a primeira venda ou conferência liga, e o servidor confirma no push). Se outro aparelho ligou antes, a venda é `RECUSADO` `MALETA_EM_OUTRO_APARELHO`. Como cada vendedor tem um aparelho ativo, isso só acontece na troca de computador.
- **Linha datada, 12/11/2026 (tarefa 5.14, decisão da orquestradora a partir do achado da 5.12):** venda feita no **computador antigo depois da troca** (o vendedor não sabia da troca e vendeu sem internet) **não se recusa por ter vindo do computador antigo**: o servidor a aceita pela cadeia de trocas (item 13, tarefa 5.5) e confere só o saldo da maleta; sem saldo, é recusada como qualquer outra venda e vai para "envios com problema". A regra é "o que aconteceu no balcão não se recusa". Também nesta tarefa, o 403 `APARELHO_NAO_LIBERADO` deixou de citar o nome do app da dona ao vendedor: "Este computador ainda não foi liberado pela dona. Avise a dona para liberar este computador."
- **Linha datada, 13/11/2026 (issue #127, achado do estresse com a semente 43338020207):** o computador trocado **só apaga com a fila local zerada; desligando, nada grava.** O servidor manda `apagarDados` pelo `aguardandoDepoisDeste` de quando o lote foi montado; se o vendedor gravou mais enquanto o envio estava na rede, o app ignora a ordem daquela resposta e manda o resto (a ordem volta no último lote). A conferência da fila e a passagem para "desligando" acontecem no mesmo trecho síncrono, e a partir do "desligando" toda gravação é recusada com "Este computador não pode mais registrar vendas. Fale com a dona." A quarentena continua só do bloqueio (item 14); o servidor não mudou.
- O acerto (Fase 7) deve exigir "último envio completo" depois da última venda conhecida e tratar venda que chegar depois. Na Fase 5, venda de maleta fora de `ENVIADA`/`CONFERIDA` é `RECUSADO` `MALETA_FECHADA` e vai para "envios com problema".

### 14. Bloqueio remoto e apagar os dados

- A dona **bloqueia o aparelho** (perdido, roubado) ou **desativa o usuário**. As famílias de sessão são revogadas (ADR-0004) com motivo próprio, e a próxima chamada (renovar, push ou pull) responde 403 `APARELHO_BLOQUEADO` ou `USUARIO_DESATIVADO` com a ordem `APAGAR_DADOS`.
- **Antes de apagar, quarentena.** Se a outbox tem algo aguardando, o app manda tudo para `POST /api/vendedor/sync/quarentena`, autenticado pelo token de renovação revogado e pelo identificador do aparelho (prova de que é aquele aparelho). O servidor guarda em `envios_em_quarentena` **sem aplicar**; a dona vê "Vendas recebidas de um computador bloqueado" e aprova cada uma (que então passa pelo mesmo serviço do push, com auditoria) ou descarta (dúvida D6). O app apaga depois da resposta definitiva do servidor (guardado ou recusado), ou de 3 tentativas sem resposta no mesmo contato.
- **Apagar** = primeiro o arquivo da chave (sem ele o banco é ilegível), depois o banco, o WAL, a pasta de fotos, o token de renovação e o identificador do aparelho (reusar o computador gera outro). A tela mostra "Este computador foi desligado da loja. Os dados foram apagados." (texto novo no glossário).
- **Linha datada, 13/11/2026 (issue #127):** o bloqueio também passa a recusar gravação durante o "desligando" (mesmo texto da troca, item 13): a lista que vai para a quarentena é tudo o que existe no computador, e nada gravado depois da ordem se apaga sem ir a lugar nenhum.
- **Risco aceito:** quem roubou e nunca conecta não recebe a ordem. A proteção é o banco cifrado com a chave no cofre do Windows (ADR-0005) e o prazo offline, que bloqueia vendas novas em 10 dias. Recomendar senha do Windows e BitLocker nos notebooks (dúvida D7).

### 15. Banco local criptografado (ADR-0005)

O que o spike da tarefa 18 achou e vale: `better-sqlite3-multiple-ciphers` 13.0.3 exata, N-API (sem compilador, sem `electron-rebuild`), SQLCipher 4, chave aleatória de 256 bits, WAL, `synchronous=FULL`, chave errada falha na hora, processo morto no meio da transação não corrompe, milissegundos para tudo. O que ficou para a Fase 5 (tarefa 5.2): abrir o arquivo com um SQLCipher padrão; a barreira do pacote com o módulo dentro; o código em TypeScript no processo principal; queda de energia e disco cheio; a cópia antes da migração e o `.ilegivel`; o teste no CI.

- **Onde fica a chave:** `dados/chave.bin`, ao lado do banco na pasta de dados do app, cifrada pelo `safeStorage` (DPAPI, preso ao usuário do Windows). Só o processo principal a vê.
- **Perda da chave** (Windows reinstalado, perfil recriado, pasta copiada para outro computador): o app **não apaga e não cria chave nova sobre o banco**. Move o banco para `.ilegivel`, começa um banco novo, puxa tudo de novo e o servidor marca o aparelho como "reiniciado", com a última contagem de "aguardando" que ele conhecia, para a dona saber se havia vendas sem enviar. Mensagem do ADR-0005.
- **O que se perde** nesse caso é a outbox não enviada. A proteção é enviar cedo (gatilho depois de cada gravação) e mostrar quantas vendas aguardam.
- **Segunda cópia da chave protegida pela senha do vendedor: não.** O arquivo copiado viraria alvo de força bruta contra uma senha de 8 caracteres, quando hoje ele exige o login do Windows; e o caso que ela salva (pasta copiada, perfil recriado) é raro, porque reinstalar o Windows já apaga a pasta. Dúvida R3.
- **Backup:** o banco local não tem backup próprio; o backup é o servidor, depois do envio. A exceção é a **cópia antes de cada migração** (item 16).
- **Disco cheio:** a transação do SQLite falha inteira (a venda nunca fica pela metade) e a tela diz "O computador está sem espaço. Libere espaço e tente de novo." Aviso ao abrir com menos de 500 MB livres.

### 16. Migração do banco local

- Lista ordenada, só para a frente, cada uma numa transação que grava `_migracoes` (versão, SHA-256 do SQL, data) e `PRAGMA user_version`; migração aplicada cujo SQL mudou derruba a abertura (ADR-0005, item 5).
- **Cópia do arquivo antes de migrar** (`banco.antes-vN`), mantendo as duas últimas. Falhou: volta para a cópia e mostra erro simples.
- **Banco mais novo que o app** (`user_version` maior do que o app conhece, por exemplo depois de voltar uma versão): o app **não abre e não mexe**; pede para atualizar.
- **A outbox não é migrada:** as operações guardam o JSON exato e o `formato`; migração de tabela nunca reescreve `dados`.
- **Teste obrigatório (§10):** o repositório guarda um banco de exemplo de **cada versão publicada** do schema local, com operações aguardando; o teste abre cada um com o app atual, migra e envia, e confere que nada se perdeu nem duplicou.

### 17. Atualização do app com envios pendentes

- Antes de instalar a atualização (Fase 9, ADR-0009), o motor para, espera o envio em andamento terminar (no máximo 30 s), tenta mais um envio se houver internet e fecha o banco limpo. A outbox atravessa a atualização no próprio banco.
- **O servidor aceita cada `formato` de operação por pelo menos 90 dias** (era 180; mudou em 11/11/2026) depois que um formato novo é publicado (dúvida R5). Mudança compatível não sobe o formato.
- **Versão mínima (426) não pode prender vendas.** O push continua aceito de app abaixo da versão mínima quando o formato das operações ainda é aceito; pull e venda nova recebem 426. Depois de enviar, o app mostra "Seu aplicativo está desatualizado." (dúvida R4). Subir a versão mínima nunca tira o suporte a um formato que ainda pode estar numa outbox.

### 18. Indicador na tela

Textos da parte 7 do glossário (já existem): "Com internet. Tudo enviado.", "Com internet. Enviando suas vendas…", "Sem internet. Suas vendas estão salvas e serão enviadas quando você conectar.", "3 vendas aguardando envio" / "3 itens aguardando envio", e o "Dados até dd/mm às hh:mm" dos relatórios (hora do servidor no último pull completo). Textos novos, **acrescentados ao glossário antes de usar**: aviso de prazo ("Conecte-se à internet em até 2 dias para continuar vendendo."), "Este computador ainda não foi liberado pela dona.", "1 venda não foi aceita. A dona já foi avisada." (com o motivo em palavras simples e o botão "Entendi"), "Uma venda não conseguiu ser enviada. Avise a dona.", "Baixando fotos: 45 de 120", disco cheio, banco ilegível e aparelho apagado. Nunca "sincronização", "fila", "pendente" ou código técnico (glossário, bloco A).

Saber se há internet: o app chama a rota de saúde da API (tempo máximo 5 s); `navigator.onLine` e `net.isOnline()` só servem de dica.

### 19. Gatilhos e falhas de rede

- **Gatilhos (§10):** abrir o app, internet voltar, a cada 60 s com internet e 2 s depois de cada gravação (agrupando gravações seguidas).
- **Falha:** nova tentativa com espera crescente (2, 4, 8 s... até 5 min) com variação aleatória. Erro de tamanho do lote: o próximo lote tem metade. Sessão vencida: renova uma vez por vez (fila única da tarefa 16) e continua.
- **Queda no meio do lote:** o que o servidor gravou fica gravado (uma transação por operação); a resposta perdida faz o app reenviar o mesmo lote, e o que já entrou volta `JA_RECEBIDO`. Nada duplica.
- **Queda no meio do pull:** o lote local não é aplicado pela metade (uma transação com o cursor); o próximo pull recomeça do último cursor gravado.

### 20. Testes de sync

Além dos testes de cada tarefa (todos com PostgreSQL de verdade e SQLCipher de verdade, nunca banco em memória, e escritos antes):

| Teste | Onde | O que prova |
| --- | --- | --- |
| Oráculo de dois vendedores | backend (5.4) | o item 5: nada vaza e nada falta |
| Push: cada resultado, ordem, dependência, lote parado no erro passageiro, dois envios do mesmo aparelho ao mesmo tempo, mesmo lote reenviado | backend (5.5) | o item 2 |
| Relógio errado: aparelho 3 dias adiantado, 2 dias atrasado, mudado no meio, estorno "no mesmo dia" com relógio errado | backend (5.5) e app (5.8) | itens 11 e 12 |
| **Duas pontas:** o motor real do app (Node, SQLCipher real) contra a API real (Docker, como os testes E2E), com vários aparelhos | `testes-sync/` (5.12) | o caminho inteiro |
| **Simulador de rede ruim:** um proxy HTTP local, com semente, que atrasa, derruba conexão, corta **depois de o servidor gravar e antes da resposta**, devolve 502 e limita banda | `testes-sync/` (5.12) | itens 2 e 19 |
| Queda no meio do push e reenvio sem duplicar; cancelamento de venda já enviada; dois aparelhos; aparelho bloqueado com quarentena; CPF repetido; prazo offline esgotado; troca de foto offline; queda no download de fotos | `testes-sync/` (5.12) | a lista de testes obrigatórios da §10 e da §20 que cabem na Fase 5 |
| **Estresse longo:** 5 vendedores (um com segundo aparelho), a dona mexendo pela API, rede ruim, 10 min; no fim, rede boa, todos enviam e puxam; banco local de cada um igual ao oráculo, nenhuma venda duplicada ou perdida (cada operação aceita existe uma vez), conciliação "estoque + maletas" fechando | `testes-sync/` (5.12) | tudo junto; curto (cerca de 1 min) em todo PR, longo semanal como o `EstresseDoPullTest` |
| Migração entre versões com outbox | app (5.2 e 5.14) | item 16 |
| Disco cheio (simulado com `PRAGMA max_page_count`), chave perdida, banco mais novo que o app | app (5.2) | item 15 |

A devolução fora do prazo offline (§10) e o acerto com sobra e falta entram nas Fases 6 e 7, usando o mesmo `testes-sync/`.

### 21. Métrica e alerta de atraso

- **Atraso do limite seguro** (pendência da tarefa 8): a API expõe a idade da transação com identificador mais antiga (`pg_stat_activity`) como métrica (`corona.sync.xmin.idade.segundos`) e no painel de saúde; passou de 30 s, alerta no log (ADR-0002, item 5).
- **Por aparelho:** `dispositivos` guarda último pull, último push, último envio completo e a última contagem de "aguardando". A dona recebe em "O que precisa da sua atenção" o aviso "O computador da Maria está há 3 dias sem enviar" (o número de dias é a dúvida D4), e o aviso de "envios com problema" (recusas e conflitos).
- **Defeito do filtro:** diferença na conferência periódica (item 6) é alerta de defeito, nunca só um número.
- **Duração do pull e do push** como métricas, para saber quando otimizar a expansão.

## Consequências

### Boas

- Uma venda feita sem internet chega uma vez só, com os totais do servidor, mesmo com a conexão caindo no meio, o relógio errado ou o app atualizado no caminho.
- A Fase 4 não é reescrita: o push chama os mesmos serviços, com a mesma idempotência.
- O furo de "entrar e sair do filtro" tem regra, gatilho e um teste com oráculo, mais a conferência diária para achar um furo novo.
- A recusa nunca some: o vendedor vê, a dona vê, o JSON fica guardado.
- O custo continua sem caminho até o aparelho (tabela fora do pull, lista explícita de colunas, varredura de marcadores).

### Ruins ou custos

- A expansão reenvia itens e produtos a cada mudança de estado da maleta (mais dados no pull).
- Mais tabelas e telas: `sync_log`, `dispositivos`, `saidas_do_vendedor`, `envios_em_quarentena`, e telas da dona para aparelhos, envios com problema e quarentena.
- O servidor carrega leitores de formatos antigos da outbox por 90 dias (era 180; mudou em 11/11/2026).
- A trava de envio por aparelho fica na memória: uma segunda instância da API pede ADR.
- Perder a chave do banco local num computador sem internet perde as vendas não enviadas. Mitigado, não eliminado.
- O caminho único pela outbox muda o comportamento das telas da Fase 4 (agora a venda aparece na hora como "aguardando envio" e depois confirma).

## Alternativas consideradas

- **Tocar nas linhas quando a pertença muda** (um update vazio para ganhar `sync_seq` novo): manda a linha a **todos** os vendedores, muda a `version` e derruba a edição otimista da dona que estiver editando. Descartada em favor da expansão e da saída por gatilho.
- **Servidor guardar o que cada aparelho já tem:** correto, mas pesado e fácil de dessincronizar. Descartada; a conferência periódica dá a mesma segurança sem guardar estado por aparelho.
- **Carga completa a cada pull:** simples e correta, mas pesada em internet ruim. Fica só para a carga inicial, a época e a recarga de uma tabela.
- **Tabela de eventos de entrada e saída escrita pelo código de cada serviço:** cobre tudo, mas depende de ninguém esquecer de escrever o evento. Ficou só a saída, e por gatilho.
- **Frameworks prontos de sync** (PowerSync, ElectricSQL, RxDB, CouchDB/PouchDB): exigem servidor próprio ou replicação lógica do PostgreSQL e não conhecem o filtro nem a barreira do ADR-0003. Descartados.
- **Push uma operação por requisição, pelas rotas da Fase 4:** mais simples, mas uma ida e volta por venda em internet ruim. Descartada; o lote chama as mesmas regras.
- **Lote numa transação só:** uma venda recusada desfaria as outras. Descartada.
- **Cliente "registro inteiro, último vence":** apagaria a mudança da dona num campo que o vendedor nem tocou. Descartada em favor de campo a campo.
- **Confiar na hora do aparelho / usar só a hora de chegada:** a primeira aceita data impossível; a segunda põe a venda de sábado na semana seguinte quando o vendedor só conecta na segunda. Descartadas em favor da hora corrigida e limitada.
- **Apagar os dados na hora do bloqueio:** perderia vendas reais de um vendedor desligado. Descartada em favor da quarentena antes.
- ~~**Cópia da chave pela senha do vendedor:** item 15. Descartada.~~ **Adotada em 11/11/2026** (ver a linha datada no fim).
- **`configuracoes` sincronizável:** item 4. Descartada em favor do bloco `parametros`.

## Riscos e o que NÃO fazer

- **Não** ordenar nem filtrar por `updated_at`, `created_at`, UUID v7 ou relógio do aparelho. A ordem é `(sync_xid, sync_seq)` e a fila.
- **Não** usar `SELECT *` nem mapeamento automático de entidade no pull. Lista explícita, sempre.
- **Não** aplicar linha do pull sem comparar a `version`.
- **Não** montar de novo o JSON de uma operação guardada, nem reaproveitar um `operacao_id`.
- **Não** apagar operação da outbox sem resposta do servidor, nem por prazo, nem por bloqueio antes da quarentena.
- **Não** usar `INSERT ... ON CONFLICT DO UPDATE` em tabela imutável (o gatilho recusa); idempotência é `ON CONFLICT DO NOTHING` e leitura (ADR-0002).
- **Não** criar chave nova sobre um banco que não abriu.
- **Não** rodar dois ciclos de sync ao mesmo tempo, nem deixar a tela tocar no banco.
- **Não** subir a versão mínima de um jeito que recuse o formato de uma operação ainda guardada.
- ~~Não contar ao vendedor nada de um cadastro de outro vendedor (CPF repetido, cliente transferido).~~ Substituído em 11/11/2026: os clientes são da loja (ver a linha datada no fim).
- **Não** travar a venda por falta de foto, nem a conferência por download.
- **Não** acrescentar tabela sincronizável sem regra de filtro, expansão (se a pertença dela depender de outra linha) e saída (se a coluna "de quem é" puder mudar).
- **Risco:** transação longa no servidor atrasa o pull de todos (ADR-0002); os limites por papel e o alerta do item 21 continuam valendo.
- **Risco:** a regra "última chegada vence" em clientes pode substituir uma correção da dona por um valor antigo do vendedor; a auditoria guarda o valor substituído (dúvida D1).

## Seções da especificação relacionadas

§6.15 (um aparelho por vendedor), §7.1 e §7.2 (maleta e venda), §9 (campos de controle, `sync_log`, `dispositivos`), §10 (inteira), §11 (sessão, prazo offline, bloqueio remoto, banco cifrado, versão mínima), §15 (Fase 5), §19 (indicador em palavras comuns, "envios com problema"), §20 (fotos offline). ADR-0002, ADR-0003, ADR-0004, ADR-0005, ADR-0006, ADR-0010, ADR-0011.

## Dúvidas

Estão no [plano da Fase 5](../progresso/fase-5-plano.md#dúvidas), cada uma com o padrão seguro que vale enquanto não houver resposta. Nada aqui muda a especificação; se o responsável decidir algo contra a §10 ou a §11, a especificação muda primeiro, em tarefa própria.

**Linhas datadas, 11/11/2026 (decisões do responsável, na aceitação deste ADR).**

1. **Formatos antigos da outbox: 90 dias** (não 180). O servidor aceita um formato de operação por 90 dias depois de publicar o novo.
2. **O Corona Vendas pede a senha do vendedor ao abrir, também sem internet** (nem todos os notebooks têm senha no Windows, então o cofre do Windows sozinho não protege). Sem internet a senha é conferida **localmente** com um verificador lento (**Argon2id**, ou scrypt, com parâmetros fortes; nunca hash rápido); **5 erros seguidos bloqueiam até conectar**. A chave do banco local é **embrulhada pela senha**, e fica uma **segunda proteção pelo DPAPI do Windows só para resgate**: quando a dona redefine a senha, o vendedor entra online com a senha nova e o app **re-embrulha a chave**. Isto **inverte** o item 15 ("segunda cópia da chave pela senha: não") e a dúvida R7 ("abrir sem pedir senha"); a regra "o banco local não guarda hash de senha" passa a valer só para hash rápido: o verificador lento fica no arquivo de dados. Senha do Windows: recomendada, não obrigatória. Mudança correspondente no ADR-0005.
3. **Clientes são da LOJA, não do vendedor.** Qualquer vendedor pode vender a qualquer cliente. Todo vendedor recebe no pull um **diretório de clientes ativos com só nome, CPF, telefone e cidade** (lista explícita de colunas, nunca `SELECT *`). Continua **privado de cada vendedor**: as vendas, o fiado (contas a receber), os pagamentos e quem comprou com quem. Efeitos: no item 4, o filtro de `clientes` passa a "todos, colunas do diretório"; no item 5, a **saída por gatilho de `clientes` deixa de ser necessária** (ninguém "perde" um cliente; `vendedor_responsavel_id` fica como "quem cadastrou/atende mais", sem restringir a venda), e a regra de expansão não muda; no item 8, **CPF repetido é barrado** (online: 409 "Este cliente já está cadastrado" mostrando o cliente existente; offline: o app confere no diretório local; dois vendedores que cadastram o mesmo CPF ao mesmo tempo sem internet: o segundo chega como `CONFLITO` e vai para a revisão da dona, que junta os cadastros). O CPF passa a ser único entre clientes não excluídos (índice único parcial, migração da tarefa de código). A barreira do ADR-0003 continua: o vendedor nunca recebe dados de **outros vendedores**; o diretório de clientes é dado do cliente, da loja, e é a exceção declarada.

**Linha datada, 11/11/2026 (tarefa 5.3, padrões seguros da implementação dos itens 13 e 14).** (1) Um identificador de computador é de **uma pessoa só**: o login de outro vendedor com o mesmo identificador é recusado (403 `APARELHO_DE_OUTRA_PESSOA`) e nada é revelado sobre o dono. (2) O computador `SUBSTITUIDO` recebe 403 `APARELHO_SUBSTITUIDO` em toda rota do vendedor, exceto as marcadas para o envio do que ele guardou (o push da 5.5). (3) A ordem `APAGAR_DADOS` (corpo `acao`) vai no 403 do computador bloqueado e do vendedor **enquanto ele estiver desativado**; o vendedor reativado com a família antiga recebe só o 401 de "entre de novo". O token copiado para outro computador nunca recebe a ordem. (4) "Vender só depois de conferir" (ADR-0011, dúvida 5) entra junto: venda de maleta `ENVIADA` é 409 `MALETA_NAO_CONFERIDA`, e só a conferência liga a maleta ao computador. (5) A liberação por maleta da tarefa 4.3 (`POST /api/dona/remessas/{id}/liberar-aparelho`) sai; fica `POST /api/dona/aparelhos/{id}/liberar` ("Trocar computador"), que passa as maletas abertas do computador antigo para o novo. (6) A chave estrangeira de `origem_dispositivo` (e de `dispositivo_id` em `remessas`, `movimentos_remessa` e `conferencias`) é `NOT VALID`: vale para linha nova ou alterada, não para as antigas; as tabelas sincronizáveis criadas depois já nascem com ela.

**Linha datada, 11/11/2026 (tarefa 5.4, o pull do vendedor).** (1) A regra do vendedor fica **na própria `TabelaSincronizavel`** (`RegraDoVendedor`: condição, expansões, diretório e "pertença muda"); o teste de convenções reprova sincronizável sem regra, e o filtro, na dúvida, não entrega nada. (2) **Clientes:** todos os clientes (ativos e inativos, com a lápide) vão a todo vendedor; as colunas fora do diretório (nome, CPF, telefone, cidade e ativo) e o próprio `vendedor_responsavel_id` saem vazios quando o cliente não é atendido pelo vendedor. A transferência de cliente é só uma alteração da linha: o vendedor antigo recebe a mesma linha sem o cadastro. (3) **`saidas_do_vendedor` não foi criada:** com os clientes da loja, nenhuma coluna de pertença muda depois de entregue, e a V032 **garante no banco** (gatilhos: vendedor e envio da maleta enviada, vendedor da venda, maleta, peça e exclusão do item da maleta enviada, peça do código e da foto); a tabela de saídas volta se uma regra futura pedir. (4) **Conferência por resumo:** conta as linhas que passam no filtro agora (lápides incluídas), sem o limite seguro; o app só compara depois de receber tudo (`temMais = false`) e confere de novo antes de recarregar, porque escrita em andamento dá diferença passageira. A diferença que persiste é avisada em `POST /api/vendedor/sync/conferencia/diferencas` (alerta de defeito no log; a métrica é da 5.11). (5) **Recarga de uma tabela:** `GET /api/vendedor/sync/pull?tabela=<nome>` (com cursor próprio, a partir de vazio). (6) O bloco `parametros` traz `limiteDescontoPercentual`, `tempoMaximoOfflineDias` e `situacaoDoAparelho`. (7) Dinheiro vai no JSON como texto ("12.30"); datas em ISO-8601.
**Linha datada, 11/11/2026 (tarefa 5.5, o push).** (1) Formato 1 de cada tipo: `VENDA` (o pedido da rota da Fase 4 mais `vendidaEm`), `ESTORNO_VENDA` (`id`, `vendaId`, `motivo`, `estornadoEm`), `CLIENTE_NOVO` (o cadastro da Fase 4), `CLIENTE_ALTERADO` (`clienteId`, `versaoBase` e, por campo, `antes` e `depois`) e `CONFERENCIA` (`id`, `remessaId`, `itens`); na criação, o `operacao_id` precisa ser o id do registro. (2) O resumo canônico é calculado sobre uma forma fixa (campos em ordem, listas ordenadas, dinheiro com duas casas): campos em outra ordem ou "10.0" no lugar de "10.00" dão `JA_RECEBIDO`. (3) Reenvio de uma operação **recusada** devolve a mesma recusa (o app já sabe); mesmo id com outro conteúdo, ou de outro vendedor, é `CONFLITO` e nada muda. (4) Viram `CONFLITO` (para a dona) só os 409 de "mesma chave com outro conteúdo" e o CPF já cadastrado; as outras regras de negócio são `RECUSADO`. (5) **Hora corrigida:** limite inferior = o maior entre o último contato com o servidor, o envio da maleta (venda) e a própria venda (estorno); superior = a chegada. A conferência fica com a hora do servidor. (6) **Computador trocado:** a venda que ele guardou passa pela regra "um computador por maleta" se a maleta está no computador que o substituiu (a corrente de trocas); terminando o envio com nada aguardando, a resposta traz `apagarDados`. (7) **Tentar de novo** só vale para `RECUSADO` (o conflito a dona resolve à mão: tentar daria o mesmo) e passa pela regra do computador da maleta; depois de 90 dias, sem o JSON, só descartar. (8) A dependência recusada é decidida pelos dados: a operação que falha e cita um registro cujo envio foi recusado (ou descartado) recebe `DEPENDE_DE_ENVIO_RECUSADO`. (9) O esvaziamento do JSON roda na agenda diária da limpeza técnica, por `UPDATE` (a linha fica). (10) O cliente campo a campo só vale para quem atende o cliente (`CLIENTE_DE_OUTRO_VENDEDOR`). (11) O prazo de 72 horas do estorno pelo vendedor (decisão do responsável) **não** entrou: continua "o mesmo dia", agora pela hora corrigida.
**Linha datada, 11/11/2026 (decisão do responsável): retenção do `sync_log`.** O `sync_log` guarda o JSON completo de cada operação por **90 dias**; depois, só o resumo (quem, quando, tipo, resultado e o resumo SHA-256 do JSON): o JSON é apagado (a linha do resumo fica; a 5.5 decide como esvaziar o JSON sem `DELETE` da linha). A tarefa 5.5 já cria a tabela assim, com a tarefa agendada de resumo. Isto muda o item 5 deste ADR ("ela fica em `sync_log` com o JSON inteiro") só quanto ao tempo: o JSON inteiro fica 90 dias.

**Linha datada, 12/11/2026 (tarefa 5.6, o motor do Corona Vendas).** (1) A resposta do push não traz a cópia do servidor: ela chega pelo pull do mesmo ciclo. Itens e movimentos provisórios (que no servidor têm outro id) só saem no fim de um recebimento completo depois de a operação ficar `ENVIADO`; uma provisória de operação ainda aguardando nunca sai por linha de outro id. Até lá a baixa pode contar duas vezes (o saldo mostrado fica menor, nunca maior). (2) **`CONFLITO` também desfaz o efeito local**, como o `RECUSADO`: o servidor guardou outro conteúdo com aquele id e não o nosso, e a cópia dele chega pelo pull; manter a provisória deixaria uma venda que não existe no saldo para sempre. (3) Recusa e conflito ficam na outbox até o "Entendi" (`entendido_em`); `ENVIADO` e recusas entendidas saem depois de 30 dias. (4) Migração local 2 (`provisorias`: tabela, linha, operação) para desfazer e limpar sem depender dos ids do servidor. (5) A conferência local considera só as cópias `SERVIDOR` (lápides incluídas) e ignora as provisórias. (6) O estado na ponte leva também `vendasAguardando`, um contador, para a tela escolher entre "N vendas" e "N itens" aguardando envio. (7) Hora do servidor, hora local do mesmo instante e a maior hora local já vista ficam em `estado_sync` (insumo da 5.8, sem regra ainda).

**Linha datada, 12/11/2026 (tarefa 5.7, as telas do Corona Vendas no banco local).** (1) As telas leem e gravam só no banco do computador (uma passagem única por `chamarApi`, mesma forma de resposta de antes); foto e identidade continuam na rede até a 5.9 e a 5.8. (2) "Meu cliente" = cadastro que o servidor mandou com `vendedor_responsavel_id` ou cadastro feito aqui ainda não confirmado. (3) Venda feita sem internet mostra Dólar e Guarani pela cotação mais nova até o dia dela, com a data; nada disso é gravado. (4) O motivo mostrado ao vendedor numa recusa é o texto do servidor (`ultimo_erro`); "Entendi" só preenche `entendido_em`. (5) O indicador usa só o estado da ponte (contadores, sim/não e datas); a lista de recusas vem do banco local por chamada própria.

**Linha datada, 12/11/2026 (tarefa 5.8, prazo offline, abrir sem internet, bloqueio, quarentena e apagar).** (1) A rota `POST /api/vendedor/sync/quarentena` é pública na lista fechada e só aceita o token de renovação **revogado por bloqueio do computador ou desativação do vendedor há no máximo 30 dias**, com o identificador da família; qualquer outro caso responde 401 igual, sem revelar nada. Guarda sem aplicar em `envios_em_quarentena` (V034); a dona aprova (mesmo serviço do envio) ou descarta. (2) O prazo conta a hora **local** desde o último contato; a tolerância de 5 minutos do relógio vale também para "antes do contato"; a maior hora local vista só sobe entre dois contatos e é refeita por uma resposta válida do servidor; o bloqueio sai na primeira resposta válida, mesmo com envios ainda saindo. (3) A quarentena do app tenta 3 vezes, esperando 30 s, 2 min e 10 min antes de cada uma, e retoma pela marca em disco sem zerar; sem resposta nas 3, apaga como manda o item 14. (4) Para ler o que aguarda envio com o app recém-aberto, o banco abre pela cópia da chave no cofre do Windows, sem senha, só no apagar. (5) A guarda de tokens não conta como "apresentação" a tentativa de renovar que não teve resposta nenhuma, para abrir o app sem internet várias vezes não custar a sessão. (6) Limite: quem saiu ("Sair") antes do bloqueio não tem token para a quarentena.

**Linha datada, 12/11/2026 (tarefa 5.13, atualização com envios aguardando).** (1) Prazo do formato antigo: **90 dias** depois de publicado o formato seguinte do mesmo tipo (`corona.sync.formato-antigo-dias`, nunca menos de 90), no catálogo `FormatosDeOperacao` (datas no código, sem tabela). Vencido, o push responde `RECUSADO` com `FORMATO_NAO_ACEITO` e guarda no `sync_log` (a dona vê em "envios com problema"); o servidor continua sabendo ler o formato vencido, então quem reenvia o que já entrou recebe `JA_RECEBIDO`, e a quarentena guarda sem aplicar. (2) Abaixo da versão mínima, o Corona Vendas (cabeçalho `vendas` com versão legível) ainda passa em três rotas, só `POST` e caminho exato: `/api/vendedor/sync/push` (a resposta traz `appDesatualizado` e `versaoMinima`), `/api/auth/renovar` (só a sessão que já existe; entrar com a senha continua 426) e `/api/vendedor/sync/quarentena`. Pull, conferência, venda pela internet e o resto: 426. (3) No app, o envio que volta com `appDesatualizado` e o 426 do recebimento **não param o motor**: os ciclos seguintes só enviam, e a tela mostra "Seu aplicativo está desatualizado." (glossário, parte 7). O 426 no próprio envio continua parando o motor. (4) Antes de instalar a versão nova (só quando há versão baixada, no `before-quit`): o motor para, espera o ciclo em andamento até 30 s, tenta mais um envio (só o envio, sem receber) até 30 s, e fecha o banco; erro ou tempo esgotado nunca impedem de fechar e instalar (a outbox está no arquivo e o reenvio é `JA_RECEBIDO`). Total máximo: 60 s. (5) Bancos de exemplo de cada versão publicada do banco local em `apps/seller-app/exemplos-do-banco/` (fora de `src/` e do pacote), com chave de teste fixa e dados fictícios; exemplo publicado nunca é gerado de novo, e o teste falha se uma versão do banco não tiver o seu. O exemplo da versão 1 não tem linhas provisórias (a versão 1 não tinha a tabela `provisorias` nem gravava operações).
