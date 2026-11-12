-- V034: envios de um computador bloqueado, guardados sem aplicar (Fase 5, tarefa 5.8; ADR-0013, item 14).
--
-- Quando a dona bloqueia um computador (ou desativa o vendedor) e ele ainda tinha vendas sem enviar, antes de apagar os
-- dados o app manda tudo para POST /api/vendedor/sync/quarentena. O servidor guarda aqui, SEM aplicar: a dona vê
-- "Vendas recebidas de um computador bloqueado" e aprova cada uma (que passa pelo mesmo serviço do envio) ou descarta.
-- Tabela só do servidor: nunca vai ao vendedor. Nada é apagado.

CREATE TABLE corona.envios_em_quarentena (
    id uuid PRIMARY KEY,
    dispositivo_id uuid NOT NULL CONSTRAINT quarentena_dispositivo_cadastrado REFERENCES corona.dispositivos (id),
    usuario_id uuid NOT NULL CONSTRAINT quarentena_usuario_existe REFERENCES corona.usuarios (id),
    vendedor_id uuid NOT NULL CONSTRAINT quarentena_vendedor_existe REFERENCES corona.vendedores (id),
    tipo text NOT NULL CONSTRAINT quarentena_tipo_formato CHECK (tipo ~ '^[A-Z][A-Z_]*$' AND length(tipo) <= 40),
    formato integer NOT NULL CONSTRAINT quarentena_formato_positivo CHECK (formato >= 1),
    resumo text NOT NULL CONSTRAINT quarentena_resumo_sha256 CHECK (resumo ~ '^[0-9a-f]{64}$'),
    dados jsonb NOT NULL,
    criado_em_local timestamptz,
    ultimo_contato_servidor timestamptz,
    hora_local_do_envio timestamptz,
    diferenca_do_relogio_ms bigint,
    recebido_em timestamptz NOT NULL,
    situacao text NOT NULL DEFAULT 'GUARDADO'
        CONSTRAINT quarentena_situacao_conhecida CHECK (situacao IN ('GUARDADO', 'APROVADO', 'DESCARTADO')),
    decidido_em timestamptz,
    decidido_por uuid CONSTRAINT quarentena_decidido_por_existe REFERENCES corona.usuarios (id),
    resultado_da_aprovacao text CONSTRAINT quarentena_resultado_curto CHECK (length(resultado_da_aprovacao) <= 40),
    motivo_do_descarte text CONSTRAINT quarentena_motivo_curto CHECK (length(motivo_do_descarte) <= 500),
    CONSTRAINT quarentena_decisao_completa CHECK ((situacao = 'GUARDADO') = (decidido_em IS NULL)
                                                  AND (situacao = 'GUARDADO') = (decidido_por IS NULL))
);
SELECT corona.aplicar_convencoes_do_servidor('corona.envios_em_quarentena', editavel => true);
COMMENT ON TABLE corona.envios_em_quarentena IS
    'Só do servidor: operações de um computador bloqueado, guardadas sem aplicar até a dona aprovar ou descartar. ADR-0013, item 14.';
CREATE INDEX quarentena_para_decidir ON corona.envios_em_quarentena (recebido_em) WHERE situacao = 'GUARDADO';
CREATE INDEX quarentena_do_computador ON corona.envios_em_quarentena (dispositivo_id);
REVOKE ALL ON corona.envios_em_quarentena FROM PUBLIC;
