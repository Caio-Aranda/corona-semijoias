# Especificação completa: Sistema de Semijoias Consignadas

Documento-base para iniciar o desenvolvimento no Claude Code. Consolida todas as decisões do planejamento. Versão 1.32, 23/11/2026.

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
| DX | **Enviado** | O que saiu para o vendedor nas maletas da linha (nova e reposições), pelo preço congelado na maleta (ADR-0015, item 5; tarefa 8.7). |
| DV | **Devolvido** | O que voltou à loja no acerto (peças boas e com defeito), pelo preço da maleta. Não é a devolução do cliente, que abate o vendido. |
| VD | **Vendido** | O vendido líquido: vendas menos devolução e troca do cliente. Para a linha acertada é o do acerto gravado; antes do acerto, o das vendas ativas até agora. Em peças, enviado = vendido + devolvido + falta em cada maleta acertada (conferido por teste). |

### Telas da referência (para inspirar o layout)

**Gerenciamento Semanal:** barra de botões no topo (Novo Pedido, Cobrança, Reposição, Troca, Vendedoras, Estoque), filtros (Semana nº, Promotor, Praça, Situação) e duas abas.

- Aba **Pedidos**, com grade: Nota nº, Vendedora, Dt Cobrança, Valor DX, Valor DV, Valor VD, Comis %, Comissão, Valor Líq, Situação.
- Aba **Totais**, em blocos: Consignado/Novos Pedidos (novas notas, total qtd, total, entrada), Consignado/Reposições (notas repostas, qtd, total), Consignado/Cobrança (notas cobradas, remarcadas, qtd e total de devolvidos e vendidos), Consignado/Acerto (descontos, total comissão, total líquido, pendência paga, pendências, desconto de entrada), Vendas (vendas, qtd, total, descontos, líquido), Vendas/Trocas (trocas em aberto, utilizadas, total de trocas), Total (total de notas, estoque, total de vendas, descontos, líquido) e Total/Acerto (dinheiro, cheque, entrada, Pix, boleto, total recebido). **No sistema novo não há entrada nem cheque** (ADR-0012, aceito em 13/11/2026): "entrada", "desconto de entrada" e "cheque" ficam aqui só como registro da tela de referência e não viram campo, total nem forma de pagamento.

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
| API | Java 21 + Spring Boot 4 (a versão menor mais recente com suporte; versão exata no ADR-0001), Flyway, OpenAPI/Swagger |
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
8. **Comissão por meta** (resposta da dona em 13/11/2026; substitui o percentual por vendedor): calculada no acerto, sobre o valor de cada venda já com o desconto. Se o vendido da semana, somado em Guarani, chega a **700.000 Gs**, o percentual do acerto é **40%**; abaixo, **30%**, sempre **sobre o total**. A meta e os dois percentuais são os mesmos para todos os vendedores e a dona pode mudar. O `comissao_percentual` do cadastro do vendedor deixa de ser usado no cálculo. Regras completas na §7.4 e no ADR-0012 (item 5); entra na Fase 7.
9. Acerto **semanal**.
10. A **nota de remessa não tem validade fiscal**: é um documento interno em PDF.
11. A loja imprime as próprias etiquetas de joia (código curto da loja, não EAN-13); cada peça tem também o código do fornecedor. EAN-13 é opcional (padrão seguro, a confirmar com a dona; ADR-0007).
12. A dona cadastra os códigos no sistema: o código da loja, o do fornecedor e, se houver, o EAN-13.
13. Roda só em Windows.
14. Assinatura de código e VPS entram no orçamento do cliente.
15. Cada vendedor usa o **próprio notebook**, um aparelho por vendedor. Desde a Fase 0 a sessão guarda o identificador do aparelho. Impedir um segundo aparelho fica para a Fase 5 (tabela `dispositivos`).

## 7. Regras de negócio

### 7.1 Maleta (remessa)

- Ciclo de estados: `RASCUNHO → ENVIADA → CONFERIDA → EM_ACERTO → ACERTADA` (mais `CANCELADA`).
- A dona monta a maleta **online**, bipando os produtos. O estoque central é baixado ao enviar.
- **Preço congelado** no envio (`preco_remessa`). O vendedor vende por esse preço, mesmo que a dona mude o preço depois.
- **Bloqueios:** o valor que o vendedor tem em mãos não pode passar o **limite de consignação** dele; vendedor com **restrição** ou inativo não recebe maleta; produto sem saldo central não entra.
- **Limite de consignação** (decisões do responsável de 11/11/2026; ADR-0010, item 7):
  - é **campo obrigatório** do cadastro do vendedor: a dona preenche um valor em reais ou marca **"Sem limite"**, nunca os dois. **Zero vale zero** (o vendedor não leva peça nenhuma); não quer dizer "sem limite". "Sem limite" nunca bloqueia.
  - a conta é pelo **preço de venda congelado** (`preco_remessa`), nunca pelo custo nem pelo preço de hoje: soma, nas maletas enviadas e ainda não acertadas, de `saldo atual × preco_remessa`, **mais** o valor da maleta que está sendo enviada. No limite exato passa; um centavo acima é recusado, dizendo o limite, o que já está com o vendedor e quanto falta.
  - a **reposição entra na mesma conta**: conta tudo o que o vendedor tem em mãos.
  - vendedor com o **limite não definido** (nenhuma das duas escolhas; só os cadastros antigos, que estavam com zero) **não recebe maleta**, nem rascunho, até a dona definir. A lista de vendedores mostra, para cada um, o valor, "Sem limite" ou "Limite não definido".
- O vendedor sincroniza, **confere** bipando as peças recebidas e registra divergências.
- **Saldo por eventos**, nunca por número digitado: `saldo = enviada − vendas − devoluções à dona − perdas − avarias + estornos de venda + devoluções de clientes boas + entradas de troca − saídas de troca`.
- **Reposição** é uma maleta complementar criada online. Se faltar peça no meio do caminho, a dona cria nova remessa.
- Gerar **nota de remessa em PDF** (itens, quantidades, preços, vendedor, semana, assinatura).
- Cada maleta fica vinculada a **um dispositivo** só, para evitar que dois aparelhos vendam a mesma peça.

### 7.2 Venda (app do vendedor, offline)

> **Desde 18/11/2026 (v1.29), a §23 vale sobre esta seção no Corona Vendas.**

- O vendedor bipa a peça, escolhe o cliente e a forma de pagamento (à vista ou a prazo/parcelado).
- Bloqueios locais: produto fora da maleta ("produto não consta na sua remessa"), saldo zerado, desconto acima do limite definido pela dona.
- Desconto só até o limite configurado, validado também no servidor.
- **Venda finalizada é imutável.** Erro vira cancelamento ou estorno, um novo registro.
- O cliente pode pagar **na hora** ou ser **fiado/parcelado**. No fiado, o vendedor controla as contas a receber dos próprios clientes.
- Pagamentos do cliente são **eventos** (data, valor, forma). Recebimento posterior é um registro novo.
- **Venda a prazo (tarefa 7.3).** O que falta pagar (total menos o pago na hora) vira de 1 a 24 parcelas mensais, sem valor mínimo, sem multa e sem juros; o servidor calcula os valores (as primeiras iguais, a última fecha o centavo) e o vencimento de cada uma a partir do primeiro vencimento. Sem parcelamento informado, vira uma parcela com vencimento um mês depois da venda. O recebimento posterior (dinheiro ou transferência) é um registro novo, distribuído pelo servidor nas parcelas abertas da venda por ordem de vencimento, e não passa do que falta. O vendedor estorna o recebimento no mesmo dia; depois, só a dona. O estorno da venda cancela o que está em aberto.

### 7.3 Devolução e troca (app do vendedor, offline)

> **Desde 18/11/2026 (v1.29), a §23 vale sobre esta seção no Corona Vendas.**

Três casos:

| Caso | O que acontece | Efeito na maleta |
| --- | --- | --- |
| Devolução do cliente | Devolve peça (total ou parcial da venda) | Peça boa de maleta ainda aberta volta ao saldo; a venda **não muda** (a devolução é um registro novo) |
| Troca | Devolve A e leva B (devolução + venda nova, ligadas) | A entra, B sai, com acerto da diferença |
| Devolução à dona | Vendedor devolve peças não vendidas ou com defeito | Sai do saldo e volta ao estoque central no acerto |

Regras:

- **Sempre ligada à venda original**, encontrada bipando a peça ou escolhendo a venda. Só aceita devolver até a quantidade vendida, pelo preço pago. O app manda as peças pelo **produto** (a venda tem uma linha por produto), com quantidade e estado; nunca pelo id da linha da venda, que sem internet ainda é provisório.
- **Motivo obrigatório:** arrependimento, defeito, tamanho, outro. Com "outro", a observação é obrigatória; com "defeito", todas as peças devolvidas são `DEFEITO` (o prazo maior só vale para peça com defeito). Foto opcional.
- **Estado da peça:** `BOA` volta ao saldo vendável da maleta original, **se ela ainda está aberta**; `BOA` de maleta já acertada e toda peça `DEFEITO` viram **peça para a loja**: não voltam ao saldo do vendedor e são esperadas à parte no próximo acerto (ADR-0014, item 7). Nenhuma maleta acertada recebe movimento novo.
- **A venda original não muda** (continua concluída). O quanto já foi devolvido é a soma das devoluções ativas dela; o total devolvido por linha nunca passa do vendido. Venda com devolução ativa não pode mais ser estornada (só cancelar a devolução antes).
- **Valor devolvido** = o preço pago, por unidade (total da linha ÷ quantidade, com a sobra de centavos nas últimas unidades). O app nunca manda valor: o servidor calcula.
- **Garantia:** o cliente pode **receber o dinheiro de volta (reembolso) ou trocar**, desde que **dentro do prazo**. Dois prazos configuráveis pela dona, contados em **dias do calendário de Brasília** a partir do dia da venda: `prazo_arrependimento_dias` (padrão 45, resposta da dona em 13/11/2026, que pensa em 2 meses mais adiante; vale para arrependimento, tamanho e outro) e `prazo_defeito_dias` (padrão 45, igual ao de arrependimento até a dona responder; vale para defeito). Ambos chegam ao app no bloco de parâmetros do pull.
- **Fora do prazo:** o app bloqueia e oferece só o pedido de **autorização da dona**, que é resolvido online. O vendedor não conclui sozinho. A autorização aprovada vale **7 dias** e só serve para uma devolução.
- A validação ocorre no app (offline) e de novo no servidor. Se a hora do aparelho divergir, **o servidor decide**; mas o que já aconteceu no balcão **nunca é recusado em silêncio**: a devolução que o app achou dentro do prazo e o servidor acha fora entra **para revisão da dona** (ela mantém, ou não aceita e o valor vira pendência do vendedor no próximo acerto). Recusa só para o impossível (venda de outro vendedor, quantidade acima da vendida).
- **Diferença na troca:** B vale mais, o cliente paga a diferença (à vista ou a prazo); B vale menos, a diferença vira **crédito** do cliente (diferença = total de B − valor em crédito de A: se A era a prazo, o que A ainda devia é abatido nas parcelas de A e não conta como pago por B) (usado em compra futura; padrão até a dona responder, dúvida D4). A troca é **uma operação só** (tudo ou nada) e não tem tabela de itens própria: as linhas ficam na devolução e na venda nova.
- **O dinheiro da troca passa pelo crédito** (um caminho só): na venda de A, o valor devolvido vira um `REEMBOLSO` na forma `CREDITO` (nenhum dinheiro sai) e nasce um crédito do cliente com esse valor; na venda de B, o crédito entra como `PAGAMENTO` na forma `CREDITO`, no valor do menor entre A e B; o resto de B o cliente paga (à vista, a prazo ou com outro crédito dele) e, se B for mais barata, a sobra fica como crédito em aberto. A troca devolve peças de **uma** venda só; B sai de uma maleta conferida do vendedor (pode ser outra que não a de A).
- A troca passa pelo prazo da garantia e pela autorização da dona como a devolução, mas fica **fora do limite de reembolso da semana e do valor de aprovação**: nenhum dinheiro sai. A taxa de devolução continua contando a troca como devolução de A e venda de B.
- **Crédito do cliente:** é usado como forma de pagamento `CREDITO` numa venda nova, **do mesmo cliente**; não vence; quem usa é **só o vendedor dono do crédito** (o que o gerou, ou o vendedor para quem a dona o transferiu), com ou sem internet. Acima do saldo: online, é recusado; pelo envio do computador, a venda **entra** (o que aconteceu no balcão não se recusa), o crédito paga só o saldo que havia (pode ser zero), o resto fica **a receber do cliente** na venda e a dona revisa (`creditos_sem_saldo`: **Manter** = o cliente continua devendo; **Não aceitar** = pendência do vendedor, cobrada no acerto; nenhuma das duas mexe em dinheiro). Crédito inexistente, de outro vendedor ou de outro cliente continua recusado. A dona **transfere** online o saldo inteiro para outro vendedor (nasce um crédito novo para ele; o original fica usado). Crédito já usado impede cancelar a troca que o gerou.
- **Venda paga com crédito:** o estorno dela devolve o crédito (o uso é estornado e o crédito volta a ficar em aberto). Na devolução dela, o que foi pago em crédito volta **primeiro** como crédito novo do cliente (origem `DEVOLUCAO`) e só o resto volta em dinheiro, na forma escolhida. O limite da semana e o valor de aprovação olham só o dinheiro.
- A venda nova e a devolução de uma troca só se desfazem pelo **"Cancelar troca"** (o estorno da venda e o "Cancelar devolução" recusam). "Cancelar troca" é recusado se a sobra do crédito já foi usada em outra venda, se a peça A já foi vendida de novo ou se uma das maletas não está mais com o vendedor.
- **Corrigir:** devolução ou troca errada se corrige com "Cancelar devolução" ou "Cancelar troca" (registro novo de sinal oposto, nunca edição): o vendedor até o fim do dia, depois só a dona.
- **Tratamento financeiro do reembolso:**
  - venda já paga: o vendedor devolve o dinheiro e registra; no acerto o valor é **abatido** do repasse;
  - venda a prazo (desde a tarefa 6.6, ADR-0014 item 10): a devolução **primeiro abate o que o cliente ainda deve**, cancelando as parcelas em aberto da última para a primeira (baixa `CANCELAMENTO` com motivo `DEVOLUCAO`; a parcela abatida em parte fica `PARCIAL`); só o que passar disso volta ao cliente, **primeiro como crédito** (o que foi pago em crédito) **e depois em dinheiro**, na forma escolhida. Venda a prazo sem pagamento: cancelam-se as parcelas, sem dinheiro a devolver. Parcialmente paga: abate o que falta e devolve só o já pago. A divisão é `abatido = min(devolvido, em aberto)`, `resto = devolvido - abatido`, `em crédito = min(resto, crédito líquido da venda)`, `em dinheiro = resto - em crédito`; dinheiro + crédito + abatido = valor devolvido, sempre. "Cancelar devolução" desfaz o abatimento por estorno das baixas. Na troca, A a prazo abate as parcelas de A e só o resto vira crédito para pagar B (B pode ser a prazo); A sem nada pago não gera crédito;
  - comissão estornada proporcionalmente pelo servidor.
- **Antifraude:** limite de reembolso semanal por vendedor (`limite_reembolso_semanal`, vazio = sem limite), aprovação da dona acima de certo valor (`valor_aprovacao_reembolso`, vazio = sem valor), alerta quando a taxa de devolução de um vendedor (valor devolvido ÷ vendido nas últimas 4 semanas) passa de um percentual configurável (`percentual_alerta_devolucao`, padrão 20). Reembolso inconsistente (sem venda, fora do prazo, maior que o pago) vira **pendência para revisão da dona**, nunca é aceito em silêncio.
  - **Limite semanal:** soma do reembolso das devoluções não canceladas do vendedor (inclusive as em revisão e as não aceitas) na **semana cadastrada** que contém o dia (Brasília) da devolução; sem semana cadastrada, de **segunda a domingo** de Brasília. Passar do limite (estritamente; no valor exato passa) põe a devolução **para revisão** nos dois caminhos (online e envio), sem travar o balcão, mesmo com autorização.
  - **Valor de aprovação:** reembolso de uma devolução acima de `valor_aprovacao_reembolso` (estritamente) sem autorização: online, recusa pedindo autorização à dona; pelo envio do computador, revisão.
  - **Autorização da dona** (`autorizacoes_excecao`): o vendedor pede online, com a venda, o motivo e as peças; o servidor decide o tipo (fora do prazo ou acima do valor) e calcula o valor pedido. Um pedido aberto por venda. Aprovada, vale até o dia da aprovação + 7, em dias de Brasília, inclusive; uma agenda diária marca as vencidas, mas a regra do uso olha sempre a data. A autorização aprovada (de qualquer tipo) dispensa o prazo **e** o valor de aprovação daquela devolução, que precisa ser da mesma venda, do mesmo motivo e com no máximo as peças autorizadas (menos pode). Serve para **uma** devolução, para sempre (cancelar a devolução não a devolve para uso).
  - **Revisão da dona:** a devolução em revisão guarda os motivos (um ou mais: fora do prazo, acima do limite semanal, acima do valor sem autorização, autorização que não vale, reembolso maior que o pago). A dona **mantém** ou **não aceita**; nenhuma das duas mexe em movimento, pagamento ou valor. "Não aceita" é a pendência do vendedor até o acerto passar a cobrá-la.
  - **Taxa de devolução:** calculada na hora, por vendedor ativo, nos últimos **28 dias de Brasília** até hoje: devolvido (devoluções não canceladas, pelo dia delas) ÷ vendido (vendas concluídas, pelo dia delas). Avisa quando passa estritamente do percentual, ou quando há devolução sem venda.
- Nada se edita: pagamento original nunca é apagado; o reembolso é um registro negativo.

### 7.4 Acerto semanal (online)

> **Desde 18/11/2026 (v1.29), a §23 vale sobre esta seção no Corona Vendas.**

1. O vendedor sincroniza as vendas, devoluções, trocas e pagamentos.
2. Bipa as peças que sobraram.
3. O servidor compara **esperado x contado**. Peça faltante vira **pendência financeira** para a dona resolver.
4. Calcula descontos, comissão, valor líquido, reembolsos abatidos e pendências. A **comissão é por meta** (resposta da dona em 13/11/2026; ADR-0012, item 5):
   - no fechamento, o servidor soma o **vendido líquido** das vendas ligadas ao acerto, **convertendo cada venda para Guarani pela cotação gravada na própria venda** (ADR-0006), cada uma arredondada sozinha;
   - soma **≥ 700.000 Gs → 40%**; abaixo → **30%**. O percentual vale **sobre o total**, não só sobre o que passa da meta, e é **por semana** (um acerto por semana);
   - a meta e os dois percentuais ficam em `configuracoes` (a dona pode mudar) e são iguais para todos os vendedores; o `comissao_percentual` do cadastro do vendedor **deixa de ser usado** no cálculo (a coluna continua no banco);
   - com o percentual decidido, a comissão continua **por venda**, arredondada uma vez por venda, e o percentual fica gravado na linha. Estorno de venda de acerto já fechado usa o percentual **da linha original**;
   - o app do vendedor mostra a prévia: quanto falta para a meta, em Guarani e em Real.
5. Aceita pagamento do repasse do vendedor à loja em **dinheiro, transferência (gravada como `PIX`) e boleto**. **No sistema novo não existe entrada nem cheque** (ADR-0012, aceito em 13/11/2026); o acerto também não aceita cartão.
6. Peças com defeito são aprovadas pela dona no acerto; as boas voltam ao estoque central.
7. Estados: `EM_ACERTO → ACERTADA`. Semana fechada **não aceita mais alteração**.

**Como ficou a semana fechada (tarefa 7.7, 14/11/2026; ADR-0012, item 17).** A semana só fecha pela rota `POST /api/dona/semanas/{id}/fechar` (só a dona, com um UUID do comando): só semana aberta que **já terminou** (o último dia é antes de hoje, em Brasília) e **sem acerto em andamento** nela. O fechamento fica registrado (`fechamentos_semana`, imutável) e a semana **nunca reabre**: não muda número, datas nem situação, não se exclui, e o formulário de semana não aceita mais "Fechada". Depois de fechada, a semana recusa acerto novo, maleta nova (rascunho) e envio de maleta, lançamento de caixa e pagamento a fornecedor com data nela; o dia de hoje nunca cai numa semana fechada, então os lançamentos automáticos nunca são recusados. Fechar e quem grava com data na semana se serializam: quem grava lê a semana com `FOR SHARE` como última trava e o fechar a trava com `FOR UPDATE`; o banco também recusa (segunda trava). O botão **Fechar semana**, com confirmação, é da tarefa 7.10.

**Como ficou na tarefa 7.4 (acerto, parte 1: iniciar, travas, contagem e desistir; ADR-0012, itens 2, 3, 4, 13, 14, 15 e 16).** Sem dinheiro: totais, comissão, pendências e cotação nascem no fechamento (7.5).
- **Iniciar** (a dona, para qualquer vendedor, ou o vendedor, para si, online): liga **todas** as maletas `ENVIADA` ou `CONFERIDA` do vendedor (nova e reposição) ao acerto, que passam a `EM_ACERTO` guardando o status de antes. Um acerto em andamento por vendedor e um por semana (um acerto desistido libera a semana). A semana é a informada pela dona; senão a mais recente das maletas ligadas; sem maleta, a semana de hoje (Brasília). O vendedor só pede com ao menos uma maleta com ele, sem envios aguardando no computador (o último número que o computador informou) e sem maleta conferida em outro computador; a dona inicia sempre e a ficha mostra o aviso de envios aguardando.
- **Com a maleta em acerto:** nenhuma venda nova, estorno pelo vendedor, devolução, troca, devolução à dona, cancelamento nem conferência, e nenhuma remessa nova enviada ao vendedor ("Termine o acerto antes"; o rascunho continua livre). **A dona pode estornar** venda de maleta em acerto (é como resolve a sobra antes do fechamento). **Exceção da Fase 5:** a venda, e o estorno de venda, feitos sem internet **antes** do início do acerto e enviados pelo computador da maleta entram; a ficha da dona conta quantas chegaram depois do início ("Chegou venda nova: confira a contagem").
- **Contagem:** cada bipe é uma leitura (id do cliente, idempotente, imutável); desfazer é leitura negativa; vale a soma por peça e estado (`BOA` ou `DEFEITO`), nunca negativa. A peça tem de ser de uma maleta do acerto ou de uma devolução "para a loja" do vendedor; peça de fora é recusada sem dizer de onde é. Dona e vendedor contam na mesma contagem e a tela da dona mostra quem leu, quando e de qual computador. Não há esperado × contado nesta tarefa (7.5).
- **Desistir** (só a dona, com motivo): cada maleta volta ao status de antes (`ENVIADA` continua `ENVIADA`, `CONFERIDA` continua `CONFERIDA`), o acerto vira `DESISTIDO` e nada de dinheiro, venda ou movimento é gravado; as leituras ficam como histórico.

**Como ficou o pedido de acerto no Corona Vendas (tarefa 8.10, 15/11/2026).** O vendedor pede o acerto e conta as sobras pelo Corona Vendas, **só com internet**, pelas rotas do vendedor da 7.4 (`POST /api/vendedor/acertos`, `GET /api/vendedor/acertos/{id}` e `POST .../leituras`): sem rota nova, sem migração e sem tipo novo na outbox. Em "Meu acerto e minha comissão": **Pedir acerto** (com confirmação; o erro do servidor aparece em português) e, com o acerto em andamento, **Contar as sobras** (ler o código, Boa ou Com defeito, desfazer uma leitura). O id do pedido e o de cada leitura nascem na tela e são reaproveitados na nova tentativa; a contagem mostrada é a que o servidor devolve; a tela não calcula comissão nem total. Sem internet, a tela diz que precisa de internet. **Só a dona fecha e desiste.**

**Como ficou o fechamento (tarefa 7.5, 14/11/2026).** Só a dona fecha (`POST /api/dona/acertos/{id}/fechar`), numa transação só, com a prévia (`GET .../fechamento`, nada é gravado) usando o mesmo cálculo. Esperado x contado por peça: o saldo das maletas do acerto mais as peças "para a loja" das devoluções ainda sem acerto; sobra impede (409, sem rota de `AJUSTE`); a falta cai na maleta mais antiga, vale o preço da maleta e vira pendência `FALTA` (cobrar no acerto, cobrar depois ou a loja assume, com motivo) e `PERDA` na maleta; peça boa e com defeito voltam ao estoque central (defeito aprovado: devolução mais avaria; recusado: volta como boa). Todo evento do vendedor ainda sem acerto entra nele (`acerto_eventos`). Devolução ou crédito sem saldo em revisão impedem o fechamento. A comissão é por meta: a soma em Guarani de cada venda (total depois do desconto, pela cotação da venda, cada uma arredondada) decide 30% ou 40% sobre todas as vendas do acerto; o estorno de comissão usa o percentual da linha original. Totais do item 8 gravados (o dinheiro do acerto é da 7.6, logo abaixo), com a cotação mais recente do fechamento. Depois de `ACERTADO` nada muda. A meta e os dois percentuais ficam em `configuracoes` (`GET/PUT /api/dona/configuracoes/comissao`, com auditoria); o vendedor vê a prévia da comissão dele (`GET /api/vendedor/comissao/previa`).

**Como ficou o dinheiro do acerto (tarefa 7.6, 14/11/2026; ADR-0012, itens 1, 2, 3.6, 7, 8, 9, 11, 13, 14, 15 e 16).** O pedido de fechar ganha o bloco `pagamento` (a prévia com decisões também); o servidor calcula tudo o mais e nunca aceita do app total, comissão, saldo, crédito nem parcela.
- **O que o vendedor deve:** líquido do acerto menos o **crédito** que a loja já devia a ele (o acerto a favor dele anterior, abatido inteiro e sozinho). O vendedor paga em **Dinheiro** e **Transferência (PIX)** (cada pagamento é um recebimento, com id do aplicativo, e entra no caixa na hora); o **Boleto** não é dinheiro na mão: vira uma conta a receber do vendedor, com o vencimento do boleto, e só entra no caixa quando a dona registra o boleto pago (inteiro, na conta do boleto). Sem entrada, sem cheque e sem cartão: `CHEQUE`, `CARTAO` e `BOLETO` como recebimento do acerto são recusados.
- **O que ficou devendo** (o que sobra, fora o boleto) vira conta a receber do vendedor, em 1 a 24 parcelas mensais a partir da data que a dona escolhe (a divisão sai de `Dinheiro.dividirEmParcelas`, como no nível 1). Sem data, o fechar é recusado: "Escolha a data de pagamento do que ficou devendo". Pagar mais do que ele deve também é recusado. A dona pode pagar, junto, **contas antigas** do vendedor: cada uma é um recebimento aplicado na conta e não entra nos totais do acerto.
- **Acerto a favor do vendedor** (líquido menor que o crédito): o padrão é **crédito** para o próximo acerto (`ficou_devendo` negativo; o próximo acerto o abate inteiro e o que sobrar continua como crédito); a dona pode **pagar na hora**, o valor inteiro, em dinheiro ou Transferência (PIX), com saída de caixa.
- **Depois do acerto:** recebimento avulso (fora do acerto, inclusive o boleto pago) e estorno de recebimento (com motivo, sem limite de dia), só da dona; o estorno de recebimento que pagou o acerto já fechado não muda o acerto: vira conta a receber do vendedor (vencimento hoje ou a data informada). O vendedor só **lê** os recebimentos e as contas dele (os dois níveis); nunca vê o caixa, quem registrou nem o motivo do estorno.
- **Conferido pelo banco no `COMMIT`:** `recebido_no_acerto` = soma dos recebimentos do acerto; contas do vendedor = o que ficou devendo; `pago_ao_vendedor` = saída de caixa; crédito usado = todo o crédito de um acerto anterior, usado uma vez só; cada recebimento com exatamente um lançamento de caixa do mesmo valor.

### 7.5 Financeiro

- **Contas a receber** em dois níveis: (1) cliente final paga ao vendedor; (2) vendedor repassa à dona no acerto. **Nível 2 (tarefa 7.6):** o que o vendedor ficou devendo no acerto, o boleto e o estorno de um recebimento do acerto viram contas a receber do vendedor (`contas_receber` com devedor `VENDEDOR`), pagas por recebimentos em dinheiro, Transferência (PIX) ou boleto pago; a conta do boleto só se paga inteira.
- **Contas a pagar** a fornecedores, ligadas às compras.
- **Contas a pagar (tarefa 7.7):** a dona gera as parcelas de uma compra (1 a 24, mensais, a partir do vencimento que ela informa; a última parcela fecha exato e a soma é sempre o total da compra) e paga cada parcela, inteira ou em parte, em dinheiro, Transferência (PIX) ou boleto (só registrado, o sistema não paga nada). Cada pagamento é uma baixa imutável mais **uma** saída de caixa, na mesma transação; o estorno (com motivo, sem limite de dia) é uma baixa negativa e uma entrada de caixa de sinal oposto. Pagar acima do que falta é recusado. Compra desfeita cancela o que está em aberto das parcelas (a parcela paga continua paga; a devolução do fornecedor é lançamento manual de entrada). A situação "Vencida" é calculada pela data de hoje, nunca gravada.
- **Caixa manual (tarefa 7.7):** a dona lança entradas e saídas avulsas com **categoria** (as do sistema não servem; o tipo da categoria é o do lançamento e nunca muda; inativar em vez de excluir), forma, data (nunca no futuro, nunca numa semana fechada) e descrição; o estorno do lançamento manual leva motivo e usa a data de hoje. Lançamento que veio de pagamento ou recebimento se desfaz pela origem. **Saldo negativo não bloqueia nada:** vira um aviso em "O que precisa da sua atenção" enquanto o saldo for menor que zero.
- **Lançamentos de caixa** (tarefa 7.6: o caixa automático, um lançamento por recebimento do vendedor e por pagamento ao vendedor, na mesma transação; o saldo é a soma dos lançamentos; sem bloqueio por saldo negativo, cujo aviso é da 7.7), **comissões** (por vendedor e semana, pagas e a pagar), **reembolsos**. **Não existe controle de cheques** no sistema novo (ADR-0012, aceito em 13/11/2026).
- O **servidor recalcula** totais, parcelas e comissão e nunca confia no que o app mandou. Preço, desconto e saldo são validados; divergência vira alerta, não rejeição silenciosa.
- Valores em `NUMERIC(14,2)`, **nunca float**, com regra única de arredondamento: **HALF_UP com escala 2**.
- Datas em UTC, exibidas em horário de Brasília.
- **O preço de custo nunca chega ao PC do vendedor.**
- O financeiro é completo, mas o sistema **não executa pagamentos**. As formas de pagamento são só **registradas**: nenhuma é executada pelo sistema, e não há integração com banco, Pix ou maquininha. O repasse do vendedor à loja aceita `DINHEIRO`, `PIX` (na tela, "Transferência") e `BOLETO`; o pagamento a fornecedor, dinheiro, transferência e boleto. **Não existe entrada nem cheque** no sistema novo; os valores `CHEQUE` e `CARTAO` que já existem no banco para o pagamento do cliente continuam aceitos (tarefa 6.13), mas não aparecem em tela nova.
- **Três moedas.** A moeda padrão é o **Real**: todo valor é **gravado em Real** (`NUMERIC(14,2)`, regras do ADR-0002). Todo valor mostrado na tela, no app da dona e no do vendedor (acertos, relatórios, telas de valores, preço da peça), aparece **também em Dólar e em Guarani** (o Guarani sem centavos). A conversão é **só para mostrar** e nunca é gravada como valor de lançamento.
  - **Recebimento do representante em Dólar ou Guarani** (pedido do responsável, 23/11/2026; plano em `docs/planos/recebimento-em-tres-moedas.md`): no recebimento avulso e no "pagou agora" do acerto, a dona escolhe a moeda (**Real**, **Dólar** ou **Guarani**) e digita o valor nela. O **servidor** converte para Real (R$ = valor × taxa de venda, HALF_UP com 2 casas; o Guarani é inteiro) e grava em Real, que é o valor que vale para contas, caixa, totais e conciliações; o app nunca manda o valor convertido. O avulso usa a cotação do dia do lançamento (se a tela mostrou outra, o servidor recusa e pede para conferir de novo); o acerto usa a cotação dele. A moeda, o valor digitado e a cotação usada ficam guardados **só como informação** ("Recebido em Gs. 500.000"), nunca somam nem entram em conferência. Na lista, na ficha do acerto e no relatório Recebimentos por forma (coluna Moeda recebida, também no PDF e na planilha), a moeda recebida mostra o valor digitado, exato. Vale também para as **contas antigas** pagas junto no acerto. Sem cotação, só Real. É a única tela em que se digita Dólar ou Guarani.
- **Cotação diária.** Vale a cotação oficial do Banco Central, que o servidor busca online uma vez por dia útil e guarda dia a dia, como histórico: **nunca se apaga nem se corrige por cima**. Cada lançamento é convertido pela cotação do **dia em que aconteceu**, nunca pela de hoje.
  - Fim de semana e feriado sem cotação publicada **não são falha**: vale a do último dia útil.
  - Busca que falhou: vale a do dia anterior, e a dona recebe um aviso em "O que precisa da sua atenção".
  - O **acerto grava a cotação que usou**: a mais recente no momento em que é fechado. Todos os valores do acerto são convertidos por ela, e a tela do acerto mostra a data dessa cotação.
  - Fonte, regras da busca, conversão e arredondamento: ADR-0006.

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

### Cadastro dos códigos

Padrão seguro (ADR-0007), até a dona confirmar como são as etiquetas: a loja imprime as próprias etiquetas, não EAN-13.

- Cada peça tem o **código da loja** (COD: interno, sequencial, da loja) e o **código do fornecedor** (COD Fábrica: do fornecedor, alfanumérico). Pode ter também um EAN-13, se existir.
- Fluxo 1: no cadastro do produto, o cursor já fica em Código; ela lê o código com o leitor.
- Fluxo 2 (principal no dia a dia): na entrada da compra, código conhecido soma; desconhecido abre **cadastro rápido**.
- **Importação em massa** por planilha Excel/CSV (código, nome, categoria, custo, preço), com **prévia de erros** e relatório do resultado (criados, atualizados, recusados).
- **Um produto pode ter vários códigos**: tabela `produto_codigos` em vez de um campo único. Cada linha tem **tipo** (`LOJA`, `FORNECEDOR`, `EAN13`), `fornecedor_id` opcional e `principal`.
- O código é **texto livre**, guardado como **texto** (zeros à esquerda), e **único** em `produto_codigos`, sem diferenciar maiúsculas de minúsculas e sem espaços nas pontas.
- O **dígito verificador** só é validado quando o tipo é `EAN13` e o código tem 13 dígitos. EAN-13 **não é obrigatório**. Quando a leitura de um EAN-13 vier truncada, avisar "leia o código de novo".
- Código do fornecedor igual em fornecedores diferentes: como o código é único, avisar no cadastro e a dona decide (usar outro código ou vincular ao fornecedor correto).
- Peça sem etiqueta ou ilegível: a loja gera um **código da loja** novo (sequencial) e imprime a etiqueta. Etiquetadora é **opcional**.
- Todos os códigos sincronizam para o app do vendedor.

### Pendências sobre o leitor (levar ao cliente)

1. **Qual modelo** de leitor vai comprar? Pedir que **não compre antes de testar** com as etiquetas reais.
2. Confirmar como são as etiquetas (a loja imprime as próprias; os fornecedores usam EAN-13 ou só código alfanumérico?). Pedir foto ou amostra de 3 a 4 etiquetas.
3. Leitor com fio (USB) ou sem fio (Bluetooth)? Sem fio é mais prático para o vendedor na rua.
4. Requisitos: modo teclado (HID) com sufixo Enter; um modelo **2D/imager** lê melhor etiquetas minúsculas de semijoia. Marcas comuns: Elgin, Bematech, Zebra, Honeywell, Datalogic.

Risco: etiquetas muito pequenas ou de baixa qualidade podem exigir leitor 2D ou reetiquetagem.

## 9. Modelo de dados

### Campos de controle em toda tabela sincronizável

`id` (**UUID gerado no app**, nunca auto-incremento), `created_at`, `updated_at`, `version`, `deleted_at` (**exclusão lógica**), `origem_dispositivo`. No servidor, também `sync_seq` (sequência crescente para o pull). O pull usa um **limite seguro** sobre essa sequência, que não perde registros de transações que confirmam fora de ordem (exemplo: o app puxa até o `sync_seq` 11 e o 10 só confirma depois). A estratégia exata fica no ADR-0002.

### Cadastros

- `usuarios` (login, senha\_hash, perfil, ativo, vendedor\_id opcional)
- `vendedores` (código, nome, datas, sexo, estrangeiro, RG/IE, RG UF, CPF/CNPJ, naturalidade, escolaridade, telefones, WhatsApp, e-mail, redes sociais, **limite\_consignacao** (Real, a preço de venda congelado; zero vale zero) e **limite\_consignacao\_ilimitado** (a opção "Sem limite"; o banco recusa os dois juntos; os dois vazios = limite não definido, só em cadastro antigo, que não recebe maleta; V026), **restricao\_id**, **comissao\_percentual** (deixou de ser usado no cálculo desde 13/11/2026: a comissão é por meta, §7.4; a coluna fica), **praca\_id**, ativo, cadastrado\_por) e tabelas filhas: endereço, filiação/contato, trabalho, cônjuge, MEI, referências, documentos (arquivos), observações
- `restricoes` (código, descrição, bloqueia\_remessa, **ativo**)
- `pracas` (nome, **ativo**), `semanas` (número, início, fim, situação ABERTA/FECHADA; **sem `ativo`**: ABERTA/FECHADA já diz se a semana está em uso)
- `clientes` (nome, CPF opcional, **data\_nascimento opcional, não futura**, telefone, WhatsApp, endereço, observações, vendedor\_responsavel\_id, **ativo**). **Clientes são da LOJA (decisão de 11/11/2026):** qualquer vendedor vende a qualquer cliente ativo e recebe só o **diretório** (nome, CPF, telefone e cidade); vendas, fiado e pagamentos continuam privados de cada vendedor; o **CPF é único** entre clientes não excluídos (repetido é barrado, ADR-0011 item 13 e ADR-0013); `vendedor_responsavel_id` é "quem cadastrou/atende mais", sem restringir a venda
- `fornecedores` (razão social, CNPJ, contato, endereço, **ativo**)
- `categorias` (nome, **ativo**)
- `produtos` (nome, referência, categoria, fornecedor, preco\_venda, estoque\_minimo, estoque\_atual, foto, ativo)
- `produtos_custos` (produto\_id, preco\_custo; **só do servidor, nunca sincronizada**; 1 para 1 com `produtos`, gravada na mesma transação)
- `produto_codigos` (produto\_id, codigo único (sem diferenciar maiúsculas e sem espaços nas pontas) e indexado, **tipo** `LOJA`/`FORNECEDOR`/`EAN13`, fornecedor\_id opcional, principal, ativo)

**Ativo e inativo (todo cadastro, v1.7):** vendedores, usuários, produtos, fornecedores, categorias, praças, restrições e clientes têm `ativo` (padrão verdadeiro). **Inativar não é excluir**: o cadastro some das listas de escolha (formulários) e das buscas por padrão, continua nos relatórios e nos históricos e pode ser reativado a qualquer hora. Inativar é permitido mesmo com o cadastro em uso e **preserva os vínculos**: a ficha de quem já está ligado a um inativo continua mostrando o inativo. As listas ganham o filtro `situacao` (ATIVOS por padrão, INATIVOS ou TODOS). `DELETE` continua só lógico. A mudança de situação vai para a auditoria e usa a versão do registro (edição otimista). Semanas não ganham `ativo` (têm ABERTA/FECHADA).

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
- `pagamentos_cliente` (venda, valor, `forma: DINHEIRO | PIX | CARTAO | CHEQUE | CREDITO`, data, `tipo: PAGAMENTO | REEMBOLSO | ESTORNO_REEMBOLSO`; reembolso é registro negativo; `credito_id` obrigatório se e só se a forma é `CREDITO`; `devolucao_id` e `estorna_id` no reembolso da devolução e no estorno dele)
- `vendas.troca_id`: a troca da qual a venda é a peça nova (B); conferida pelo banco no fim da transação

### Devolução e troca

Modelo no ADR-0014 (tabelas criadas nas tarefas 6.3 a 6.5; todas imutáveis, correção por registro de sinal oposto):

- `devolucoes` (venda, cliente, vendedor, remessa, motivo, observação, `destino: REEMBOLSO | TROCA`, dentro\_garantia, `situacao: ACEITA | EM_REVISAO | MANTIDA | NAO_ACEITA | CANCELADA`, autorizacao\_id, valor\_devolvido, valor\_reembolsado (só o dinheiro), valor\_em\_credito (o que voltou como crédito do cliente; na troca, o que passou do que A ainda devia), valor\_abatido (o que abateu das parcelas em aberto; dinheiro + crédito + abatido = valor devolvido), hora e hora ajustada)
- `itens_devolucao` (devolução, item da venda, quantidade, valor, `estado_peca: BOA | DEFEITO`, `destino_peca: MALETA | LOJA`)
- `trocas` (venda de origem, devolução, venda nova, cliente, vendedor, diferença com sinal = total de B − valor em crédito de A (o que A abate nas próprias parcelas não entra), cotação do dia, `situacao: ATIVA | CANCELADA` e os dados do cancelamento). **Não existe `itens_troca`**: as linhas estão na devolução e na venda nova (`vendas.troca_id`); na maleta, os tipos `TROCA_ENTRADA` e `TROCA_SAIDA` separam a troca da devolução. O banco confere no fim da transação que a devolução é `TROCA` da venda de origem, que a venda nova aponta para a troca e que a diferença bate.
- `creditos_cliente` (cliente, vendedor dono do crédito, `origem: TROCA | DEVOLUCAO | TRANSFERENCIA`, troca, devolução ou crédito de origem, valor, valor\_utilizado, `situacao: ABERTO | UTILIZADO | CANCELADO`, cotação do dia; `valor_utilizado` é sempre a soma dos usos) e `usos_credito` (imutável: crédito, `tipo: USO | ESTORNO | TRANSFERENCIA`, venda e pagamento no uso e no estorno, crédito de destino na transferência, valor; o estorno é negativo e aponta para o uso que desfaz)
- `autorizacoes_excecao` (venda, vendedor, cliente, motivo, observação, `tipo: FORA_DO_PRAZO | ACIMA_DO_VALOR`, `situacao: PEDIDA | APROVADA | NEGADA | USADA | VENCIDA`, valor pedido calculado pelo servidor, cotação do dia, quem pediu e quando, quem decidiu, quando, motivo da negação e `valida_ate`, o último dia de Brasília em que vale)
- `itens_autorizacao` (autorização, item da venda, quantidade; imutável)
- Em `devolucoes`, a revisão: uma coluna booleana por motivo (`revisao_fora_do_prazo`, `revisao_acima_do_limite_semanal`, `revisao_acima_do_valor_de_aprovacao`, `revisao_autorizacao_invalida`, `revisao_reembolso_maior_que_pago`), `revisada_em`, `revisada_por` e `observacao_revisao`. Em revisão, mantida ou não aceita exige ao menos um motivo; aceita, nenhum. Uma devolução por autorização.
- `creditos_sem_saldo` (tarefa 6.7; sincronizável, vai ao vendedor da venda): a venda paga pelo envio com crédito acima do saldo. Id do servidor; `pagamento_id` é o pagamento em crédito que o computador mandou (único); venda, crédito, `valor_pedido` (> 0), `valor_usado` (de zero a menos que o pedido; o pagamento `CREDITO` gravado, quando maior que zero, tem esse valor e o id `pagamento_id`), `situacao: EM_REVISAO | MANTIDA | NAO_ACEITA`, `registrada_em` e a revisão da dona (`revisada_em`, `revisada_por`, `observacao_revisao`, obrigatória no "não aceita"). Só a situação e a revisão mudam; a linha nunca mexe em dinheiro.

### Acerto e financeiro

- `acertos` (vendedor, semana, remessa, esperado, contado, faltas, descontos, comissão, percentual da comissão decidido pela meta, vendido em Guarani usado na meta, líquido, status, cotação usada; sem coluna de entrada). **Na tarefa 7.4 (V043) existem só as colunas do início e da desistência:** vendedor, semana, `status` (`EM_ACERTO`, `ACERTADO` ou `DESISTIDO`), `pedido_pelo_vendedor`, `iniciado_em` e `iniciado_por`, o computador que pediu, e `desistencia_id`, `desistido_em`, `desistido_por` e `motivo_desistencia`; sincronizável, só o status muda. As colunas de dinheiro entram na 7.5.
- `leituras_acerto` (tarefa 7.4; sincronizável e imutável: acerto, peça, quantidade com sinal, `estado_peca` `BOA | DEFEITO`, `lida_em`, `lida_por`, `pelo_vendedor`, computador; o banco confere que o acerto está em andamento e que a soma por peça e estado nunca fica negativa)
- `remessas.acerto_id` e `remessas.status_antes_do_acerto` (tarefa 7.4): a ligação da maleta ao acerto e o status de antes, para o "desistir" voltar exatamente a ele; só o serviço liga e desliga, e o banco recusa qualquer outro caminho. Não vão ao aplicativo do vendedor (a maleta dele chega com `status = EM_ACERTO`).
- `pendencias_acerto` (acerto, produto, quantidade, valor, status, resolvida\_por)
- `contas_receber` (origem venda ou acerto, devedor cliente/vendedor, parcela, vencimento, valor, atalhos `valor_pago` e `valor_cancelado`, status `ABERTA | PARCIAL | PAGA | CANCELADA`, em que `PARCIAL` é parte paga, parte abatida por devolução, ou as duas; "vencida" é calculada, nunca gravada; só o serviço mexe nos atalhos, e o banco confere com a soma das baixas)
- `baixas_receber` (conta, `tipo: PAGAMENTO | CANCELAMENTO`, valor, `pagamento_cliente_id`, `motivo_cancelamento: ESTORNO_VENDA | DEVOLUCAO`, `devolucao_id` (no cancelamento por devolução), `estorna_id`; imutável, estorno é registro negativo). O recebimento posterior é um `pagamentos_cliente` com `posterior = true` e o estorno dele é `tipo = ESTORNO_PAGAMENTO` (F9 do ADR-0012)
- `contas_pagar` (tarefa 7.7, V046; só do servidor, o conteúdo nunca muda: compra, fornecedor, `geracao_id`, parcela e total de parcelas (1 a 24), vencimento, valor, e os atalhos `valor_pago`, `valor_cancelado` e `status` `ABERTA | PARCIAL | PAGA | CANCELADA`, sempre a soma das baixas; "Vencida" é calculada) e `baixas_pagar` (só do servidor e imutável: `PAGAMENTO` com forma `DINHEIRO | PIX | BOLETO`, o estorno como baixa negativa, ou `CANCELAMENTO` da compra desfeita; cada pagamento tem exatamente um lançamento de caixa). A soma das parcelas de uma compra é o total da compra (conferido no banco). `fechamentos_semana` (só do servidor e imutável): um por semana fechada.
- `recebimentos_vendedor` (tarefa 7.6, V045; sincronizável e imutável: vendedor, `momento` `ACERTO | AVULSO`, acerto, `aplicado_em` `ACERTO | CONTAS`, `forma` `DINHEIRO | PIX | BOLETO`, valor, `recebido_em`, quem registrou, motivo e `estorna_id` no estorno, resumo do pedido; o boleto só avulso, nas contas)
- `lancamentos_caixa` (tarefa 7.6; só do servidor e imutável: `tipo` `ENTRADA | SAIDA`, valor com sinal, forma, categoria, origem única `RECEBIMENTO_VENDEDOR | ACERTO_A_FAVOR_DO_VENDEDOR | PAGAMENTO_FORNECEDOR | MANUAL` (o manual é a própria origem; tem categoria do usuário, descrição e, no estorno, motivo), data de Brasília, `estorna_id`) e `categorias_caixa` (só do servidor; três categorias do sistema, "Repasse do representante", "Pagamento ao representante" e "Pagamento a fornecedor" (renomeadas na 9.8, V056; antes "do vendedor" e "ao vendedor"), e as do usuário; o tipo nunca muda)
- `acertos` ganha `pago_ao_vendedor`, `credito_usado` e `credito_do_acerto_id` (único) na 7.6; a regra do item 8 passa a ser `liquido - credito_usado = recebido_no_acerto - pago_ao_vendedor + ficou_devendo`, e `ficou_devendo` negativo é o crédito do vendedor. `contas_receber` ganha o devedor `VENDEDOR` (origens `ACERTO` e `ESTORNO_RECEBIMENTO`, sem venda) e `forma_prevista` (`BOLETO`); `baixas_receber` ganha `recebimento_vendedor_id`.
- `comissoes` (vendedor, venda, acerto, base, percentual gravado na linha, valor, status de pagamento)
- **Não existe a tabela `cheques`** (prevista até a v1.13; retirada no aceite do ADR-0012 em 13/11/2026). O modelo completo da Fase 7 está no ADR-0012, item 2.

### Controle

- `configuracoes` (uma linha por chave, valor numa coluna do tipo certo: `valor_inteiro` ou `valor_dinheiro`; só do servidor: prazo\_arrependimento\_dias (padrão 45), prazo\_defeito\_dias (padrão 45), limite\_desconto, limite\_reembolso\_semanal (Real; vazio = sem limite), valor\_aprovacao\_reembolso (Real; vazio = sem valor), percentual\_alerta\_devolucao (padrão 20), tempo máximo offline (padrão 10 dias, configurável), tentativas de login antes do bloqueio (padrão 5), tempo de bloqueio (padrão 15 minutos); na Fase 7: meta da comissão em Guarani (padrão 700.000), percentual da comissão abaixo da meta (padrão 30) e a partir da meta (padrão 40), iguais para todos os vendedores)
- `auditoria` (usuário, ação, tabela, registro, antes/depois, data)
- `cotacoes` (data da cotação; compra, venda e as duas paridades do Dólar e do Guarani; hora da busca; endereço de origem). Uma linha por dia útil. **Nasce só no servidor**, é **imutável** e chega ao app do vendedor só para leitura (ADR-0006)
- `dispositivos` (vendedor, identificação, ativo, bloqueado, último contato)
- `sync_log` (dispositivo, lote, resultado por item)
- `importacoes` (arquivo, criados, atualizados, recusados, erros)

Cada mudança de tabela é uma **migração Flyway** versionada (`V001__...sql`), nunca editada depois de publicada. O SQLite do vendedor também tem migrações versionadas aplicadas ao abrir o app.

## 10. Offline e sincronização (só no app do vendedor)

O app é **offline-first**: lê e grava sempre no SQLite local, e um processo em segundo plano sincroniza quando há internet.

**Componentes locais:** SQLite criptografado (SQLCipher; verificar a biblioteca compatível com Electron, por exemplo `better-sqlite3-multiple-ciphers`), tabela `outbox` (fila ordenada de operações pendentes), cursor `ultimo_sync_seq` e motor de sincronização no processo principal do Electron.

**Push:** cada ação offline vira item na `outbox`. Quando há internet, o app envia em lotes para `POST /sync/push`. O servidor responde **item por item** (aceito, rejeitado, conflito).

**Pull:** `GET /sync/pull?since=cursor` devolve tudo que mudou desde o último `sync_seq`. O app aplica no banco local. O pull usa um **limite seguro**, que não perde registros de transações que confirmam fora de ordem (exemplo: o app puxa até o `sync_seq` 11 e o 10 só confirma depois). A estratégia exata fica no ADR-0002.

**Dinheiro do vendedor no pull (tarefa 7.6):** `recebimentos_vendedor` (só do vendedor dono, sem quem registrou, motivo nem resumo do pedido), as contas a receber dos dois níveis (as parcelas das vendas dele e as contas que ele deve à loja, com `forma_prevista`), as baixas pelas contas e as colunas novas de `acertos`. `lancamentos_caixa` e `categorias_caixa` **nunca** vão ao app. O vendedor não registra nem estorna recebimento: não há tipo novo na outbox.

**Clientes no pull:** todo vendedor recebe o diretório de clientes ativos da loja (só nome, CPF, telefone e cidade, colunas explícitas). **CPF repetido** é barrado: online, 409 "Este cliente já está cadastrado" mostrando o existente; offline, o app confere no diretório local, e dois cadastros do mesmo CPF feitos ao mesmo tempo sem internet chegam como conflito para a revisão da dona, que junta os cadastros. **Formatos antigos da `outbox`:** o servidor os aceita por 90 dias. **Tipos da outbox:** `VENDA` (formato 1, com o `creditoId` opcional em cada pagamento), `ESTORNO_VENDA`, `CLIENTE_NOVO`, `CLIENTE_ALTERADO`, `CONFERENCIA` e, da Fase 6 (tarefa 6.7), `DEVOLUCAO`, `PEDIDO_AUTORIZACAO`, `TROCA`, `CANCELAR_DEVOLUCAO` e `CANCELAR_TROCA`; da Fase 7 (tarefa 7.3), `VENDA` e `TROCA` ganham o `parcelamento` opcional e entram `RECEBIMENTO_CLIENTE` e `ESTORNO_RECEBIMENTO_CLIENTE`; `contas_receber` e `baixas_receber` chegam no pull só ao vendedor dono; da Fase 7 (tarefa 7.4), `acertos` e `leituras_acerto` também chegam só ao vendedor dono (sem os "\*\_por"); pedir o acerto e bipar são **online**, sem tipo novo na outbox; cada um passa pelo mesmo serviço da rota online, com a hora corrigida, e o que o servidor acha fora do prazo, acima do limite ou acima do saldo do crédito vai para revisão da dona, nunca recusado.

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
| Cotações | Geradas **pelo servidor** e imutáveis. O app só recebe e **nunca usa cotação própria**. |

**O que cada vendedor sincroniza (somente o necessário):** suas maletas abertas e os produtos delas (com foto e **todos os códigos de barras**, sem custo), seus clientes (**com a data de nascimento e a situação**; só os dele, filtro da Fase 5), suas vendas, devoluções, trocas, pagamentos, a configuração de garantia e descontos e as **cotações do Dólar e do Guarani**. Sem internet, o app usa a última cotação que recebeu e **mostra a data dela**. **Nada** de financeiro geral, fornecedores, custo ou dados de outros vendedores.

**Ao receber o push, o servidor:** confere a idempotência; valida que a maleta pertence ao vendedor e está ativa; valida preço, desconto, garantia e saldo (saldo negativo só ocorre por bug ou fraude e vira **alerta**, não rejeição silenciosa); **recalcula** total, parcelas e comissão; gera `contas_receber` e `comissoes`.

**Limitações assumidas:** o vendedor precisa estar online ao menos uma vez para receber a maleta e para o acerto. Não há venda do estoque geral, então não existe disputa de peça entre vendedores. Uma maleta é vinculada a **um dispositivo**.

**Testes obrigatórios de sync:** venda offline com queda de conexão no meio do envio e reenvio sem duplicar; dois dispositivos; relógio do aparelho errado; devolução fora do prazo offline; cancelamento de venda já sincronizada; acerto com sobra e com falta; dispositivo bloqueado; cliente duplicado por CPF; migração do SQLite entre versões do app.

## 11. Segurança

- Senhas com **BCrypt**; JWT de acesso curto; **sessão longa (refresh token) de 30 dias, com rotação**, para login offline; **prazo máximo offline padrão de 10 dias**, configurável.
- Ao esgotar o prazo máximo offline, as vendas guardadas **nunca se perdem**: o Corona Vendas bloqueia vendas novas, pede para conectar e envia tudo assim que houver internet.
- A dona cria o usuário com **senha temporária** e a redefine quando preciso; a **troca da senha é obrigatória no primeiro acesso**. A senha temporária **vale 7 dias** a partir da criação ou da redefinição; vencida, não entra mais e a dona gera outra. A **senha definitiva** (do vendedor e da dona) **não expira**.
- A senha nova tem **pelo menos 8 caracteres, com letras e números**, e é diferente da senha temporária.
- Senha da própria dona: **outro admin** redefine. Se não houver outro admin, vale um **procedimento documentado no servidor** (comando de redefinição), sem e-mail.
- Os dois apps enviam **cabeçalhos de versão e de tipo de app** desde a Fase 0.
- O cabeçalho de tipo de app é **conveniência de experiência, não segurança**: a proteção real é a permissão por perfil nas rotas, e nenhum código pode depender só dele.
- Permissões por perfil **validadas na API**; rotas de dona e de vendedor separadas.
- Limite de tentativas de login: **bloqueio de 15 minutos depois de 5 erros seguidos**. Os dois números são configuráveis.
- Muitos erros num mesmo login vindos de IPs diferentes **não bloqueiam** (para ninguém trancar a conta de outra pessoa): geram só um **alerta** à dona.
- Banco central **nunca exposto** à internet, só a API (HTTPS).
- Banco local **criptografado**, porque notebook de vendedor pode ser perdido ou roubado. O **Corona Vendas pede a senha do vendedor ao abrir, também sem internet** (nem todo notebook tem senha no Windows): sem internet a senha é conferida localmente com um verificador lento (Argon2id ou scrypt, nunca hash rápido), **5 erros seguidos bloqueiam até conectar**, e a chave do banco é protegida pela senha (com uma segunda proteção pelo DPAPI do Windows só para resgate quando a dona redefine a senha). Senha do Windows: recomendada, não obrigatória (ADR-0005 e ADR-0013).
- **Bloqueio remoto**: a dona desativa o usuário ou dispositivo e o app apaga os dados locais no próximo contato com o servidor.
- **Auditoria** de preço, estoque, desconto, cancelamento, reembolso, autorização de exceção e liberação de restrição. A dona lê a auditoria no relatório **Controle** (§12); trocar a restrição de um vendedor e editar uma restrição também ficam registrados (tarefa 8.9).
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
- **Três moedas (§7.5):** todo valor aparece em Real, Dólar e Guarani. Cada lançamento é convertido pela **cotação do seu dia**, nunca pela de hoje, e o total em Dólar ou em Guarani é a **soma dos lançamentos já convertidos**. O Excel e o CSV trazem as três moedas e a data da cotação de cada linha. O cabeçalho do PDF diz que o Dólar e o Guarani seguem a cotação do Banco Central do dia de cada lançamento.

Os nomes de relatório desta seção são descrição interna. O nome de tela de cada relatório segue `docs/glossario-interface.md` e é definido na tarefa daquele relatório.

### App da dona

| Área | Relatórios |
| --- | --- |
| Cadastros | Vendedores (limite, restrição, situação), clientes e fornecedores (com filtro de situação), produtos, produtos sem código de barras, códigos duplicados ou em conflito, resultado das importações, aniversariantes (vendedores e clientes) |
| Estoque | Posição atual, movimentações por período, estoque mínimo, produtos parados, curva ABC, inventário e divergências, mercadoria em maletas por vendedor |
| Compras | Por fornecedor e período, histórico de custo por produto |
| Maletas | Remessas por vendedor e semana, **nota de remessa (PDF)**, saldo atual por maleta, maletas em aberto e atrasadas, reposições |
| Vendas | Por vendedor, praça, cliente, produto, categoria e período; ranking; ticket médio; vendas canceladas |
| Devolução e troca | Por vendedor e motivo, taxa de devolução, peças com defeito, trocas, créditos em aberto e utilizados, reembolsos (dinheiro devolvido), autorizações de exceção, e a **conferência** das devoluções e trocas (§13). Só a dona (ADMIN); linhas, rodapé e detalhamento saem das mesmas condições; Dólar e Guarani pela cotação do dia de **cada lançamento** (da devolução, da troca, de quando o crédito nasceu, do dinheiro devolvido, do pedido), o total é a soma das linhas já convertidas; a devolução, a troca e o crédito cancelados aparecem marcados e ficam fora do total (tarefa 6.10) |
| Acerto | Por vendedor e semana, faltas e pendências, descontos concedidos, esperado x contado |
| Financeiro | Contas a receber e a pagar, fluxo de caixa, inadimplência, recebimentos por forma de pagamento (não há relatório de cheques: no sistema novo não existe cheque), DRE simplificado, lucro por produto e por vendedor |
| Comissões | Por vendedor e semana, pagas e a pagar, com o vendido em Guarani e o percentual que a meta decidiu (30% ou 40%) |
| Controle | Auditoria, sincronizações e dispositivos, acessos |
| Painel semanal | Totais por semana no estilo da tela de referência (blocos Consignado, Reposições, Cobrança, Acerto, Vendas, Trocas, Total e Total/Acerto) |

**Resultado do período, lucro por produto e lucro por vendedor (tarefa 8.6; ADR-0015, itens 1 a 3).** Só a dona, só leitura, sempre por período (de e até, dias de Brasília, obrigatórios). Linhas do resultado, cada uma com o detalhamento até o registro: **Vendas** (vendas feitas, antes do desconto da devolução); (−) **Devoluções e trocas**, que abatem o **mês da venda** (a mesma regra dos relatórios de vendas: o resultado de um mês pode mudar se chegar devolução de venda dele); = **Receita líquida**; (−) **Custo das peças vendidas** (último custo de compra até o dia da venda; sem compra, o **custo do cadastro**, que a tela marca e conta); = **Lucro bruto**; (−) **Comissões** (pelo dia do fechamento do acerto, com os estornos); (−) **Perdas** (faltas do acerto pelo custo, menos as encontradas depois; avarias e inventário para menos, pelo custo); (+) **Cobrado do vendedor** (o que o acerto cobrou das faltas); (+/−) **Outras entradas e despesas da loja** (lançamentos manuais do caixa, uma linha por categoria); = **Resultado**. Ficam de fora o repasse do vendedor, o pagamento a fornecedor, o reembolso, o crédito do cliente e as parcelas. **Lucro por produto** e **lucro por vendedor** usam a mesma consulta (receita líquida menos custo; por vendedor, menos a comissão); perdas, cobrado e caixa são da loja e não se dividem. O custo só aparece nestas telas, só na Gestão.

**Painel semanal (tarefa 8.7; ADR-0015, item 5).** Só a dona, só leitura, por semana (filtros: semana, vendedor, praça e situação). **Pedidos:** uma linha por vendedor na semana, com as maletas em "Nota", Enviado, Devolvido, Vendido, Comissão (% e valor), Valor líquido e a situação (acertado, em acerto ou em aberto); a linha sem acerto fechado é **prévia** (a mesma conta do fechamento, nada é gravado) e a prévia inclui o que o vendedor ainda não acertou de semanas anteriores. **Totais:** Novas maletas, Reposições, Acerto (vendido, descontos, devolvido, comissão, faltas cobradas, valor do acerto, recebido, ficou devendo, devolvido à loja, pendências), Prévia (vendido, comissão prevista, líquido previsto), Vendas, Trocas e créditos, Total e Total recebido no acerto (Dinheiro, Transferência, Boleto). Sem entrada, cheque nem remarcadas. Cada número sai do mesmo relatório que o mostra em outro lugar (acertos, maletas, vendas, trocas, recebimentos).

**Controle (tarefa 8.9).** Só a dona, só leitura, lendo o que o servidor já grava (sem tabela nova). **Auditoria:** quem, quando, o quê, em qual registro, e o antes e o depois em texto da loja (nome do campo do glossário; nunca senha, hash nem token), da mais nova para a mais antiga, com filtros de pessoa, ação, tipo de registro, registro e período; ação do servidor aparece como "Pelo servidor". **Sincronizações e computadores:** um computador por linha (vendedor, situação, versão, último recebimento e último envio, operações esperando envio, envios com problema sem decisão e envios guardados à parte esperando a dona). **Acessos:** cada tentativa de entrar (pessoa, programa, resultado, endereço, até quando bloqueou); login que não existe aparece sem nome de pessoa. Guarda tudo; a limpeza técnica continua só com o que já apagava. No Corona Gestão: grupo **Controle**.

### App do vendedor (só dados dele, sem custo)

Minha maleta (saldo e peças); minhas vendas por período, cliente e produto; meus clientes e valores a receber deles; minhas devoluções e trocas (por motivo, com o que voltou em dinheiro e em crédito) e o crédito dos meus clientes (gerado, usado e em aberto); meu acerto da semana e minha comissão (com quanto falta para a meta, em Guarani e em Real); recebimentos por forma de pagamento (o crédito do cliente não é dinheiro recebido e fica de fora). Tudo só dele, sem custo (tarefa 6.10).

Como o app é offline, o relatório sai do banco local com o aviso: **Dados até dd/mm às hh:mm. N itens aguardando envio** (ou **N vendas aguardando envio**, quando só houver vendas). O Dólar e o Guarani usam as cotações recebidas do servidor, e a data da última cotação aparece junto desse aviso.

## 13. Garantia de que tudo esteja correto

1. **Dado imutável e saldo por eventos.** Estoque, saldo de maleta e financeiro são somas de registros que não se editam; corrigir é estornar.
2. **Uma única fonte de cálculo no servidor.** Relatórios leem de consultas validadas, sem fórmulas duplicadas em telas diferentes.
3. **Conciliações automáticas diárias** com alerta à dona:
   - estoque central + peças em maletas = comprado − vendido − perdido;
   - saldo da maleta = enviado − vendido − devolvido − perdido ± trocas;
   - soma das vendas = contas a receber geradas;
   - total recebido = lançamentos de caixa;
   - total do acerto = vendas − descontos − reembolsos − comissão;
   - **devolução e troca** (tarefa 6.10; rota `GET /api/dona/relatorios/devolucoes/conferencia`, só leitura, num retrato só do banco, deve vir sempre vazia): (1) o movimento da maleta de cada devolução e troca bate com as peças que deviam voltar a ela (e o estorno dele, se cancelada) e a conta do estoque da loja mais as maletas fecha com devolução, troca e os estornos; (2) por linha vendida, o devolvido (peças e valor) nunca passa do vendido; (3) por crédito, o usado é a soma dos usos e nunca passa do valor (créditos − usos = em aberto); (4) por venda, o dinheiro devolvido não passa do pago, salvo devolução que a dona já foi avisada para revisar; (5) por venda concluída, o recebido líquido (pagamentos menos reembolsos, inclusive em crédito) fica entre zero e o total menos o devolvido.

   - **conferência do dinheiro da Fase 7** (tarefa 7.8; rota `GET /api/dona/conciliacoes`, só ADMIN, só leitura, num retrato só do banco e com uma fórmula só; `?chave=C4` devolve uma; chave desconhecida, inclusive a C6 que está vaga, dá 400): são **onze**, C1 a C12 sem a C6. **C1** vendas: total menos devolvido é igual ao pago líquido (inclusive em crédito) mais o em aberto das parcelas, e venda desfeita não tem parcela em aberto; **C2** acerto: o líquido fecha com vendido, descontos, devolvido, comissão e faltas, o vendido é a soma dos eventos, e recebido, devendo e pago ao vendedor conferem com recebimentos, contas e caixa; **C3** comissão: a soma por vendedor é a dos acertos, cada linha é `Dinheiro.aplicarPorcentagem(base, percentual)` com o percentual do acerto, e estorno nunca passa da comissão; **C4** caixa: cada recebimento do vendedor, cada pagamento a fornecedor e cada pagamento ao vendedor tem exatamente um lançamento do mesmo valor, nenhum lançamento automático sem origem, e o sinal combina com entrada ou saída; **C5** contas (cliente, vendedor e fornecedor): pago e cancelado são a soma das baixas, nunca passam do valor, e a situação combina; **C7** estoque: o guardado é a soma dos lançamentos e a loja mais as maletas abertas fecha; **C8** maleta acertada com saldo zero e falta com a perda da mesma quantidade; **C9** cada venda, estorno e devolução em um acerto só e nenhuma de maleta acertada de fora; **C10** soma das parcelas da compra igual ao total; **C11** estoque completo (tarefa 8.3): por peça, o que está na loja mais nas maletas abertas é o que foi comprado e lançado pela dona, menos o vendido, o que faltou ou estragou no acerto, mais o que o cliente devolveu à maleta, o que chegou à loja pelo acerto e a peça achada, cada parcela tirada do documento de origem (compra, venda, devolução, acerto); **C12** saldo de toda maleta enviada (não só a acertada): por maleta e peça, o saldo pelos movimentos é o enviado, menos o vendido, o devolvido à dona e a falta, mais o que o cliente devolveu à maleta, e nenhuma peça fica negativa nem a maleta cancelada guarda peça. Cada diferença traz o tipo e o id do registro, o esperado e o encontrado. No Corona Gestão: **Conferência do dinheiro**. **Conferência diária (tarefa 8.4; ADR-0015, item 6).** Todo dia às 03:00 de Brasília e quando a dona toca em **Conferir agora** (`POST /api/dona/conferencias`, comando com UUID: 201 na primeira vez, 200 no reenvio do mesmo id, 409 se outra rodada está rodando), o servidor roda **treze** conferências num retrato só do banco (estoque da ADR-0008, devolução e troca da 6.10 e as onze acima) e guarda o resultado em duas tabelas só do servidor, imutáveis e nunca sincronizadas (`rodadas_conferencia` e `diferencas_conferencia`, só `INSERT`; V047). Uma conferência que dá erro não derruba as outras: a rodada fica `FALHOU` com a chave que falhou. Uma rodada por vez (trava consultiva do PostgreSQL). Nada é corrigido sozinho. O histórico (`GET /api/dona/conferencias`) e o detalhe (`GET /api/dona/conferencias/{id}`) são só da dona. Em **O que precisa da sua atenção** (calculado na hora, sobre a última rodada): "A conferência de hoje achou N diferenças" ou "A conferência de hoje não rodou" (rodada que não terminou, nenhuma rodada ou a última com mais de 26 horas). No Corona Gestão: **Conferência de hoje**.

   As conciliações são feitas **em Real**, com os valores gravados. Dólar e Guarani **nunca entram em conferência**, porque são só conversão para a tela (§7.5).
4. **Testes automatizados** para cada relatório, com massa de dados conhecida e resultado calculado à mão, rodando no CI a cada Pull Request (backend com PostgreSQL via Testcontainers).
5. `NUMERIC(14,2)`, sem float, com arredondamento HALF_UP e escala 2 (regra única, §7.5). A cotação não é dinheiro: fica em `NUMERIC(18,8)`, só nas colunas de cotação (ADR-0006).
6. Datas em UTC, exibição em horário de Brasília, **semanas fechadas imutáveis**.
7. **Validação com o cliente:** comparar os relatórios com as planilhas dele numa semana real antes da entrega. Para a Fase 8 o roteiro está em [roteiro-teste-fase-8.md](roteiro-teste-fase-8.md): o painel da semana, o resultado do período, a curva ABC e as vendas, lado a lado com as planilhas da dona; diferença achada vira tarefa, não pergunta.

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
| **1. Cadastros (dona)** | CRUD de fornecedor, produto (com `produto_codigos`, leitura de código, códigos da loja e do fornecedor, EAN-13 opcional), cliente, vendedor completo (limite, restrição, comissão, praça, abas da referência), pracas, semanas, relatórios de cadastro, cotação diária do Banco Central e valores em Real, Dólar e Guarani no app da dona | Base de dados da loja |
| **2. Estoque e compras** | Entrada de compra com leitor, cadastro rápido, **importação por planilha**, movimentações, estoque mínimo, **inventário por leitor**, relatórios de estoque | Estoque central confiável |
| **3. Maleta** | Montagem por leitor, limite e restrição, preço congelado, reposição, **PDF da nota de remessa**, relatórios de maleta | A dona entrega a maleta |
| **4. App do vendedor online** | Login, conferência, venda, clientes, consulta, relatórios próprios, valores nas três moedas | Vendedor vende com internet |
| **5. Offline e sync** | SQLite criptografado, outbox, push e pull (inclusive as cotações), indicador, bloqueio remoto, testes de sync | Vendedor vende sem internet |
| **6. Devolução e troca** | Garantia, reembolso, crédito, autorização da dona, relatórios | Pós-venda completo |
| **7. Acerto e financeiro** | Acerto semanal, comissões, contas a receber e a pagar, caixa, relatórios financeiros. **Entregue (código concluído em 15/11/2026, tarefas 7.2 a 7.13):** limite de consignação; parcelas da venda a prazo e recebimento posterior; acerto com contagem, fechamento atômico, comissão por meta (700.000 Gs, 30% ou 40%) e pendências; recebimentos do vendedor (dinheiro, Transferência e boleto) e caixa automático; contas a pagar, caixa manual com categorias e fechar semana; nove conciliações; telas da dona (Acerto e Financeiro) e do vendedor (A receber, Meu acerto e minha comissão); nove relatórios financeiros. Sem entrada e sem cheque. **Falta o teste de uso** ([roteiro](roteiro-teste-fase-7.md)) | Fechamento semanal |
| **8. Painel, relatórios avançados e conciliações** | Gerenciamento semanal (referência), curva ABC, DRE, conciliações automáticas, auditoria. **Entregue (código concluído em 17/11/2026, tarefas 8.2 a 8.13):** arrumação dos testes; conciliações C11 e C12; conferência diária (03:00 de Brasília e "Conferir agora", resultado guardado, aviso e histórico); relatórios de vendas e curva ABC; resultado do período (DRE), lucro por produto e por vendedor; **Painel da semana** (consulta e tela); relatórios de Controle (auditoria, sincronizações e computadores, acessos); no Corona Vendas, pedir o acerto e contar as sobras (só com internet); prévia do fechamento tipada e "Falta para a meta" na Gestão; cenário Playwright da dona; teste de ponta a ponta da semana (`FechamentoDaSemanaTest`). **Falta:** o teste de uso com as planilhas da dona ([roteiro](roteiro-teste-fase-8.md)) e a decisão do responsável sobre o [ADR-0015](decisoes/ADR-0015-numeros-gerenciais-e-conferencia-diaria.md) (Proposto; valem os padrões seguros) | Visão gerencial e confiança nos números |
| **9. Entrega** | Instaladores, auto-update, assinatura, deploy na VPS, HTTPS, backup e teste de restauração, **homologação do leitor com etiquetas reais**, validação com dados reais, treinamento | Sistema em produção |

As fases 1 a 4 funcionam **online, mas já preparadas para sync** (UUID, `deleted_at`, vendas imutáveis, idempotência). O offline entra por cima na fase 5, com esforço estimado de **20% a 30% a mais** de trabalho, concentrado em testes.

## 16. Fora do escopo (por enquanto)

Nota fiscal eletrônica (a nota de remessa é só interna), loja virtual, aplicativo de celular, multiloja, integração com maquininha de cartão, modo offline no app da dona, venda do estoque geral pelo vendedor. Qualquer um pode virar fase futura.

## 17. Pendências para confirmar com o cliente

1. DX = valor consignado, DV = devolvido, VD = vendido (confirmar o significado na referência).
2. **Modelo do leitor** de código de barras (não comprar antes de testar com etiquetas reais), com ou sem fio.
3. Como são as etiquetas? A loja imprime as próprias (código da loja) e o fornecedor manda código alfanumérico, ou algum fornecedor usa **EAN-13**? Obter fotos ou amostras de 3 a 4 etiquetas. O leitor funciona no Bloco de Notas? Qual impressora de etiquetas? O preço de venda é o custo vezes 7? (ADR-0007)
4. ~~Prazos de garantia~~ **Respondido em 13/11/2026:** 45 dias para arrependimento (mais adiante, talvez 2 meses). Falta: o prazo para peça com defeito (usamos 45).
5. Limite de desconto, limite de reembolso semanal e valor que exige aprovação da dona.
6. ~~Percentual de comissão~~ **Respondido em 13/11/2026:** sobre o valor da venda (com desconto), igual para todos, com meta: a partir de 700.000 Gs → 40%; abaixo → 30%. **Decidido pelo responsável em 13/11/2026 (ADR-0012 aceito):** a meta é por semana (um acerto por semana), os 40% valem sobre o total, e cada venda é convertida para Guarani pela cotação gravada nela (§7.4).
7. Quando a diferença de uma troca vira crédito: pode ser devolvida em dinheiro?
8. ~~Semana de acerto~~ **Respondido em 13/11/2026:** começa na segunda (segunda a domingo) e há 3 a 4 dias depois para fazer o acerto.
9. Quantos vendedores e quantos produtos aproximadamente (dimensionar a VPS).
10. Quem paga VPS, domínio e certificado de assinatura de código.
11. Existe alguma planilha atual do cliente para usar na validação dos relatórios e na migração de cadastros?
12. ~~Entrada~~ O que significa na tela de referência? Valor pago pela vendedora ao levar a maleta, abatido no acerto, ou entrada de venda parcelada? É obrigatória? Como se calcula e o que acontece no acerto? Precisa de resposta antes da Fase 3. **13/11/2026:** a dona não sabe ("creo que es entrada de dinero"). **Decidido pelo responsável em 13/11/2026 (ADR-0012 aceito): no sistema novo não existe entrada.** Se um dia existir, entra como tarefa nova.
13. ~~Cartão~~ **Respondido em 13/11/2026:** não. O cliente paga **em dinheiro ou por transferência**. Nas telas de venda e de reembolso só aparecem Dinheiro e Transferência (gravada como `PIX`); `CARTAO` e `CHEQUE` continuam aceitos no banco para não quebrar dados antigos. **No acerto (ADR-0012 aceito em 13/11/2026):** o vendedor repassa em dinheiro, transferência ou boleto; não existe cheque nem cartão.
14. **Língua das telas (13/11/2026):** a dona quer as telas **em espanhol**. É a última tarefa do projeto, com autorização do responsável. **Decisão do responsável (13/11/2026):** os dois apps ficam em espanhol; mais adiante, o app do vendedor terá espanhol e português (escolha da língua), e o da dona só espanhol.
15. ~~Limite de consignação: conta o preço de venda ou o custo? A reposição entra? Vendedor sem limite cadastrado recebe maleta?~~ **Decidido pelo responsável em 11/11/2026** (dúvidas 1, 2 e 6 do ADR-0010): conta o **preço de venda congelado**; a **reposição entra**; o limite é **obrigatório**, com a opção **"Sem limite"**, e zero vale zero (§7.1). Não é mais pendência.

## 18. Identidade visual: Corona Semijoias

A loja se chama **Corona Semijoias**. O nome aparece nos dois apps, no instalador, na nota de remessa e em todos os relatórios.

**Atenção ao logotipo:** a imagem enviada pelo cliente traz a palavra escrita **SEMJOIAS**, mas o correto é **SEMIJOIAS**. Esse arquivo **não deve ser usado em produção**. Antes de entrar no projeto, o logotipo precisa ser corrigido (pelo designer ou regerado) e entregue nos formatos abaixo. Enquanto isso, usar o texto "Corona Semijoias" como substituto provisório.

**13/11/2026:** chegou a versão com a grafia correta (**SEMIJOIAS**): `docs/marca/logo-correto-semijoias-print.jpg`. É um print em JPG (~1180 px, fundo bege, sem transparência): serve como referência de desenho e de cores e pode ser usado provisoriamente, mas ainda falta o arquivo original (SVG ou PNG grande transparente) e o símbolo isolado para os ícones. O arquivo antigo (`logo-PROVISORIO-erro-semjoias.png`) não deve mais ser usado. Também chegou um PNG com fundo transparente, `docs/marca/logo-correto-semijoias-transparente.png` (509×490): é o que se usa provisoriamente nas telas e no ícone grande; continua faltando uma versão maior para os PDFs impressos e o símbolo isolado (sem texto) para os ícones pequenos.

**Descrição da marca:** perfil feminino de rosto sereno, com tiara de pedras e brinco de gota, desenhado em linhas douradas finas, dentro de um círculo fino. A palavra CORONA em letras serifadas elegantes e de alto contraste, com a palavra semijoias espaçada abaixo, separada por um filete com uma estrela de quatro pontas. Fundo bege champanhe suave com brilho dourado.

**Arquivos necessários (pedir ao cliente ou designer):**

- Logotipo completo corrigido em **SVG** (ou PNG de alta resolução com fundo transparente).
- Versão **horizontal** (para cabeçalho de relatório e nota de remessa).
- Versão **só o ícone** (perfil com a tiara), para ícone do app, instalador e favicon.
- Versão **monocromática** (preto e branco) para impressão simples, sem cor e sem brilho.
- Ícones do Windows em `.ico` (16, 32, 48, 64, 128 e 256 px), um para cada app, com identificação visual diferente entre o app da dona e o do vendedor.

**Arquivos atuais de `docs/marca/` (17/11/2026), todos com fundo transparente:**

| Arquivo | Uso |
| --- | --- |
| `corona_semijoias_completa_2000.png` e `corona_semijoias_completa.svg` | Logo redonda completa (login, abertura) |
| `corona_semijoias_horizontal.png` | Logo horizontal (cabeçalhos, relatórios, nota de remessa) |
| `corona_semijoias_simbolo_1024.png` e `corona_semijoias_simbolo.svg` | Só o rosto com a tiara (ícones, favicon) |
| `corona_semijoias_completa_preto.png`, `corona_semijoias_horizontal_preto.png`, `corona_semijoias_simbolo_preto.png` | Versões em preto, para impressão simples e para pintar o símbolo de outra cor |
| `proposta-visual.html` | Proposta de ícones, entrada e venda, para o responsável escolher |

**Não usar:** `logo-PROVISORIO-erro-semjoias.png` (grafia errada), `logo-correto-semijoias-print.jpg`, `logo-correto-semijoias-transparente.png`, `simbolo-transparente.png` e `simbolo-transparente-256.png` (arquivos antigos, de baixa resolução). O PNG colorido do símbolo tem uma franja clara nas bordas: sobre fundo escuro, usar o SVG ou a versão preta pintada de dourado.

**Paleta (cores medidas na logo corrigida):**

| Uso | Cor |
| --- | --- |
| Destaques e detalhes | Dourado médio `#C08E3B` (principal), dourado escuro `#9D6D21` (títulos grandes e linhas) e dourado claro `#DEB75E` (brilhos) |
| Fundo de telas de marca (login, abertura) | Quase branco `#FAF7F2` (o fundo da logo) |
| Fundo das telas de dados | Branco ou `#FAF7F2` |
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

13/11/2026 (tarefa 6.8; ADR-0014): a tela inicial passa a ter cinco botões grandes; o quinto é **Devolução e troca** (ler a peça que o cliente trouxe, devolver ou trocar, e a lista das devoluções e trocas). O desenho vai à dona na pergunta 29.

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
| Consulta de produto | Foto grande da peça; clique amplia (zoom) |
| Cadastro e edição de produto (dona) | Área da foto, com o botão "Trocar foto" |
| Nota de remessa em PDF | Miniatura ao lado de cada item (opção que a dona liga ou desliga) |
| Relatório "Catálogo de produtos" | Foto, nome, código, preço e saldo; exportável em PDF para mostrar a clientes |

- **Sem foto:** mostrar uma imagem padrão "sem foto" com a marca, nunca um espaço quebrado, e listar no relatório **produtos sem foto**.
- **Feedback imediato:** a foto aparece junto com o som e a cor de sucesso do leitor. Código não encontrado não mostra foto, só a mensagem.
- Se a leitura soma uma peça já bipada, a foto não pisca nem recarrega.

### Cadastro das fotos (app da dona)

- **Uma foto só por peça** (decisão do responsável, 11/11/2026): a foto serve só para reconhecer a peça. Enviar uma foto nova **substitui** a anterior (a antiga sai da peça por exclusão lógica e o arquivo dela sai do disco depois, numa limpeza diária). O servidor garante uma só foto ativa por produto (índice único parcial, migração V029). Não há mais foto principal nem ordem; as colunas `principal` e `ordem` ficam na tabela só por compatibilidade (a foto ativa é sempre a principal).
- Formas de inserir: escolher arquivo, **arrastar e soltar**, **colar** da área de transferência e, opcionalmente, **tirar pela webcam**.
- **Importação em massa:** pasta com fotos nomeadas pelo código do produto (por exemplo `7891234567895.jpg` ou `A-0123.jpg`). O sistema associa cada foto ao produto (é uma foto por código: se vier outra para o mesmo código, ela substitui a que a peça já tinha), e a prévia mostra as que não encontraram produto e os produtos que ficaram sem foto.
- Formatos aceitos: JPG, PNG e WebP. **Limite de 10 MB por arquivo.** Recusar arquivo que não seja imagem, com mensagem clara.
  - O tipo vale pelo **conteúdo** do arquivo, não pelo nome. Também há limite de **25 milhões de pontos** (largura x altura), conferido antes de abrir a imagem, contra imagem-bomba (tarefa 1.6).
- **Processamento no servidor:** corrigir a rotação, remover dados EXIF (como localização), gerar três tamanhos, e guardar o original reduzido:
  - miniatura (cerca de 150 px), para listas;
  - média (cerca de 600 px), para o painel da peça lida;
  - grande (cerca de 1600 px), para o zoom.
- Fotos guardadas **como arquivos** (volume da VPS ou bucket S3 ou Backblaze), **nunca como binário dentro do PostgreSQL**. O banco guarda só caminho, hash e tamanho. O **backup diário inclui as fotos**.
- Acesso às fotos **somente autenticado** (URL assinada ou token), nunca pública.
- **App da dona (tarefa 1.13):** as fotos já baixadas ficam em **cache em disco** no processo principal, por hash e tamanho, com limite de tamanho (descarta a mais antiga). A foto é imutável por hash, então nunca se pergunta ao servidor se mudou. Só entra foto entregue a sessão autenticada e a pasta é **apagada ao sair da sessão**. No máximo 4 fotos são buscadas ao mesmo tempo, e as miniaturas da página da lista são buscadas antes de a linha aparecer.
- **Somente a dona** envia, troca ou apaga a foto. O vendedor não cadastra produto, então também não mexe nelas.

### Modelo de dados

```
produtos_fotos (
  id uuid PK, produto_id,
  arquivo_hash,          -- identifica o conteúdo (evita baixar de novo)
  principal boolean, ordem int,   -- sem uso desde 11/11/2026: uma foto ativa por produto (índice único parcial)
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
- O pull de sincronização traz **metadados** das fotos (hash); o download das imagens é uma fila separada, com retomada se a conexão cair.
- Dimensionar: 1 foto por produto e 500 produtos em maleta, em tamanho miniatura e médio, cabem em algumas dezenas de MB. Validar com fotos reais.

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

**Três moedas (§7.5, ADR-0006)**

- **Antes da Fase 1:** tarefa de backend das cotações: tabela `cotacoes`, busca diária, conversão com arredondamento por moeda e aviso de falha.
- **Fase 1:** o app da dona mostra os valores em Real, Dólar e Guarani e o aviso de falha da busca em "O que precisa da sua atenção".
- **Fase 4:** o app do vendedor, online, mostra os valores nas três moedas com a cotação que recebe da API.
- **Fase 5:** as cotações chegam pelo pull; sem internet, o app usa a última cotação recebida e mostra a data dela.
- **Fase 7:** o acerto grava a cotação que usou.

**Novas pendências para o cliente**

1. O vendedor deve ver **só as peças da própria maleta** ou o **catálogo completo** (para mostrar outras opções ao cliente)? Isso define quantas fotos baixar.
2. De onde vêm as fotos dos produtos: o fornecedor manda, a dona fotografa ou ambos? Qual a qualidade e o tamanho típico?
3. Quantos produtos e quantas fotos por produto, em média, para dimensionar o armazenamento da VPS?
4. A nota de remessa deve ter foto dos itens?
5. Quem da loja vai participar dos testes de uso (a dona e pelo menos um vendedor)?

## 22. Histórico de mudanças

- **v1.32, 23/11/2026:** §7.5, Três moedas: o recebimento do representante (avulso e no acerto) pode ser digitado em Dólar ou Guarani; o servidor converte pela cotação e grava em Real; a moeda e o valor digitado ficam só como informação.
- **v1.31, 20/11/2026:** tarefa 9.8, código: §23.10 (aba Dinheiro do Fechamento da maleta em Real, Dólar e Guarani, cada lançamento pela cotação do dia dele), §23.11 (Dólar e Guarani em cada linha dos relatórios; Tam./Ton. do produto volta como campo opcional, coluna nas telas e relatórios) e §7 do caixa (categorias do sistema "Repasse do representante" e "Pagamento ao representante").
- **v1.30, 20/11/2026:** tarefa 9.8 (plano aprovado): §23.2, recibo com contador próprio e prefixo `R` (D2 do ADR-0016 decidida: um contador para notas e outro para recibos); §23.7, Dt. Acerto editável (hoje ou dia passado, nunca no futuro); §23.8, a ordem dos débitos é a do lançamento do acerto, não a Dt. Acerto.
- **v1.29, 18/11/2026:** nova §23, Corona Vendas com consignado ao cliente (teste de uso de 18/11): nota por cliente com DX/DV/VD, comissão do cliente (30%, até 40%), acerto por nota, pendência acumulativa, Lançar pagamento, semanas do representante, Reposição, Troca, Remarcado, cadastro de cliente com fotos e relatórios novos. A venda ao consumidor (§7.2 e §7.3) sai do Corona Vendas; o acerto em dinheiro com a Lucia (§7.4) fica escondido e dá lugar ao Fechamento da maleta. Na tela: Representante e Cliente. Prints em `docs/referencia/2026-11-18-corona-vendas/`.
- **v1.28, 17/11/2026:** diferença da troca (issue 208): passa a ser total de B − valor em crédito de A (§7.3 e §9; ADR-0014, itens 8, 10 e 12); migração V048 troca só a regra de `conferir_troca()`, sem corrigir dados (não há produção). A devolução desfaz tudo (409 `DEVOLUCAO_NAO_CONFERE`) se o abatido nas parcelas não bater; a leitura repetida do acerto dá 409 em vez de 500. Nenhuma tabela, coluna ou rota nova.
- **v1.27, 17/11/2026:** proposta visual dos apps (issue 141): página `docs/marca/proposta-visual.html` com ícones em três estilos, tela de entrada em duas variações e tela de venda. §18: lista dos arquivos atuais de `docs/marca/` (os antigos marcados como "não usar") e paleta com as cores medidas no lugar das aproximadas (o bege `#F3E2C7` veio do arquivo antigo errado). Nenhuma regra de negócio mudou, nenhuma migração.
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
- **v1.4, 09/11/2026:** decisões do responsável sobre os ADRs de fundação.
  - §5: Spring Boot 4, na versão menor mais recente com suporte; a versão exata fica no ADR-0001.
  - §9: `preco_custo` sai de `produtos` e vai para a nova tabela `produtos_custos`, só do servidor e nunca sincronizada.
  - §11: senha temporária vale 7 dias; a senha definitiva não expira; muitos erros num mesmo login vindos de IPs diferentes geram só um alerta, sem bloqueio.
- **v1.5, 09/11/2026:** decisão do responsável sobre moedas e pagamentos (ADR-0006).
  - §7.5: o sistema não executa pagamentos; as formas de pagamento são só registradas. Tudo é gravado em Real; todo valor da tela aparece também em Dólar e em Guarani (sem centavos), pela cotação oficial do Banco Central do dia de cada lançamento, guardada dia a dia sem correção por cima. Fim de semana e feriado usam a do último dia útil; busca que falhou usa a do dia anterior e avisa a dona; o acerto grava a cotação usada (a mais recente no fechamento) e converte todos os seus valores por ela.
  - §9: nova tabela `cotacoes` (só o servidor grava, imutável, enviada ao vendedor); `acertos` com a cotação usada.
  - §10: cotações no que o vendedor recebe e na política de conflito; sem internet, a última cotação recebida, com a data dela.
  - §12: relatórios nas três moedas, cada lançamento pela cotação do seu dia, total como soma dos convertidos; a data da cotação no aviso do app do vendedor.
  - §13: conciliações em Real; a cotação em `NUMERIC(18,8)`.
  - §15 e §21: em que fase entra cada parte (backend antes da Fase 1; dona na Fase 1; vendedor online na Fase 4; pull e uso sem internet na Fase 5; acerto na Fase 7).
  - §17: nova pendência 13, pagamento com cartão.
- **v1.6, 10/11/2026:** códigos das etiquetas (ADR-0007) e plano da Fase 1.
  - §6: premissas 11 e 12, a loja imprime as próprias etiquetas; EAN-13 é opcional.
  - §8: "Cadastro dos códigos" reescrito: código da loja e código do fornecedor, texto livre e único sem diferenciar maiúsculas, dígito verificador só para o tipo EAN-13 com 13 dígitos; peça sem etiqueta ganha novo código da loja.
  - §9: `produto_codigos` ganha o tipo (`LOJA`, `FORNECEDOR`, `EAN13`).
  - §15, §17 e §20: texto ajustado aos códigos; nova pendência do leitor no Bloco de Notas, da impressora e da regra do preço.
- **v1.7, 10/11/2026:** decisões do responsável sobre ativo/inativo e data de nascimento do cliente (tarefa 1.14).
  - §9: todo cadastro tem `ativo` e pode ser recuperado (fornecedores, categorias, praças, restrições e clientes passam a ter; semanas não, pois têm ABERTA/FECHADA). Inativar não exclui, preserva os vínculos e some só das escolhas e buscas. `clientes` ganha `data_nascimento` opcional (não futura).
  - §10: `clientes` e `categorias` levam `ativo` ao vendedor; `clientes` leva também a data de nascimento, porque o vendedor é quem cadastra o cliente (só dos clientes dele, filtro da Fase 5). Padrão seguro adotado na tarefa.
  - §12: filtro de situação em clientes e fornecedores; aniversariantes passa a incluir clientes.
- **v1.8, 12/11/2026:** garantia e devolução (ADR-0014, tarefa 6.2).
  - §7.3: a devolução é registro novo e a venda não muda; peça boa de maleta acertada e peça com defeito vão para a loja; prazos em dias de Brasília; devolução fora do prazo pelo servidor entra para revisão, nunca recusada; autorização vale 7 dias; troca é uma operação só, sem tabela de itens; crédito do cliente; cancelar devolução e troca.
  - §9: `devolucoes`, `itens_devolucao`, `trocas`, `creditos_cliente`, `usos_credito` e `autorizacoes_excecao` reescritas; `itens_troca` deixa de existir; `configuracoes` ganha o tipo dinheiro e as cinco chaves da garantia (migração V035), com os padrões seguros.
  - §10: o bloco de parâmetros do pull ganha `prazoArrependimentoDias` e `prazoDefeitoDias` (limites de reembolso ficam só no servidor).
- **v1.9, 12/11/2026:** devolução do cliente (tarefa 6.3, dúvidas Q1 a Q3 do plano aprovadas).
  - §7.3: as peças da devolução vão pelo produto (nunca pelo id da linha da venda); motivo "outro" exige observação; motivo "defeito" exige todas as peças com defeito.
- **v1.10, 12/11/2026:** autorização, revisão e antifraude (tarefa 6.4, dúvidas P1 e P3 a P13 do plano aprovadas; P2 com ajuste da orquestradora).
  - §7.3: limite semanal pela semana cadastrada (ou segunda a domingo de Brasília), estrito e em revisão nos dois caminhos; valor de aprovação estrito, recusado online e em revisão pelo envio; autorização dispensa prazo e valor, vale até o dia da aprovação + 7 (Brasília), uma devolução por autorização; manter e não aceitar sem mexer em valores; taxa de devolução em 28 dias de Brasília, calculada na hora.
  - §9: `autorizacoes_excecao` detalhada; `itens_autorizacao` nova; `devolucoes` ganha uma coluna booleana por motivo de revisão e os dados da revisão da dona.
- **v1.11, 13/11/2026:** crédito do cliente e troca (tarefa 6.5, dúvidas T1 a T14 do plano aprovadas no PR #129).
  - §7.3: o dinheiro da troca passa pelo crédito (reembolso `CREDITO` na venda de A, pagamento `CREDITO` na venda de B); troca fora do limite da semana e do valor de aprovação, com prazo e autorização; uma venda de origem por troca; crédito só do vendedor dono e do mesmo cliente, recusado acima do saldo; transferência pela dona; estorno de venda paga com crédito devolve o crédito; devolução de venda paga com crédito devolve primeiro o crédito; venda e devolução de troca só pelo "Cancelar troca", com as recusas dele.
  - §9: `trocas` com a situação e o cancelamento; `vendas.troca_id`; `creditos_cliente` com a origem `TRANSFERENCIA` e a situação `CANCELADO`; `usos_credito` com o tipo; `pagamentos_cliente` com a forma `CREDITO` e `credito_id`; `devolucoes.valor_em_credito`.
- **v1.12, 13/11/2026:** push e pull das tabelas da Fase 6 (tarefa 6.7, dúvidas S1 a S12 do plano aprovadas no PR #131).
  - §7.3: crédito acima do saldo pelo envio vira revisão da dona (a venda entra, o crédito paga só o saldo, o resto fica a receber), não recusa; online continua recusado.
  - §9: `creditos_sem_saldo` nova (migração V039).
  - §10: tipos novos da outbox, formato 1: `DEVOLUCAO`, `PEDIDO_AUTORIZACAO`, `TROCA`, `CANCELAR_DEVOLUCAO` e `CANCELAR_TROCA`; a `VENDA` continua no formato 1, com o `creditoId` opcional em cada pagamento. O vendedor recebe as devoluções, os pedidos de autorização, as trocas e os créditos dele (sem as colunas que apontam para crédito de outro vendedor) e as vendas com crédito para revisar.
- **v1.13, 13/11/2026:** respostas da dona por WhatsApp (perguntas de garantia, pagamento, comissão, semana e língua).
  - §7.3 e §9: prazos de arrependimento e de defeito com padrão 45 dias.
  - §6 (premissa 8) e §17: comissão sobre o valor com desconto e com meta (30% ou 40%, 700.000 Gs), para a Fase 7; cliente paga em dinheiro ou transferência (sem cartão); semana de segunda a domingo com 3 a 4 dias para o acerto; telas em espanhol como última tarefa (os dois apps; mais adiante, o do vendedor também em português).
  - §18: logotipo com a grafia correta guardado em `docs/marca/` (print, falta o arquivo original).
- **v1.14, 13/11/2026:** ADR-0012 aceito pelo responsável com quatro mudanças (tarefa 7.1b), antes de qualquer código da Fase 7.
  - §2, §7.4, §7.5 e §17 (item 12): no sistema novo **não existe entrada**.
  - §7.4, §7.5, §9, §12, §15 e §17 (item 13): **não existe cheque** (sai a tabela `cheques` e o relatório de cheques); o repasse do vendedor à loja aceita `DINHEIRO`, `PIX` (na tela, "Transferência") e `BOLETO`, e o pagamento a fornecedor, dinheiro, transferência e boleto; `CHEQUE` e `CARTAO` do pagamento do cliente continuam aceitos no banco, sem tela nova.
  - §6 (premissa 8), §7.4, §9, §12 e §17 (item 6): **comissão por meta** no lugar do percentual por vendedor (700.000 Gs na semana → 40%, abaixo → 30%, sobre o total; cada venda convertida pela cotação gravada nela; meta e percentuais em `configuracoes`; `comissao_percentual` do vendedor fica sem uso no cálculo; prévia do vendedor com quanto falta para a meta).
  - Cabeçalho corrigido para a versão atual.
- **v1.15, 13/11/2026:** limite de consignação com as decisões do responsável de 11/11/2026 (tarefa 7.2; o código veio no PR 86, migração V026).
  - §7.1: o limite é obrigatório, com a opção "Sem limite" (zero vale zero); conta o preço de venda congelado (`preco_remessa`) do que o vendedor tem em mãos mais a maleta que está saindo; a reposição entra; limite não definido não recebe maleta; a lista de vendedores mostra a situação do limite.
  - §9: `vendedores` ganha `limite_consignacao_ilimitado` e a regra das duas colunas.
  - §17 (item 15): as três perguntas do limite ficam registradas como decididas.
- **v1.16, 14/11/2026:** contas a receber do cliente (tarefa 7.3, ADR-0012 itens 2 e 7).
  - §7.2: venda a prazo em parcelas mensais, recebimento posterior distribuído por vencimento e estorno do recebimento.
  - §9: `contas_receber` com atalhos, `baixas_receber`, `pagamentos_cliente.posterior` e o tipo `ESTORNO_PAGAMENTO`.
  - §10: `parcelamento` opcional na `VENDA` e na `TROCA`; tipos novos da outbox; tabelas novas no pull.
- **v1.17, 14/11/2026:** devolução e troca de venda a prazo (tarefa 6.6, ADR-0014 item 10, ADR-0012 item 7).
  - §7.3: a devolução abate primeiro o que o cliente deve, depois crédito, depois dinheiro; cancelar desfaz o abatimento; a troca de A a prazo.
  - §9: `devolucoes.valor_abatido`, `baixas_receber.devolucao_id` e o motivo `DEVOLUCAO`; `PARCIAL` também para parcela abatida.
  - §10: `devolucoes.valor_abatido` e `baixas_receber.devolucao_id` entram no pull; o que o app manda não muda.
- **v1.26, 17/11/2026:** fechamento da Fase 8 (tarefa 8.13); nenhuma regra de negócio mudou, nenhuma migração.
  - §2: DX, DV e VD deixam de ser "premissa a confirmar" e passam a ter a definição de **Enviado**, **Devolvido** e **Vendido** que o painel da semana usa (ADR-0015, item 5).
  - §13, item 7: o roteiro da comparação com as planilhas da dona (`docs/roteiro-teste-fase-8.md`).
  - §15: a Fase 8 passa a constar como entregue no código (tarefas 8.2 a 8.13), com o que falta (o teste de uso e a decisão do responsável sobre o ADR-0015).
  - §12 e §13: já descritos nas versões 1.24 e 1.25 e nos parágrafos das tarefas 8.5 a 8.9; o painel, o resultado do período, a curva ABC, as vendas e a conferência diária passaram a ter um teste de ponta a ponta sobre a mesma semana (`FechamentoDaSemanaTest`).
  - §7.4: pedir o acerto e contar as sobras pelo Corona Vendas passaram a existir na tarefa 8.10 (só com internet, padrão seguro da dúvida R3 do ADR-0015); a nota da v1.23 sobre isso está superada. Quem fecha o acerto continua sendo só a dona.
- **v1.25, 16/11/2026:** conferência diária: rodada automática e "Conferir agora", resultado guardado, aviso e histórico (tarefa 8.4, ADR-0015 item 6).
- **v1.24, 16/11/2026:** conciliações C11 e C12 do estoque completo (tarefa 8.3, ADR-0012 item 17, ADR-0008 item 9).
  - §13: a conferência do dinheiro passa de nove para onze (C11 estoque completo por peça, C12 saldo de cada maleta enviada), na mesma rota, só leitura e no mesmo retrato; nenhuma regra de venda, maleta, acerto ou sync mudou.
- **v1.23, 15/11/2026:** fechamento da Fase 7 (tarefa 7.13); nenhuma regra de negócio mudou.
  - §15: a Fase 7 passa a constar como entregue no código (tarefas 7.2 a 7.12), com o que ela inclui e o que falta (o teste de uso, com o [roteiro](roteiro-teste-fase-7.md)).
  - §7.4 a §7.5, §9, §10, §12 e §13: já descritos nas versões 1.14 a 1.22; o ciclo inteiro (venda a prazo, recebimento, devolução, acerto, pagamento, comissão por meta, caixa, contas a pagar, semana fechada e conciliações) passou a ter um teste de ponta a ponta (`CicloDaFase7Test`).
  - Diferenças entre o texto e o comportamento (registradas em `docs/progresso/fase-7.md`, sem mudar regra): o passo 2 da §7.4 ("o vendedor bipa as sobras") hoje é feito pela dona no Corona Gestão (a rota do vendedor existe, a tela do Corona Vendas não); o vendedor também não pede o acerto pelo aplicativo.
- **v1.22, 14/11/2026:** as nove conciliações da Fase 7 com rota e tela (tarefa 7.8, ADR-0012 item 17).
  - §13: a conferência do dinheiro (C1 a C10, sem a C6), a rota `GET /api/dona/conciliacoes` e o que cada uma confere; só leitura, num retrato só, uma fórmula só.
- **v1.21, 14/11/2026:** contas a pagar das compras, caixa manual com categorias, aviso de caixa negativo e fechar semana (tarefa 7.7, ADR-0012 itens 2, 3.8, 11, 12, 13, 14, 15, 16 e 17).
  - §7.4: a semana fechada (só pela rota, só depois do fim, sem acerto em andamento, nunca reabre, recusa acerto, maleta, lançamento e pagamento com data nela).
  - §7.5: contas a pagar (gerar, pagar, estornar, compra desfeita) e o caixa manual com categorias e o aviso de saldo negativo.
- **v1.20, 14/11/2026:** recebimentos do vendedor, contas do nível 2 e caixa automático (tarefa 7.6, ADR-0012 itens 1, 2, 3.6, 7, 8, 9, 11, 13, 14, 15 e 16).
  - §7.4: como ficou o dinheiro do acerto (recebimentos em dinheiro e Transferência (PIX), boleto como conta, o que ficou devendo em parcelas, contas antigas pagas junto, a favor do vendedor como crédito ou pago na hora, estorno de recebimento).
  - §7.5: contas a receber do nível 2 e o caixa automático.
  - §9: `recebimentos_vendedor`, `lancamentos_caixa`, `categorias_caixa`, as colunas novas de `acertos`, `contas_receber` e `baixas_receber`; a conta C2 com o crédito e o pago ao vendedor.
  - §10: `recebimentos_vendedor`, as contas do nível 2 e as colunas novas entram no pull (só do vendedor dono); o caixa e as categorias nunca vão ao aplicativo; o vendedor não registra nem estorna recebimento.
  - §22: o que não está na 7.6 (lançamento manual, categorias com rota, aviso de caixa negativo e fechar semana, que a 7.7 fez; conciliações formais, da 7.8; telas, da 7.9, 7.10 e 7.11).
- **v1.19, 14/11/2026:** acerto, parte 2: fechamento, comissão por meta e pendências (tarefa 7.5, ADR-0012 itens 3.6, 5, 6, 8, 13, 14, 15 e 16).
  - §7.4: como ficou o fechamento (uma transação só: esperado x contado, sobra impede, falta vira pendência e perda, peça boa e com defeito voltam à loja, eventos ligados ao acerto, comissão por meta, totais e cotação gravados, maletas acertadas), a meta e os percentuais em `configuracoes`, o estorno de comissão pelo percentual da linha original, as pendências e a prévia da comissão do vendedor.
  - §9: `acertos` (colunas do fechamento), `acerto_eventos`, `itens_acerto`, `comissoes`, `pendencias_acerto`, as três chaves da comissão em `configuracoes` e a origem `ACERTO` no estoque central.
  - §10: as quatro tabelas novas e as colunas do fechamento entram no pull (só do vendedor dono); fechar, resolver pendência e configurar a comissão são online, da dona.
  - §11: a prévia do vendedor (`GET /api/vendedor/comissao/previa`) e as rotas do fechamento e das pendências.
  - §22: o que não está na 7.5 (recebimentos, caixa e contas do nível 2, da 7.6; telas, da 7.9, 7.10 e 7.11).
- **v1.18, 14/11/2026:** acerto, parte 1: iniciar, travas, contagem e desistir (tarefa 7.4, ADR-0012 itens 2, 3, 4, 13, 14, 15 e 16).
  - §7.4: como ficou o início do acerto (todas as maletas abertas), as travas, a exceção da venda e do estorno feitos sem internet antes do início, a contagem por leituras e o desistir.
  - §9: `acertos` (só início e desistência), `leituras_acerto`, `remessas.acerto_id` e `remessas.status_antes_do_acerto`.
  - §10: `acertos` e `leituras_acerto` entram no pull; pedir o acerto e bipar são online.

## 23. Corona Vendas: consignado ao cliente (18/11/2026)

Mudança vinda do teste de uso de 18/11/2026 na praça do representante (Ponta Porã). **Esta seção vale sobre as §7.2, §7.3 e §7.4 e sobre o que elas dizem do Corona Vendas** onde as duas discordarem. Prints de referência em `docs/referencia/2026-11-18-corona-vendas/` (`print-gerenciamento-semanal.png`, `print-cobranca-devolucao.png`, `print-novo-pedido.png`). Onde esta seção e os prints discordarem, **o print vale**, exceto no que esta seção muda de propósito (sem cheque, sem boleto, sem desconto, sem entrada, sem NF-e, sem limite de crédito).

### 23.1 Decisões do responsável (18/11/2026)

1. **A venda ao consumidor sai do Corona Vendas.** O fluxo único passa a ser a **nota consignada ao cliente** (esta seção). As telas de venda à vista/a prazo, parcelas, devolução e troca com garantia e crédito do cliente (§7.2 e §7.3) saem do Corona Vendas. Os dados antigos ficam no servidor, nada é apagado; as rotas antigas podem continuar existindo, mas o app não as usa.
2. **O acerto em dinheiro do representante com a Lucia fica fora do sistema por enquanto.** O acerto da §7.4 (comissão por meta 30%/40% em Guarani, repasse em dinheiro, PIX ou boleto) **fica escondido** no Corona Gestão, sem apagar dados. No lugar, a Lucia bipa a maleta quando ela volta e o sistema gera o relatório **Fechamento da maleta** (§23.10).
3. **Prazo:** a seção inteira até sexta, 20/11/2026.
4. **Nomes:** na tela, **Representante** (quem usa o Corona Vendas; antes "vendedor") e **Cliente** (quem pega peças em consignado para revender). Vale nos dois apps e nos dois idiomas; `docs/glossario-interface.md` acompanha. No código e no banco os nomes técnicos atuais (`vendedor`, `cliente`) continuam.
5. **Continuam valendo** as regras inegociáveis do CLAUDE.md: gravado em Real e mostrado também em Dólar e Guarani pela cotação do dia (ADR-0006); `NUMERIC(14,2)`; UUID gerado no cliente; exclusão lógica; nota finalizada, acerto, pagamento e movimento de maleta são imutáveis (correção = estorno, registro novo); o servidor recalcula totais e comissões; o Corona Vendas nunca recebe custo nem dados de outro representante; o Corona Vendas continua **offline-first** (tudo desta seção funciona sem internet e vai pela outbox).
6. **GitHub Actions:** o mês já passou do gratuito. PR em rascunho até passar tudo localmente; um único "pronto" por tarefa; nada de rodar de novo às cegas.

### 23.2 Glossário

| Termo | Significado |
| --- | --- |
| **Empresa (Lucia)** | A dona. Monta as maletas e envia aos representantes. |
| **Representante** | Quem usa o Corona Vendas. Recebe maletas e atende os clientes da praça. No print: "Promotor". |
| **Cliente** | Quem pega peças da maleta para revender, em consignado. No print: "Vendedora". O cliente não acessa o sistema. |
| **Praça** | Cidade ou região do representante (ex.: "PONTA PORÃ - MS"). |
| **Maleta** | Peças montadas pela Lucia, com **código único** gerado pelo servidor em sequência simples (`0001`, `0002`...; texto, único sem diferenciar maiúsculas; as maletas que já existiam recebem pela ordem de criação). Um representante pode ter várias. |
| **Código do produto** | O **código do fornecedor** (tipo `FORNECEDOR` do ADR-0007): é o que se bipa e o que aparece em todas as telas. Peças iguais têm o mesmo código; bipar de novo soma 1. |
| **Nota (pedido)** | Tudo o que **um** cliente levou em **uma** saída. Número `semana-sequência`, ex.: `0000065-32`. |
| **Acerto (cobrança)** | O retorno do cliente: devolve o que não vendeu e paga o vendido menos a comissão. |
| **Data do retorno** | Dia em que o cliente volta para o acerto (sugestão: saída + 45 dias, editável). |
| **Dt. Acerto** | Data em que o acerto foi feito: hoje por padrão, editável para um dia que já passou, nunca no futuro (§23.7). |
| **DX / DV / VD** | DX (Deixado): o que o cliente levou, a preço de venda, bruto. DV (Devolvido): o que ele devolveu. VD (Vendido) = DX − DV. Itens DX/DV/VD = quantidades de peças. Nesta seção DX/DV/VD são **da nota do cliente**, não da maleta da §2. |
| **Comissão** | Parte do vendido que fica com o cliente: padrão 30%, de 0% a 40%. |
| **Valor Líquido** | VD − Comissão, sempre, em todas as telas. |
| **Pendência** | O que o cliente ficou devendo; acumula e entra sozinha no próximo acerto. |
| **Recibo** | Registro de um pagamento, numerado `semana-sequência` com contador próprio, separado do das notas, e mostrado com o prefixo `R` (ex.: `R 0000065-01`; tarefa 9.8, ADR-0016 item 3). Os recibos gravados antes da 9.8 mantêm o número. |
| **Semana** | Ciclo do representante na praça, numerado em sequência **por representante** (ex.: `0000065`). Não é a semana cadastrada pela dona da §7.4. |

### 23.3 Fluxo

1. A Lucia monta a maleta bipando as peças e envia (§7.1, sem mudança). **A maleta chega pronta** no Corona Vendas, com as peças e o código; o representante **não confere peça por peça** (a conferência por bipe da §7.1 deixa de ser obrigatória para usar a maleta).
2. **Novo Pedido:** o representante bipa o que o cliente leva e finaliza a nota.
3. O cliente fica de 45 a 60 dias com as peças.
4. **Acerto:** o representante bipa as devoluções, que voltam à maleta de onde saíram; o que não foi bipado é **vendido**.
5. **Pagamento:** dinheiro, Pix, cartão de débito ou de crédito à vista (pode misturar).
6. Pagou tudo → nota **Fechado** (verde) e recibo. Faltou → **pendência**, nota **Pendente** (amarela), somada ao próximo acerto até ser quitada.

O representante bipa só na saída e no acerto.

### 23.4 Tela principal (Gerenciamento Semanal)

Igual ao print: botões no topo, cabeçalho da semana, abas **Pedidos** e **Totais**.

- **Botões, nesta ordem:** **Novo Pedido** (§23.6), **Cobrança** (§23.7), **Reposição**, **Troca**, **Clientes**, **Maletas** e o campo **Pesquisar** (nome do cliente, número da nota ou código da maleta).
  - **Reposição:** acrescenta peças a um cliente com nota em aberto. Nota da semana atual: as peças entram nela e a nota de saída é gerada de novo para assinar. Nota de semana anterior: nasce uma nota nova na semana atual só com as peças novas, com a mesma Data do retorno.
  - **Troca:** troca uma peça que está com o cliente por outra, sem acerto: a peça trocada volta à maleta de onde saiu e a nova entra na nota (uma operação só; a nota guarda o histórico). A tela mostra, antes de confirmar, o valor das duas peças e quanto o valor da nota sobe ou desce; não há crédito nem pagamento na Troca, a diferença é paga no acerto.
- **Cabeçalho:** Semana nº, Representante, Praça e Situação da semana (**Em andamento** ou **Fechada**), com seletor de semanas anteriores.
- **Nova semana:** botão, com confirmação. Só uma semana aberta por representante; abrir a nova fecha a anterior. Funciona sem internet.
- **Semanas anteriores** abrem para acertar ou corrigir notas, mesmo **Fechada**; **Novo Pedido só na semana atual** (desabilitado nas outras).
- **Recibo** fica na semana em que o pagamento foi feito, mesmo de nota de semana anterior (o total recebido da semana bate com o dinheiro na mão).
- **Colunas de Pedidos:** Nota nº · Cliente · Data do retorno · Valor DX · Valor DV · Valor VD · Comis. % · Comissão · Valor Líq. · Situação. Dois cliques abrem o acerto da nota.
- **Aba Totais:** soma da semana: DX, DV, VD (valores e itens), comissão, líquido, total recebido (por forma) e total pendente.
- **Situação e cor da linha:**

| Situação | Quando | Cor |
| --- | --- | --- |
| Em aberto | Cliente com as peças, sem acerto. | Branca |
| Pendente | Acerto feito, cliente ficou devendo, até quitar. | Amarela |
| Fechado | Acerto feito e nada a pagar; a Pendente vira Fechado sozinha ao quitar. | Verde |
| Remarcado | Data do retorno mudada sem acerto; DV, VD, comissão e líquido aparecem zerados até o acerto. | Rosa |

### 23.5 Cadastro de cliente

Cada representante tem a sua lista (o Corona Gestão vê todos; o Corona Vendas só os do representante).

| Campo | Obrigatório | Detalhe |
| --- | --- | --- |
| Nome completo | Sim | Usado nas buscas. |
| Endereço | Sim | Rua, número, bairro, cidade, UF. |
| Foto do cliente | Sim | Câmera na hora ou arquivo. |
| Foto do documento | Sim | Frente e verso. |
| Indicado por | Não | Cliente já cadastrado **ou** nome digitado. |
| Como conheceu | Não | Texto livre. |
| Telefone / WhatsApp | Sim | Contato e envio de nota e relatórios. |
| Documento (CPF ou outro) | Não | Avisa cadastro duplicado. |
| Comissão % | Sim | Padrão 30%, de 0% a 40%. |

- As fotos são **arquivos** (como as de produto, §20; nunca binário no PostgreSQL). Tiradas sem internet ficam no computador e sobem quando houver conexão; a falta de envio da foto **nunca trava** a nota nem o acerto.
- A maleta **não** é campo do cadastro: o cadastro só **mostra** de quais maletas o cliente está com peças agora, a **pendência atual** e o **histórico de notas**, e tem o botão **Lançar pagamento** (§23.8).
- Fotos do cliente e do documento são dados pessoais: só o representante dono do cliente e a dona veem.

### 23.6 Saída de peças (Novo Pedido)

1. **Novo Pedido** cria a nota na **semana atual**; o número `semana-sequência` nasce sozinho no computador do representante (um aparelho por representante, §6 item 15; o servidor confere que não repete).
2. Escolher o **cliente** (busca por nome). Com pendência: aviso "Este cliente deve R$ X. O valor será somado ao próximo acerto."
3. **Data do retorno:** hoje + 45 dias, editável.
4. Escolher a **maleta** (o mesmo código pode estar em mais de uma).
5. **Bipar** cada peça: o sistema procura o código **na maleta escolhida**; linha nova Ref. · Tam./Ton. · Produto · **Maleta** · Qtd. DX · Preço unitário · Sub-total, ou soma 1 se o mesmo código e a mesma maleta já estão na lista; atualiza Total de itens e Total DX.
6. Trocar a maleta escolhida e continuar bipando põe peças de **outra maleta** na mesma nota.
7. Antes de finalizar: diminuir quantidade ou remover a linha.
8. **Finalizar:** a nota não aceita mais peças (só por Reposição) e as peças passam a "com o cliente". É imutável depois disso.
9. Gera a **nota de saída** (§23.11).

**Não deixa bipar** (mensagem, nada é adicionado): código fora da maleta escolhida (se estiver em outra maleta do representante, a mensagem diz qual); quantidade acima do disponível na maleta.

**Preço:** o da maleta (`preco_remessa`, congelado no envio, §7.1).

**Não existem:** desconto, entrada, limite de crédito, tipo de saída e NF-e, mesmo aparecendo no print.

**Saldo da maleta por eventos** (no Corona Vendas, substitui a fórmula da §7.1): `disponível = enviada − com clientes − vendida pelos clientes − devolvida à loja`. "Com clientes" sobe na saída, na Reposição e na entrada de troca, e desce na devolução do acerto e na peça que volta na troca; a venda pelo cliente só acontece no acerto.

### 23.7 Retorno e acerto (Cobrança)

1. **Encontrar a nota** pelo botão Cobrança (ou dois cliques na linha): por **nome do cliente** (as notas em aberto dele), por **código da maleta** (todos os clientes com peças dela e as notas de cada um) ou por **número da nota**. Um acerto **por nota**; um cliente pode ter várias notas em aberto.
2. **Aba Itens:** todas as linhas com Qtd. DX e Qtd. DV (começa em zero). Cada bipe soma 1 em DV. Mesmo código vindo de duas maletas na nota → pergunta para qual maleta volta. Código fora da nota ou DV acima de DX → avisa e não aceita. **Concluir devolução** lista as peças não bipadas: "Estas X peças serão consideradas vendidas. Confirmar?". Confirmado: as devolvidas voltam **à maleta de onde saíram** e ficam disponíveis; as vendidas saem do estoque da maleta.
3. **Aba Forma de Pagamento** (disposição do print): **Acerto** (Venda %, Comissão %, Comissão R$, Total a Receber Bruto); a Comissão % vem do cadastro e pode mudar **só neste acerto**, até 40%. **Pendências anteriores** (nota, data, valor e total do débito): entram sozinhas e não se removem. **Total a Receber** = Bruto + pendências anteriores. **Pagamento**: Dinheiro, Pix, Débito e Crédito à vista (**sem cheque e sem boleto**). **Total Pago** e **Ficou devendo**. Rodapé: Itens e Total DX / DV / VD.
4. **Salvar:** gera o **recibo** (se houve pagamento) e o **relatório do acerto**. Pagou tudo → **Fechado**; faltou → **Pendente** e o valor vira pendência do cliente.
   - **Dt. Acerto** (tarefa 9.8): hoje por padrão; pode ser um dia que já passou, nunca no futuro (a tela não deixa; o servidor confere de novo em Brasília, pelo relógio dele, como no Lançar pagamento). O acerto e o recibo dele usam essa data e a cotação desse dia (ADR-0006). O número do recibo continua sendo da semana aberta no momento do lançamento (§23.4). A ordem dos débitos não muda com a data: vale a ordem em que os acertos foram lançados (§23.8). **Relógio errado (21/11/2026):** com a diferença de relógio guardada do último contato com o servidor (ADR-0013, item 11), o Vendas confere a data pelo "hoje" e pelo dia da saída como o servidor os verá; data anterior à saída corrigida mostra erro em português e não deixa salvar. O acerto de hoje (sem escolher data) não é barrado. Sem diferença guardada, vale a conferência de antes. Limite conhecido: uma nota já enviada mas ainda não devolvida pelo recebimento (situação ENVIADO na fila, que nunca sai dela) segue com a saída da hora local, sem correção, até o servidor devolvê-la. O acerto salvo mostra "Deve agora" quando o saldo da nota mudou depois do acerto, e a ficha do cliente mostra a sobra "a aplicar".
5. **Cliente não veio:** mudar a Data do retorno → **Remarcado** (rosa).
6. **Corrigir** um acerto já salvo (inclusive de semana anterior) é **estornar** o acerto (registro novo, com motivo) e fazer de novo; nada se edita. O estorno desfaz os movimentos de maleta e os pagamentos daquele acerto.

### 23.8 Cálculos, pagamento e pendência

Caso de teste obrigatório (print da nota `0000064-21`):

| Conta | Fórmula | Exemplo |
| --- | --- | --- |
| Itens VD | Itens DX − Itens DV | 26 − 15 = **11** |
| Valor VD | Valor DX − Valor DV | 5.370,00 − 3.060,00 = **2.310,00** |
| Venda % | VD ÷ DX × 100 (só mostrado, 2 casas) | **43,02%** |
| Comissão R$ | VD × Comissão % (0% a 40%), HALF_UP 2 casas | 2.310,00 × 30% = **693,00** |
| Total a Receber Bruto (= Valor Líq.) | VD − Comissão | **1.617,00** |
| Pendências anteriores | Soma do que o cliente deve de antes | nota `0000063-32`: **498,10** |
| Total a Receber | Bruto + pendências anteriores | **2.115,10** |
| Total Pago | Dinheiro + Pix + Débito + Crédito | 1.470,00 + 300,00 = **1.770,00** |
| Ficou devendo | Total a Receber − Total Pago | **345,10** |

- Comissão **sobre o VD**, nunca sobre o DX; acima de 40% é recusado (na tela e no servidor).
- "Ficou devendo" sempre positivo. "Valor Líquido" é sempre VD − Comissão (o "T. Líquido" do print, que mostra o VD, **não** é seguido).
- Pagar mais que o Total a Receber é recusado.
- **Ordem do pagamento no acerto:** o pago quita primeiro as **pendências anteriores, da mais antiga para a mais nova**, e depois o bruto desta nota; o que sobra sem pagar vira a pendência desta nota. Cada nota de origem quitada passa sozinha a **Fechado**. "Mais antiga" é a do acerto **lançado** antes (proposta da tarefa 9.8, esperando aprovação): um acerto lançado hoje com Dt. Acerto de um dia que já passou continua sendo o mais novo.
- **Lançar pagamento** (no cadastro do cliente, sem esperar o acerto): valor, forma e data; gera recibo; abate a pendência **mais antiga primeiro**; não passa do total devido.
- A pendência **nunca** é removida nem editada: só diminui por pagamento. Pagamento errado se corrige por **estorno** (registro novo, com motivo), que reabre a pendência.
- Exemplo: semana 1 bruto 400, pago 300 → deve 100; semana 2 bruto 500 + 100 = 600, pago 550 → deve 50; pagamento lançado de 50 → deve 0.
- Formas aceitas: `DINHEIRO`, `PIX`, `DEBITO`, `CREDITO` (crédito à vista). **Não existem** cheque, boleto, crédito parcelado nem entrada. Nada é executado: só registrado (§7.5).
- O servidor recalcula DX, DV, VD, comissão, pendências e situação de cada nota a partir dos itens e pagamentos; nunca confia no que o app mandou. O que aconteceu no balcão sem internet nunca é recusado em silêncio: divergência vira alerta para a dona.

### 23.9 Sem internet

Nova semana, cadastro de cliente (com fotos), Novo Pedido, Reposição, Troca, Remarcar, acerto, Lançar pagamento e estornos funcionam **sem internet** e vão pela outbox (§10), cada um com UUID do cliente, idempotentes, com o número `semana-sequência` gerado no computador. O que o servidor recusar aparece em português simples dizendo o que fazer. O pull traz clientes, semanas, notas, acertos, pagamentos e pendências **só do representante**.

### 23.10 Fechamento da maleta (Corona Gestão)

Quando a maleta volta, **a Lucia bipa** as peças no Corona Gestão (o representante não bipa). O relatório **Fechamento da maleta** mostra: peças enviadas, devolvidas pelos clientes, vendidas e ainda com clientes (com quem), as bipadas na volta e a diferença; valor vendido (VD), comissão dos clientes, valor recebido e pendências a receber. O acerto em dinheiro com a Lucia fica fora do sistema por enquanto (§23.1, item 2).

**Duas partes separadas (9.7, pedido da dona de 19/11).** A tela tem duas abas: **Peças da maleta** (enviadas, devolvidas pelos clientes, vendidas, ainda com clientes e com quem, esperadas, lidas na volta e a diferença) e **Dinheiro** (valor vendido, comissão dos clientes, valor recebido e pendências a receber, cada um em Real, Dólar e Guarani: cada acerto e cada recibo pela cotação do dia dele, e os resultados somados; faltando a cotação de algum, só o Real, 9.8). O PDF, o Excel e o CSV saem com as duas partes como seções distintas, uma depois da outra. Ler uma peça que existe mas é de outra maleta diz "Esta peça não é desta maleta" e, se achar, de qual maleta ela é (erro `PECA_NAO_E_DESTA_MALETA`, 422). A tela e o relatório existem em espanhol (padrão) e português.

O menu esconde, pela mesma configuração `GET /api/configuracoes/telas` do acerto antigo, também o grupo **Devoluções e créditos** e **Vendas de computador bloqueado** (fluxo antigo de venda ao consumidor); nada é apagado e as rotas continuam. O cadastro de cliente pela dona tem **Comissão %** (0 a 40, padrão 30), documento livre com tipo (CPF, RG, CI ou outro; só o tipo CPF exige dígito válido), indicado por e como conheceu; o representante do cliente não muda depois de definido (D10).

### 23.11 Relatórios

Em todo relatório e tela de informação: **DX, DV e VD** (valores) e **Itens DX, DV e VD**. Todos imprimem, salvam em PDF e preparam o envio pelo WhatsApp (o sistema prepara o arquivo e a mensagem; quem envia é o representante).

| Relatório | Para | Conteúdo |
| --- | --- | --- |
| Nota de saída | Cliente | Número, data de saída e de retorno, representante, dados do cliente, peças (Ref. · Produto · Tam./Ton. · Maleta · Qtd. · Preço unitário · Sub-total), Itens DX, Total DX, pendência anterior, espaço para assinatura. Comprovante interno, sem NF-e. |
| Relatório do acerto | Cliente | Nota, cliente, datas de saída e de acerto, peças vendidas, DX/DV/VD, comissão, pendências anteriores, total a receber, pago por forma, ficou devendo. |
| Situação do cliente | Cliente | Já vendeu (notas fechadas: DX, DV, VD, comissão, líquido, pago) e o que falta (peças com ele, maleta, data de retorno, quanto deve). |
| Peças vendidas por cliente | Representante | Cliente e período: cada peça vendida (Ref., produto, quantidade, valor) e total. |
| Totais da semana | Representante | A aba Totais. |
| Situação da maleta | Representante | Peças na maleta, com clientes (com quem) e vendidas. |
| Fechamento da maleta | Lucia | §23.10. |

Valores em Real, com Dólar e Guarani abaixo, pela cotação do dia de cada lançamento (ADR-0006). **Em cada linha (9.8):** o sub-total da Nota de saída, o valor de cada peça vendida do Relatório do acerto, cada nota da Situação do cliente (DX, DV, VD, comissão, líquido, pago e quanto ainda deve) e cada peça das Peças vendidas por cliente trazem Dólar e Guarani pela cotação do dia do lançamento da própria linha (a da saída da nota, ou a do acerto, a Dt. Acerto). Sem cotação para o lançamento, só o Real e o texto "Sem cotação". Onde um total soma lançamentos de dias diferentes (Situação do cliente, Totais da semana, Situação da maleta), cada lançamento é convertido pela sua cotação e os resultados se somam; faltando a cotação de algum, o total sai só em Real.

**Como saem (Corona Vendas, tarefa 9.3):** cada relatório é montado do banco local (sem internet), com as mesmas fórmulas de `packages/shared/consignado`, e a mesma folha serve para **imprimir**, **salvar em PDF** e **preparar o WhatsApp**. Preparar o WhatsApp = o sistema guarda o PDF numa pasta (Documentos › Corona Vendas › Relatórios), mostra a pasta e abre a conversa do cliente (`https://wa.me/<número>?text=...`, no navegador padrão) com uma mensagem curta pronta; **o representante anexa o PDF e envia, nada é enviado sozinho**. Abrir o WhatsApp precisa de internet; sem ela o PDF fica guardado e a tela avisa. Os relatórios do próprio representante (Totais da semana, Situação da maleta) abrem o WhatsApp sem número, e ele escolhe a conversa. Cabeçalho de todos: Representante e Praça (a praça do cadastro do representante, que chega com as maletas). A Nota de saída é gerada de novo a cada Reposição na semana atual.

**Tam./Ton. (9.8).** O produto ganha o campo opcional **Tam./Ton.** (texto curto, até 30 letras, ex.: "17" ou "Dourado"; coluna `produtos.tam_ton`, V057), preenchido no cadastro do produto do Corona Gestão e aceito como coluna opcional na importação por planilha (títulos Tam./Ton., Tamanho/Tom ou Talle/Tono; planilha sem o valor não apaga o que o produto já tem). Vai ao Corona Vendas só no pull de **formato 2** (o formato 1 nunca leva a coluna) e volta como coluna **Tam./Ton.** no Novo Pedido, na Cobrança, na Nota de saída e no Relatório do acerto; vazio quando a peça não tem. Não é dinheiro nem custo.

### 23.12 Fora desta versão

Limite de crédito do cliente; acerto em dinheiro com a Lucia; desconto; entrada; NF-e; acesso do cliente.
