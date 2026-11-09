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
