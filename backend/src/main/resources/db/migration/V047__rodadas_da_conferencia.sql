-- V047: o resultado de cada rodada da conferência diária (Fase 8, tarefa 8.4; ADR-0015, item 6; plano aprovado no PR #195).
--
-- Só do servidor: nunca vai ao Corona Vendas nem ao sync. As duas tabelas só recebem INSERT (as convenções do servidor recusam
-- UPDATE, DELETE e TRUNCATE, e nada se apaga, nem logicamente: o histórico fica para sempre). Esperado e encontrado são texto com o
-- número exato (BigDecimal.toPlainString): as unidades são reais, peças, registros e percentual, e nada daqui é somado.

CREATE TABLE corona.rodadas_conferencia (
    id                uuid PRIMARY KEY,
    origem            text NOT NULL CONSTRAINT rodadas_conferencia_origem CHECK (origem IN ('AUTOMATICA', 'PEDIDA')),
    pedida_por        uuid REFERENCES corona.usuarios (id),
    iniciada_em       timestamptz NOT NULL,
    terminada_em      timestamptz NOT NULL,
    situacao          text NOT NULL CONSTRAINT rodadas_conferencia_situacao CHECK (situacao IN ('SEM_DIFERENCA', 'COM_DIFERENCA', 'FALHOU')),
    chaves_conferidas text[] NOT NULL,
    chaves_com_falha  text[] NOT NULL DEFAULT '{}',
    total_diferencas  integer NOT NULL CONSTRAINT rodadas_conferencia_total CHECK (total_diferencas >= 0),
    CONSTRAINT rodadas_conferencia_quem_pediu CHECK ((origem = 'PEDIDA') = (pedida_por IS NOT NULL)),
    CONSTRAINT rodadas_conferencia_tempo CHECK (terminada_em >= iniciada_em),
    CONSTRAINT rodadas_conferencia_falha CHECK ((situacao = 'FALHOU') = (cardinality(chaves_com_falha) > 0)),
    CONSTRAINT rodadas_conferencia_sem_diferenca CHECK (situacao <> 'SEM_DIFERENCA' OR total_diferencas = 0)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.rodadas_conferencia', editavel => false);
CREATE INDEX rodadas_conferencia_recentes ON corona.rodadas_conferencia (iniciada_em DESC);
COMMENT ON TABLE corona.rodadas_conferencia IS
    'Uma rodada da conferência diária (ADR-0015, item 6): automática (agenda) ou pedida pela dona. Imutável, guardada para sempre, só do servidor.';

CREATE TABLE corona.diferencas_conferencia (
    id         uuid PRIMARY KEY,
    rodada_id  uuid NOT NULL REFERENCES corona.rodadas_conferencia (id),
    chave      text NOT NULL,
    item       text,
    tipo       text,
    referencia text,
    descricao  text NOT NULL,
    esperado   text,
    encontrado text,
    unidade    text CONSTRAINT diferencas_conferencia_unidade CHECK (unidade IN ('REAIS', 'PECAS', 'REGISTROS', 'PERCENTUAL')),
    ordem      integer NOT NULL
);
SELECT corona.aplicar_convencoes_do_servidor('corona.diferencas_conferencia', editavel => false);
CREATE INDEX diferencas_conferencia_por_rodada ON corona.diferencas_conferencia (rodada_id, ordem);
COMMENT ON TABLE corona.diferencas_conferencia IS
    'As linhas que não bateram numa rodada da conferência diária. Imutável, só do servidor. Nada daqui é corrigido sozinho.';
