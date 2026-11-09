# Progresso

Atualizado pela implementadora ao fim de cada tarefa: o que foi feito, o que ficou pendente e as decisões tomadas.

## Fase 0

### Tarefa 1: especificação v1.1 com as decisões da Fase 0

**O que foi feito**
- `docs/ESPECIFICACAO.md` atualizado para a versão 1.1 (08/11/2026) com as 11 mudanças da tarefa: perfis (§3), API (§5), premissa do aparelho por vendedor (§6), arredondamento HALF_UP escala 2 (§7.5 e §13), tempo máximo offline e limite seguro do pull (§9 e §10), segurança (§11), fluxo Git e árvore do repositório (§14), registro de problemas de uso (§19).
- Nova seção 22, "Histórico de mudanças".
- Criado `docs/PROBLEMAS-DE-USO.md` com um modelo de registro.

**O que ficou pendente**
- Nada desta tarefa. Seguem fora do escopo: glossário (tarefa 2), ADRs (tarefa 3), pendências do cliente (§17 e §21).

**Decisões tomadas**
- §3: o texto diz que a API recusa o perfil incompatível com o app no login e que cada rota exige o perfil certo. Não diz "valida o tipo de app por rota", para não contradizer o §11 (o cabeçalho de tipo de app não é segurança). A forma de implementar os perfis fica para o ADR-0004 e a tarefa 9.
- Os termos "issue", "Closes #", "main protegida" e `loja-semijoias` aparecem só na seção 22. Nas seções 14 e 19 o novo fluxo é descrito sem citá-los.
- Os nomes exatos dos cabeçalhos de versão e de tipo de app não foram definidos na especificação.
- A estratégia do limite seguro do pull não foi detalhada; fica no ADR-0002.

### Tarefa 2: glossário da interface e especificação v1.2

**O que foi feito**
- Criado `docs/glossario-interface.md` com as 10 partes pedidas: termos da loja, lista de palavras proibidas em dois blocos com marcadores (A, sem diferenciar maiúsculas; B, só a forma exata em maiúsculas), situações na tela, botões e posição, modelo de mensagem, mensagens padrão da Fase 0, estado de conexão do app do vendedor, formatos, termos a confirmar e dúvidas para a dona.
- `docs/ESPECIFICACAO.md` na versão 1.2 (09/11/2026): §14 passa a dizer que o prefixo da branch é o tipo do Conventional Commits; §22 com a entrada da v1.2. Nenhuma outra linha mudou.

**O que ficou pendente**
- Verificador automático das palavras proibidas (fora de escopo; o glossário só entrega as listas).
- As 12 dúvidas da parte 10 do glossário, que dependem da dona ou do responsável.

**Decisões tomadas**
- "Cancelar" segue a §19.5: o botão genérico é "desistir sem salvar"; cancelar venda, pagamento ou devolução é ação nomeada com o objeto, com confirmação dizendo que nada é apagado. "Anular" não foi adotado e ficou só como dúvida para a dona.
- Posição dos botões (Voltar no alto à esquerda; Cancelar e depois o botão principal no rodapé, à direita) é proposta deste glossário, pois a especificação só pede posição igual.
- Regras da senha nova e `{minutos}` do bloqueio são valores propostos, a fixar na decisão de autenticação.
- Conflito não resolvido na especificação (registrado como dúvida 12, sem alterar a especificação): a §12 usa "Remessas", "Movimentações" e "sincronização" em nomes de relatório e no aviso de dados, o que conflita com §19.1 e §19.11.

### Tarefa 2.1: decisões do responsável (especificação v1.3 e glossário)

**O que foi feito**
- `docs/ESPECIFICACAO.md` na versão 1.3 (09/11/2026): nomes Corona Gestão e Corona Vendas (§18); "leia o código de novo" no lugar de "bipe novamente" (§8); senha nova, senha da própria dona, prazo offline esgotado e bloqueio de 15 minutos após 5 erros (§11); `configuracoes` com tentativas de login e tempo de bloqueio (§9); novo aviso de dados e nota sobre nomes de relatório (§12); pendência 12, "Entrada" (§17); regras de "vendedor", "ler o código", "produto" e "peça" (§19.1); "envios com problema" (§19.3); entrada v1.3 na §22.
- `docs/glossario-interface.md`: seção "Decisões tomadas"; "bipar" e "bipe" no bloco A de palavras proibidas; nomes dos aplicativos nos termos e nas mensagens de aplicativo errado; senha, bloqueio e prazo offline com os valores definitivos; textos de "itens" e "vendas" aguardando envio; "Entrada" como a confirmar; parte 10 só com a dúvida da "Entrada".

**O que ficou pendente**
- Pendência 12 da §17 ("Entrada"): precisa de resposta do cliente antes da Fase 3. A regra de negócio não foi definida.
- Nome de tela de cada relatório: definido na tarefa daquele relatório.

**Decisões tomadas**
- Acréscimo autorizado pelo responsável: a §9 `configuracoes` passou a listar "tentativas de login antes do bloqueio (padrão 5)" e "tempo de bloqueio (padrão 15 minutos)", coerente com a §11.
- A §7, a §8 e a §20 continuam com "bipar" como descrição técnica, como a decisão permite.
- A §12 e a §22 da especificação não alteram nenhuma outra regra.
