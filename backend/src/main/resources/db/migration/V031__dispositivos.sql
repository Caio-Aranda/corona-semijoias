-- V031: aparelhos do Corona Vendas (Fase 5, tarefa 5.3; ADR-0013, item 13; ESPECIFICACAO §6.15 e §11).
--
-- Cada computador do vendedor é um aparelho, identificado pelo dispositivoId que o app gera ao ser instalado
-- (ADR-0004, item 4). O primeiro aparelho do vendedor nasce ATIVO no login; os seguintes entram (o login funciona),
-- mas nascem AGUARDANDO_LIBERACAO e não vendem nem recebem dados até a dona liberar ("Trocar computador"). Liberar
-- o novo deixa o antigo SUBSTITUIDO (ainda pode enviar o que guardou) e passa as maletas dele para o novo. A dona
-- também bloqueia o aparelho perdido ou roubado (BLOQUEADO): as sessões dele caem e a próxima chamada recebe a
-- ordem de apagar os dados.
--
-- Tabela só do servidor: nunca vai ao vendedor (RegistroDeTabelas). Nada é apagado.

CREATE TABLE corona.dispositivos (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL
        CONSTRAINT dispositivos_usuario_existe REFERENCES corona.usuarios (id),
    situacao text NOT NULL
        CONSTRAINT dispositivos_situacao_conhecida CHECK (situacao IN (
            'ATIVO', 'AGUARDANDO_LIBERACAO', 'SUBSTITUIDO', 'BLOQUEADO')),
    primeiro_contato_em timestamptz NOT NULL,
    ultimo_contato_em timestamptz NOT NULL,
    versao_app text
        CONSTRAINT dispositivos_versao_curta CHECK (length(versao_app) <= 40),
    -- Preenchidos pelo envio e pelo recebimento de dados (tarefas 5.4 e 5.5).
    ultimo_pull_em timestamptz,
    ultimo_push_em timestamptz,
    ultimo_envio_completo_em timestamptz,
    aguardando_envio integer
        CONSTRAINT dispositivos_aguardando_nao_negativo CHECK (aguardando_envio >= 0),
    aguardando_informado_em timestamptz,
    -- Liberação, troca e bloqueio (pela dona).
    liberado_em timestamptz,
    substituido_em timestamptz,
    substituido_por uuid
        CONSTRAINT dispositivos_substituto_existe REFERENCES corona.dispositivos (id),
    bloqueado_em timestamptz,
    motivo_bloqueio text
        CONSTRAINT dispositivos_motivo_curto CHECK (length(motivo_bloqueio) <= 500),
    CONSTRAINT dispositivos_bloqueio_com_data CHECK ((situacao = 'BLOQUEADO') = (bloqueado_em IS NOT NULL)),
    CONSTRAINT dispositivos_substituicao_com_data CHECK (situacao <> 'SUBSTITUIDO' OR substituido_em IS NOT NULL)
);
SELECT corona.aplicar_convencoes_do_servidor('corona.dispositivos', editavel => true);
COMMENT ON TABLE corona.dispositivos IS
    'Só do servidor: os computadores do Corona Vendas (id = dispositivoId do app; ADR-0004, item 4; ADR-0013, item 13). Um ATIVO por usuário.';
COMMENT ON COLUMN corona.dispositivos.situacao IS
    'ATIVO (vende e recebe dados), AGUARDANDO_LIBERACAO (segundo computador, espera a dona), SUBSTITUIDO (trocado por outro: só envia o que guardou), BLOQUEADO (perdido ou roubado: recebe a ordem de apagar).';
COMMENT ON COLUMN corona.dispositivos.ultimo_contato_em IS
    'Último login ou renovação da sessão vindos deste computador (hora do servidor).';
COMMENT ON COLUMN corona.dispositivos.aguardando_envio IS
    'Quantas operações o computador disse ter aguardando envio no último envio (aguardandoDepoisDeste; tarefa 5.5).';
COMMENT ON COLUMN corona.dispositivos.ultimo_envio_completo_em IS
    'Hora do último envio que terminou com zero aguardando (o acerto da Fase 7 exige que seja depois da última venda).';

-- Um computador ativo por pessoa: o banco garante, não só o serviço.
CREATE UNIQUE INDEX dispositivos_um_ativo_por_usuario ON corona.dispositivos (usuario_id) WHERE situacao = 'ATIVO';
CREATE INDEX dispositivos_do_usuario ON corona.dispositivos (usuario_id, primeiro_contato_em);
REVOKE ALL ON corona.dispositivos FROM PUBLIC;

-- Os computadores que já entraram antes desta migração (Fase 4, só com internet) vêm das sessões de vendedor. O de
-- uso mais recente de cada vendedor fica ATIVO; os outros esperam a dona (o padrão seguro: nada que já existia passa
-- a vender sem ela ver). Só identificadores no formato aceito (UUID v4 ou v7).
INSERT INTO corona.dispositivos (id, usuario_id, situacao, primeiro_contato_em, ultimo_contato_em)
SELECT s.dispositivo_id, s.usuario_id,
       CASE WHEN row_number() OVER (PARTITION BY s.usuario_id ORDER BY max(s.usada_em) DESC, s.dispositivo_id) = 1
            THEN 'ATIVO' ELSE 'AGUARDANDO_LIBERACAO' END,
       min(s.criada_em), max(s.usada_em)
  FROM corona.sessoes s
  JOIN corona.usuarios u ON u.id = s.usuario_id AND u.perfil = 'VENDEDOR'
 WHERE s.dispositivo_id IS NOT NULL
   AND coalesce(uuid_extract_version(s.dispositivo_id), 0) IN (4, 7)
   -- O mesmo identificador com duas pessoas (não acontece no app): fica com a primeira que entrou.
   AND s.usuario_id = (SELECT s2.usuario_id FROM corona.sessoes s2 WHERE s2.dispositivo_id = s.dispositivo_id
                        ORDER BY s2.criada_em, s2.usuario_id LIMIT 1)
 GROUP BY s.dispositivo_id, s.usuario_id;

-- Chave estrangeira de origem_dispositivo (pendência da tarefa 8) e dos dispositivo_id das maletas, dos movimentos e
-- das conferências. NOT VALID: vale para toda linha nova ou alterada, e as antigas (de antes de existir o cadastro de
-- aparelhos) não são conferidas.
DO $$
DECLARE
    t record;
BEGIN
    FOR t IN
        SELECT c.table_name, c.column_name
          FROM information_schema.columns c
          JOIN information_schema.tables tb ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
         WHERE c.table_schema = 'corona' AND tb.table_type = 'BASE TABLE'
           AND (c.column_name = 'origem_dispositivo'
                OR (c.column_name = 'dispositivo_id' AND c.table_name IN ('remessas', 'movimentos_remessa', 'conferencias')))
    LOOP
        EXECUTE format('ALTER TABLE corona.%I ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES corona.dispositivos (id) NOT VALID',
            t.table_name, t.table_name || '_' || t.column_name || '_cadastrado', t.column_name);
    END LOOP;
END;
$$;

-- Tabelas sincronizáveis criadas daqui em diante já nascem com a mesma chave estrangeira.
CREATE OR REPLACE FUNCTION corona.aplicar_convencoes_sincronizavel(tabela regclass, imutavel boolean DEFAULT false) RETURNS void
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    nome text;
    tem_linhas boolean;
BEGIN
    nome := corona.conferir_tabela_nova(tabela, ARRAY['created_at', 'updated_at', 'version', 'deleted_at',
        'origem_dispositivo', 'sync_seq', 'sync_xid']);
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s)', tabela) INTO tem_linhas;
    IF tem_linhas THEN
        RAISE EXCEPTION 'Aplique as convenções logo depois do CREATE TABLE, com a tabela % vazia.', tabela;
    END IF;

    EXECUTE format(
        'ALTER TABLE %s'
        '  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),'
        '  ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),'
        '  ADD COLUMN version integer NOT NULL DEFAULT 1 CONSTRAINT %I CHECK (version >= 1),'
        '  ADD COLUMN deleted_at timestamptz,'
        '  ADD COLUMN origem_dispositivo uuid CONSTRAINT %I REFERENCES corona.dispositivos (id),'
        '  ADD COLUMN sync_seq bigint NOT NULL,'
        '  ADD COLUMN sync_xid xid8 NOT NULL,'
        '  ADD CONSTRAINT %I CHECK (coalesce(uuid_extract_version(id), 0) IN (4, 7))',
        tabela, nome || '_version_positiva', nome || '_origem_dispositivo_cadastrado', nome || '_id_v4_ou_v7');

    -- As recusas são por comando e disparam antes do controle, que é por linha.
    EXECUTE format('CREATE TRIGGER corona_sync_controle BEFORE INSERT OR UPDATE ON %s '
        'FOR EACH ROW EXECUTE FUNCTION corona.controle_sincronizavel()', tabela);
    EXECUTE format('CREATE TRIGGER corona_recusa_delete BEFORE DELETE ON %s '
        'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_exclusao()', tabela);
    EXECUTE format('CREATE TRIGGER corona_recusa_truncate BEFORE TRUNCATE ON %s '
        'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_exclusao()', tabela);
    IF imutavel THEN
        EXECUTE format('CREATE TRIGGER corona_recusa_update BEFORE UPDATE ON %s '
            'FOR EACH STATEMENT EXECUTE FUNCTION corona.recusar_alteracao()', tabela);
    END IF;

    EXECUTE format('CREATE INDEX %I ON %s (sync_xid, sync_seq)', nome || '_sync_idx', tabela);
END;
$$;
COMMENT ON FUNCTION corona.aplicar_convencoes_sincronizavel(regclass, boolean) IS
    'Monta uma tabela sincronizável nova: colunas de controle, CHECK do id, chave estrangeira da origem para dispositivos (V031), gatilhos e índice (sync_xid, sync_seq). ADR-0002.';
REVOKE EXECUTE ON FUNCTION corona.aplicar_convencoes_sincronizavel(regclass, boolean) FROM PUBLIC;

-- Motivo novo de revogação de sessão: a dona bloqueou o computador (perdido ou roubado). A V013 não pode ser editada.
ALTER TABLE corona.sessoes DROP CONSTRAINT sessoes_motivo_conhecido;
ALTER TABLE corona.sessoes ADD CONSTRAINT sessoes_motivo_conhecido CHECK (motivo_da_revogacao IN (
    'SAIDA', 'REUSO_FORA_DA_JANELA', 'SEGUNDA_REAPRESENTACAO', 'ANTERIOR_DEPOIS_DO_SUCESSOR',
    'OUTRO_APARELHO', 'SENHA_REDEFINIDA', 'SENHA_REDEFINIDA_PELO_SERVIDOR', 'SENHA_TROCADA',
    'USUARIO_DESATIVADO', 'ACESSO_ALTERADO', 'APARELHO_BLOQUEADO'));
CREATE INDEX sessoes_abertas_do_aparelho ON corona.sessoes (dispositivo_id) WHERE revogada_em IS NULL;

-- Resultados novos da tentativa de login: computador bloqueado e computador de outra pessoa.
ALTER TABLE corona.tentativas_login DROP CONSTRAINT tentativas_login_resultado_conhecido;
ALTER TABLE corona.tentativas_login ADD CONSTRAINT tentativas_login_resultado_conhecido CHECK (resultado IN (
    'SUCESSO', 'SENHA_INCORRETA', 'BLOQUEADO', 'USUARIO_DESATIVADO', 'APP_INCOMPATIVEL',
    'SENHA_TEMPORARIA_VENCIDA', 'PEDIDO_INVALIDO', 'BLOQUEIO_LIMPO', 'APARELHO_BLOQUEADO', 'APARELHO_DE_OUTRA_PESSOA'));
