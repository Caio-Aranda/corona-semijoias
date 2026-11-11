// Gera o instalador de um aplicativo (ADR-0009, item 4):
//   npm run owner:empacotar   (Corona Gestão)      npm run vendas:empacotar   (Corona Vendas)
// Lê o endereço do servidor e as impressões digitais do certificado de apps/<app>/.env.producao (ou das
// variáveis de ambiente), confere tudo ANTES de compilar e só então empacota. Nada disso mora no código.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  ErroDeConfiguracao,
  validarConfiguracaoDeProducao,
} from '../packages/conexao-segura/src/configuracao.ts';
import { alvoDoArgumento } from './lib/alvos.mjs';

const raiz = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const alvo = alvoDoArgumento(process.argv);
const app = join(raiz, 'apps', alvo.pasta);

/** Lê "CHAVE=valor" de um arquivo .env (sem expandir nada). */
function lerEnv(texto) {
  const resultado = {};
  for (const linha of texto.split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(linha);
    if (m && !linha.trim().startsWith('#')) resultado[m[1]] = m[2];
  }
  return resultado;
}

function executar(comando, args, opcoes = {}) {
  const r = spawnSync(comando, args, { stdio: 'inherit', shell: true, ...opcoes });
  if (r.status !== 0) {
    console.error(`\nO passo "${comando} ${args.join(' ')}" falhou.`);
    process.exit(r.status ?? 1);
  }
}

function principal() {
  const arquivo = join(app, '.env.producao');
  const doArquivo = existsSync(arquivo) ? lerEnv(readFileSync(arquivo, 'utf8')) : {};
  const env = {
    MAIN_VITE_CORONA_API_URL:
      process.env.MAIN_VITE_CORONA_API_URL ?? doArquivo.MAIN_VITE_CORONA_API_URL,
    MAIN_VITE_CORONA_API_PINS:
      process.env.MAIN_VITE_CORONA_API_PINS ?? doArquivo.MAIN_VITE_CORONA_API_PINS,
  };
  let config;
  try {
    const paraTeste = process.argv.includes('--para-teste');
    if (paraTeste)
      console.warn(
        'ATENÇÃO: instalador PARA TESTE (pode apontar para este computador). Não entregue a ninguém.',
      );
    config = validarConfiguracaoDeProducao(env, { permitirEsteComputador: paraTeste });
  } catch (erro) {
    if (!(erro instanceof ErroDeConfiguracao)) throw erro;
    console.error(`\nNão deu para gerar o instalador: ${erro.message}`);
    console.error(
      `Copie apps/${alvo.pasta}/.env.producao.example para apps/${alvo.pasta}/.env.producao e preencha.\n`,
    );
    process.exit(1);
  }
  console.log(`${alvo.nome}: servidor ${config.endereco}`);
  console.log(`Impressões digitais fixadas: ${config.impressoes.length}`);

  // Instaladores de versões anteriores ficam de fora: o que sai daqui é só desta versão (e nunca se
  // publica um arquivo velho por engano).
  const pastaDeSaida = join(app, 'release');
  if (existsSync(pastaDeSaida)) {
    const antigo = new RegExp(String.raw`^(${alvo.prefixo}-Setup-.*|latest\.yml)$`);
    for (const nome of readdirSync(pastaDeSaida)) {
      if (antigo.test(nome)) rmSync(join(pastaDeSaida, nome), { force: true });
    }
  }

  const urlDeAtualizacoes = `${config.endereco}/atualizacoes/${alvo.canal ? `${alvo.canal}/` : ''}`;
  // As variáveis do ambiente valem na compilação (o Vite as lê); o arquivo .env.producao também.
  const ambiente = { ...process.env, ...env, CORONA_URL_ATUALIZACOES: urlDeAtualizacoes };
  executar('npx', ['electron-vite', 'build', '--mode', 'producao'], { cwd: app, env: ambiente });
  executar(
    'npx',
    [
      'electron-builder',
      '--config',
      'electron-builder.yml',
      '--win',
      'nsis',
      '--publish',
      'never',
      ...alvo.argsDoBuilder(urlDeAtualizacoes),
    ],
    { cwd: app, env: ambiente },
  );

  const pasta = join(app, 'release');
  const versao = JSON.parse(readFileSync(join(app, 'package.json'), 'utf8')).version;
  const instalador = `${alvo.prefixo}-Setup-${versao}.exe`;
  if (!existsSync(join(pasta, instalador))) {
    console.error(
      `O electron-builder terminou, mas não achei o instalador em apps/${alvo.pasta}/release.`,
    );
    process.exit(1);
  }
  const hash = createHash('sha512')
    .update(readFileSync(join(pasta, instalador)))
    .digest('base64');
  console.log(`\nPronto: apps/${alvo.pasta}/release/${instalador}`);
  console.log(`SHA-512: ${hash}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) principal();
