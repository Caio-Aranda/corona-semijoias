// Publica uma versão nova do Corona Gestão na VPS (ADR-0009, item 3): npm run owner:publicar.
// 1) gera o instalador (npm run owner:empacotar); 2) confere que a versão é MAIOR que a do servidor;
// 3) envia o instalador e, SÓ DEPOIS, o latest.yml (o arquivo que anuncia a versão); 4) confere o SHA-512
// no servidor; 5) mantém só os 3 instaladores mais novos lá. Os computadores se atualizam sozinhos.
//
// Configuração (apps/owner-app/.env.publicar, ignorado pelo Git, ou variáveis de ambiente):
//   CORONA_VPS=usuario@IP-da-VPS        (o acesso é por chave SSH)
//   CORONA_VPS_CHAVE=C:\caminho\chave    (opcional: arquivo da chave privada)
//   CORONA_VPS_PASTA=/srv/corona/atualizacoes   (opcional)
// Para testar sem servidor: CORONA_PUBLICAR_LOCAL=<pasta> copia para uma pasta deste computador.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  renameSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { conferirVersaoNova, lerLatest, nomeDoInstaladorValido } from './lib/publicacao.mjs';

const raiz = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const app = join(raiz, 'apps', 'owner-app');
const pasta = join(app, 'release');

function falhar(mensagem) {
  console.error(`\nNão foi possível publicar: ${mensagem}\n`);
  process.exit(1);
}

function lerEnv(texto) {
  const r = {};
  for (const linha of texto.split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(linha);
    if (m && !linha.trim().startsWith('#')) r[m[1]] = m[2];
  }
  return r;
}

const arquivoEnv = join(app, '.env.publicar');
const cfg = {
  ...(existsSync(arquivoEnv) ? lerEnv(readFileSync(arquivoEnv, 'utf8')) : {}),
  ...process.env,
};
const local = cfg.CORONA_PUBLICAR_LOCAL;
const vps = cfg.CORONA_VPS;
const destino = cfg.CORONA_VPS_PASTA || '/srv/corona/atualizacoes';
if (!local && !vps) {
  falhar(
    'falta CORONA_VPS (usuario@IP) em apps/owner-app/.env.publicar. Veja .env.publicar.example e docs/entrega-corona-gestao.md.',
  );
}
if (vps && !/^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+$/.test(vps))
  falhar('CORONA_VPS precisa ter o formato usuario@IP.');
if (!/^\/[A-Za-z0-9._/-]+$/.test(destino))
  falhar('CORONA_VPS_PASTA tem caracteres que não são aceitos.');

const chave = cfg.CORONA_VPS_CHAVE ? ['-i', cfg.CORONA_VPS_CHAVE] : [];
const opcoesSsh = ['-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new'];

function ssh(comando) {
  const r = spawnSync('ssh', [...opcoesSsh, ...chave, vps, comando], { encoding: 'utf8' });
  if (r.status !== 0) falhar(`o comando no servidor falhou (${comando}): ${r.stderr || r.stdout}`);
  return r.stdout;
}
function scp(arquivo, remoto) {
  const r = spawnSync('scp', [...opcoesSsh, ...chave, arquivo, `${vps}:${remoto}`], {
    stdio: 'inherit',
  });
  if (r.status !== 0) falhar(`não consegui enviar ${arquivo} ao servidor.`);
}

// 1) Instalador
if (!process.argv.includes('--sem-empacotar')) {
  const r = spawnSync('node', [join(raiz, 'scripts', 'owner-empacotar.mjs')], { stdio: 'inherit' });
  if (r.status !== 0) process.exit(r.status ?? 1);
}
const latestTexto = existsSync(join(pasta, 'latest.yml'))
  ? readFileSync(join(pasta, 'latest.yml'), 'utf8')
  : null;
if (!latestTexto)
  falhar('não achei apps/owner-app/release/latest.yml. Rode npm run owner:empacotar.');
const novo = lerLatest(latestTexto);
if (!nomeDoInstaladorValido(novo.arquivo)) falhar(`nome de instalador inesperado: ${novo.arquivo}`);
const caminhoExe = join(pasta, novo.arquivo);
if (!existsSync(caminhoExe)) falhar(`não achei ${novo.arquivo} em apps/owner-app/release.`);
const hashLocal = createHash('sha512').update(readFileSync(caminhoExe)).digest('base64');
if (hashLocal !== novo.sha512)
  falhar('o SHA-512 do instalador não bate com o do latest.yml. Gere o instalador de novo.');

// 2) Versão publicada hoje
let publicada = null;
if (local) {
  const f = join(local, 'latest.yml');
  if (existsSync(f)) publicada = lerLatest(readFileSync(f, 'utf8')).versao;
} else {
  const saida = spawnSync(
    'ssh',
    [...opcoesSsh, ...chave, vps, `cat '${destino}/latest.yml' 2>/dev/null || true`],
    { encoding: 'utf8' },
  );
  if (saida.status !== 0) falhar(`não consegui falar com o servidor: ${saida.stderr}`);
  if (saida.stdout.trim()) publicada = lerLatest(saida.stdout).versao;
}
try {
  conferirVersaoNova(publicada, novo.versao);
} catch (erro) {
  falhar(erro.message);
}
console.log(`Versão no servidor: ${publicada ?? '(nenhuma)'}  ->  nova: ${novo.versao}`);

// 3) Envio: instalador primeiro, latest.yml por último (só ele anuncia a versão).
const blockmap = `${novo.arquivo}.blockmap`;
if (local) {
  mkdirSync(local, { recursive: true });
  for (const nome of [novo.arquivo, ...(existsSync(join(pasta, blockmap)) ? [blockmap] : [])]) {
    copyFileSync(join(pasta, nome), join(local, `${nome}.envio`));
    renameSync(join(local, `${nome}.envio`), join(local, nome));
  }
  writeFileSync(join(local, 'latest.yml.envio'), latestTexto);
  renameSync(join(local, 'latest.yml.envio'), join(local, 'latest.yml'));
  const aposentar = readdirSync(local)
    .filter((n) => nomeDoInstaladorValido(n))
    .sort()
    .slice(0, -3);
  for (const n of aposentar) {
    rmSync(join(local, n));
    rmSync(join(local, `${n}.blockmap`), { force: true });
  }
} else {
  ssh(`mkdir -p '${destino}/.envio'`);
  scp(caminhoExe, `${destino}/.envio/${novo.arquivo}`);
  if (existsSync(join(pasta, blockmap)))
    scp(join(pasta, blockmap), `${destino}/.envio/${blockmap}`);
  scp(join(pasta, 'latest.yml'), `${destino}/.envio/latest.yml`);
  // 4) Confere o hash no servidor ANTES de anunciar.
  const hashRemoto = ssh(`sha512sum '${destino}/.envio/${novo.arquivo}' | cut -d' ' -f1`).trim();
  const hashEsperado = createHash('sha512').update(readFileSync(caminhoExe)).digest('hex');
  if (hashRemoto !== hashEsperado) {
    ssh(`rm -rf '${destino}/.envio'`);
    falhar(
      'o instalador chegou ao servidor diferente do que saiu daqui (hash). Nada foi publicado; tente de novo.',
    );
  }
  ssh(
    `cd '${destino}' && mv -f '.envio/${novo.arquivo}' . && (mv -f '.envio/${blockmap}' . 2>/dev/null || true) && mv -f '.envio/latest.yml' latest.yml && rmdir .envio`,
  );
  // 5) Guarda só os 3 instaladores mais novos.
  ssh(
    `cd '${destino}' && ls -1 Corona-Gestao-Setup-*.exe | sort -V | head -n -3 | while read f; do rm -f "$f" "$f.blockmap"; done; chmod 644 *.exe *.blockmap latest.yml 2>/dev/null || true`,
  );
}

console.log(`\nPublicado: Corona Gestão ${novo.versao}.`);
console.log(
  'Os computadores se atualizam sozinhos (ao abrir o programa e a cada poucas horas); a versão entra quando a pessoa fechar o programa.',
);
