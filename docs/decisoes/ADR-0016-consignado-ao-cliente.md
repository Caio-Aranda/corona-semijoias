# ADR-0016: Consignado ao cliente (nota DX/DV/VD, acerto por nota, pendência e Fechamento da maleta)

- **Status:** Aceito (18/11/2026; plano aprovado pela orquestradora no PR 226 com os padrões de D1 a D9; fase CÓDIGO da tarefa 9.1 implementada em 19/11/2026).
- **Data:** 18/11/2026
- **Tarefas que implementam:** 9.1 (servidor, sync e OpenAPI), 9.2 (Corona Vendas), 9.3 (relatórios), 9.4 (Corona Gestão). Plano: [plano-9-1-consignado-ao-cliente.md](../progresso/plano-9-1-consignado-ao-cliente.md).
- **Especificação:** §23 inteira (vale sobre §7.2, §7.3 e §7.4 onde discordarem). Prints em `docs/referencia/2026-11-18-corona-vendas/`.

## Contexto

No teste de uso de 18/11/2026 o responsável mudou o centro do Corona Vendas: o representante deixa peças da maleta **em consignado** com clientes (nota DX), faz o **acerto por nota** (devolução bipada DV, vendido VD = DX − DV, comissão do cliente de 0% a 40%), recebe em dinheiro, Pix, débito ou crédito à vista e a falta vira **pendência acumulativa** do cliente. A venda ao consumidor, as parcelas, a devolução/troca com garantia e o crédito do cliente saem do Corona Vendas; o acerto em dinheiro com a dona (§7.4) fica escondido e, no lugar, a dona bipa a maleta na volta (**Fechamento da maleta**, §23.10).

Já estão decididos e continuam valendo: dinheiro em `NUMERIC(14,2)`, tudo gravado em Real com a cotação do dia (ADR-0006); UUID gerado no cliente, exclusão lógica e `sync_seq` (ADR-0002); separação Gestão/Vendas, sem custo e sem dado de outro vendedor no Corona Vendas (ADR-0003); maleta com histórico imutável de movimentos e `preco_remessa` congelado (ADR-0010); outbox, push com resposta por item, pull por cursor, filtro por vendedor com expansão e saída por gatilho, um aparelho ativo por vendedor, hora ajustada e formatos antigos aceitos por 90 dias (ADR-0013); as conciliações como rede de segurança (§13).

Ficam abertos: como guardar semana, nota, acerto, recibo e pendência sem quebrar a imutabilidade; como numerar `semana-sequência` sem internet; como o saldo da maleta passa a contar "com clientes"; o que o servidor faz com divergência vinda sem internet; quem vê os clientes; e como desligar o fluxo antigo sem apagar dados.

## Decisão

### 1. Nomes

Na tela: **Representante** e **Cliente** (§23.1, item 4). No banco e no código continuam `vendedor` e `cliente`. As tabelas novas usam o sufixo `_consignado` quando o nome sozinho colidiria com uma tabela existente (`semanas`, `acertos`, `trocas`, `pendencias_acerto`, `pagamentos_cliente` já existem e são de outro fluxo).

### 2. Semana do representante

- Tabela nova **`semanas_vendedor`** (sincronizável): `vendedor_id`, `numero` (inteiro, sequencial **por vendedor**, começa em 1; a tela mostra com 7 dígitos, `0000065`), `aberta_em`, `fechada_em`, `situacao` (`ABERTA` | `FECHADA`), `dispositivo_id`.
- Índices: único `(vendedor_id, numero)`; único parcial `(vendedor_id) WHERE situacao = 'ABERTA'` (uma aberta por representante).
- **Nova semana** é uma operação (`SEMANA_NOVA`): grava a nova `ABERTA` e fecha a anterior na **mesma transação** (`fechada_em` = `aberta_em` da nova). O número nasce no computador (`maior número local + 1`). O primeiro uso do Corona Vendas depois da migração abre a semana 1 sozinho (o representante pode digitar o número inicial, para continuar a numeração do sistema antigo: dúvida D1).
- Só `situacao` e `fechada_em` mudam depois de gravada (gatilho protege o resto). Não confundir com `corona.semanas` (semana da dona, §7.4), que fica como está.

### 3. Numeração `semana-sequência`

- Nota e recibo recebem `semana_id` + `sequencia` (inteiro ≥ 1). O número mostrado é `<semana com 7 dígitos>-<sequência com pelo menos 2 dígitos>` (`0000064-21`), **calculado na leitura**, nunca gravado como texto.
- **Um contador por semana, compartilhado por notas e recibos** (no print, na semana 65 a nota é `-32` e o recibo `-53`; um contador só evita "nota 32" e "recibo 32" na mesma semana): dúvida D2. O contador vive no computador (tabela local `contadores_da_semana`), porque só há um aparelho ativo por representante (ADR-0013, item 13).
- Recibo de nota de semana anterior recebe o número da **semana do pagamento** (a semana aberta no momento do pagamento).
- Servidor: índice único `(vendedor_id, semana_id, sequencia)` sobre notas e recibos juntos (tabela auxiliar `numeros_da_semana`, só do servidor, com `tipo` e `registro_id`). Número repetido com **outro** id só acontece na troca de computador: a operação é `RECUSADO` `NUMERO_REPETIDO` e vai para "envios com problema" da dona (alerta visível, nunca silencioso), porque o número já foi impresso e assinado e não pode ser trocado em silêncio (dúvida D5).

### 4. Cliente

- Colunas novas em `clientes` (nova migração): `documento` (texto livre, CPF, RG, CI paraguaia...), `documento_tipo` (`CPF` | `RG` | `CI` | `OUTRO`), `indicado_por_cliente_id` (uuid, cliente do mesmo vendedor) **ou** `indicado_por_nome` (texto; no máximo um dos dois), `como_conheceu`, `comissao_percentual` (`NUMERIC(5,2)`, `DEFAULT 30`, `CHECK 0..40`), `foto_hash`, `foto_documento_frente_hash`, `foto_documento_verso_hash`. Endereço (`endereco_*`), `telefone` e `whatsapp` já existem.
- **Obrigatoriedade:** a tela exige nome, endereço (rua, número, bairro, cidade, UF), telefone/WhatsApp, comissão e as três fotos. O servidor exige nome, endereço e telefone; as fotos **não** são exigidas no servidor (a foto pode ainda não ter subido, e a falta de envio nunca trava nota nem acerto, §23.5). Cliente com foto faltando há mais de 3 dias aparece no aviso da dona.
- **Documento repetido avisa, não barra** (§23.5): o app avisa ao digitar (procura nos clientes dele); o servidor aceita e gera alerta "possível cadastro repetido" (item 11). A coluna antiga `cpf` e o índice único `clientes_cpf_unico` (V030) ficam para os cadastros da dona; o Corona Vendas passa a gravar em `documento` (dúvida D7).
- **Cada representante tem a sua lista.** Isto **reverte, para o Corona Vendas, a dúvida 3 do ADR-0013** (clientes da loja num diretório comum): o filtro de `clientes` no pull passa de `diretorioDaLoja` para `doVendedor("t.vendedor_responsavel_id = ?")`, com todas as colunas do cadastro (menos `cpf` de cadastros alheios, que nem chegam). A **Regra 2 do ADR-0013, item 5** volta a valer: gatilho em `clientes.vendedor_responsavel_id` grava `saidas_do_vendedor` para o antigo responsável. O banco local, na migração 9, apaga os clientes que não são do representante (o app conhece o próprio `vendedor_id`).
- **Fotos do cliente** (item 9): arquivos, nunca binário no PostgreSQL.

### 5. Nota (pedido)

- **`notas_consignado`** (sincronizável): `vendedor_id`, `semana_id`, `sequencia`, `cliente_id`, `saida_em` (instante), `data_cobranca` (date, Brasília), `nota_origem_id` (Reposição em semana nova, item 7), `situacao` (`EM_ABERTO` | `PENDENTE` | `FECHADO` | `REMARCADO`), `cotacao_id`, `dispositivo_id`, `hora_ajustada`.
- **Rascunho é só local.** O Novo Pedido em montagem (bipes, diminuir, remover) vive no SQLite e nunca vai ao servidor; a operação `NOTA_CONSIGNADO` só nasce ao **Finalizar**, com todos os itens. Assim a nota chega ao servidor já imutável.
- Depois de gravada só mudam `situacao` e `data_cobranca`, e só pelo servidor (a situação é recalculada, a data muda por remarcação). Gatilho protege o resto.
- **`itens_nota_consignado`** (sincronizável, imutável): `nota_id`, `produto_id`, `remessa_id` (a maleta de origem), `origem` (`SAIDA` | `REPOSICAO` | `TROCA_VOLTA` | `TROCA_ENTRADA`), `quantidade` (inteiro com sinal: negativo só em `TROCA_VOLTA`), `preco_unitario` (o `preco_remessa` da maleta, conferido pelo servidor), `reposicao_id`, `troca_id`.
- **DX de cada linha (produto + maleta)** = soma das quantidades dos itens daquela linha; **Valor DX** = soma de `quantidade × preco_unitario`. Uma linha por produto e maleta na tela.

### 6. Saldo da maleta

- Os movimentos de maleta continuam em **`movimentos_remessa`** (histórico imutável, ADR-0010). Tipos novos (nova migração troca o `CHECK` e a regra de sinal): `CONSIGNADO_SAIDA` (negativo: saída na nota, na Reposição e a peça nova da troca) e `CONSIGNADO_RETORNO` (positivo: devolução no acerto e a peça que volta na troca). Colunas novas: `nota_id`, `acerto_nota_id`.
- **A venda pelo cliente não é movimento de maleta**: a peça já saiu da maleta na nota; o acerto só decide se ela volta (`CONSIGNADO_RETORNO`) ou fica vendida. O vendido fica nos itens do acerto (item 8). Isto mantém a regra atual "saldo = enviada + soma dos movimentos".
- Contas (§23.6), por produto e maleta:
  - `disponível = enviada + Σ movimentos` (com os tipos antigos, que continuam contando);
  - `com clientes = −Σ (CONSIGNADO_SAIDA + CONSIGNADO_RETORNO) − vendida`;
  - `vendida = Σ VD dos itens de acertos ativos`;
  - `devolvida à loja = −Σ DEVOLUCAO_DONA`.
- A conferência por bipe da §7.1 **deixa de ser obrigatória**: nota sai de maleta `ENVIADA` ou `CONFERIDA`.

### 7. Reposição, troca e remarcação

- **Reposição na semana atual** (`REPOSICAO_NOTA`): registro `reposicoes_nota` (`nota_id`, `feita_em`) e itens novos `REPOSICAO` na mesma nota, com `CONSIGNADO_SAIDA`. Nada antigo muda: a nota "cresce" por evento. A nota de saída é impressa de novo com tudo.
- **Reposição de nota de semana anterior**: é um `NOTA_CONSIGNADO` novo na semana atual, só com as peças novas, `nota_origem_id` = a nota antiga e a **mesma** `data_cobranca`.
- **Só nota sem acerto** aceita Reposição ou Troca.
- **Troca** (`TROCA_NA_NOTA`): registro `trocas_nota` (`nota_id`, `trocada_em`) com dois itens, `TROCA_VOLTA` (−1 da peça que volta, na maleta de onde saiu) e `TROCA_ENTRADA` (+1 da nova, da maleta escolhida), e os dois movimentos. Uma peça por operação (para várias, várias operações). Preço diferente muda o DX, como deve.
- **Remarcar** (`REMARCAR_NOTA`): registro imutável `remarcacoes_nota` (`nota_id`, `data_cobranca_anterior`, `data_cobranca_nova`, `remarcada_em`); o servidor copia a data nova para a nota. Só nota sem acerto ativo.

### 8. Acerto da nota

- **`acertos_nota`** (sincronizável, imutável salvo o estorno): `nota_id`, `vendedor_id`, `semana_id` (semana em que o acerto foi feito), `acertado_em`, `comissao_percentual`, `itens_dx`, `itens_dv`, `itens_vd`, `valor_dx`, `valor_dv`, `valor_vd`, `comissao`, `bruto` (= Valor Líquido), `pendencias_anteriores`, `total_a_receber`, `total_pago`, `ficou_devendo`, `recibo_id`, `cotacao_id`, `dispositivo_id`, `hora_ajustada`, `estornado_em`, `estorno_id`, `motivo_estorno`. Único parcial `(nota_id) WHERE estornado_em IS NULL` (um acerto ativo por nota).
- **`itens_acerto_nota`** (imutável): `acerto_id`, `produto_id`, `remessa_id`, `qtd_dx`, `qtd_dv`, `preco_unitario`. VD da linha = DX − DV.
- **O app manda só o que aconteceu no balcão:** as devoluções (produto, maleta, quantidade), a comissão % deste acerto, as formas pagas e, à parte, os totais que viu (para comparar). O servidor **recalcula tudo** a partir dos itens da nota, das devoluções e das pendências que ele conhece (§23.8):
  - `valor_vd = valor_dx − valor_dv`; `comissao = arredondar(valor_vd × % ÷ 100)` HALF_UP 2 casas, **sobre o total da nota** (não por linha); `bruto = valor_vd − comissao`;
  - `pendencias_anteriores` = soma dos saldos dos débitos do cliente antes deste (item 10); `total_a_receber = bruto + pendencias_anteriores`; `ficou_devendo = total_a_receber − total_pago` (nunca negativo).
  - Venda % só é mostrada; não é gravada.
- Movimentos: um `CONSIGNADO_RETORNO` por linha com DV > 0, com `acerto_nota_id`.
- Totais vistos pelo app diferentes dos recalculados: vale o do servidor e nasce alerta "valor do acerto recalculado" (item 11).

### 9. Fotos do cliente

- Mesmo armazenamento das fotos de produto (`ArmazenamentoDeArquivos`, arquivo por hash SHA-256, §20), em pasta própria `clientes/`. O cadastro guarda só o hash.
- **Rotas do vendedor:** `PUT /api/vendedor/clientes/{id}/fotos/{tipo}` (`tipo` = `cliente` | `documento-frente` | `documento-verso`; corpo = a imagem; o servidor confere o SHA-256 contra o hash do cadastro; repetir é inofensivo) e `GET /api/vendedor/clientes/{id}/fotos/{tipo}` (só cliente do próprio vendedor). **Rota da dona:** `GET /api/clientes/{id}/fotos/{tipo}`. Nunca no pull; nunca em relatório de outro vendedor.
- **No computador:** fila própria de envio (`fila_fotos_cliente`), separada da outbox: a operação `CLIENTE_NOVO` leva os hashes e sai logo; o arquivo sobe quando houver internet. Foto cujo cadastro ainda não chegou ao servidor espera (o servidor responde 409 e a fila tenta de novo).
- **As fotos ficam criptografadas no disco** (AES-256-GCM, chave derivada da chave do banco local, ADR-0005): documento é dado pessoal sensível e as fotos de produto não precisavam disso (dúvida D8). Depois de enviadas, ficam no computador enquanto o cliente for do representante.

### 10. Pagamento, recibo e pendência

- **`recibos_consignado`** (sincronizável, imutável salvo o estorno): `vendedor_id`, `cliente_id`, `semana_id` (semana do pagamento), `sequencia`, `acerto_id` (no acerto) ou nulo (Lançar pagamento), `pago_em`, `total`, `cotacao_id`, `dispositivo_id`, `hora_ajustada`, `estornado_em`, `estorno_id`, `motivo_estorno`.
- **`formas_recibo_consignado`** (imutável): `recibo_id`, `forma` (`DINHEIRO` | `PIX` | `DEBITO` | `CREDITO`), `valor > 0`. Uma linha por forma. Sem cheque, boleto, entrada, crédito parcelado ou desconto.
- **A pendência não é tabela própria: é o saldo dos débitos.** Cada acerto ativo é um **débito** do cliente no valor do `bruto`. `saldo do débito = bruto − Σ aplicações ativas nele`. Pendência do cliente = soma dos saldos. Nunca se edita nem se remove: só diminui por aplicação e só volta por estorno.
- **`aplicacoes_recibo`** (sincronizável, imutável): `recibo_id`, `acerto_id` (o débito), `valor` (com sinal: negativo só no estorno), `estorna_id`.
- **Ordem (§23.8), feita pelo servidor:** o recibo é aplicado aos débitos do cliente **do mais antigo para o mais novo** (`acertado_em`, depois semana e sequência da nota). No acerto, o débito da própria nota é o mais novo, então o pago quita primeiro as pendências anteriores e depois esta nota. Com isso o exemplo dos 3 momentos e a nota `0000064-21` saem iguais à §23.8.
- **Situação da nota** (gravada pelo servidor na mesma transação, recalculável a qualquer momento): sem acerto ativo → `REMARCADO` se houver remarcação, senão `EM_ABERTO`; com acerto ativo → `PENDENTE` se o saldo do débito > 0, senão `FECHADO`. Cada aplicação reavalia as notas tocadas (a Pendente vira Fechado sozinha).
- **Pagar mais que o devido:** a tela não deixa (Total Pago ≤ Total a Receber no acerto; ≤ pendência no Lançar pagamento). No servidor, se o valor passar do devido que **ele** conhece (só por divergência sem internet), o recibo é **aceito**, aplica-se o que der e o resto fica **"a aplicar"** (saldo do recibo), que entra sozinho no próximo débito do cliente; nasce alerta para a dona (dúvida D6).

### 11. Recusas e divergências

Duas classes, para nunca recusar em silêncio o que aconteceu no balcão:

| Situação | Na tela (online e offline) | No servidor |
| --- | --- | --- |
| Bipe de código fora da maleta escolhida | Não deixa; diz em qual maleta está | `RECUSADO` `PECA_FORA_DA_MALETA` (só chega por defeito do app) |
| Quantidade acima do disponível na maleta | Não deixa | **Aceita** e alerta "peça saiu sem saldo na maleta" (a peça está com o cliente; o saldo do servidor pode ter mudado por ação da dona) (dúvida D4) |
| DV acima do DX, ou código fora da nota no acerto | Não deixa | `RECUSADO` `DEVOLUCAO_MAIOR_QUE_O_LEVADO` |
| Comissão acima de 40% ou abaixo de 0% | Não deixa | `RECUSADO` `COMISSAO_FORA_DO_LIMITE` |
| Pagar acima do devido | Não deixa | Aceita, sobra "a aplicar" e alerta (item 10) |
| Novo Pedido fora da semana atual | Botão desligado | `RECUSADO` `SEMANA_FECHADA` se `saida_em` > `fechada_em` da semana da nota |
| Reposição, troca ou remarcação em nota já acertada | Não deixa | `RECUSADO` `NOTA_JA_ACERTADA` |
| Segundo acerto ativo da mesma nota | Não deixa | `RECUSADO` `NOTA_JA_ACERTADA` |
| Número `semana-sequência` repetido com outro id | (não acontece com um aparelho) | `RECUSADO` `NUMERO_REPETIDO` (item 3) |
| Totais do app diferentes dos recalculados | (o app recalcula igual) | Aceita, vale o do servidor, alerta |
| Documento repetido | Avisa | Aceita e alerta |

- Todo `RECUSADO` vai para **"envios com problema"** (tarefa 5.10) com a mensagem em português simples, e o app mostra o motivo (PR #220).
- **Alertas** vão para a tabela nova `alertas_consignado` (só do servidor: `tipo`, `vendedor_id`, `registro_tabela`, `registro_id`, `detalhe` JSON, `criado_em`, `visto_em`) e para os avisos da dona por uma `FonteDeAvisos` nova. Alerta é registro (não se calcula depois), porque guarda o que o app viu.

### 12. Estornos

- **Estornar acerto** (`ESTORNO_ACERTO_NOTA`, com motivo): marca o acerto (`estornado_em`, `estorno_id`, `motivo_estorno`), estorna os `CONSIGNADO_RETORNO` dele (movimento com `estorna_id`), estorna o recibo do acerto (que estorna as aplicações dele, reabrindo as pendências antigas que ele tinha quitado) e **estorna as aplicações de outros recibos** feitas no débito deste acerto; o valor liberado volta a ser "a aplicar" do recibo de origem e entra no próximo débito (normalmente o acerto refeito). A nota volta a `EM_ABERTO` ou `REMARCADO`. Funciona em nota de semana anterior e em semana `FECHADA`.
- **Estornar recibo** (`ESTORNO_RECIBO`, com motivo): marca o recibo e grava aplicações negativas que anulam as dele; as notas tocadas são reavaliadas (podem voltar a `PENDENTE`). O recibo de um acerto só se estorna junto com o acerto (o app não oferece estornar só o recibo do acerto; se o representante pagou errado no acerto, estorna o acerto e refaz).
- Estorno é registro novo, nunca edição; estorno de estorno não existe (refazer = novo acerto ou novo recibo).

### 13. Sincronização

- **Tipos novos da outbox** (formato 1): `SEMANA_NOVA`, `NOTA_CONSIGNADO`, `REPOSICAO_NOTA`, `TROCA_NA_NOTA`, `REMARCAR_NOTA`, `ACERTO_NOTA` (com o recibo do acerto dentro, quando houve pagamento), `PAGAMENTO_CONSIGNADO` (Lançar pagamento), `ESTORNO_ACERTO_NOTA`, `ESTORNO_RECIBO`. `CLIENTE_NOVO` e `CLIENTE_ALTERADO` ganham o **formato 2** (campos novos); o formato 1 continua aceito por 90 dias (ADR-0013, item 17).
- **Idempotência** igual às outras: `operacaoId` + resumo SHA-256 da forma canônica (`OperacoesDoVendedor`); mesmo id e mesmo conteúdo → `JA_RECEBIDO`; mesmo id e outro conteúdo → recusado e alerta. Os ids dos registros (nota, itens, acerto, recibo, movimentos) também vêm do app, então reaplicar nunca duplica.
- **Ordem:** a outbox é enviada na ordem em que foi gravada; uma operação que depende de outra ainda não aplicada (acerto de nota que não chegou) fica `NAO_PROCESSADO` e volta no próximo envio, como hoje.
- **Concorrência:** a aplicação de nota, acerto, recibo e estorno trava a linha do **cliente** (`SELECT … FOR UPDATE`) antes de ler os débitos, e a da semana trava a linha do vendedor. Assim dois envios simultâneos (repetição de rede, ou a dona lendo no meio) nunca aplicam o mesmo dinheiro duas vezes nem quebram a ordem do mais antigo.
- **Pull só do representante** (`RegraDoVendedor.doVendedor`): `semanas_vendedor`, `notas_consignado`, `itens_nota_consignado`, `reposicoes_nota`, `trocas_nota`, `remarcacoes_nota`, `acertos_nota`, `itens_acerto_nota`, `recibos_consignado`, `formas_recibo_consignado`, `aplicacoes_recibo` (pela coluna `vendedor_id` da linha ou da mãe, que nunca muda) e `clientes` (item 4). **Não vão:** `alertas_consignado`, `numeros_da_semana`, fechamento da maleta, custo, `*_por`. Teste de marcadores proibidos e do oráculo de dois vendedores estendido às tabelas novas.
- **Hora:** `saida_em`, `acertado_em`, `pago_em` etc. seguem a correção de relógio e a marca `hora_ajustada` do ADR-0013, item 11.
- **Banco local** (migração 9 do SQLite): espelho das tabelas acima + locais: `rascunhos_nota` e `itens_rascunho_nota` (só no computador), `contadores_da_semana`, `fila_fotos_cliente`. A camada de pendentes (ADR-0013, item 8) projeta as operações ainda não enviadas sobre o espelho (o representante vê a nota, o acerto e a pendência na hora, sem internet). O cálculo do acerto no app usa **as mesmas fórmulas** do servidor, provadas pelo **mesmo arquivo de exemplos** (item 16).

### 14. Fechamento da maleta (Corona Gestão)

- Tabelas só do servidor: **`fechamentos_maleta`** (`remessa_id`, `iniciado_em`, `concluido_em`, `situacao` `EM_ANDAMENTO` | `CONCLUIDO`, `*_por`) e **`leituras_fechamento_maleta`** (imutável: `fechamento_id`, `produto_id`, `quantidade`, `lida_em`). Um fechamento em andamento por maleta.
- **Rotas da dona:** `POST /api/maletas/{id}/fechamento` (inicia), `POST /api/maletas/{id}/fechamento/leituras` (bipes, em lote, idempotentes por id), `GET /api/maletas/{id}/fechamento` (a leitura abaixo), `POST /api/maletas/{id}/fechamento/concluir`.
- **A leitura** (consulta única, a mesma do relatório): por produto, enviadas, devolvidas pelos clientes (Σ `CONSIGNADO_RETORNO`), vendidas (Σ VD dos acertos ativos), ainda com clientes (com cliente, nota e Dt. Cobrança), esperado na maleta (= disponível), bipadas na volta e **diferença** (bipadas − esperado); e os valores da maleta: VD, comissão dos clientes, recebido e pendências a receber.
- **Valores por maleta:** comissão, recebido e pendência são da nota, não da maleta. Na leitura, cada nota é **rateada pelo VD de cada maleta** dentro dela (a última maleta da nota leva a diferença de arredondamento), e o relatório diz "valores por maleta divididos pelo vendido de cada uma" (dúvida D3).
- **Concluir:** grava `DEVOLUCAO_DONA` das peças bipadas (devolve ao estoque da loja pelo serviço que já existe, `ServicoDaMaleta`) e a maleta passa ao status novo **`FECHADA`** (nova migração no `CHECK` de `remessas.status`). A diferença não é corrigida sozinha: fica no relatório para a dona decidir (perda, ajuste) pelas telas que já existem.
- **Peça ainda com cliente na hora de concluir:** permitido. A maleta `FECHADA` não aceita mais `CONSIGNADO_SAIDA`, mas aceita `CONSIGNADO_RETORNO` (o acerto dessas notas acontece depois); essas peças aparecem na próxima leitura do fechamento como "devolvidas depois do fechamento", para o representante trazer (dúvida D2b).
- **Relatório "Fechamento da maleta"** no `MotorDeRelatorio` (PDF, Excel, CSV), com a mesma consulta. A tela é da 9.4.

### 15. Desligar o fluxo antigo sem apagar dados

- **Corona Gestão:** configuração nova `acerto_do_vendedor_visivel` (`BOOLEANO`, padrão `false`, migração); `GET /api/configuracoes/telas` diz ao app o que esconder. Com `false`, o menu esconde Acertos, Comissão por meta, Pendências do acerto e a "semana" da §7.4. As rotas e os dados continuam; nada é apagado; ligar de volta é mudar a configuração.
- **Corona Vendas:** saem do menu e das rotas da tela Venda, Fiado/parcelas, Devolução, Troca com garantia, Crédito do cliente, Pedir acerto e a Conferência obrigatória. As tabelas locais e o pull dessas tabelas continuam (histórico, e envios antigos pendentes ainda sobem); os tipos antigos da outbox continuam aceitos pelos 90 dias da regra; a tela deixa de criá-los. O app novo **não** grava `VENDA`.
- Os relatórios antigos continuam na Gestão, com os dados antigos.

### 16. Testes primeiro

Antes de cada serviço, o teste (lista completa no plano):

1. **Nota `0000064-21`** (§23.8), número por número, no servidor e no app, **lidos do mesmo arquivo de exemplos** `packages/shared/exemplos/consignado-acerto.json` (Java lê pelo classpath de teste; TypeScript importa).
2. **Pendência em 3 momentos** (400/300 → 100; 500+100/550 → 50; pagamento de 50 → 0, nota Fechado).
3. Ordem do mais antigo com três débitos; estorno de recibo reabre; estorno de acerto desfaz retornos, recibo e aplicações e libera o "a aplicar".
4. Recusas da tabela do item 11, uma por linha; divergências viram alerta e são aceitas.
5. Sync: mesma operação duas vezes (sequencial e em duas threads) → um `ACEITO` e um `JA_RECEBIDO`, nenhum dinheiro duplicado; dois recibos do mesmo cliente ao mesmo tempo → aplicações nunca passam do débito; oráculo de dois vendedores com as tabelas novas; nenhuma coluna de custo nem foto no pull; cliente transferido sai do computador do antigo.
6. Conciliações novas (item 17).

### 17. Conciliações novas (§13)

Só leitura, no padrão das da Fase 7: (a) `situacao` gravada = situação recalculada, para toda nota; (b) acerto: totais gravados = recalculados dos itens; (c) Σ aplicações ativas de cada débito ≤ bruto; (d) total do recibo = Σ formas = Σ aplicações ativas + a aplicar; (e) disponível ≥ 0 por produto e maleta (ou alerta já registrado); (f) cada `CONSIGNADO_RETORNO` ativo tem DV correspondente em acerto ativo; (g) uma semana aberta por vendedor.

## Consequências

### Boas

- Uma fonte de verdade por conta: o débito é o acerto, a pendência é saldo, a situação é recalculável; as conciliações conferem tudo sem estado extra.
- Nada se edita: nota, itens, acertos, recibos, aplicações e movimentos só crescem; correção = estorno, como o resto do sistema.
- O saldo da maleta continua sendo "enviada + Σ movimentos": as telas e relatórios antigos de maleta não quebram.
- O rascunho local tira do servidor a nota pela metade e o problema de editar nota.
- Um arquivo de exemplos para servidor e app impede que as duas contas se afastem.

### Ruins ou custos

- Onze tabelas sincronizáveis novas e quatro migrações; o oráculo e os testes de filtro crescem.
- O "a aplicar" é, na prática, um saldo a favor do cliente; só aparece por divergência ou estorno, mas precisa de tela e conciliação.
- O rateio por maleta no Fechamento é aproximado por natureza (o dinheiro é da nota).
- Reverter o diretório de clientes do ADR-0013 exige a limpeza local e o gatilho de saída de novo.

## Alternativas consideradas

- **Pendência como tabela com saldo editável:** descartada; quebra "pendência nunca editada" e precisa de trava contra corrida. O saldo por aplicações é auditável.
- **Venda pelo cliente como movimento de maleta:** descartada; a peça já está fora, e um movimento a mais mudaria a soma do saldo usada pelas telas antigas.
- **Nota editável até o acerto (Reposição editando a nota):** descartada; Reposição e Troca são eventos novos sobre a nota.
- **Número `semana-sequência` gerado pelo servidor:** impossível sem internet; o número é impresso na hora.
- **Recusar a nota sem saldo na maleta:** descartada (D4); a peça já está com o cliente, recusar deixaria o computador e o servidor diferentes sem conserto pelo representante.
- **Reaproveitar `vendas`/`itens_venda` para a nota:** descartada; a venda tem pagamento na hora, desconto e status próprios, e as regras dela (§7.2) saem do Corona Vendas.

## Riscos e o que NÃO fazer

- **Não** confiar nos totais do app: sempre recalcular; o app manda só os fatos.
- **Não** aplicar pagamento sem travar o cliente.
- **Não** mandar ao Corona Vendas fotos ou documento de cliente de outro representante, nem custo.
- **Não** apagar dados antigos (vendas, parcelas, acertos da §7.4) nem editar migração publicada.
- **Não** recusar em silêncio: todo `RECUSADO` vai para "envios com problema" e toda divergência aceita vira alerta.
- **Não** gravar o número `semana-sequência` como texto (formatação muda; o par semana + sequência é a chave).

## Seções da especificação relacionadas

§23 (inteira), §7.1, §7.4, §7.5, §10, §13, §20; ADR-0002, 0003, 0006, 0010, 0012, 0013.

## Dúvidas

- **D1.** Primeira semana no Corona Vendas: começar em 1 ou deixar o representante digitar o número atual do sistema antigo (no print, semana 65)? Padrão proposto: perguntar na primeira abertura, com 1 sugerido.
- **D2.** Contador `semana-sequência` **único** para notas e recibos (proposto, pelo print) ou um para cada?
- **D2b.** Maleta fechada com peças ainda com clientes: permitir (proposto) ou exigir acerto de todas as notas antes?
- **D3.** Valores por maleta no Fechamento: rateio pelo VD (proposto) ou mostrar as notas inteiras sem dividir?
- **D4.** Nota que chega sem saldo na maleta: aceitar com alerta (proposto) ou recusar?
- **D5.** Número repetido (só na troca de computador): recusar para "envios com problema" (proposto) ou aceitar com marca?
- **D6.** Sobra de pagamento por divergência ou estorno: "a aplicar" automático no próximo débito (proposto) ou a dona decide?
- **D7.** Documento: campo novo livre (proposto, aceita CI paraguaia) ou continuar no `cpf` com o índice único?
- **D8.** Fotos do cliente criptografadas no disco do computador (proposto) ou como as de produto?
- **D9.** O print deixa escolher quanto pagar de cada pendência ("Valor a Pagar", "Pagar Tudo"); a §23.8 manda a ordem fixa do mais antigo. Seguimos a §23.8 (proposto).

### Ajustes da fase CÓDIGO (19/11/2026)

- **D10 (nova).** A tabela de "saídas do vendedor" (regra 2 do ADR-0013, item 5) **nunca foi construída**; o gatilho citado no item 4 não tinha onde gravar. Para a lista própria do representante não deixar cópia velha no computador do antigo responsável, a V049 faz o responsável do cliente **não mudar depois de definido** (como a V032 faz com as outras colunas de pertença); salvar o cadastro sem responsável mantém o que já existe. Transferir cliente entre representantes fica para uma decisão futura (precisa da tabela de saídas e de o pull avisar o apagar). A rota online antiga `GET /api/vendedor/clientes` (diretório da loja) não foi mexida.
- **Configuração** `acerto_do_vendedor_visivel`: a tabela não tem tipo booleano; vale `INTEIRO` 0 ou 1 (padrão 0).
- **Fotos:** tabela só do servidor `fotos_de_cliente` (hash, chave e tipo) na V052, porque o armazenamento gera a chave do arquivo.
- **Pendência não vinda de NAO_PROCESSADO:** operação que depende de registro inexistente é recusada com o código do registro (por exemplo `NOTA_NAO_ENCONTRADA`); a outbox sai na ordem, então o caso só aparece depois de uma recusa anterior, que vira `DEPENDE_DE_ENVIO_RECUSADO`.
- **Conciliações** `K1` a `K7` ficam à parte (`GET /api/dona/consignado/conciliacoes`), na forma das da Fase 7; ainda não entram na conferência diária.
- **Relatório "Fechamento da maleta":** a leitura única (`GET /api/maletas/{id}/fechamento`) é a base; o PDF/Excel/CSV no `MotorDeRelatorio` fica para a 9.3/9.4, que montam a exportação a partir dela.

- **Compatibilidade do pull (item 13, achado no CI):** o Corona Vendas 1.0.0 recusa o lote INTEIRO do pull se vier tabela ou coluna que ele não conhece (`conferirLinhas`: "tabela desconhecida", "coluna fora da lista"), e o ciclo trava. Por isso o servidor **nunca manda o novo a quem não o declarou**: o pedido `GET /api/vendedor/sync/pull` (e `/conferencia`) leva `formato`; sem ele, ou com 1, vale o pull de antes (sem as 11 tabelas, `clientes` e `movimentos_remessa` com as colunas e a regra antigas, diretório da loja incluído). O Corona Vendas da 9.2 pede `formato=2`. Foi a opção que menos mexe no app e não depende de versão. Quando o app passa a `formato=2`, o cursor continua válido, mas as linhas novas que ficaram atrás dele não voltam sozinhas: a 9.2 faz a recarga completa na migração 9 (a recarga dirigida por tabela já existe). Tabela do consignado pedida no formato 1 é 400. O formato 1 acaba quando o Corona Vendas 1.0.0 sair de uso.