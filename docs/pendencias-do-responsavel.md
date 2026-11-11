# Pendências do responsável

O que só o responsável decide ou faz. As perguntas da dona estão em [perguntas-para-a-dona.md](perguntas-para-a-dona.md).
Situação em 11/11/2026.

## Decisões tomadas em 11/11/2026 (registro)

Estas saíram da lista da dona: são do sistema ou do responsável.

1. **Saída recusada quando falta peça.** Não é decisão da dona: é validação do sistema. O saldo nunca fica negativo (vale o [ADR-0008](decisoes/ADR-0008-estoque-e-movimentacoes.md), item 4). Saída, perda, remessa, ajuste ou estorno que deixaria o saldo abaixo de zero é recusado, e a dona lança a compra antes. A dúvida da tarefa 2.1 está encerrada.
2. **Limite de consignação** (dúvidas 1, 2 e 6 do [ADR-0010](decisoes/ADR-0010-maleta-e-remessa.md)):
   - conta o **preço de venda congelado na remessa** (não o custo);
   - a **reposição entra na conta**: conta tudo o que o vendedor tem em mãos;
   - o campo do limite no cadastro do vendedor passa a ser **obrigatório**, com a opção explícita **"Sem limite"**. **Zero deixa de significar "sem limite".**
   - A mudança no código (migração, tela, mensagens e testes) é **outra tarefa**, ainda não feita. O ADR-0010 e a §17/§21 da especificação devem ser atualizados nessa tarefa.

## Respostas do responsável (11/11/2026)

Também saíram da lista da dona:

3. **Consulta fora da maleta** (dúvida 6 do ADR-0011): o vendedor vê **só a própria maleta**. O Corona Vendas baixa só as fotos das peças da maleta dele (§21, pendência nova 1).
4. **Prazo para o vendedor estornar** (dúvida 3 do ADR-0011): **72 horas** a partir da venda (hoje o padrão provisório é "até o fim do dia"). Depois disso, só a dona estorna. A mudança no código é outra tarefa.
5. **Data de nascimento do cliente no app do vendedor:** **sim**, só dos clientes dele (dúvida da tarefa 1.14).
6. **Garantia:** **1 ano** (§17, item 4). O prazo por arrependimento continua com a dona (pergunta 11).
7. **Diferença de troca e reembolso** (§17, itens 5 e 7): a loja **não faz** essas transações pelo sistema, só **registra**. O sistema terá um **campo para digitar** o valor e como foi acertado; sem regra de crédito automático nem limite semanal de reembolso. Detalhar na Fase 6.

## Pendências

| # | O que fazer | Impacto | O que está parado por causa disso |
| --- | --- | --- | --- |
| 1 | **Contratar a VPS e entregar o IP fixo** | Sem IP não há certificado (ele é gerado para o IP) nem instalador apontando para o servidor. Se o IP mudar depois, os programas instalados param de conectar (ADR-0009). | Implantação real, teste de ponta a ponta na VPS, `api:redefinir-senha` dentro do contêiner, entrega à dona ([entrega-corona-gestao.md](entrega-corona-gestao.md)). |
| 2 | **Cadastrar a chave SSH pública na VPS** | Sem ela não há acesso seguro (a senha do SSH é desligada). | Script de preparação da VPS e publicação das atualizações pela rede (`ssh`/`scp`), testada só em pasta local (tarefa 9.5). |
| 3 | **Autorizar o Google Drive no `rclone`** e decidir se usa `rclone crypt` (a senha do `crypt` fica guardada fora da VPS) | Sem isso o backup fica só dentro da VPS: se ela for perdida, os dados vão junto. | Cópia semanal fora da VPS e o teste de restauração a partir do Drive ([restauracao-de-backup.md](restauracao-de-backup.md)). |
| 4 | **Escolher o aviso de queda** (`ntfy` ou e-mail) e ligar a verificação externa (UptimeRobot) | Sem aviso, ninguém fica sabendo se a API cair ou o disco encher. | Monitoramento da tarefa 9.2 (pronto, mas desligado). |
| 5 | **Aceitar o ADR-0005** (SQLCipher no Electron) | É a base do banco criptografado do Corona Vendas. Inclui aceitar o risco de perder a `outbox` num computador reinstalado sem internet. | **Fase 5** (offline e sync) inteira. |
| 6 | **Aceitar o ADR-0011** (venda do vendedor online) | Regras de venda, estorno e totais no servidor; os padrões provisórios das dúvidas 1 a 6 valem até a dona responder. | Fechamento da Fase 4 (tarefas 4.3 a 4.5 já usam o ADR). |
| 7 | **Aceitar o ADR-0007** (códigos das etiquetas) | A Fase 1 e 2 usam o padrão seguro dele (aceita código da loja, do fornecedor e EAN-13). Pode esperar as respostas 18 a 22 da dona. | Ajuste da §8, do `CLAUDE.md` e do glossário; impressão de etiquetas, se a dona quiser. |
| 8 | **Testar com leitor de código físico e com o app instalado** (Corona Gestão e Corona Vendas, no Windows) | Até hoje o leitor só foi testado "como teclado" nos testes automáticos; o pacote instalado nunca foi usado de verdade. | Confiança nas telas de cadastro, compra, contagem e maleta; homologação do leitor (Fase 9). Comprar o leitor **só depois** de testar com etiquetas reais (§17, item 2). |
| 9 | **Pendência 12 da §17 ("Entrada")** — levar à dona (pergunta 14) e decidir com a resposta | Muda o acerto: se for valor pago pelo vendedor ao levar a maleta, entra no cálculo do acerto; se for entrada de venda parcelada, entra nas parcelas. A §17 dizia "antes da Fase 3" e ainda não foi respondida. | **Fase 7** (acerto) e, se for entrada de parcela, as parcelas da venda. |
| 10 | **Trocar o ícone provisório** dos dois instaladores (hoje vem de `docs/marca`) | Só aparência; o programa funciona. | Entrega com a marca final. |
| 11 | **Assinatura digital do instalador** (se um dia quiser) — e quem paga o certificado (§17, item 10) | Sem assinatura, o Windows mostra o aviso "editor desconhecido" ao instalar. Funciona, mas assusta a dona e os vendedores. | Nada trava; é melhoria da Fase 9 completa. |
| 12 | **Domínio próprio** (se um dia quiser) — e quem paga (§17, item 10) | Com domínio entra o Let's Encrypt e acaba o risco de troca de IP (item 1). Hoje vale o certificado fixado no IP (ADR-0009). | Nada trava; é melhoria da Fase 9 completa. |
| 13 | **Levar as perguntas à dona** ([perguntas-para-a-dona.md](perguntas-para-a-dona.md)) e trazer as respostas | As 🔴 travam a Fase 5 (1 e 2), a Fase 6 (11), a venda (8 e 9) e a Fase 7 (12 a 16). | Ver a lista. |

## O que não depende de ninguém agora

Os demais itens de "Pendências abertas" do [PROGRESSO.md](PROGRESSO.md) (rotas do sync, chaves estrangeiras, `npm audit`, retenção de `sessoes`, etc.) são tarefas técnicas das próximas fases e não precisam de decisão do responsável.
