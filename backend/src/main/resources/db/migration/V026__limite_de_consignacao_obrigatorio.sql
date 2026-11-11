-- V026: o limite de consignação do vendedor passa a ser um campo a preencher, com a opção explícita "Sem limite"
-- (decisão do responsável de 11/11/2026; ADR-0010, item 6, linha de 11/11/2026).
--
-- Antes, limite 0 queria dizer "sem limite": um vendedor novo, com o campo em branco, recebia maleta ilimitada sem
-- ninguém perceber. Agora há três situações, e zero NÃO é uma delas ("sem limite"):
--   * limite definido (limite_consignacao >= 0, em reais; 0 vale 0: não pode levar peça nenhuma);
--   * "sem limite" (limite_consignacao_ilimitado = true e limite_consignacao NULL);
--   * sem limite definido (os dois vazios): a remessa é recusada até a dona definir.
--
-- Vendedores que já tinham limite maior que zero mantêm. Os que estavam com 0 (que era "sem limite cadastrado") ficam
-- "sem limite definido", o padrão seguro: a dona define o limite, ou marca "Sem limite", antes da próxima maleta. As
-- maletas já enviadas não mudam.

ALTER TABLE corona.vendedores ALTER COLUMN limite_consignacao DROP NOT NULL;
ALTER TABLE corona.vendedores ALTER COLUMN limite_consignacao DROP DEFAULT;
ALTER TABLE corona.vendedores ADD COLUMN limite_consignacao_ilimitado boolean NOT NULL DEFAULT false;

UPDATE corona.vendedores SET limite_consignacao = NULL WHERE limite_consignacao = 0;

ALTER TABLE corona.vendedores ADD CONSTRAINT vendedores_limite_ou_sem_limite
    CHECK (NOT (limite_consignacao_ilimitado AND limite_consignacao IS NOT NULL));

COMMENT ON COLUMN corona.vendedores.limite_consignacao IS
    'Limite de consignação em reais, a preço de venda congelado. NULL = sem limite definido (ou "Sem limite", ver limite_consignacao_ilimitado). Zero vale zero.';
COMMENT ON COLUMN corona.vendedores.limite_consignacao_ilimitado IS
    'A dona escolheu "Sem limite" de propósito (V026). Só vale com limite_consignacao NULL.';
