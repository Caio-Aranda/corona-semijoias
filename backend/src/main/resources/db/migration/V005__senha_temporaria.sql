-- Senha temporária e troca de credencial (tarefa 10; ADR-0004, itens 2, 6 e 8). Só acrescenta colunas à
-- tabela só do servidor "usuarios"; a V004 não é editada.

ALTER TABLE corona.usuarios
    ADD COLUMN troca_de_senha_obrigatoria boolean NOT NULL DEFAULT false,
    ADD COLUMN senha_temporaria_vence_em timestamptz,
    ADD COLUMN credencial_alterada_em timestamptz,
    ADD CONSTRAINT usuarios_vencimento_so_com_troca
        CHECK (senha_temporaria_vence_em IS NULL OR troca_de_senha_obrigatoria);

COMMENT ON COLUMN corona.usuarios.troca_de_senha_obrigatoria IS
    'true = a senha atual é temporária: o login só devolve o token restrito (escopo TROCA_SENHA) até a pessoa criar a senha nova. ADR-0004, item 6.';
COMMENT ON COLUMN corona.usuarios.senha_temporaria_vence_em IS
    'Quando a senha temporária deixa de valer (UTC). NULL com troca obrigatória = sem vencimento (primeiro administrador, cuja senha veio do .env). A senha definitiva não expira.';
COMMENT ON COLUMN corona.usuarios.credencial_alterada_em IS
    'Última redefinição ou troca de senha (UTC); NULL = nunca. Token de acesso emitido antes dela é recusado (ADR-0004, item 2: revoga as sessões até a tarefa 11 trazer as famílias).';
