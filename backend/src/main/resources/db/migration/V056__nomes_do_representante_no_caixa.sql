-- V056: "representante" no lugar de "vendedor" nos textos gravados do caixa (Leva 9, tarefa 9.8, item 4; glossário da interface).
--
-- Só o nome que aparece na tela muda: as duas categorias do sistema e a descrição dos lançamentos automáticos. Códigos, ids,
-- origens (RECEBIMENTO_VENDEDOR, ACERTO_A_FAVOR_DO_VENDEDOR) e valores não mudam. A categoria do sistema e o lançamento são
-- protegidos por gatilho (nome fixo, lançamento imutável), então os gatilhos de usuário saem só durante este UPDATE de dados.

ALTER TABLE corona.categorias_caixa DISABLE TRIGGER USER;
UPDATE corona.categorias_caixa SET nome = 'Repasse do representante' WHERE sistema AND nome = 'Repasse do vendedor';
UPDATE corona.categorias_caixa SET nome = 'Pagamento ao representante' WHERE sistema AND nome = 'Pagamento ao vendedor';
ALTER TABLE corona.categorias_caixa ENABLE TRIGGER USER;

ALTER TABLE corona.lancamentos_caixa DISABLE TRIGGER USER;
UPDATE corona.lancamentos_caixa SET descricao = 'Recebimento do representante' WHERE descricao = 'Recebimento do vendedor';
UPDATE corona.lancamentos_caixa SET descricao = 'Estorno de recebimento do representante' WHERE descricao = 'Estorno de recebimento do vendedor';
UPDATE corona.lancamentos_caixa SET descricao = 'Pagamento ao representante no acerto' WHERE descricao = 'Pagamento ao vendedor no acerto';
ALTER TABLE corona.lancamentos_caixa ENABLE TRIGGER USER;
