-- V033: registro dos envios do Corona Vendas (sync_log) e a marca "hora ajustada" na venda (Fase 5, tarefa 5.5;
-- ADR-0013, itens 2 e 11; decisão do responsável de 11/11/2026 sobre a retenção).
--
-- Uma linha por operação recebida no push, com o operacao_id do app como chave: é o que torna o reenvio inofensivo
-- (mesmo id e mesmo resumo = JA_RECEBIDO; mesmo id e outro conteúdo = CONFLITO). Toda recusa e todo conflito ficam
-- aqui para a dona resolver ("envios com problema": Tentar de novo ou Descartar). Nada é apagado: depois de 90 dias o
-- JSON completo vira vazio (a limpeza agendada faz UPDATE) e fica o resumo (quem, quando, tipo, resultado e o SHA-256).

CREATE TABLE corona.sync_log (
    id uuid PRIMARY KEY,
    dispositivo_id uuid NOT NULL CONSTRAINT sync_log_dispositivo_cadastrado REFERENCES corona.dispositivos (id),
    usuario_id uuid NOT NULL CONSTRAINT sync_log_usuario_existe REFERENCES corona.usuarios (id),
    vendedor_id uuid NOT NULL CONSTRAINT sync_log_vendedor_existe REFERENCES corona.vendedores (id),
    tipo text NOT NULL CONSTRAINT sync_log_tipo_formato CHECK (tipo ~ '^[A-Z][A-Z_]*$' AND length(tipo) <= 40),
    formato integer NOT NULL CONSTRAINT sync_log_formato_positivo CHECK (formato >= 1),
    resumo text NOT NULL CONSTRAINT sync_log_resumo_sha256 CHECK (resumo ~ '^[0-9a-f]{64}$'),
    resultado text NOT NULL CONSTRAINT sync_log_resultado_conhecido CHECK (resultado IN (
        'ACEITO', 'JA_RECEBIDO', 'CONFLITO', 'RECUSADO')),
    codigo text CONSTRAINT sync_log_codigo_curto CHECK (length(codigo) <= 80),
    mensagem text CONSTRAINT sync_log_mensagem_curta CHECK (length(mensagem) <= 1000),
    recebido_em timestamptz NOT NULL,
    hora_declarada timestamptz,
    hora_corrigida timestamptz,
    diferenca_do_relogio_ms bigint,
    dados jsonb,
    dados_esvaziados_em timestamptz,
    -- A dona resolve a recusa ou o conflito (ADR-0013, item 2).
    resolucao text CONSTRAINT sync_log_resolucao_conhecida CHECK (resolucao IN ('TENTADO_DE_NOVO', 'DESCARTADO')),
    resolvido_em timestamptz,
    resolvido_por uuid CONSTRAINT sync_log_resolvido_por_existe REFERENCES corona.usuarios (id),
    motivo_da_resolucao text CONSTRAINT sync_log_motivo_curto CHECK (length(motivo_da_resolucao) <= 500),
    CONSTRAINT sync_log_dados_ou_esvaziado CHECK ((dados IS NULL) = (dados_esvaziados_em IS NOT NULL)),
    CONSTRAINT sync_log_resolucao_completa CHECK ((resolucao IS NULL) = (resolvido_em IS NULL)
                                                  AND (resolucao IS NULL) = (resolvido_por IS NULL))
);
SELECT corona.aplicar_convencoes_do_servidor('corona.sync_log', editavel => true);
COMMENT ON TABLE corona.sync_log IS
    'Só do servidor: cada operação recebida do Corona Vendas (id = operacao_id do app). JSON completo por 90 dias; depois fica o resumo. ADR-0013, item 2.';
COMMENT ON COLUMN corona.sync_log.resumo IS
    'SHA-256 do conteúdo em forma canônica (campos em ordem fixa, dinheiro com duas casas): dois JSON equivalentes dão o mesmo resumo.';
COMMENT ON COLUMN corona.sync_log.dados IS
    'O JSON recebido, como veio. Esvaziado (NULL) pela limpeza depois de 90 dias; a linha fica.';
COMMENT ON COLUMN corona.sync_log.diferenca_do_relogio_ms IS
    'Hora do computador no envio menos a hora do servidor (ADR-0013, item 11).';
CREATE INDEX sync_log_com_problema ON corona.sync_log (recebido_em) WHERE resultado IN ('RECUSADO', 'CONFLITO') AND resolucao IS NULL;
CREATE INDEX sync_log_do_vendedor ON corona.sync_log (vendedor_id, recebido_em);
CREATE INDEX sync_log_para_esvaziar ON corona.sync_log (recebido_em) WHERE dados IS NOT NULL;
REVOKE ALL ON corona.sync_log FROM PUBLIC;

-- Venda cuja hora foi corrigida em mais de 5 minutos porque o relógio do computador estava errado (dúvida D12).
ALTER TABLE corona.vendas ADD COLUMN hora_ajustada boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN corona.vendas.hora_ajustada IS
    'A hora da venda foi corrigida pelo servidor em mais de 5 minutos (relógio do computador errado; ADR-0013, item 11).';
