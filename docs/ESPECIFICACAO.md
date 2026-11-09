# Especificação completa: Sistema de Semijoias Consignadas

Documento-base para iniciar o desenvolvimento no Claude Code. Consolida todas as decisões do planejamento. Versão 1.3, 09/11/2026.

## 1. Objetivo e contexto

Sistema para uma loja de semijoias que trabalha com **consignação**. A dona monta **maletas** (remessas) com peças e entrega a **vendedores**. Cada vendedor vende as peças a **clientes finais**, inclusive **sem internet**. Toda semana ocorre o **acerto**: o vendedor devolve o que sobrou, repassa o dinheiro e recebe comissão. Há devolução (reembolso) e troca dentro da garantia. O sistema precisa de **relatórios completos** e de **leitura por código de barras**.

São **dois sistemas** instalados em desktop Windows, que conversam com **uma API única** na nuvem.

## 2. Glossário e mapeamento da tela de referência

O cliente enviou fotos de um sistema existente (parece Delphi, marca MCB) como referência de funcionalidade e layout.

| Termo da referência | Termo neste projeto | Significado |
| --- | --- | --- |
| Promotor | **Vendedor** | Usuário do app do vendedor. Recebe a maleta, vende, cobra e faz o acerto. Tem praça, limite de consignação, restrição e comissão. |
| Vendedora (cadastro com cônjuge, referências, documentos, MEI) | **Vendedor** (mesma entidade) | O cadastro rico da referência é o do vendedor, pois a dona precisa de garantias de quem leva mercadoria. Não existe tabela separada de promotor. |
| Cliente final | **Cliente** | Consumidor que compra do vendedor. Cadastro simples. |
| Nota / Pedido / Consignado | **Maleta (remessa)** | Conjunto de peças entregue ao vendedor, com quantidade e preço congelado. |
| Reposição | **Reposição** | Maleta complementar, sempre criada online pela dona. |
| Troca | **Troca** | Cliente devolve a peça A e leva a peça B. |
| Cobrança | **Cobrança** | Recebimentos do cliente pelo vendedor e repasse do vendedor à dona. |
| Praça | **Praça** | Região/rota atendida pelo vendedor. |
| Semana nº | **Semana** | Período de controle: remessa, cobrança e acerto são semanais. |
| DX | Valor consignado (enviado) | Premissa a confirmar com o cliente. |
| DV | Valor devolvido | Premissa a confirmar. |
| VD | Valor vendido | Premissa a confirmar. |

### Telas da referência (para inspirar o layout)

**Gerenciamento Semanal:** barra de botões no topo (Novo Pedido, Cobrança, Reposição, Troca, Vendedoras, Estoque), filtros (Semana nº, Promotor, Praça, Situação) e duas abas.

- Aba **Pedidos**, com grade: Nota nº, Vendedora, Dt Cobrança, Valor DX, Valor DV, Valor VD, Comis %, Comissão, Valor Líq, Situação.
- Aba **Totais**, em blocos: Consignado/Novos Pedidos (novas notas, total qtd, total, entrada), Consignado/Reposições (notas repostas, qtd, total), Consignado/Cobrança (notas cobradas, remarcadas, qtd e total de devolvidos e vendidos), Consignado/Acerto (descontos, total comissão, total líquido, pendência paga, pendências, desconto de entrada), Vendas (vendas, qtd, total, descontos, líquido), Vendas/Trocas (trocas em aberto, utilizadas, total de trocas), Total (total de notas, estoque, total de vendas, descontos, líquido) e Total/Acerto (dinheiro, cheque, entrada, Pix, boleto, total recebido).

**Cadastro de Vendedora (vira Vendedor):** barra de ações (salvar, etc.), abas Cadastro e Consulta. Campos: cadastrado por, promotor atual, praça atual, código, nome, data de cadastro, data de nascimento, sexo, estrangeiro, RG/IE/Doc, RG UF, CPF/CNPJ, naturalidade, escolaridade, telefone 1 e 2, WhatsApp, e-mail, Facebook, outras mídias sociais, **limite de consignação**, **restrição** (código e descrição), ativo. Sub-abas: Endereço (logradouro, número, complemento, bairro, CEP, cidade, local de referência, residência, valor), Filiação/Contato, Observação, Trabalho, Cônjuge, MEI, Referências, Documentos.

**Consulta de vendedor:** filtros nome, praça, promotor, RG, CPF, cidade; grade com código, vendedor, telefone 1, telefone 2, praça; contador de resultados.

O layout deve **seguir a organização** dessas telas, que a equipe já conhece, com visual moderno em React.

## 3. Perfis e permissões

| Perfil | Descrição |
| --- | --- |
| **Admin (dona)** | Acesso total. Poucos usuários. |
| **Vendedor** | Acessa apenas o app do vendedor e apenas os próprios dados. |

Hoje existem dois perfis (Admin e Vendedor), mas o sistema deve permitir acrescentar perfis novos depois, sem refatoração. Cada app aceita só o seu perfil: a dona não entra no app do vendedor e o vendedor não entra no app da dona. No login, a API recusa o perfil incompatível com o app, e cada rota exige o perfil certo (ver §11).

Permissões são **validadas na API**, nunca só na tela. Rotas por perfil são separadas.

## 4. Os dois sistemas

| Funcionalidade | App da Dona | App do Vendedor |
| --- | --- | --- |
| Fornecedores e compras | Completo | Não tem |
| Produtos e preços | Completo (CRUD) | **Sem cadastro**, só consulta, sem preço de custo |
| Estoque central | Completo | Não tem; só o saldo da própria maleta |
| Cadastro de vendedores (limite, restrição, comissão, praça) | Completo | Não tem |
| Clientes | Completo | **Cadastra e edita os seus** |
| Maletas, reposição, acerto | Cria, envia, acerta | Confere, vende, devolve, inicia o acerto |
| Vendas | Visualiza todas | Registra as suas |
| Devolução e troca | Autoriza exceções | Registra dentro da garantia |
| Financeiro | Completo | **Só relatórios dele** |
| Painel semanal | Completo | Resumo próprio |
| Modo offline | Não (só online) | **Obrigatório** |

O código exclusivo da dona **não pode ir dentro do instalador do vendedor**. Esconder telas não basta. São **dois builds separados**, dois instaladores, no mesmo repositório.

## 5. Tecnologia

| Camada | Escolha |
| --- | --- |
| Apps desktop | Electron + React + TypeScript (dois instaladores .exe, só Windows) |
| API | Java 21 + Spring Boot 4.1.x (versão exata fixada no ADR-0001), Flyway, OpenAPI/Swagger |
| Banco central | PostgreSQL |
| Banco local (vendedor) | SQLite criptografado (SQLCipher), com migrações versionadas |
| Servidor | VPS Linux, Docker Compose (API + PostgreSQL), Nginx, HTTPS com Let's Encrypt, domínio próprio |
| Autenticação | JWT de acesso curto + refresh token longo (para uso offline), senhas com BCrypt |
| Versionamento | Monorepo no GitHub, GitHub Actions |
| Ambiente de dev | VS Code (sem IntelliJ), JDK 21, Maven wrapper, Node LTS, Git, Docker Desktop |
| Atualização automática | electron-builder + electron-updater, instaladores hospedados na VPS ou bucket (S3/Backblaze), **não** no GitHub Releases, porque o repositório é privado |

Alternativa já descartada: tudo em C#. Mantemos Electron + React porque o desenvolvedor domina React e a API serve futuramente web e celular.

### Arquitetura

```
App da Dona (online) ───────┐
                            ├── HTTPS ──► API Spring Boot ──► PostgreSQL
App do Vendedor ────────────┘               ▲                (VPS na nuvem)
 React → SQLite local + outbox ── sync ─────┘
```

O app **nunca acessa o banco direto**, apenas a API. Segurança (ninguém tem a senha do banco) e manutenção (regras num só lugar).

## 6. Premissas e decisões já tomadas

1. Uma loja (sem multiloja) e uma dona (ou poucos admins).
2. Dezenas de vendedores, no máximo.
3. Vendedores em **locais diferentes**, logo servidor na nuvem.
4. **Modo offline obrigatório** no app do vendedor. O app da dona é só online.
5. O vendedor **só vende o que está na própria maleta**, nunca do estoque geral. Isso elimina quase todo conflito de estoque.
6. O vendedor precisa estar **online** para receber a maleta e para fazer o acerto.
7. O código de barras identifica o **modelo do produto**, e o controle é por quantidade, não por peça individual.
8. Comissão é percentual por vendedor, calculado sobre o vendido no acerto.
9. Acerto **semanal**.
10. A **nota de remessa não tem validade fiscal**: é um documento interno em PDF.
11. Fornecedores usam **EAN-13** (premissa provisória, a confirmar).
12. Peça com código EAN-13 vem etiquetada pelo fornecedor; a dona cadastra os códigos no sistema.
13. Roda só em Windows.
14. Assinatura de código e VPS entram no orçamento do cliente.
15. Cada vendedor usa o **próprio notebook**, um aparelho por vendedor. Desde a Fase 0 a sessão guarda o identificador do aparelho. Impedir um segundo aparelho fica para a Fase 5 (tabela `dispositivos`).

## 7. Regras de negócio

### 7.1 Maleta (remessa)

- Ciclo de estados: `RASCUNHO → ENVIADA → CONFERIDA → EM_ACERTO → ACERTADA` (mais `CANCELADA`).
- A dona monta a maleta **online**, bipando os produtos. O estoque central é baixado ao enviar.
- **Preço congelado** no envio (`preco_remessa`). O vendedor vende por esse preço, mesmo que a dona mude o preço depois.
- **Bloqueios:** valor total da maleta não pode passar o **limite de consignação** do vendedor; vendedor com **restrição** ou inativo não recebe maleta; produto sem saldo central não entra.
- O vendedor sincroniza, **confere** bipando as peças recebidas e registra divergências.
- **Saldo por eventos**, nunca por número digitado: `saldo = enviada − vendas − devoluções à dona − perdas − avarias + estornos de venda + devoluções de clientes boas + entradas de troca − saídas de troca`.
- **Reposição** é uma maleta complementar criada online. Se faltar peça no meio do caminho, a dona cria nova remessa.
- Gerar **nota de remessa em PDF** (itens, quantidades, preços, vendedor, semana, assinatura).
- Cada maleta fica vinculada a **um dispositivo** só, para evitar que dois aparelhos vendam a mesma peça.

### 7.2 Venda (app do vendedor, offline)

- O vendedor bipa a peça, escolhe o cliente e a forma de pagamento (à vista ou a prazo/parcelado).
- Bloqueios locais: produto fora da maleta ("produto não consta na sua remessa"), saldo zerado, desconto acima do limite definido pela dona.
- Desconto só até o limite configurado, validado também no servidor.
- **Venda finalizada é imutável.** Erro vira cancelamento ou estorno, um novo registro.
- O cliente pode pagar **na hora** ou ser **fiado/parcelado**. No fiado, o vendedor controla as contas a receber dos próprios clientes.
- Pagamentos do cliente são **eventos** (data, valor, forma). Recebimento posterior é um registro novo.

### 7.3 Devolução e troca (app do vendedor, offline)

Três casos:

| Caso | O que acontece | Efeito na maleta |
| --- | --- | --- |
| Devolução do cliente | Devolve peça (total ou parcial da venda) | Peça boa volta ao saldo; venda estornada |
| Troca | Devolve A e leva B | A entra, B sai, com acerto da diferença |
| Devolução à dona | Vendedor devolve peças não vendidas ou com defeito | Sai do saldo e volta ao estoque central no acerto |

Regras:

- **Sempre ligada à venda original**, encontrada bipando a peça ou escolhendo a venda. Só aceita devolver até a quantidade vendida, pelo preço pago.
- **Motivo obrigatório:** arrependimento, defeito, tamanho, outro. Foto opcional.
- **Estado da peça:** `BOA` volta ao saldo vendável; `DEFEITO` fica separada e segue para a dona no acerto, sem voltar a ser vendida.
- **Garantia:** o cliente pode **receber o dinheiro de volta (reembolso) ou trocar**, desde que **dentro do prazo**. Dois prazos configuráveis pela dona: `prazo_arrependimento_dias` e `prazo_defeito_dias`, contados da data da venda.
- **Fora do prazo:** o app bloqueia e oferece só o pedido de **autorização da dona**, que fica pendente e é resolvido online. O vendedor não conclui sozinho.
- A validação ocorre no app (offline) e de novo no servidor. Se a hora do aparelho divergir, **o servidor decide**.
- **Diferença na troca:** B vale mais, o cliente paga a diferença (à vista ou a prazo); B vale menos, a diferença vira **crédito** do cliente (usado em compra futura) ou é devolvida, conforme a regra da loja.
- **Tratamento financeiro do reembolso:**
  - venda já paga: o vendedor devolve o dinheiro e registra; no acerto o valor é **abatido** do repasse;
  - venda a prazo sem pagamento: cancelam-se as parcelas, sem dinheiro a devolver;
  - venda a prazo parcialmente paga: devolve só o já pago e cancela as parcelas restantes;
  - comissão estornada proporcionalmente pelo servidor.
- **Antifraude:** limite de reembolso semanal por vendedor, aprovação da dona acima de certo valor, alerta quando a taxa de devolução de um vendedor passa de um percentual configurável. Reembolso inconsistente (sem venda, fora do prazo, maior que o pago) vira **pendência para revisão da dona**, nunca é aceito em silêncio.
- Nada se edita: pagamento original nunca é apagado; o reembolso é um registro negativo.

### 7.4 Acerto semanal (online)

1. O vendedor sincroniza as vendas, devoluções, trocas e pagamentos.
2. Bipa as peças que sobraram.
3. O servidor compara **esperado x contado**. Peça faltante vira **pendência financeira** para a dona resolver.
4. Calcula descontos, comissão, valor líquido, reembolsos abatidos e pendências.
5. Aceita pagamento do repasse em **dinheiro, Pix, cheque (com status), boleto e entrada**.
6. Peças com defeito são aprovadas pela dona no acerto; as boas voltam ao estoque central.
7. Estados: `EM_ACERTO → ACERTADA`. Semana fechada **não aceita mais alteração**.

### 7.5 Financeiro

- **Contas a receber** em dois níveis: (1) cliente final paga ao vendedor; (2) vendedor repassa à dona no acerto.
- **Contas a pagar** a fornecedores, ligadas às compras.
- **Lançamentos de caixa**, **comissões** (por vendedor e semana, pagas e a pagar), **cheques** (a compensar, compensado, devolvido), **reembolsos**.
- O **servidor recalcula** totais, parcelas e comissão e nunca confia no que o app mandou. Preço, desconto e saldo são validados; divergência vira alerta, não rejeição silenciosa.
- Valores em `NUMERIC(14,2)`, **nunca float**, com regra única de arredondamento: **HALF_UP com escala 2**.
- Datas em UTC, exibidas em horário de Brasília.
- **O preço de custo nunca chega ao PC do vendedor.**

### 7.6 Estoque central e compras

- Compras de fornecedor geram entrada no estoque central, **bipando as peças**.
- Registrar **cada movimentação** (`ENTRADA`, `SAIDA`, `AJUSTE`, `REMESSA`, `DEVOLUCAO`, `AVARIA`). O saldo é a soma, com coluna `estoque_atual` mantida **na mesma transação** para ser rápida.
- Estoque mínimo com alerta; **inventário por leitor** com relatório de divergências; relatório de inventário por vendedor (mercadoria em maleta).

## 8. Código de barras e leitor

O leitor USB/Bluetooth funciona como **teclado**: digita o código e envia **Enter**. Sem driver, só uma tela preparada.

### Onde o leitor atua

| Tela | Ao bipar |
| --- | --- |
| Cadastro de produto (dona) | Preenche o código; verifica duplicidade na hora |
| Entrada de compra (dona) | Soma a quantidade; código desconhecido abre cadastro rápido já com o código |
| Montagem da maleta (dona) | Adiciona a peça; valida limite e saldo central |
| Conferência da maleta (vendedor) | Marca como recebida; aponta divergências |
| Venda (vendedor) | Adiciona o item; valida se está na maleta e se há saldo |
| Devolução e troca (vendedor) | Localiza a venda original |
| Acerto | Conta o que sobrou e compara com o esperado |
| Inventário (dona) | Contagem física e divergências |
| Consulta de produto | Mostra preço, saldo e foto |

### Requisitos de robustez

- Campo com **foco automático** que retorna sozinho após cada leitura.
- Distinguir leitor de digitação manual pela **velocidade das teclas**; digitação manual também funciona.
- **Som e cor** diferentes para sucesso e erro.
- Bipar de novo a mesma peça **soma a quantidade** (opção de digitar a quantidade).
- Mensagens claras para código não encontrado, produto fora da maleta e saldo zerado, **sem travar o fluxo**.
- No app do vendedor a busca é no **banco local**, instantânea e offline.
- **Busca manual** por nome ou referência sempre disponível.

### Cadastro dos códigos (EAN-13)

- O fornecedor envia a peça já com código; a dona **cadastra os códigos** no sistema.
- Fluxo 1: no cadastro do produto, o cursor já fica em Código de barras; ela bipa.
- Fluxo 2 (principal no dia a dia): na entrada da compra, código conhecido soma; desconhecido abre **cadastro rápido**.
- **Importação em massa** por planilha Excel/CSV (código, nome, categoria, custo, preço), com **prévia de erros** e relatório do resultado (criados, atualizados, recusados).
- **Um produto pode ter vários códigos**: tabela `produto_codigos` em vez de um campo único.
- Código **único globalmente**, guardado como **texto** (zeros à esquerda).
- **Validar o dígito verificador do EAN-13** e avisar quando a leitura vier truncada ("leia o código de novo").
- Aceitar outros formatos (Code 128, códigos curtos) **com aviso**, caso algum fornecedor fuja do padrão. Se a premissa do EAN-13 falhar, só se ajusta a regra de validação.
- Códigos curtos iguais de fornecedores diferentes: avisar no cadastro e a dona decide (código interno ou código vinculado ao fornecedor).
- **Código interno** para peça sem etiqueta ou ilegível: EAN-13 com prefixo `2` (faixa de uso interno), nunca conflita com os dos fornecedores. Etiquetadora é **opcional**, só para esses casos.
- Todos os códigos sincronizam para o app do vendedor.

### Pendências sobre o leitor (levar ao cliente)

1. **Qual modelo** de leitor vai comprar? Pedir que **não compre antes de testar** com as etiquetas reais.
2. Confirmar se os fornecedores usam **EAN-13**. Pedir foto ou amostra de 3 a 4 etiquetas.
3. Leitor com fio (USB) ou sem fio (Bluetooth)? Sem fio é mais prático para o vendedor na rua.
4. Requisitos: modo teclado (HID) com sufixo Enter; um modelo **2D/imager** lê melhor etiquetas minúsculas de semijoia. Marcas comuns: Elgin, Bematech, Zebra, Honeywell, Datalogic.

Risco: etiquetas muito pequenas ou de baixa qualidade podem exigir leitor 2D ou reetiquetagem.

## 9. Modelo de dados

### Campos de controle em toda tabela sincronizável

`id` (**UUID gerado no app**, nunca auto-incremento), `created_at`, `updated_at`, `version`, `deleted_at` (**exclusão lógica**), `origem_dispositivo`. No servidor, também `sync_seq` (sequência crescente para o pull). O pull usa um **limite seguro** sobre essa sequência, que não perde registros de transações que confirmam fora de ordem (exemplo: o app puxa até o `sync_seq` 11 e o 10 só confirma depois). A estratégia exata fica no ADR-0002.

### Cadastros

- `usuarios` (login, senha\_hash, perfil, ativo, vendedor\_id opcional)
- `vendedores` (código, nome, datas, sexo, estrangeiro, RG/IE, RG UF, CPF/CNPJ, naturalidade, escolaridade, telefones, WhatsApp, e-mail, redes sociais, **limite\_consignacao**, **restricao\_id**, **comissao\_percentual**, **praca\_id**, ativo, cadastrado\_por) e tabelas filhas: endereço, filiação/contato, trabalho, cônjuge, MEI, referências, documentos (arquivos), observações
- `restricoes` (código, descrição, bloqueia\_remessa)
- `pracas`, `semanas` (número, início, fim, situação ABERTA/FECHADA)
- `clientes` (nome, CPF opcional, telefone, WhatsApp, endereço, observações, vendedor\_responsavel\_id)
- `fornecedores` (razão social, CNPJ, contato, endereço)
- `categorias`
- `produtos` (nome, referência, categoria, fornecedor, preco\_custo, preco\_venda, estoque\_minimo, estoque\_atual, foto, ativo)
- `produto_codigos` (produto\_id, codigo único e indexado, fornecedor\_id, principal, ativo)

### Estoque e compras

- `movimentacoes_estoque` (produto, tipo, quantidade, origem, usuario, data)
- `compras`, `itens_compra`
- `inventarios`, `itens_inventario` (contado x esperado)

### Maleta

- `remessas` (vendedor, semana, praça, tipo NOVA/REPOSICAO, status, dispositivo\_id, totais)
- `itens_remessa` (remessa, produto, qtd\_enviada, **preco\_remessa** congelado)
- `movimentos_remessa` (id UUID do app como chave de idempotência, remessa, produto, tipo `VENDA | ESTORNO_VENDA | DEVOLUCAO_CLIENTE | TROCA_ENTRADA | TROCA_SAIDA | PERDA | AVARIA | AJUSTE | DEVOLUCAO_DONA`, quantidade, venda\_id, dispositivo\_id, criado\_em)

### Vendas

- `vendas` (cliente, vendedor, remessa, data, status, total, desconto, chave de idempotência)
- `itens_venda` (venda, produto, quantidade, preço unitário)
- `pagamentos_cliente` (venda, valor, forma, data, `tipo: PAGAMENTO | REEMBOLSO`; reembolso é registro negativo)

### Devolução e troca

- `devolucoes` (venda, cliente, vendedor, remessa, motivo, data, status, `destino: REEMBOLSO | TROCA`, dentro\_garantia, autorizada\_por, valor\_reembolsado)
- `itens_devolucao` (devolução, produto, quantidade, valor\_unitario, `estado_peca: BOA | DEFEITO`)
- `trocas` (venda\_origem, cliente, vendedor, remessa, diferenca\_valor, data)
- `itens_troca` (troca, produto, quantidade, valor\_unitario, `sentido: ENTRADA | SAIDA`)
- `creditos_cliente` (cliente, origem, valor, valor\_utilizado, status ABERTO/UTILIZADO)
- `autorizacoes_excecao` (devolução fora do prazo: solicitada, aprovada ou negada pela dona)

### Acerto e financeiro

- `acertos` (vendedor, semana, remessa, esperado, contado, faltas, descontos, comissão, líquido, status)
- `pendencias_acerto` (acerto, produto, quantidade, valor, status, resolvida\_por)
- `contas_receber` (venda ou acerto, devedor cliente/vendedor, parcela, vencimento, valor, status, data\_pagamento)
- `contas_pagar` (fornecedor, compra, vencimento, valor, status)
- `lancamentos_caixa` (tipo, valor, categoria, origem, data)
- `comissoes` (vendedor, venda, acerto, valor, status de pagamento)
- `cheques` (número, banco, valor, vencimento, status A\_COMPENSAR/COMPENSADO/DEVOLVIDO)

### Controle

- `configuracoes` (prazo\_arrependimento\_dias, prazo\_defeito\_dias, limite\_desconto, limite\_reembolso\_semanal, aprovação acima de valor, percentual de alerta de devolução, tempo máximo offline (padrão 10 dias, configurável), tentativas de login antes do bloqueio (padrão 5), tempo de bloqueio (padrão 15 minutos))
- `auditoria` (usuário, ação, tabela, registro, antes/depois, data)
- `dispositivos` (vendedor, identificação, ativo, bloqueado, último contato)
- `sync_log` (dispositivo, lote, resultado por item)
- `importacoes` (arquivo, criados, atualizados, recusados, erros)

Cada mudança de tabela é uma **migração Flyway** versionada (`V001__...sql`), nunca editada depois de publicada. O SQLite do vendedor também tem migrações versionadas aplicadas ao abrir o app.

## 10. Offline e sincronização (só no app do vendedor)

O app é **offline-first**: lê e grava sempre no SQLite local, e um processo em segundo plano sincroniza quando há internet.

**Componentes locais:** SQLite criptografado (SQLCipher; verificar a biblioteca compatível com Electron, por exemplo `better-sqlite3-multiple-ciphers`), tabela `outbox` (fila ordenada de operações pendentes), cursor `ultimo_sync_seq` e motor de sincronização no processo principal do Electron.

**Push:** cada ação offline vira item na `outbox`. Quando há internet, o app envia em lotes para `POST /sync/push`. O servidor responde **item por item** (aceito, rejeitado, conflito).

**Pull:** `GET /sync/pull?since=cursor` devolve tudo que mudou desde o último `sync_seq`. O app aplica no banco local. O pull usa um **limite seguro**, que não perde registros de transações que confirmam fora de ordem (exemplo: o app puxa até o `sync_seq` 11 e o 10 só confirma depois). A estratégia exata fica no ADR-0002.

**Gatilhos:** abrir o app, voltar a conexão, a cada X segundos se online e depois de cada gravação.

**Indicador na tela:** Online, Offline e quantidade de pendentes de envio.

**Regras de projeto obrigatórias:**

1. IDs UUID gerados no app.
2. Exclusão lógica (`deleted_at`).
3. Campos de sync em toda tabela sincronizável.
4. **Chave de idempotência** em toda operação: reenviar uma venda após queda de conexão não duplica.
5. Dados imutáveis sempre que possível (venda, movimento de maleta, pagamento). Correção vira novo registro.

**Política de conflito por tipo de dado:**

| Dado | Regra |
| --- | --- |
| Vendas, itens, movimentos, pagamentos | Só inserção, sem conflito. Cancelamento é novo evento. |
| Clientes | Última alteração vence, campo a campo se possível. CPF duplicado vai para revisão da dona. |
| Produtos, preços, códigos | Somente a dona edita; **servidor vence**. O vendedor só recebe. |
| Contas a receber, comissões, acertos | Gerados **pelo servidor**. O app só exibe. |
| Maleta e acerto | Estados controlados pelo servidor. |

**O que cada vendedor sincroniza (somente o necessário):** suas maletas abertas e os produtos delas (com foto e **todos os códigos de barras**, sem custo), seus clientes, suas vendas, devoluções, trocas, pagamentos e a configuração de garantia e descontos. **Nada** de financeiro geral, fornecedores, custo ou dados de outros vendedores.

**Ao receber o push, o servidor:** confere a idempotência; valida que a maleta pertence ao vendedor e está ativa; valida preço, desconto, garantia e saldo (saldo negativo só ocorre por bug ou fraude e vira **alerta**, não rejeição silenciosa); **recalcula** total, parcelas e comissão; gera `contas_receber` e `comissoes`.

**Limitações assumidas:** o vendedor precisa estar online ao menos uma vez para receber a maleta e para o acerto. Não há venda do estoque geral, então não existe disputa de peça entre vendedores. Uma maleta é vinculada a **um dispositivo**.

**Testes obrigatórios de sync:** venda offline com queda de conexão no meio do envio e reenvio sem duplicar; dois dispositivos; relógio do aparelho errado; devolução fora do prazo offline; cancelamento de venda já sincronizada; acerto com sobra e com falta; dispositivo bloqueado; cliente duplicado por CPF; migração do SQLite entre versões do app.

## 11. Segurança

- Senhas com **BCrypt**; JWT de acesso curto; **sessão longa (refresh token) de 30 dias, com rotação**, para login offline; **prazo máximo offline padrão de 10 dias**, configurável.
- Ao esgotar o prazo máximo offline, as vendas guardadas **nunca se perdem**: o Corona Vendas bloqueia vendas novas, pede para conectar e envia tudo assim que houver internet.
- A dona cria o usuário com **senha temporária** e a redefine quando preciso; a **troca da senha é obrigatória no primeiro acesso**.
- A senha nova tem **pelo menos 8 caracteres, com letras e números**, e é diferente da senha temporária.
- Senha da própria dona: **outro admin** redefine. Se não houver outro admin, vale um **procedimento documentado no servidor** (comando de redefinição), sem e-mail.
- Os dois apps enviam **cabeçalhos de versão e de tipo de app** desde a Fase 0.
- O cabeçalho de tipo de app é **conveniência de experiência, não segurança**: a proteção real é a permissão por perfil nas rotas, e nenhum código pode depender só dele.
- Permissões por perfil **validadas na API**; rotas de dona e de vendedor separadas.
- Limite de tentativas de login: **bloqueio de 15 minutos depois de 5 erros seguidos**. Os dois números são configuráveis.
- Banco central **nunca exposto** à internet, só a API (HTTPS).
- Banco local **criptografado**, porque notebook de vendedor pode ser perdido ou roubado.
- **Bloqueio remoto**: a dona desativa o usuário ou dispositivo e o app apaga os dados locais no próximo contato com o servidor.
- **Auditoria** de preço, estoque, desconto, cancelamento, reembolso, autorização de exceção e liberação de restrição.
- O **preço de custo** e dados de outros vendedores nunca saem do servidor para o app do vendedor.
- **Backup diário** do PostgreSQL (`pg_dump`) enviado para fora da VPS (S3 ou Backblaze), com **teste periódico de restauração**.
- Segredos fora do repositório: `.env` local (no `.gitignore`), **GitHub Secrets** no CI e `.env.example` versionado.
- Servidor: Nginx com HTTPS, firewall, atualizações, usuário do banco sem privilégios de superusuário.
- **Assinatura de código** do instalador (certificado) para evitar o alerta do SmartScreen do Windows.
- Compatibilidade de versão: a API **recusa apps muito antigos**, para não gravar dados errados.

## 12. Relatórios

**Princípio:** toda operação do sistema tem pelo menos um relatório, e todo número é **rastreável** até os registros que o formaram.

**Padrão para todos:**

- Filtros: período, semana, vendedor, praça, cliente, produto, categoria e situação.
- Exportação em **PDF, Excel e CSV**, com cabeçalho (loja, filtros usados, data, usuário que gerou) e totais no rodapé.
- **Detalhamento:** clicar no total abre os registros que o compõem.

Os nomes de relatório desta seção são descrição interna. O nome de tela de cada relatório segue `docs/glossario-interface.md` e é definido na tarefa daquele relatório.

### App da dona

| Área | Relatórios |
| --- | --- |
| Cadastros | Vendedores (limite, restrição, situação), clientes, fornecedores, produtos, produtos sem código de barras, códigos duplicados ou em conflito, resultado das importações, aniversariantes |
| Estoque | Posição atual, movimentações por período, estoque mínimo, produtos parados, curva ABC, inventário e divergências, mercadoria em maletas por vendedor |
| Compras | Por fornecedor e período, histórico de custo por produto |
| Maletas | Remessas por vendedor e semana, **nota de remessa (PDF)**, saldo atual por maleta, maletas em aberto e atrasadas, reposições |
| Vendas | Por vendedor, praça, cliente, produto, categoria e período; ranking; ticket médio; vendas canceladas |
| Devolução e troca | Por vendedor e motivo, taxa de devolução, peças com defeito, créditos em aberto e utilizados, reembolsos, autorizações de exceção |
| Acerto | Por vendedor e semana, faltas e pendências, descontos concedidos, esperado x contado |
| Financeiro | Contas a receber e a pagar, fluxo de caixa, inadimplência, cheques (a compensar, devolvidos), recebimentos por forma de pagamento, DRE simplificado, lucro por produto e por vendedor |
| Comissões | Por vendedor e semana, pagas e a pagar |
| Controle | Auditoria, sincronizações e dispositivos, acessos |
| Painel semanal | Totais por semana no estilo da tela de referência (blocos Consignado, Reposições, Cobrança, Acerto, Vendas, Trocas, Total e Total/Acerto) |

### App do vendedor (só dados dele, sem custo)

Minha maleta (saldo e peças); minhas vendas por período, cliente e produto; meus clientes e valores a receber deles; minhas devoluções e trocas; meu acerto da semana e minha comissão; recebimentos por forma de pagamento.

Como o app é offline, o relatório sai do banco local com o aviso: **Dados até dd/mm às hh:mm. N itens aguardando envio** (ou **N vendas aguardando envio**, quando só houver vendas).

## 13. Garantia de que tudo esteja correto

1. **Dado imutável e saldo por eventos.** Estoque, saldo de maleta e financeiro são somas de registros que não se editam; corrigir é estornar.
2. **Uma única fonte de cálculo no servidor.** Relatórios leem de consultas validadas, sem fórmulas duplicadas em telas diferentes.
3. **Conciliações automáticas diárias** com alerta à dona:
   - estoque central + peças em maletas = comprado − vendido − perdido;
   - saldo da maleta = enviado − vendido − devolvido − perdido ± trocas;
   - soma das vendas = contas a receber geradas;
   - total recebido = lançamentos de caixa;
   - total do acerto = vendas − descontos − reembolsos − comissão.
4. **Testes automatizados** para cada relatório, com massa de dados conhecida e resultado calculado à mão, rodando no CI a cada Pull Request (backend com PostgreSQL via Testcontainers).
5. `NUMERIC(14,2)`, sem float, com arredondamento HALF_UP e escala 2 (regra única, §7.5).
6. Datas em UTC, exibição em horário de Brasília, **semanas fechadas imutáveis**.
7. **Validação com o cliente:** comparar os relatórios com as planilhas dele numa semana real antes da entrega.

Cada fase só é considerada pronta quando seus **relatórios e testes de conferência** estão prontos. Relatório não é etapa final.

## 14. Repositório, GitHub e VS Code

**Monorepo único no GitHub (privado):**

```
corona-semijoias/
├── .github/            workflows (ci, release) e modelo de PR
├── .vscode/            launch, tasks, extensions, settings
├── .claude/            agents, skills, commands, settings do Claude Code
├── backend/            Spring Boot + Flyway + testes
├── apps/owner-app/     Electron + React (somente online)
├── apps/seller-app/    Electron + React + SQLite + sync
├── packages/ui | shared | api-client-dona | api-client-vendedor
├── docs/               especificação, diagramas, decisões (ADRs)
├── docker-compose.yml
├── CLAUDE.md
└── README.md
```

Os apps compartilham `packages/` (componentes, clientes da API gerados do OpenAPI, validações de CPF e EAN-13, formatadores), mas **o app do vendedor nunca importa código exclusivo da dona**: ele depende só de `api-client-vendedor` e nunca de `api-client-dona`.

**Fluxo Git:**

- `main` sem proteção técnica (repositório privado no plano gratuito do GitHub). Regra de disciplina: sem push direto, só Pull Request com CI verde, conferido pela orquestradora.
- Branches curtas. O prefixo da branch é o tipo do Conventional Commits do trabalho (`feat/`, `fix/`, `docs/`, `chore/`, `ci/`, `test/`, `refactor/`, `build/`, `perf/`, `style/`). Sem `develop`.
- **Conventional Commits** (`feat(remessa): bloquear venda acima do saldo`).
- Cada tarefa é entregue pela orquestradora como prompt. Uma branch e um Pull Request por tarefa.
- Versionamento semântico; tag na `main` gera os instaladores; release-please ou semantic-release para changelog.
- **Dependabot** ativo.

**CI (GitHub Actions) em todo PR:** backend (build, testes, validação das migrações Flyway); frontend (lint, typecheck, testes, build dos dois apps); varredura de dependências.

**VS Code:** instalar JDK 21, Maven wrapper, Node LTS, Git e Docker Desktop. Extensões: Extension Pack for Java, Spring Boot Extension Pack, Docker, ESLint, Prettier, GitLens, GitHub Pull Requests, um cliente de banco (PostgreSQL e SQLite) e Thunder Client ou REST Client. A pasta `.vscode/` versionada traz `launch.json` (debug da API e do Electron), `tasks.json` (`db:up`, `api:run`, `owner:dev`, `seller:dev`), `extensions.json` e `settings.json`. Fluxo diário: `docker compose up -d`, rodar a API pelo Boot Dashboard e `npm run dev:owner` ou `npm run dev:seller`.

**Distribuição:** instaladores e atualizações hospedados na VPS ou em bucket (não no GitHub Releases, por causa do repositório privado). Assinatura de código no orçamento.

## 15. Fases de entrega

| Fase | Entrega | Resultado |
| --- | --- | --- |
| **0. Fundação** | Monorepo, VS Code, Docker, CI, Flyway, login JWT, perfis, `CLAUDE.md` | Projeto roda do zero com um comando |
| **1. Cadastros (dona)** | CRUD de fornecedor, produto (com `produto_codigos`, leitura de código, EAN-13), cliente, vendedor completo (limite, restrição, comissão, praça, abas da referência), pracas, semanas, relatórios de cadastro | Base de dados da loja |
| **2. Estoque e compras** | Entrada de compra com leitor, cadastro rápido, **importação por planilha**, movimentações, estoque mínimo, **inventário por leitor**, relatórios de estoque | Estoque central confiável |
| **3. Maleta** | Montagem por leitor, limite e restrição, preço congelado, reposição, **PDF da nota de remessa**, relatórios de maleta | A dona entrega a maleta |
| **4. App do vendedor online** | Login, conferência, venda, clientes, consulta, relatórios próprios | Vendedor vende com internet |
| **5. Offline e sync** | SQLite criptografado, outbox, push e pull, indicador, bloqueio remoto, testes de sync | Vendedor vende sem internet |
| **6. Devolução e troca** | Garantia, reembolso, crédito, autorização da dona, relatórios | Pós-venda completo |
| **7. Acerto e financeiro** | Acerto semanal, comissões, contas a receber e a pagar, caixa, cheques, relatórios financeiros | Fechamento semanal |
| **8. Painel, relatórios avançados e conciliações** | Gerenciamento semanal (referência), curva ABC, DRE, conciliações automáticas, auditoria | Visão gerencial e confiança nos números |
| **9. Entrega** | Instaladores, auto-update, assinatura, deploy na VPS, HTTPS, backup e teste de restauração, **homologação do leitor com etiquetas reais**, validação com dados reais, treinamento | Sistema em produção |

As fases 1 a 4 funcionam **online, mas já preparadas para sync** (UUID, `deleted_at`, vendas imutáveis, idempotência). O offline entra por cima na fase 5, com esforço estimado de **20% a 30% a mais** de trabalho, concentrado em testes.

## 16. Fora do escopo (por enquanto)

Nota fiscal eletrônica (a nota de remessa é só interna), loja virtual, aplicativo de celular, multiloja, integração com maquininha de cartão, modo offline no app da dona, venda do estoque geral pelo vendedor. Qualquer um pode virar fase futura.

## 17. Pendências para confirmar com o cliente

1. DX = valor consignado, DV = devolvido, VD = vendido (confirmar o significado na referência).
2. **Modelo do leitor** de código de barras (não comprar antes de testar com etiquetas reais), com ou sem fio.
3. Fornecedores realmente usam **EAN-13**? Obter fotos ou amostras de 3 a 4 etiquetas de fornecedores diferentes.
4. Prazos de garantia (arrependimento e defeito) e se variam por categoria.
5. Limite de desconto, limite de reembolso semanal e valor que exige aprovação da dona.
6. Percentual de comissão (único ou por vendedor, por categoria?) e base de cálculo exata.
7. Quando a diferença de uma troca vira crédito: pode ser devolvida em dinheiro?
8. Dia de início e fim da semana de acerto e como numerar as semanas.
9. Quantos vendedores e quantos produtos aproximadamente (dimensionar a VPS).
10. Quem paga VPS, domínio e certificado de assinatura de código.
11. Existe alguma planilha atual do cliente para usar na validação dos relatórios e na migração de cadastros?
12. **Entrada:** o que significa na tela de referência? Valor pago pela vendedora ao levar a maleta, abatido no acerto, ou entrada de venda parcelada? É obrigatória? Como se calcula e o que acontece no acerto? Precisa de resposta antes da Fase 3.

## 18. Identidade visual: Corona Semijoias

A loja se chama **Corona Semijoias**. O nome aparece nos dois apps, no instalador, na nota de remessa e em todos os relatórios.

**Atenção ao logotipo:** a imagem enviada pelo cliente traz a palavra escrita **SEMJOIAS**, mas o correto é **SEMIJOIAS**. Esse arquivo **não deve ser usado em produção**. Antes de entrar no projeto, o logotipo precisa ser corrigido (pelo designer ou regerado) e entregue nos formatos abaixo. Enquanto isso, usar o texto "Corona Semijoias" como substituto provisório.

**Descrição da marca:** perfil feminino de rosto sereno, com tiara de pedras e brinco de gota, desenhado em linhas douradas finas, dentro de um círculo fino. A palavra CORONA em letras serifadas elegantes e de alto contraste, com a palavra semijoias espaçada abaixo, separada por um filete com uma estrela de quatro pontas. Fundo bege champanhe suave com brilho dourado.

**Arquivos necessários (pedir ao cliente ou designer):**

- Logotipo completo corrigido em **SVG** (ou PNG de alta resolução com fundo transparente).
- Versão **horizontal** (para cabeçalho de relatório e nota de remessa).
- Versão **só o ícone** (perfil com a tiara), para ícone do app, instalador e favicon.
- Versão **monocromática** (preto e branco) para impressão simples, sem cor e sem brilho.
- Ícones do Windows em `.ico` (16, 32, 48, 64, 128 e 256 px), um para cada app, com identificação visual diferente entre o app da dona e o do vendedor.

**Paleta (aproximada, extrair os valores exatos do logotipo corrigido):**

| Uso | Cor |
| --- | --- |
| Destaques e detalhes | Dourado (cerca de `#C9A24B`) |
| Fundo de telas de marca (login, abertura) | Bege champanhe (cerca de `#F3E2C7`) |
| Fundo das telas de dados | Branco ou bege muito claro |
| Texto principal | Marrom escuro ou grafite (alto contraste) |

**Regras de uso na interface:**

- Dourado sobre bege tem **pouco contraste**: usar para detalhes, ícones, bordas e títulos grandes, **nunca para texto pequeno** de tabelas, formulários ou relatórios.
- Telas de trabalho (grades, formulários, leitor de código de barras) devem priorizar leitura rápida: texto escuro sobre fundo claro. A identidade da marca aparece na tela de login, na abertura (splash), no cabeçalho e nos botões principais.
- Tipografia: fonte serifada elegante só em títulos e momentos de marca (por exemplo Cormorant Garamond ou Playfair Display); fonte sem serifa legível (por exemplo Inter) em tabelas, formulários e relatórios.
- Os estados de erro e sucesso do leitor (cor e som) **não** seguem a paleta da marca: precisam ser claramente verde e vermelho.

**Onde a marca aparece:**

- Nome dos aplicativos: **Corona Gestão** (app da dona) e **Corona Vendas** (app do vendedor), com ícones diferentes.
- Tela de login e abertura dos dois apps, com o ícone da janela e da barra de tarefas.
- Instalador `.exe` e tela de atualização automática.
- Cabeçalho de todos os relatórios em PDF, Excel e CSV (nome da loja, logotipo horizontal), e da nota de remessa.
- Etiquetas internas e documentos impressos, usando a versão monocromática.
- Nome da loja guardado em `configuracoes` (por exemplo `nome_loja = Corona Semijoias`), para não ficar fixo no código.

## 19. Facilidade de uso (requisito principal)

A dona não é da área de tecnologia. **Se ela não consegue usar sozinha, o sistema não está pronto**, mesmo que esteja tecnicamente correto. Isto vale para o app da dona e, ainda mais, para o app do vendedor.

**Critério de aceite geral:** sem treinamento longo e sem ajuda, a dona consegue (1) cadastrar um produto com código de barras e foto, (2) montar e enviar uma maleta, (3) fazer o acerto semanal e (4) tirar o relatório de vendas da semana.

### Princípios

1. **Linguagem da loja, não do programador.** Usar: maleta, acerto, vendedor, cliente, peça, semana, garantia. Evitar na tela: remessa (só no PDF), movimentação, outbox, sync, payload, UUID, status técnicos. Na tela, sempre "vendedor" e "ler o código" (nunca "bipar"). Usar "produto" no cadastro, na consulta e nos relatórios, e "peça" na maleta, na venda, na troca, na devolução e na leitura do código. Manter um **glossário de termos da interface** em `docs/glossario-interface.md` e usá-lo em todas as telas.
2. **Uma tarefa principal por tela.** Poucos campos visíveis; o que é raro fica em "Mais opções".
3. **Tela inicial com tarefas, não com menus.** Botões grandes: Montar maleta, Receber compra, Fazer acerto, Cadastrar produto, Relatórios. Abaixo, um bloco **"O que precisa da sua atenção"**: acertos pendentes, devoluções para autorizar, estoque baixo, maletas atrasadas, envios com problema.
4. **Fluxos guiados passo a passo** para o que é complexo (montar maleta, acerto, entrada de compra), com indicação de progresso e uma **tela de conferência antes de confirmar**.
5. **Confirmação clara em ações sem volta** (enviar maleta, fechar semana, aprovar reembolso), com resumo do que vai acontecer. Como vendas e movimentos são imutáveis, o texto explica "cancelar" como um novo lançamento e não como apagar.
6. **Mensagens de erro em português simples, dizendo o que fazer.** Exemplo: "Esta peça não está na maleta da Maria. Confira o código ou escolha outra peça." Nada de códigos técnicos ou mensagens em inglês.
7. **Valores padrão inteligentes e menos digitação:** semana atual já selecionada, campos com busca ao digitar, máscaras de CPF, telefone, CEP e valores em reais, cadastro rápido dentro do fluxo (por exemplo, criar cliente sem sair da venda).
8. **Leitura confortável:** fonte mínima de 14 a 16 px, botões grandes, bom contraste, opção de aumentar o zoom. Mouse e leitor de código de barras são os principais meios de entrada; atalhos de teclado são extras.
9. **Ajuda dentro do sistema:** ícone "?" em cada tela com explicação curta, dicas na primeira vez que a tela é aberta e um **guia "Como fazer"** com os 10 fluxos mais usados, em texto e imagens (vídeos curtos opcionais).
10. **Relatórios fáceis de achar:** catálogo com nomes em linguagem simples e uma frase explicando o que cada um mostra, filtro padrão na semana atual e exportação em um clique.
11. **Estado de conexão em palavras comuns** (app do vendedor): "Sem internet. Suas vendas estão salvas e serão enviadas quando você conectar." Nunca "fila de sincronização".
12. **Consistência:** mesma posição de botões (Salvar, Cancelar, Voltar) e mesmos nomes em todas as telas. A organização das telas da referência (barra de botões no topo, filtros, abas) é mantida porque a equipe já a conhece.

### App do vendedor: ainda mais simples

Tela inicial com quatro botões grandes: **Vender**, **Minha maleta**, **Meus clientes**, **Meus números** (vendas, comissão, acerto). A venda é o fluxo mais enxuto do sistema: bipar, escolher o cliente, forma de pagamento, concluir.

### Como garantir que ficou fácil

- **Telas aprovadas antes de programar:** para cada fase, mostrar à dona o desenho (protótipo ou captura) das telas principais e ajustar antes de implementar.
- **Teste de uso com a dona** ao fim de cada fase: ela executa tarefas reais enquanto alguém observa, sem ajudar. Anotar onde hesitou ou errou e corrigir.
- **Teste com um vendedor real** do app, de preferência numa situação sem internet.
- **Revisão de textos** da interface por alguém da loja.
- Registrar os problemas de uso encontrados em `docs/PROBLEMAS-DE-USO.md`.

## 20. Fotos de produto

Toda vez que um produto aparece por **leitura de código de barras** ou por **pesquisa**, a **foto aparece junto**. A foto ajuda a conferir se a peça bipada é mesmo a peça certa, evita erro de etiqueta trocada e ajuda o vendedor a mostrar ao cliente.

### Onde a foto aparece

| Situação | Comportamento |
| --- | --- |
| Bipou o código (venda, maleta, conferência, inventário, devolução, acerto, compra) | **Foto grande da última peça lida** ao lado da lista, mais miniatura em cada linha dos itens |
| Pesquisa por nome, referência ou código | Lista de resultados com **miniatura** em cada linha; ao selecionar, foto em tamanho maior |
| Consulta de produto | Foto principal grande, com as demais ao lado; clique amplia (zoom) |
| Cadastro e edição de produto (dona) | Área de fotos com a principal em destaque |
| Nota de remessa em PDF | Miniatura ao lado de cada item (opção que a dona liga ou desliga) |
| Relatório "Catálogo de produtos" | Foto, nome, código, preço e saldo; exportável em PDF para mostrar a clientes |

- **Sem foto:** mostrar uma imagem padrão "sem foto" com a marca, nunca um espaço quebrado, e listar no relatório **produtos sem foto**.
- **Feedback imediato:** a foto aparece junto com o som e a cor de sucesso do leitor. Código não encontrado não mostra foto, só a mensagem.
- Se a leitura soma uma peça já bipada, a foto não pisca nem recarrega.

### Cadastro das fotos (app da dona)

- Cada produto tem **uma foto principal e até 5 fotos** no total.
- Formas de inserir: escolher arquivo, **arrastar e soltar**, **colar** da área de transferência e, opcionalmente, **tirar pela webcam**.
- **Importação em massa:** pasta com fotos nomeadas pelo código de barras (por exemplo `7891234567895.jpg`). O sistema associa cada foto ao produto, e a prévia mostra as que não encontraram produto e os produtos que ficaram sem foto.
- Formatos aceitos: JPG, PNG e WebP. **Limite de 10 MB por arquivo.** Recusar arquivo que não seja imagem, com mensagem clara.
- **Processamento no servidor:** corrigir a rotação, remover dados EXIF (como localização), gerar três tamanhos, e guardar o original reduzido:
  - miniatura (cerca de 150 px), para listas;
  - média (cerca de 600 px), para o painel da peça lida;
  - grande (cerca de 1600 px), para o zoom.
- Fotos guardadas **como arquivos** (volume da VPS ou bucket S3 ou Backblaze), **nunca como binário dentro do PostgreSQL**. O banco guarda só caminho, hash e tamanho. O **backup diário inclui as fotos**.
- Acesso às fotos **somente autenticado** (URL assinada ou token), nunca pública.
- **Somente a dona** envia, troca ou apaga fotos. O vendedor não cadastra produto, então também não mexe nelas.

### Modelo de dados

```
produtos_fotos (
  id uuid PK, produto_id,
  arquivo_hash,          -- identifica o conteúdo (evita baixar de novo)
  principal boolean, ordem int,
  largura int, altura int, tamanho_bytes int,
  + campos de sync (updated_at, version, deleted_at, sync_seq)
)
```

Substitui o campo simples `foto` da tabela `produtos`. Os três tamanhos são derivados do arquivo e servidos pelo hash.

### Fotos no modo offline (app do vendedor)

- As fotos precisam estar **no computador do vendedor**, senão a regra "toda leitura mostra a foto" falha sem internet.
- **O que baixar:** miniatura e tamanho médio de **todos os produtos das maletas do vendedor**, mais os produtos que ele pode consultar. O tamanho grande é baixado sob demanda, com internet.
- **Quando:** logo depois de receber a maleta, em segundo plano. A conferência da maleta só é liberada quando as fotos principais já baixaram, ou mostra o aviso "Baixando fotos: 45 de 120".
- Guardar em **cache em disco** (pasta do app, fora do banco), referenciado por hash. Se a dona trocar a foto, o hash muda e o app baixa a nova e apaga a antiga. Foto de produto que saiu das maletas é removida do cache após o acerto.
- Se uma foto não estiver disponível, mostrar a imagem "sem foto" e **nunca travar a venda**.
- O pull de sincronização traz **metadados** das fotos (hash, ordem); o download das imagens é uma fila separada, com retomada se a conexão cair.
- Dimensionar: 5 fotos por produto e 500 produtos em maleta, em tamanho miniatura e médio, cabem em algumas dezenas de MB. Validar com fotos reais.

### Desempenho

- Listas longas carregam miniaturas sob demanda (carregamento preguiçoso e virtualização da lista).
- A foto da peça lida aparece **antes de 100 ms** no vendedor (cache local) e sem travar o leitor.
- Fotos grandes da dona são cacheadas no navegador do Electron para não baixar de novo.

### Testes

Importação com nome de arquivo inválido, foto duplicada, arquivo corrompido, foto de 10 MB, troca de foto com o vendedor offline, venda sem foto disponível, queda de conexão durante o download das fotos e recuperação ao reconectar.

## 21. Impacto nas fases e novas pendências

**Ajustes nas fases**

- **Fase 0:** criar `docs/glossario-interface.md`; definir o padrão visual e os componentes base (botões, tabela, painel de peça lida com foto) no `packages/ui`.
- **Fase 1:** cadastro de produto já com fotos (upload, processamento, três tamanhos, importação em massa) e relatório de produtos sem foto. Primeira rodada de telas mostradas à dona.
- **Fase 2:** entrada de compra e inventário com o painel de foto da peça lida.
- **Fase 3:** montagem da maleta com foto; PDF da nota de remessa com miniaturas opcionais.
- **Fase 4:** app do vendedor online com foto na venda e na conferência.
- **Fase 5:** download de fotos em segundo plano, cache em disco, retomada e limpeza.
- **Todas as fases:** teste de uso com a dona ao final.

**Novas pendências para o cliente**

1. O vendedor deve ver **só as peças da própria maleta** ou o **catálogo completo** (para mostrar outras opções ao cliente)? Isso define quantas fotos baixar.
2. De onde vêm as fotos dos produtos: o fornecedor manda, a dona fotografa ou ambos? Qual a qualidade e o tamanho típico?
3. Quantos produtos e quantas fotos por produto, em média, para dimensionar o armazenamento da VPS?
4. A nota de remessa deve ter foto dos itens?
5. Quem da loja vai participar dos testes de uso (a dona e pelo menos um vendedor)?

## 22. Histórico de mudanças

- **v1.0, 08/11/2026:** versão inicial.
- **v1.1, 08/11/2026:** decisões da Fase 0.
  - §3: perfis extensíveis sem refatoração; cada app aceita só o seu perfil, e a API recusa o perfil incompatível no login.
  - §5: API em Spring Boot 4.1.x, com a versão exata fixada no ADR-0001.
  - §6: nova premissa 15, um aparelho por vendedor; a sessão guarda o identificador do aparelho desde a Fase 0 e o bloqueio de um segundo aparelho fica para a Fase 5.
  - §7.5 e §13: regra única de arredondamento HALF_UP com escala 2.
  - §9: tempo máximo offline padrão de 10 dias, configurável.
  - §9 e §10: o pull usa um limite seguro que não perde registros de transações que confirmam fora de ordem (ADR-0002).
  - §11: sessão longa de 30 dias com rotação; senha temporária com troca obrigatória no primeiro acesso; cabeçalhos de versão e de tipo de app, sendo o de tipo apenas conveniência e não segurança.
  - §14: a `main` deixa de ser "main protegida" (sem proteção técnica no plano gratuito; disciplina de PR com CI verde conferido pela orquestradora); cada tarefa chega como prompt, com uma branch e um PR, sem issues do GitHub e sem "Closes #"; raiz `corona-semijoias/` (antes `loja-semijoias/`); `packages/` com um cliente de API por app; modelo de PR, sem modelos de issue; o app do vendedor depende só de `api-client-vendedor`.
  - §19: problemas de uso registrados em `docs/PROBLEMAS-DE-USO.md`.
- **v1.2, 09/11/2026:** §14, prefixos de branch seguem os tipos do Conventional Commits.
- **v1.3, 09/11/2026:** decisões do responsável sobre as dúvidas do glossário.
  - §18: nomes dos aplicativos, Corona Gestão (dona) e Corona Vendas (vendedor), com ícones diferentes.
  - §8: o aviso de leitura truncada passa de "bipe novamente" para "leia o código de novo".
  - §9 e §11: tentativas de login antes do bloqueio (padrão 5) e tempo de bloqueio (padrão 15 minutos), configuráveis; senha nova com pelo menos 8 caracteres, letras e números, diferente da temporária; redefinição da senha da própria dona; prazo offline esgotado sem perda de vendas.
  - §12: o aviso de dados do app do vendedor passa de "última sincronização… operações pendentes" para "Dados até dd/mm às hh:mm. N itens aguardando envio"; os nomes de relatório são descrição interna.
  - §17: nova pendência 12, "Entrada".
  - §19.1: na tela, "vendedor", "ler o código" e a regra de "produto" e "peça".
  - §19.3: "divergências de sincronização" passa a ser "envios com problema".
