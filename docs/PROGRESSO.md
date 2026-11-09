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
