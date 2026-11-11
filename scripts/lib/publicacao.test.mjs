import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  compararVersoes,
  conferirVersaoNova,
  lerLatest,
  nomeDoInstaladorValido,
} from './publicacao.mjs';

const LATEST = `version: 1.0.1
files:
  - url: Corona-Gestao-Setup-1.0.1.exe
    sha512: abc123+/==
    size: 100
path: Corona-Gestao-Setup-1.0.1.exe
sha512: abc123+/==
releaseDate: '2026-11-13T12:00:00.000Z'
`;

test('lê versão, arquivo e hash do latest.yml', () => {
  assert.deepEqual(lerLatest(LATEST), {
    versao: '1.0.1',
    arquivo: 'Corona-Gestao-Setup-1.0.1.exe',
    sha512: 'abc123+/==',
  });
  assert.throws(() => lerLatest('version: 1.0.1\n'));
});

test('compara versões como números, não como texto', () => {
  assert.ok(compararVersoes('1.0.10', '1.0.9') > 0);
  assert.ok(compararVersoes('1.2.0', '1.10.0') < 0);
  assert.equal(compararVersoes('2.0.0', '2.0.0'), 0);
  assert.throws(() => compararVersoes('1.0', '1.0.0'));
});

test('recusa publicar versão igual ou menor que a do servidor', () => {
  assert.doesNotThrow(() => conferirVersaoNova(null, '1.0.0'));
  assert.doesNotThrow(() => conferirVersaoNova('1.0.0', '1.0.1'));
  assert.throws(() => conferirVersaoNova('1.0.1', '1.0.1'), /Aumente a "version"/);
  assert.throws(() => conferirVersaoNova('1.0.2', '1.0.1'));
});

test('só aceita o nome de instalador que o electron-builder gera', () => {
  assert.ok(nomeDoInstaladorValido('Corona-Gestao-Setup-1.0.1.exe'));
  assert.ok(!nomeDoInstaladorValido('../Corona-Gestao-Setup-1.0.1.exe'));
  assert.ok(!nomeDoInstaladorValido('qualquer.exe'));
  assert.ok(nomeDoInstaladorValido('Corona-Vendas-Setup-1.0.1.exe', 'Corona-Vendas'));
  assert.ok(!nomeDoInstaladorValido('Corona-Vendas-Setup-1.0.1.exe'));
  assert.ok(!nomeDoInstaladorValido('Corona-Gestao-Setup-1.0.1.exe; rm -rf /'));
});
