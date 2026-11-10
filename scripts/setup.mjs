// "npm run setup": prepara o computador para desenvolver. Pode rodar quantas vezes quiser:
// nunca sobrescreve um .env que já existe e nunca imprime segredos.
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  compararVersoes,
  criarEnvNovo,
  lerVersao,
  variaveisFaltando,
  versaoDoJava,
} from './setup/logica.mjs';

const raiz = join(dirname(fileURLToPath(import.meta.url)), '..');
const windows = process.platform === 'win32';
const avisos = [];

const ok = (t) => console.log(`  ok  ${t}`);
const aviso = (t, acao) => {
  console.log(`  ATENÇÃO  ${t}`);
  console.log(`           ${acao}`);
  avisos.push(t);
};

function rodar(comando, args, opcoes = {}) {
  return spawnSync(comando, args, { cwd: raiz, encoding: 'utf8', shell: windows, ...opcoes });
}

function conferirNode() {
  const pedida = lerVersao(readFileSync(join(raiz, '.nvmrc'), 'utf8'));
  const atual = lerVersao(process.version);
  if (pedida && atual && atual[0] === pedida[0] && compararVersoes(atual, pedida) >= 0) {
    ok(`Node ${process.version}`);
  } else {
    aviso(
      `O Node deste computador é ${process.version}, e o projeto pede o ${pedida?.join('.')}.`,
      'Instale a versão pedida (arquivo .nvmrc) e rode "npm run setup" de novo.',
    );
  }
}

function conferirJava() {
  const r = rodar('java', ['-version']);
  const versao = versaoDoJava(`${r.stderr ?? ''}${r.stdout ?? ''}`);
  if (r.error || !versao) {
    aviso('O Java não foi encontrado.', 'Instale o JDK 21 (Temurin) e abra um terminal novo.');
    return;
  }
  const v = lerVersao(versao);
  if (!v || v[0] !== 21) {
    aviso(
      `O Java deste computador é o ${versao}, e o projeto usa o 21.`,
      'Instale o JDK 21 e aponte a variável JAVA_HOME para ele.',
    );
    return;
  }
  ok(`Java ${versao}`);
  const home = process.env.JAVA_HOME;
  if (!home) {
    aviso('A variável JAVA_HOME não está definida.', 'Aponte JAVA_HOME para a pasta do JDK 21.');
  } else if (!/21/.test(home)) {
    aviso(
      'A variável JAVA_HOME parece apontar para outra versão do Java (o nome da pasta não tem "21").',
      'Aponte JAVA_HOME para o JDK 21 (por exemplo, o 21.0.12); senão o Maven pode usar o Java errado.',
    );
  } else {
    ok('JAVA_HOME aponta para um Java 21');
  }
}

function conferirDocker() {
  const r = rodar('docker', ['info', '--format', '{{.ServerVersion}}']);
  if (r.status === 0) ok('Docker aberto');
  else
    aviso(
      'O Docker não está aberto (ou não está instalado).',
      'Abra o Docker Desktop, espere ele ficar pronto e rode de novo.',
    );
}

function prepararEnv(pasta, rotulo, gerarSegredos) {
  const prefixo = pasta ? `${pasta}/` : '';
  const exemplo = join(raiz, pasta, '.env.example');
  const alvo = join(raiz, pasta, '.env');
  if (!existsSync(exemplo)) return;
  const textoExemplo = readFileSync(exemplo, 'utf8');
  if (!existsSync(alvo)) {
    writeFileSync(alvo, gerarSegredos ? criarEnvNovo(textoExemplo) : textoExemplo);
    ok(
      `${rotulo}: arquivo .env criado${gerarSegredos ? ' (com senhas e chaves de desenvolvimento geradas)' : ''}`,
    );
    return;
  }
  const faltando = variaveisFaltando(textoExemplo, readFileSync(alvo, 'utf8'));
  if (faltando.length === 0) ok(`${rotulo}: .env completo (não foi alterado)`);
  else
    aviso(
      `${rotulo}: o .env não tem estas variáveis: ${faltando.join(', ')}.`,
      `Copie essas linhas de ${prefixo}.env.example para o ${prefixo}.env e preencha (o .env existente não foi alterado).`,
    );
}

console.log('\nConferindo o computador...');
conferirNode();
conferirJava();
conferirDocker();

console.log('\nInstalando as dependências (npm ci)...');
const instalacao = rodar('npm', ['ci'], { stdio: 'inherit' });
if (instalacao.status !== 0)
  aviso('A instalação das dependências falhou.', 'Leia a mensagem acima e rode de novo.');

console.log('\nArquivos .env...');
prepararEnv('', 'Raiz', true);
prepararEnv('apps/owner-app', 'Corona Gestão', false);
prepararEnv('apps/seller-app', 'Corona Vendas', false);

console.log('\nBanco de dados...');
const banco = rodar('npm', ['run', 'db:up'], { stdio: 'inherit' });
if (banco.status === 0) ok('Banco no ar');
else
  aviso(
    'O banco não subiu.',
    'Confira se o Docker está aberto e se a porta do banco (DB_PORT no .env) está livre.',
  );

console.log('\nPróximos passos (cada um num terminal):');
console.log('  npm run api:run      sobe a API');
console.log('  npm run owner:dev    abre o Corona Gestão');
console.log('  npm run seller:dev   abre o Corona Vendas');
if (avisos.length > 0) {
  console.log(
    `\nTerminou com ${avisos.length} atenção(ões) acima. Resolva e rode "npm run setup" de novo.`,
  );
  process.exitCode = 1;
} else {
  console.log('\nTudo pronto.');
}
