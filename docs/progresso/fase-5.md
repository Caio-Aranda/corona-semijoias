# Fase 5: offline e sincronização (detalhe das tarefas)

O plano está em [fase-5-plano.md](fase-5-plano.md); o desenho, no [ADR-0013](../decisoes/ADR-0013-sincronizacao-e-offline.md) e no [ADR-0005](../decisoes/ADR-0005-sqlcipher-no-electron.md).

## Tarefa 5.3: computadores do vendedor (backend)

**O que entrou**

- Migração **V031** (`dispositivos`, só do servidor): situação `ATIVO`, `AGUARDANDO_LIBERACAO`, `SUBSTITUIDO` ou `BLOQUEADO`, primeiro e último contato, versão do app, campos do envio (último pull, último push, último envio completo, contagem de "aguardando") que a 5.4 e a 5.5 preenchem, e liberação, troca e bloqueio. Índice único parcial: **um computador ativo por pessoa** (o banco garante). Os computadores que já tinham entrado (Fase 4) vêm das sessões: o de uso mais recente de cada vendedor fica ativo, os outros esperam a dona.
- **Chave estrangeira** de `origem_dispositivo` (pendência da tarefa 8) e de `dispositivo_id` em `remessas`, `movimentos_remessa` e `conferencias` para `dispositivos`, `NOT VALID` (só linhas novas ou alteradas). `aplicar_convencoes_sincronizavel` passa a criar a mesma chave nas sincronizáveis novas.
- **Login** registra o computador (`ServicoDeAparelhos`): o primeiro do vendedor nasce ativo; os seguintes entram mas esperam a dona. Computador de outra pessoa: 403 `APARELHO_DE_OUTRA_PESSOA`. Computador bloqueado: 403 `APARELHO_BLOQUEADO` com `acao: APAGAR_DADOS`. A renovação guarda o último contato e a versão.
- **Rotas do vendedor** (`/api/vendedor/**`) só para o computador ativo (`InterceptadorDoAparelho`): aguardando liberação dá 403 `APARELHO_NAO_LIBERADO`; trocado, 403 `APARELHO_SUBSTITUIDO` (o push da 5.5 vai ser a exceção, marcada com `@AceitaComputadorTrocado`); bloqueado, 403 com a ordem de apagar. `/api/auth/eu` e a saída continuam para o computador que espera a dona mostrar o aviso.
- **Rotas da dona:** `GET /api/dona/vendedores/{id}/aparelhos`, `POST /api/dona/aparelhos/{id}/liberar` ("Trocar computador": o antigo vira trocado e as maletas abertas dele passam para o novo) e `POST /api/dona/aparelhos/{id}/bloquear` (encerra as sessões com o motivo novo `APARELHO_BLOQUEADO`). Tudo na auditoria. A liberação por maleta da 4.3 saiu.
- **Ordem de apagar** (`acao: APAGAR_DADOS`, ADR-0013 item 14) no token, na renovação e no login do computador bloqueado e do vendedor desativado. O reuso do token, a saída e a senha trocada continuam no 401 de "entre de novo". O vendedor reativado não recebe a ordem.
- **Vender só depois de conferir** (decisão de 11/11/2026): venda de maleta enviada e não conferida é 409 `MALETA_NAO_CONFERIDA`; só a conferência liga a maleta ao computador. No Corona Vendas, a tela Vender só lista maletas conferidas e diz "Confira todas as peças da maleta antes de vender." quando só há maleta por conferir.
- Glossário: textos dos computadores e da venda antes de conferir.

**Testes (escritos antes do código):** `AparelhosTest` (primeiro e segundo computador, troca com as maletas, antigo trocado só envia, 403 cruzado e rotas da dona fechadas ao vendedor, bloqueio com a ordem em toda chamada, token copiado sem ordem, desativado com ordem e a dona sem, reuso só pede para entrar, venda antes de conferir, chave estrangeira da origem). Ajustados: os testes de login, renovação, senha temporária, tentativas e versão do app passam a usar o computador de cada vendedor (`UsuariosDeTeste.aparelhoDe`), porque um computador é de uma pessoa só; o vendedor desativado agora recebe 403 com a ordem; os testes de venda conferem a maleta antes.

**Decisões que tomei:** ver a linha datada da tarefa 5.3 no ADR-0013.

**Ficou para depois:** telas da dona (5.10); preencher último pull/push e "aguardando" (5.4/5.5); a quarentena antes de apagar e o apagar no app (5.8); marcar o computador "reiniciado" quando o banco local fica ilegível (5.6/5.8).

## Tarefa 5.4: pull do vendedor (backend)

**O que entrou**

- `GET /api/vendedor/sync/pull` (cursor, limite 500 por padrão e 1000 no máximo, `tabela` para recarregar uma só): linhas com tabela, id, `version`, lápide e as colunas da lista explícita; cursor; `temMais`; hora do servidor; bloco `parametros`. Cursor de época antiga: 409 `CURSOR_INVALIDO`. Só o computador em uso (5.3); guarda o último pull em `dispositivos`.
- `GET /api/vendedor/sync/conferencia` (quantidade e SHA-256 de `id:version` por tabela) e `POST /api/vendedor/sync/conferencia/diferencas` (alerta de defeito no log).
- `RegraDoVendedor` em cada `TabelaSincronizavel` (`RegistroDeTabelas`): cotações, categorias e semanas para todos; clientes da loja com o diretório para todos e o cadastro só para quem atende; maletas enviadas do vendedor (rascunho nunca); itens e movimentos das maletas dele; peças que já estiveram numa maleta enviada a ele, com códigos e fotos; vendas, itens, pagamentos e conferências dele. `FiltroPorVendedor` aplica a regra com o vendedor do token.
- **Expansão** (ADR-0013, item 5, regra 1) no `ServicoDePull`, na mesma fotografia do banco: a maleta leva os itens; o item leva a peça, os códigos e as fotos. As expandidas passam também pelo filtro e não mexem no cursor.
- Migração **V032**: gatilhos que impedem a pertença de mudar depois de entregue (o que dispensa a tabela de saídas) e índices do filtro.
- Glossário: texto do `CURSOR_INVALIDO`. Clientes da API regerados.

**Testes (escritos antes, PostgreSQL de verdade):**
- `OraculoDeDoisVendedoresTest`: o oráculo de dois vendedores com semente impressa (300 operações por padrão; rodei 600): a dona cria peças, monta, envia e exclui maletas, muda preço, troca foto, cria e exclui código, cadastra e transfere cliente; A e B conferem e vendem; puxam em momentos aleatórios com lotes de 1 a 50. A cópia de cada um fica igual ao oráculo (id, versão, lápide), nada privado do outro, nenhum cadastro de cliente do outro, nenhuma coluna de custo. **Prova de que pega o furo:** com a expansão desligada, o oráculo falha (códigos da peça antiga faltando).
- `PullDoVendedorTest`: peça antiga com código e foto chegando quando entra numa maleta nova; rascunho e rascunho excluído nunca chegam; troca de foto e código excluído; cliente transferido nos dois sentidos; vendas, conferências e maletas do outro nunca chegam; dinheiro como texto; erros da rota (cursor, limite, tabela de fora, dona, sem token, computador aguardando); recarga de uma tabela; conferência por resumo igual ao que o app guardou; gatilhos da V032. Toda resposta passa pela varredura dos marcadores proibidos (mais `custo`, `estoque_atual` e `fornecedor_id`).
- `RegrasDoVendedorTest`: toda sincronizável com regra; toda coluna de pertença garantida no banco; expansões válidas; na dúvida, nada.
- `EstresseDoPullComFiltroTest` (20 s em todo PR; 10 min no semanal, junto do estresse da Fase 0): 4 escritores mudando pertença (peças entrando em maletas de dois vendedores, fotos, códigos, preços, conferências, vendas) enquanto o vendedor puxa sem parar; no fim, a cópia igual ao oráculo (cerca de 6 mil operações em 20 s).

**Decisões que tomei:** ver a linha datada da tarefa 5.4 no ADR-0013.

**Ficou para depois:** o motor do app que aplica o pull (5.6); a métrica do defeito do filtro e da duração do pull (5.11).

## Tarefa 5.5: push do vendedor (backend)

**O que entrou**

- `POST /api/vendedor/sync/push`: até 50 operações ou 512 KB (413 `LOTE_GRANDE_DEMAIS`), um envio por vez por computador (429 `ENVIO_EM_ANDAMENTO`, com `Retry-After`), **uma transação por operação** com a linha do `sync_log`, resultado por operação (`ACEITO`, `JA_RECEBIDO`, `CONFLITO`, `RECUSADO`, `NAO_PROCESSADO`), parada do lote no erro passageiro e no limite de 20 s. O computador trocado ainda envia (e recebe `apagarDados` no fim).
- `OperacoesDoVendedor`: formato 1 de `VENDA`, `ESTORNO_VENDA`, `CLIENTE_NOVO`, `CLIENTE_ALTERADO` e `CONFERENCIA`, a forma canônica do resumo e a aplicação pelos **mesmos serviços da Fase 4** (`ServicoDeVendas`, `ClientesDoVendedor`, `ServicoDeConferencia`).
- **Hora corrigida e limitada** (ADR-0013, item 11): a venda e o estorno recebem a hora do computador corrigida pela diferença do relógio dele; correção acima de 5 minutos marca a venda (`hora_ajustada`); o dia da cotação e o "mesmo dia" do estorno saem dela.
- **Cliente campo a campo** (`ClientesDoVendedor.alterar`): só quem atende; vale a última chegada, e o valor da dona substituído vai para a auditoria (`CLIENTE_VALOR_SUBSTITUIDO`). CPF repetido de cadastros feitos sem internet: `CONFLITO` para a dona.
- **Envios com problema** (`/api/dona/envios-com-problema`): listar, **Tentar de novo** (mesma operação, mesmo id, mesma hora) e **Descartar** (com motivo), tudo auditado. A tela é da 5.10.
- Migração **V033**: `sync_log` (JSON completo por 90 dias, depois só o resumo, por `UPDATE` na agenda diária) e `vendas.hora_ajustada`. `dispositivos` passa a guardar último push, "aguardando" e último envio completo.
- Glossário com os textos novos; clientes da API regerados.

**Testes (escritos antes, PostgreSQL de verdade):** `PushDoVendedorTest` (aceito, já recebido e conflito no reenvio; JSON equivalente em outra ordem; recusa guardada com o JSON e saldo que nunca fica negativo; a dona descarta e tenta de novo depois de corrigir a causa; dependência recusada; maleta cancelada e de outro vendedor; segundo computador, dona e lote grande; relógio 3 dias adiantado e 2 atrasado; hora impossível no limite; estorno no limite do dia em Brasília; dois envios do mesmo computador ao mesmo tempo sem duplicar; cliente campo a campo com o valor substituído; cliente de outro vendedor; CPF repetido em conflito; computador trocado enviando e recebendo a ordem de apagar; retenção de 90 dias) e `ServicoDeEnvioTest` (erro passageiro para o lote ali, sem quebrar a ordem).

**Decisões que tomei:** ver a linha datada da tarefa 5.5 no ADR-0013.

**Ficou para depois:** o motor do app que envia (5.6); a tela dos envios com problema e os avisos (5.10); push abaixo da versão mínima (5.13); métrica da duração do push (5.11).
