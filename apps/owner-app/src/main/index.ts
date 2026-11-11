import { app, BrowserWindow, safeStorage, session, type WebContents } from 'electron';
import electronUpdater from 'electron-updater';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { CANAIS, type AvisoDeAtualizacao, type AvisoDeSessao } from '../shared/ponte';
import { criarApiDoGestao } from './api';
import { CacheDeFotos } from './cache-de-fotos';
import {
  enderecoDaApi,
  exigirImpressoesFora,
  impressoesDoServidor,
  versaoDoApp,
} from './configuracao';
import { GuardaDeTokens } from './guarda-de-tokens';
import { registrarAtualizacao, registrarPonte } from './ipc';
import {
  instalarCertificadoFixado,
  PARTICAO_DO_ATUALIZADOR,
  ServicoDeAtualizacao,
} from '@corona/conexao-segura';
import { enderecoDoApp } from './seguranca';
import { ServicoDeSessao } from './servico-de-sessao';
import type { ArquivoDoToken, Cofre, Relogio } from './tipos';

const NOME_DO_APP = 'Corona Gestão';
const enderecoDeDesenvolvimento = process.env['ELECTRON_RENDERER_URL'];

app.setName(NOME_DO_APP);

let janela: BrowserWindow | null = null;

const cofre: Cofre = {
  disponivel: () => safeStorage.isAsyncEncryptionAvailable(),
  cifrar: async (texto) => new Uint8Array(await safeStorage.encryptStringAsync(texto)),
  // Ao renovar, o token é gravado de novo, então "shouldReEncrypt" já fica atendido.
  decifrar: async (dados) => (await safeStorage.decryptStringAsync(Buffer.from(dados))).result,
};

function criarArquivoDoToken(): ArquivoDoToken {
  const caminho = join(app.getPath('userData'), 'sessao', 'renovacao.bin');
  return {
    async ler() {
      try {
        return new Uint8Array(await readFile(caminho));
      } catch {
        return null;
      }
    },
    async gravar(dados) {
      await mkdir(dirname(caminho), { recursive: true });
      await writeFile(caminho, dados, { mode: 0o600 });
    },
    async apagar() {
      await rm(caminho, { force: true });
    },
  };
}

const relogio: Relogio = {
  agora: () => Date.now(),
  agendar(ms, fn) {
    const id = setTimeout(fn, ms);
    return () => clearTimeout(id);
  },
};

function avisarATela(aviso: AvisoDeSessao): void {
  if (janela && !janela.isDestroyed()) janela.webContents.send(CANAIS.sessaoMudou, aviso);
}

function avisarAtualizacao(aviso: AvisoDeAtualizacao): void {
  if (janela && !janela.isDestroyed()) janela.webContents.send(CANAIS.atualizacaoMudou, aviso);
}

/** A janela só carrega a tela do próprio aplicativo e não abre nada além dela. */
function travarNavegacao(conteudo: WebContents): void {
  conteudo.setWindowOpenHandler(() => ({ action: 'deny' }));
  conteudo.on('will-navigate', (evento, url) => {
    if (!enderecoDoApp(url, enderecoDeDesenvolvimento)) evento.preventDefault();
  });
  conteudo.on('will-attach-webview', (evento) => evento.preventDefault());
}

function criarJanela(): void {
  janela = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    show: false,
    title: NOME_DO_APP,
    backgroundColor: '#FBF7F0',
    webPreferences: {
      preload: join(__dirname, '../preload/index.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      webviewTag: false,
      allowRunningInsecureContent: false,
      spellcheck: false,
    },
  });
  travarNavegacao(janela.webContents);
  janela.once('ready-to-show', () => janela?.show());
  janela.on('closed', () => {
    janela = null;
  });

  if (enderecoDeDesenvolvimento) void janela.loadURL(enderecoDeDesenvolvimento);
  else void janela.loadFile(join(__dirname, '../renderer/index.html'));
}

function iniciar(): void {
  // Nenhuma permissão do navegador (câmera, microfone, localização...) é concedida.
  session.defaultSession.setPermissionRequestHandler((_conteudo, _permissao, responder) => {
    responder(false);
  });
  session.defaultSession.setPermissionCheckHandler(() => false);

  // Fotos já baixadas ficam em disco (por hash, nunca mudam). Saiu da sessão, a pasta é apagada.
  const cacheDeFotos = new CacheDeFotos({
    pasta: join(app.getPath('userData'), 'fotos'),
    limiteEmBytes: 200 * 1024 * 1024,
  });
  // Servidor sem domínio: o HTTPS usa um certificado autoassinado e o app só confia na chave dele (ADR-0009).
  // Sem impressão digital, fora deste computador, o app não conecta. A verificação nunca é desligada.
  const enderecoDoServidor = enderecoDaApi(import.meta.env.MAIN_VITE_CORONA_API_URL);
  const impressoes = impressoesDoServidor(import.meta.env.MAIN_VITE_CORONA_API_PINS);
  exigirImpressoesFora(enderecoDoServidor, impressoes);
  if (impressoes.length > 0) {
    const servidor = { endereco: enderecoDoServidor, impressoes };
    instalarCertificadoFixado(session.defaultSession, servidor);
    // A atualização automática baixa por outra sessão do Chromium; a mesma regra vale nela (ADR-0009).
    instalarCertificadoFixado(
      session.fromPartition(PARTICAO_DO_ATUALIZADOR, { cache: false }),
      servidor,
    );
  }
  const api = criarApiDoGestao({
    baseUrl: enderecoDoServidor,
    versaoDoApp: versaoDoApp(app.getVersion()),
    cacheDeFotos,
    // Rede do Chromium: é ela que consulta o verificador de certificado acima.
    fetch: (entrada, inicio) => session.defaultSession.fetch(entrada as string | Request, inicio),
  });
  // `servico` nasce depois da guarda, e a guarda avisa o serviço pelo `aoPerderASessao`.
  let servico: ServicoDeSessao | null = null;
  const guarda = new GuardaDeTokens({
    api,
    cofre,
    arquivo: criarArquivoDoToken(),
    relogio,
    aoPerderASessao: (aviso) => {
      if (aviso.estado === 'fora') void cacheDeFotos.limpar().catch(() => undefined);
      if (aviso.estado === 'app-desatualizado' && aviso.mensagem) {
        servico?.marcarDesatualizado(aviso.mensagem);
      }
      avisarATela(aviso);
    },
  });
  // A sessão guardada é retomada antes de a tela perguntar "estou logado?".
  servico = new ServicoDeSessao(guarda, api, guarda.restaurar(), () => cacheDeFotos.limpar());

  registrarPonte(servico, (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento)
    );
  });

  // Atualização automática: só no aplicativo instalado (em desenvolvimento não há instalador para buscar).
  // O servidor é o mesmo da API, e o canal já é o do certificado fixado.
  const atualizacao = new ServicoDeAtualizacao({
    atualizador: electronUpdater.autoUpdater,
    endereco: enderecoDoServidor,
    relogio,
    aoMudar: avisarAtualizacao,
    registrar: (mensagem) => console.warn(mensagem),
  });
  registrarAtualizacao(atualizacao, (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento)
    );
  });
  if (app.isPackaged) atualizacao.iniciar();

  criarJanela();
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (janela) {
      if (janela.isMinimized()) janela.restore();
      janela.focus();
    }
  });
  app.on('web-contents-created', (_evento, conteudo) => {
    travarNavegacao(conteudo);
  });
  void app.whenReady().then(iniciar);
  app.on('window-all-closed', () => {
    app.quit();
  });
}
