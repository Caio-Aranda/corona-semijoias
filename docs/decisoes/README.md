# Decisões de arquitetura (ADRs)

Um ADR (registro de decisão de arquitetura) guarda **uma decisão** que a equipe não quer rediscutir a cada tarefa: o que foi decidido, por quê, o que isso custa e o que foi descartado. Eles existem porque algumas escolhas, como o formato dos dados e a forma de sincronizar, não podem mudar depois que a primeira migração do banco é publicada (migrações publicadas nunca são editadas).

A especificação (`docs/ESPECIFICACAO.md`) manda. Um ADR detalha a especificação, nunca a contradiz. Se uma decisão exigir mudar a especificação, o ADR registra a dúvida e a especificação só muda por tarefa própria, aprovada pelo responsável.

## Índice

| ADR | Título | Status | Tarefas da Fase 0 que implementam |
| --- | --- | --- | --- |
| [0001](ADR-0001-stack-e-versoes.md) | Stack e versões | Aceito | 4, 5, 6, 7, 13, 15, 16 |
| [0002](ADR-0002-convencoes-de-dados-e-sync-seq.md) | Convenções de dados e `sync_seq` seguro | Aceito | 5, 6, 8, 9, 11 |
| [0003](ADR-0003-separacao-gestao-vendas.md) | Separação entre o Corona Gestão e o Corona Vendas | Aceito | 4, 6, 13, 14, 15, 16 |
| [0004](ADR-0004-autenticacao-e-sessao.md) | Autenticação e sessão | Aceito | 9, 10, 11, 12, 15, 16 |
| [0005](ADR-0005-sqlcipher-no-electron.md) | SQLCipher no Electron | Proposto | 18 (spike) |
| [0006](ADR-0006-moedas-e-cotacao.md) | Moedas e cotação | Aceito | 19 (e telas das Fases 1, 4, 5 e 7) |
| [0007](ADR-0007-codigos-das-etiquetas.md) | Códigos das etiquetas | Proposto | 1.5, 1.11 (Fase 1) |
| [0008](ADR-0008-estoque-e-movimentacoes.md) | Estoque e movimentações | Aceito | 2.2 a 2.5 (Fase 2); reutilizado na Fase 3 |
| [0009](ADR-0009-implantacao-certificado-atualizacao-e-backup.md) | Implantação, certificado fixado, atualização e backup | Proposto | 9.2 a 9.6 (Fase 9 mínima) |

A numeração das tarefas é a do "Plano da Fase 0" em `docs/PROGRESSO.md`.

## Como usar

- **Status:** `Proposto` (escrito, aguardando o responsável), `Aceito` (aprovado), `Substituído por ADR-NNNN` ou `Rejeitado`. Só o responsável muda um ADR de `Proposto` para `Aceito`, e isso é feito num Pull Request que altera apenas a linha de status.
- **ADR aceito não se reescreve.** Para mudar uma decisão, crie um ADR novo que a substitui e marque o antigo como `Substituído por ADR-NNNN`. Correção de erro de digitação pode ser feita direto.
- **Corrigir uma regra ainda não usada.** Se nenhuma tarefa usou a regra, ela pode ser corrigida no próprio ADR, com uma linha datada no cabeçalho (por exemplo, "Corrigido em dd/mm/aaaa: ..."). Mudança de decisão que já está em uso pede um ADR novo.
- **Quando criar um ADR:** troca de versão maior de qualquer item do ADR-0001 (com a exceção do Electron descrita lá), mudança de formato de dados, de sincronização, de autenticação ou da barreira entre os dois aplicativos. Dúvida de produto vai para a seção de dúvidas da especificação ou do glossário, não para um ADR.
- **Nome do arquivo:** `ADR-NNNN-titulo-curto.md`, em português, sem acentos no nome do arquivo.

## Modelo

```markdown
# ADR-NNNN: Título curto

- **Status:** Proposto
- **Data:** dd/mm/aaaa
- **Tarefas que implementam:** números do Plano da Fase 0

## Contexto
O problema, as restrições (cite as seções da especificação) e por que decidir agora.

## Decisão
O que ficou decidido, em frases diretas, com exemplos quando ajudarem.

## Consequências
### Boas
### Ruins ou custos

## Alternativas consideradas
Cada alternativa e o motivo de ter sido descartada.

## Seções da especificação relacionadas
§X, §Y.

## Dúvidas
O que ficou sem decisão e quem precisa responder. Nenhuma dúvida muda a especificação sozinha.
```
