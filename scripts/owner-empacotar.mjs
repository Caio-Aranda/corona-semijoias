// Gera o instalador do Corona Gestão (ADR-0009, item 4): npm run owner:empacotar.
// Lê o endereço do servidor e as impressões digitais do certificado de apps/owner-app/.env.producao (ou das
// variáveis de ambiente), confere tudo ANTES de compilar e só então empacota. Nada disso mora no código.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  ErroDeConfiguracao,
  validarConfiguracaoDeProducao,
} from '../apps/owner-app/src/main/configuracao.ts';

const raiz = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const app = join(raiz, 'apps', 'owner-app');

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
        'ATENÇÃO: instalador PARA TESTE (pode apontar para este computador). Não entregue à dona.',
      );
    config = validarConfiguracaoDeProducao(env, { permitirEsteComputador: paraTeste });
  } catch (erro) {
    if (!(erro instanceof ErroDeConfiguracao)) throw erro;
    console.error(`\nNão deu para gerar o instalador: ${erro.message}`);
    console.error(
      'Copie apps/owner-app/.env.producao.example para apps/owner-app/.env.producao e preencha.\n',
    );
    process.exit(1);
  }
  console.log(`Servidor: ${config.endereco}`);
  console.log(`Impressões digitais fixadas: ${config.impressoes.length}`);

  // Instaladores de versões anteriores ficam de fora: o que sai daqui é só desta versão (e nunca se
  // publica um arquivo velho por engano).
  const pastaDeSaida = join(app, 'release');
  if (existsSync(pastaDeSaida)) {
    for (const nome of readdirSync(pastaDeSaida)) {
      if (/^(Corona-Gestao-Setup-.*|latest\.yml)$/.test(nome))
        rmSync(join(pastaDeSaida, nome), { force: true });
    }
  }

  // As variáveis do ambiente valem na compilação (o Vite as lê); o arquivo .env.producao também.
  const ambiente = {
    ...process.env,
    ...env,
    CORONA_URL_ATUALIZACOES: `${config.endereco}/atualizacoes/`,
  };
  executar('npx', ['electron-vite', 'build', '--mode', 'producao'], { cwd: app, env: ambiente });
  executar(
    'npx',
    ['electron-builder', '--config', 'electron-builder.yml', '--win', 'nsis', '--publish', 'never'],
    {
      cwd: app,
      env: ambiente,
    },
  );

  const pasta = join(app, 'release');
  const versao = JSON.parse(readFileSync(join(app, 'package.json'), 'utf8')).version;
  const instalador = `Corona-Gestao-Setup-${versao}.exe`;
  if (!existsSync(join(pasta, instalador))) {
    console.error(
      'O electron-builder terminou, mas não achei o instalador em apps/owner-app/release.',
    );
    process.exit(1);
  }
  const hash = createHash('sha512')
    .update(readFileSync(join(pasta, instalador)))
    .digest('base64');
  console.log(`\nPronto: apps/owner-app/release/${instalador}`);
  console.log(`SHA-512: ${hash}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) principal();
