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
