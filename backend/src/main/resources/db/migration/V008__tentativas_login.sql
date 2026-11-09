-- Registro das tentativas de login e bloqueio (tarefa 11; ADR-0004, item 7). Tabela de histórico
-- só do servidor: só inserção, nada é apagado nem alterado (ADR-0002). A hora vem do relógio da
-- aplicação.
--
-- O bloqueio fica gravado na linha da tentativa que o causou (bloqueio_ate, bloqueio_do_ip_ate):
-- "está bloqueado?" é olhar se existe uma linha com o fim do bloqueio no futuro. Retirar um
-- bloqueio é gravar uma linha nova (BLOQUEIO_LIMPO), nunca alterar as antigas.

CREATE TABLE corona.tentativas_login (
    id uuid PRIMARY KEY,
    login text NOT NULL,
    ip inet,
    dispositivo_id uuid,
    tipo_app text
        CONSTRAINT tentativas_login_tipo_app_conhecido CHECK (tipo_app IN ('gestao', 'vendas')),
    resultado text NOT NULL
        CONSTRAINT tentativas_login_resultado_conhecido CHECK (resultado IN (
            'SUCESSO', 'SENHA_INCORRETA', 'BLOQUEADO', 'USUARIO_DESATIVADO', 'APP_INCOMPATIVEL',
            'SENHA_TEMPORARIA_VENCIDA', 'PEDIDO_INVALIDO', 'BLOQUEIO_LIMPO')),
    bloqueio_ate timestamptz,
    bloqueio_do_ip_ate timestamptz,
    ocorrida_em timestamptz NOT NULL,
    CONSTRAINT tentativas_login_ip_so_falta_na_limpeza CHECK ((ip IS NULL) = (resultado = 'BLOQUEIO_LIMPO')),
    CONSTRAINT tentativas_login_bloqueio_so_no_erro
        CHECK ((bloqueio_ate IS NULL AND bloqueio_do_ip_ate IS NULL) OR resultado = 'SENHA_INCORRETA')
);
COMMENT ON TABLE corona.tentativas_login IS
    'Só do servidor, histórico (só inserção): cada tentativa de login, com o resultado. Login que não existe é gravado do mesmo jeito. ADR-0004, item 7.';
COMMENT ON COLUMN corona.tentativas_login.login IS
    'O login digitado, normalizado pela aplicação (sem espaços nas pontas, minúsculas; caractere de controle vira "?"). Pode não existir em usuarios.';
COMMENT ON COLUMN corona.tentativas_login.tipo_app IS
    'gestao ou vendas, do cabeçalho X-App-Tipo. Vazio até a tarefa 12.';
COMMENT ON COLUMN corona.tentativas_login.resultado IS
    'SENHA_INCORRETA é o único que conta para o bloqueio. SENHA_TEMPORARIA_VENCIDA e PEDIDO_INVALIDO (aparelho que não combina com o perfil) não contam e não zeram a contagem. BLOQUEIO_LIMPO é gravado pela redefinição da senha (sem IP) e retira o bloqueio daquele login.';
COMMENT ON COLUMN corona.tentativas_login.bloqueio_ate IS
    'Esta tentativa errada completou os erros seguidos de login + IP: a combinação fica bloqueada até esta hora.';
COMMENT ON COLUMN corona.tentativas_login.bloqueio_do_ip_ate IS
    'Esta tentativa errada completou o limite de erros do IP (qualquer login): o IP fica bloqueado até esta hora.';
SELECT corona.aplicar_convencoes_do_servidor('corona.tentativas_login', editavel => false);

CREATE INDEX tentativas_login_por_login_e_ip ON corona.tentativas_login (login, ip, ocorrida_em);
CREATE INDEX tentativas_login_por_ip ON corona.tentativas_login (ip, ocorrida_em);
CREATE INDEX tentativas_login_por_login ON corona.tentativas_login (login, ocorrida_em);

REVOKE ALL ON corona.tentativas_login FROM PUBLIC;
