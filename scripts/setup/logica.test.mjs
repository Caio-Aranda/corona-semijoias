import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  compararVersoes,
  criarEnvNovo,
  lerVersao,
  variaveisFaltando,
  versaoDoJava,
} from './logica.mjs';

test('compara versões', () => {
  assert.deepEqual(lerVersao('v26.13.0'), [26, 13, 0]);
  assert.ok(compararVersoes(lerVersao('24.18.1'), lerVersao('26.13.0')) < 0);
  assert.equal(compararVersoes(lerVersao('21.0.12'), lerVersao('21.0.12')), 0);
  assert.equal(lerVersao('abc'), null);
});

test('lê a versão do Java', () => {
  assert.equal(versaoDoJava('openjdk version "21.0.12" 2026-07-21'), '21.0.12');
  assert.equal(versaoDoJava('nada'), null);
});

test('acha as variáveis que faltam, sem mostrar valores', () => {
  const exemplo = 'A=1\n# B=2\nC=3\nJWT_CHAVE_ATUAL=x\n';
  assert.deepEqual(variaveisFaltando(exemplo, 'A=9\r\n'), ['C', 'JWT_CHAVE_ATUAL']);
  assert.deepEqual(variaveisFaltando(exemplo, 'A=\nC=\nJWT_CHAVE_ATUAL='), []);
});

test('o .env novo troca os segredos e mantém o resto', () => {
  const exemplo =
    'DB_HOST=localhost\nJWT_CHAVE_ATUAL=troque-isto\nDB_APP_PASSWORD=troque-isto\nADMIN_LOGIN=troque-isto\n# nota\n';
  const novo = criarEnvNovo(exemplo, { base64: () => 'SEGREDO64', senha: () => 'senha1' });
  assert.match(novo, /DB_HOST=localhost/);
  assert.match(novo, /JWT_CHAVE_ATUAL=SEGREDO64/);
  assert.match(novo, /DB_APP_PASSWORD=senha1/);
  assert.doesNotMatch(novo, /ADMIN_LOGIN=troque-isto/);
  assert.match(novo, /# nota/);
});
